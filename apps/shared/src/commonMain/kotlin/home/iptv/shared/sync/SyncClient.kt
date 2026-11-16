package home.iptv.shared.sync

import home.iptv.shared.xtream.XtreamCreds
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.auth.status.SessionStatus
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

object SupabaseConfig {
    const val URL = "https://gisjeuukbrgjgkrmlyvs.supabase.co"
    // Clé publique (publishable) : publique par conception, la RLS protège les données.
    const val PUBLISHABLE_KEY = "sb_publishable_Rfqg2UOqhABAF4ugGAfzHQ_84UIpABt"
}

interface SyncClient {
    suspend fun ensureSignedIn()
    suspend fun currentHousehold(): String?
    suspend fun createPairingCode(deviceName: String): PairingState.Waiting
    suspend fun reportDeviceIdentity(householdId: String, defaultName: String): String? = null
    suspend fun credentials(householdId: String): XtreamCreds?
    suspend fun profiles(householdId: String): List<Profile>
    suspend fun globalHiddenCategories(householdId: String): List<HiddenItem>

    /** Publie les catégories du fournisseur ; renvoie l'empreinte déjà connue du serveur pour chacune (id → empreinte, ou null). */
    suspend fun publishLiveCategories(householdId: String, cats: List<PublishedCategory>): Map<String, String?>
    suspend fun publishLiveChannels(
        householdId: String,
        categoryId: String,
        fingerprint: String,
        chunk: Int,
        isLast: Boolean,
        channels: List<PublishedChannel>,
    )
    suspend fun profilePrefs(profileId: String): ProfilePrefs
    suspend fun prefsRevision(householdId: String): Long

    /** Publie la liste des catégories de séries du fournisseur (toutes, masquées comprises ; noms seulement). */
    suspend fun publishSeriesCategories(householdId: String, cats: List<PublishedCategory>)
}

/** Sans session authentifiée, la clé publique interroge en `anon` : une lecture répondrait `[]` sans erreur. */
internal fun SupabaseClient.requireAuthenticated() {
    if (auth.sessionStatus.value !is SessionStatus.Authenticated) {
        throw IOException("aucune session authentifiée")
    }
}

fun supabaseClient(
    url: String,
    publishableKey: String,
    sessionStore: SessionManager? = null,
    engine: HttpClientEngine? = null,
    autoRefresh: Boolean = engine == null,
): SupabaseClient = createSupabaseClient(url, publishableKey) {
    if (engine != null) httpEngine = engine
    install(Auth) {
        if (sessionStore != null) sessionManager = sessionStore
        alwaysAutoRefresh = autoRefresh
    }
    install(Postgrest)
}

@Serializable
private data class HouseholdRow(val id: String)

@Serializable
private data class CredentialsRow(val server: String, val username: String, val password: String)

class SupabaseSyncClient(
    private val supabase: SupabaseClient,
    private val now: () -> Instant = { Clock.System.now() },
    private val initTimeout: Duration = 5.seconds,
) : SyncClient {

    /**
     * Inscription anonyme UNIQUEMENT quand il n'y a vraiment aucune session (`NotAuthenticated`).
     * Une session stockée dont le rafraîchissement échoue (réseau, 5xx : `RefreshFailure`) ne doit jamais
     * créer un nouvel utilisateur : la télé perdrait son appairage. On lève alors une erreur réseau et le
     * démarrage passe hors ligne avec le foyer local.
     */
    override suspend fun ensureSignedIn() {
        val auth = supabase.auth
        val status = withTimeoutOrNull(initTimeout) {
            auth.sessionStatus.first { it !is SessionStatus.Initializing }
        } ?: throw IOException("session Supabase non initialisée")
        when (status) {
            is SessionStatus.Authenticated -> Unit
            is SessionStatus.NotAuthenticated -> auth.signInAnonymously()
            is SessionStatus.RefreshFailure -> throw IOException("rafraîchissement de la session impossible : ${status.cause}")
            is SessionStatus.Initializing -> throw IOException("session Supabase non initialisée")
        }
    }

    private fun requireSession() = supabase.requireAuthenticated()

    override suspend fun currentHousehold(): String? {
        requireSession()
        return supabase.postgrest.rpc("my_households").decodeList<HouseholdRow>().firstOrNull()?.id
    }

    override suspend fun reportDeviceIdentity(householdId: String, defaultName: String): String {
        requireSession()
        return supabase.postgrest.rpc("report_device_identity", buildJsonObject {
            put("h", householdId); put("default_name", defaultName)
        }).decodeAs<String>()
    }

    override suspend fun createPairingCode(deviceName: String): PairingState.Waiting {
        requireSession()
        val code = supabase.postgrest
            .rpc("create_pairing_code", buildJsonObject { put("device_name", deviceName) })
            .decodeAs<String>()
        return PairingState.Waiting(code, now() + 10.minutes)
    }

    override suspend fun credentials(householdId: String): XtreamCreds? {
        requireSession()
        return supabase.postgrest.from("xtream_credentials")
            .select { filter { eq("household_id", householdId) } }
            .decodeList<CredentialsRow>()
            .firstOrNull()
            ?.let { XtreamCreds(it.server, it.username, it.password) }
    }

    override suspend fun profiles(householdId: String): List<Profile> {
        requireSession()
        return supabase.postgrest.from("profiles")
            .select { filter { eq("household_id", householdId) } }
            .decodeList<Profile>()
            .sortedBy { it.position }
    }

    /** RPC, pas un GET de table : PostgREST coupe les réponses à « Max rows » (1 000), et une catégorie masquée oubliée serait téléchargée. */
    override suspend fun globalHiddenCategories(householdId: String): List<HiddenItem> {
        requireSession()
        return supabase.postgrest
            .rpc("global_hidden_live_categories", buildJsonObject { put("h", householdId) })
            .decodeAs<List<String>>()
            .map { HiddenItem("live_category", it) }
    }

    override suspend fun publishLiveCategories(householdId: String, cats: List<PublishedCategory>): Map<String, String?> {
        requireSession()
        return supabase.postgrest
            .rpc("publish_live_categories", buildJsonObject {
                put("h", householdId)
                put("cats", Json.encodeToJsonElement(ListSerializer(PublishedCategory.serializer()), cats))
            })
            .decodeAs<Map<String, String?>>()
    }

    override suspend fun publishSeriesCategories(householdId: String, cats: List<PublishedCategory>) {
        requireSession()
        supabase.postgrest.rpc("publish_series_categories", buildJsonObject {
            put("h", householdId)
            put("cats", Json.encodeToJsonElement(ListSerializer(PublishedCategory.serializer()), cats))
        })
    }

    override suspend fun publishLiveChannels(
        householdId: String,
        categoryId: String,
        fingerprint: String,
        chunk: Int,
        isLast: Boolean,
        channels: List<PublishedChannel>,
    ) {
        requireSession()
        supabase.postgrest.rpc("publish_live_channels", buildJsonObject {
            put("h", householdId)
            put("category", categoryId)
            put("fingerprint", fingerprint)
            put("chunk", chunk)
            put("is_last", isLast)
            put("channels", Json.encodeToJsonElement(ListSerializer(PublishedChannel.serializer()), channels))
        })
    }

    override suspend fun profilePrefs(profileId: String): ProfilePrefs {
        requireSession()
        return supabase.postgrest.rpc("profile_prefs", buildJsonObject { put("p", profileId) }).decodeAs<ProfilePrefs>()
    }

    override suspend fun prefsRevision(householdId: String): Long {
        requireSession()
        return supabase.postgrest.rpc("prefs_rev", buildJsonObject { put("h", householdId) }).decodeAs<Long>()
    }
}
