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
    /** Réglages du foyer (thème de « Qui regarde ? ») ; un serveur sans cette colonne donne les réglages par défaut. */
    suspend fun householdSettings(householdId: String): HouseholdSettings = HouseholdSettings()
    suspend fun sportVotes(householdId: String): List<SportVote> = emptyList()
    suspend fun sportVote(householdId: String, teamKey: String, channelKey: String, delta: Int) {}
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

    /**
     * Publie un lot de titres du catalogue (`publish_catalog_titles`, [kind] « series » ou « movie ») pour la recherche
     * et les noms affichés sur l'iPhone. [gen] : heure de la publication ; [done] : dernier lot.
     */
    suspend fun publishCatalogTitles(householdId: String, kind: String, gen: Long, rows: List<home.iptv.shared.catalog.CatalogTitleRow>, done: Boolean) {}
    /** Ce que le fournisseur dit du compte (fin d'abonnement en secondes, état, connexions), pour la page iPhone. */
    suspend fun reportIptvAccount(householdId: String, expiresAt: Long?, status: String?, maxConnections: Int?, activeConnections: Int? = null, message: String? = null) {}
    /** Relevé léger d'un compte supplémentaire : vidéos ouvertes en ce moment, message du fournisseur, date de fin. */
    suspend fun reportExtraStatus(householdId: String, accountId: String, activeConnections: Int?, message: String?, expiresAt: Long?) {}
    /** Sépare ([split] vrai) ou réunit une version ([kind] « movie » ou « series ») pour toute la maison. */
    suspend fun setVersionSplit(householdId: String, kind: String, itemId: String, split: Boolean) {}

    /** Comptes IPTV supplémentaires du foyer (même serveur que le compte principal), dans l'ordre d'utilisation. */
    suspend fun extraAccounts(householdId: String): List<ExtraAccount> = emptyList()
    /** Résultat de la vérification d'un compte supplémentaire par la télé ([reason] : voir [ExtraAccount.REASONS]). */
    suspend fun reportExtraAccount(householdId: String, accountId: String, ok: Boolean, reason: String?, expiresAt: Long?) {}
    /**
     * Réserve le premier compte libre pour regarder (1 = principal). [takeover] : réservation d'une autre télé à
     * reprendre (« Prendre la main ») quand tout est pris.
     */
    suspend fun claimAccount(householdId: String, profileId: String?, deviceName: String, label: String, deviceKey: String, takeover: String? = null): AccountClaim =
        AccountClaim.Granted(null, 1)
    /** Signe de vie d'une réservation, avec ce qui est regardé ([label]) ; faux si elle n'existe plus. */
    suspend fun heartbeatStream(streamId: String, label: String? = null): Boolean = true
    /** Réservation perdue : qui a pris la main (`null` : personne, simple coupure). */
    suspend fun streamTakeover(streamId: String): TakenBy? = null
    /** Après « Prendre la main » : l'autre télé a-t-elle arrêté sa vidéo ? */
    suspend fun takeoverDone(streamId: String): Boolean = true
    suspend fun releaseStream(streamId: String) {}

    /** Toute la progression d'un profil (films et épisodes), pour la retrouver après un changement de fournisseur. */
    suspend fun allProgress(profileId: String): List<home.iptv.shared.progress.ProgressRow> = emptyList()
    /** Recopie des positions sous de nouveaux numéros ; rend le nombre de lignes écrites. */
    suspend fun progressCopy(profileId: String, rows: List<home.iptv.shared.progress.ProgressRow>): Int = 0
}

/** Compte IPTV supplémentaire. [status] : « a_verifier », « ok » ou « refuse ». `toString()` masque les identifiants. */
data class ExtraAccount(
    val id: String, val position: Int, val username: String, val password: String, val status: String,
    /** Dernière vérification par une télé (secondes depuis 1970) ; `null` : jamais vérifié. */
    val checkedAt: Long? = null,
) {
    override fun toString(): String = "ExtraAccount(id=$id, position=$position, status=$status)"
    companion object {
        /** Raisons d'un refus, comprises par le serveur et la page iPhone. */
        val REASONS = setOf("identifiants", "expire", "catalogue", "meme_compte", "films", "series", "films_series")
    }
}

/** Réponse à une demande de compte pour regarder. */
sealed interface AccountClaim {
    /** [streamId] : réservation à confirmer puis rendre (`null` : rien à suivre) ; [position] : 1 = compte principal. */
    data class Granted(val streamId: String?, val position: Int, val replaced: String? = null) : AccountClaim
    /** Tous les comptes sont pris : qui regarde quoi (« Télé du salon · Maha · TSN 1 »). */
    data class Busy(val occupants: List<Occupant>) : AccountClaim
}

/** [streamId] : sa réservation, pour « Prendre la main » ; [label] : ce qui est regardé (« Série : Lost »). */
data class Occupant(val deviceName: String, val profileName: String?, val label: String, val streamId: String? = null)

/** Télé qui a pris la main. */
data class TakenBy(val deviceName: String, val profileName: String?)

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

@Serializable
private data class ExtraAccountRow(val id: String, val position: Int, val username: String, val password: String, @SerialName("check_status") val status: String, @SerialName("checked_at") val checkedAt: String? = null)

@Serializable
private data class OccupantRow(val id: String? = null, @SerialName("device_name") val deviceName: String = "", @SerialName("profile_name") val profileName: String? = null, val label: String = "")

@Serializable
private data class TakenByRow(@SerialName("device_name") val deviceName: String = "", @SerialName("profile_name") val profileName: String? = null)

@Serializable
private data class ClaimRow(val status: String, @SerialName("stream_id") val streamId: String? = null, val position: Int = 1, val replaced: String? = null, val occupants: List<OccupantRow> = emptyList())

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

    override suspend fun householdSettings(householdId: String): HouseholdSettings {
        requireSession()
        return supabase.postgrest.from("households")
            .select(io.github.jan.supabase.postgrest.query.Columns.list("id", "settings")) { filter { eq("id", householdId) } }
            .decodeList<HouseholdSettingsRow>().firstOrNull()?.settings ?: HouseholdSettings()
    }

    override suspend fun sportVotes(householdId: String): List<SportVote> {
        requireSession()
        return supabase.postgrest.from("sport_channel_vote")
            .select(io.github.jan.supabase.postgrest.query.Columns.list("team_key", "channel_key", "score")) { filter { eq("household_id", householdId) } }
            .decodeList<SportVote>()
    }

    override suspend fun sportVote(householdId: String, teamKey: String, channelKey: String, delta: Int) {
        requireSession()
        supabase.postgrest.rpc("sport_vote", buildJsonObject {
            put("h", householdId); put("team_key", teamKey); put("channel_key", channelKey); put("delta", delta)
        })
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

    override suspend fun reportIptvAccount(householdId: String, expiresAt: Long?, status: String?, maxConnections: Int?, activeConnections: Int?, message: String?) {
        requireSession()
        supabase.postgrest.rpc("report_iptv_account", buildJsonObject {
            put("h", householdId)
            put("expires_at", expiresAt?.let { kotlin.time.Instant.fromEpochSeconds(it).toString() })
            put("status", status); put("max_connections", maxConnections)
            put("active_connections", activeConnections); put("message", message)
        })
    }

    override suspend fun reportExtraStatus(householdId: String, accountId: String, activeConnections: Int?, message: String?, expiresAt: Long?) {
        requireSession()
        supabase.postgrest.rpc("report_extra_status", buildJsonObject {
            put("h", householdId); put("account", accountId)
            put("active_connections", activeConnections); put("message", message)
            put("expires_at", expiresAt?.let { kotlin.time.Instant.fromEpochSeconds(it).toString() })
        })
    }

    override suspend fun extraAccounts(householdId: String): List<ExtraAccount> {
        requireSession()
        return supabase.postgrest.from("xtream_extra_accounts")
            .select(io.github.jan.supabase.postgrest.query.Columns.list("id", "position", "username", "password", "check_status", "checked_at")) {
                filter { eq("household_id", householdId) }
            }
            .decodeList<ExtraAccountRow>()
            .sortedBy { it.position }
            .map { r ->
                val checked = r.checkedAt?.let { runCatching { kotlin.time.Instant.parse(it).epochSeconds }.getOrNull() }
                ExtraAccount(r.id, r.position, r.username, r.password, r.status, checked)
            }
    }

    override suspend fun reportExtraAccount(householdId: String, accountId: String, ok: Boolean, reason: String?, expiresAt: Long?) {
        requireSession()
        supabase.postgrest.rpc("report_extra_account", buildJsonObject {
            put("h", householdId); put("account", accountId); put("ok", ok); put("reason", reason)
            put("expires_at", expiresAt?.let { kotlin.time.Instant.fromEpochSeconds(it).toString() })
        })
    }

    override suspend fun claimAccount(householdId: String, profileId: String?, deviceName: String, label: String, deviceKey: String, takeover: String?): AccountClaim {
        requireSession()
        val r = supabase.postgrest.rpc("claim_account", buildJsonObject {
            put("h", householdId); put("p", profileId); put("device_name", deviceName); put("label", label); put("device_key", deviceKey)
            if (takeover != null) put("takeover", takeover)
        }).decodeAs<ClaimRow>()
        return if (r.status == "ok") AccountClaim.Granted(r.streamId, r.position, r.replaced)
        else AccountClaim.Busy(r.occupants.map { Occupant(it.deviceName, it.profileName, it.label, it.id) })
    }

    override suspend fun heartbeatStream(streamId: String, label: String?): Boolean {
        requireSession()
        return supabase.postgrest.rpc("heartbeat_stream", buildJsonObject { put("id", streamId); if (label != null) put("label", label) }).decodeAs<Boolean>()
    }

    override suspend fun streamTakeover(streamId: String): TakenBy? {
        requireSession()
        val text = supabase.postgrest.rpc("stream_takeover", buildJsonObject { put("id", streamId) }).data.trim()
        if (text.isEmpty() || text == "null") return null
        val r = Json { ignoreUnknownKeys = true }.decodeFromString(TakenByRow.serializer(), text)
        return TakenBy(r.deviceName, r.profileName)
    }

    override suspend fun takeoverDone(streamId: String): Boolean {
        requireSession()
        return supabase.postgrest.rpc("takeover_done", buildJsonObject { put("id", streamId) }).decodeAs<Boolean>()
    }

    override suspend fun allProgress(profileId: String): List<home.iptv.shared.progress.ProgressRow> {
        requireSession()
        return supabase.postgrest.from("watch_progress").select(io.github.jan.supabase.postgrest.query.Columns.list(
            "item_id", "item_type", "series_id", "season", "episode", "position_s", "duration_s", "watched", "client_updated_at",
            "series_name", "series_cover", "series_category", "movie_name", "movie_cover", "movie_category",
        )) { filter { eq("profile_id", profileId) } }
            .decodeList<home.iptv.shared.progress.ProgressRow>()
    }

    override suspend fun progressCopy(profileId: String, rows: List<home.iptv.shared.progress.ProgressRow>): Int {
        requireSession()
        if (rows.isEmpty()) return 0
        return supabase.postgrest.rpc("progress_copy", buildJsonObject {
            put("p", profileId)
            put("items", Json.encodeToJsonElement(ListSerializer(home.iptv.shared.progress.ProgressRow.serializer()), rows))
        }).decodeAs<Int>()
    }

    override suspend fun releaseStream(streamId: String) {
        requireSession()
        supabase.postgrest.rpc("release_stream", buildJsonObject { put("id", streamId) })
    }

    override suspend fun setVersionSplit(householdId: String, kind: String, itemId: String, split: Boolean) {
        requireSession()
        supabase.postgrest.rpc("set_version_split", buildJsonObject {
            put("h", householdId); put("kind", kind); put("item_id", itemId); put("split", split)
        })
    }

    override suspend fun publishCatalogTitles(householdId: String, kind: String, gen: Long, rows: List<home.iptv.shared.catalog.CatalogTitleRow>, done: Boolean) {
        requireSession()
        supabase.postgrest.rpc("publish_catalog_titles", buildJsonObject {
            put("h", householdId); put("kind", kind); put("gen", gen); put("done", done)
            put("rows", Json.encodeToJsonElement(ListSerializer(home.iptv.shared.catalog.CatalogTitleRow.serializer()), rows))
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
