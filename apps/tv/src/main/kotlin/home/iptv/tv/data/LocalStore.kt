package home.iptv.tv.data

import home.iptv.shared.groups.GroupSnapshot
import home.iptv.shared.progress.ProgressQueueCodec
import home.iptv.shared.progress.ProgressStore
import home.iptv.shared.progress.QueuedProgress
import android.content.Context
import android.content.SharedPreferences
import home.iptv.shared.sync.Profile
import home.iptv.shared.sync.ProfilePrefs
import home.iptv.shared.sync.SessionCodec
import home.iptv.shared.xtream.XtreamCreds
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

enum class HomeTab(val storedValue: String) { DIRECT("direct"), SERIES("series"), MOVIES("movies"), SPORT("sport") }

/** Onglet où arrive un profil quand on le choisit, réglé sur la page iPhone (profiles.home) ; Direct par défaut. */
fun openingTab(home: String?): HomeTab = when (home?.trim()?.lowercase()) {
    "series" -> HomeTab.SERIES
    "films", "movies" -> HomeTab.MOVIES
    "sport" -> HomeTab.SPORT
    else -> HomeTab.DIRECT
}

/** Sections choisies sur l'iPhone (« direct », « series », « films », « sport »), dans l'ordre de la barre ; vide = toutes. */
fun visibleTabs(sections: List<String>?): List<HomeTab> {
    val wanted = sections.orEmpty().mapNotNull { s ->
        when (s.trim().lowercase()) { "direct" -> HomeTab.DIRECT; "series" -> HomeTab.SERIES; "films", "movies" -> HomeTab.MOVIES; "sport" -> HomeTab.SPORT; else -> null }
    }.toSet()
    return HomeTab.entries.filter { it in wanted }.ifEmpty { HomeTab.entries }
}

/** Section d'ouverture d'un profil, toujours parmi ses sections visibles (sinon la première). */
fun openingTab(home: String?, sections: List<String>?): HomeTab {
    val tabs = visibleTabs(sections)
    return openingTab(home).takeIf { it in tabs } ?: tabs.first()
}

/**
 * Langue audio et sous-titres retenues sur cette télé, par profil, par code de langue.
 * `null` = jamais choisi ; pour les sous-titres, [SUBTITLES_OFF] = « Aucun » choisi.
 */
interface TrackPrefsStore {
    fun audioLang(profileId: String): String?
    fun subtitleLang(profileId: String): String?
    fun saveTrackPrefs(profileId: String, audioLang: String?, subtitleLang: String?)
}

const val SUBTITLES_OFF = "off"

/** Cache local de la télé : foyer, identifiants IPTV et profils (pour démarrer hors ligne). */
interface LocalStore : ProgressStore, TrackPrefsStore {
    /** Flush pending queue writes to disk at onStop; false reports a failed commit. */
    fun persistNow(): Boolean
    fun householdId(): String?
    fun saveHouseholdId(id: String)
    fun creds(): XtreamCreds?
    fun saveCreds(c: XtreamCreds)
    /** Jeton d'accès au serveur maison de ce foyer (propre à cette télé), gardé comme les identifiants ; jamais journalisé. */
    fun homeToken(householdId: String): String? = null
    fun saveHomeToken(householdId: String, token: String?) {}
    /** Révision du Direct reçu du serveur maison et écrit dans le cache ; `null` si le cache ne vient pas du serveur. */
    fun lastSnapshotRev(): String? = null
    fun saveLastSnapshotRev(rev: String?) {}
    /** Dernière réponse du serveur maison (heure murale, ms) ; `null` s'il n'a jamais répondu. */
    fun lastServerContactMs(): Long? = null
    fun saveLastServerContactMs(ms: Long) {}
    fun profiles(): List<Profile>
    fun saveProfiles(p: List<Profile>)
    /** Thème de « Qui regarde ? » connu (réglage du foyer), pour démarrer hors ligne. */
    fun welcomeTheme(): String? = null
    fun saveWelcomeTheme(theme: String?) {}
    /** Fin de l'abonnement IPTV lue au dernier contrôle (secondes) ; null = inconnue ou sans fin. */
    /** Thèmes de saison (réglage de la maison) gardés pour un démarrage hors ligne. */
    fun seasons(): home.iptv.shared.account.SeasonSettings? = null
    fun saveSeasons(s: home.iptv.shared.account.SeasonSettings?) {}
    /** Versions séparées à la main (réglage de la maison, « movie:123 »). */
    fun versionSplits(): Set<String> = emptySet()
    fun saveVersionSplits(s: Collection<String>) {}
    fun iptvExpiresAt(): Long? = null
    fun saveIptvExpiresAt(at: Long?) {}

    /** Dernières préférences connues d'un profil (organisation du téléphone), pour démarrer hors ligne. */
    fun prefs(profileId: String): ProfilePrefs?
    fun savePrefs(profileId: String, prefs: ProfilePrefs)
    fun groupSnapshot(h: String): GroupSnapshot?
    fun saveGroupSnapshot(h: String, snapshot: GroupSnapshot)
    fun lastGoodSource(profileId: String, groupId: String): String?
    fun saveLastGoodSource(profileId: String, groupId: String, sourceId: String)

    /** Dernier onglet (Direct / Séries) ouvert par ce profil. */
    fun lastTab(profileId: String): HomeTab?
    fun saveLastTab(profileId: String, tab: HomeTab)
}

private val json = Json { ignoreUnknownKeys = true }

/** Stockage privé de l'app (SharedPreferences, non lisible par les autres apps). */
class AndroidLocalStore(context: Context) : LocalStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("iptv_home_local", Context.MODE_PRIVATE)

    override fun groupSnapshot(h: String): GroupSnapshot? = runCatching {
        json.decodeFromString<GroupSnapshot>(prefs.getString("groups_$h", null) ?: return null)
            .takeIf { it.rulesVersion == 1 }
    }.getOrNull()

    override fun saveGroupSnapshot(h: String, snapshot: GroupSnapshot) {
        require(snapshot.rulesVersion == 1)
        // Un seul enregistrement remplace le snapshot complet et sa revision ensemble.
        check(prefs.edit().putString("groups_$h", json.encodeToString(GroupSnapshot.serializer(), snapshot)).commit())
    }

    private fun sourceKey(profileId: String, groupId: String) = "source:${profileId.length}:$profileId:$groupId"
    override fun lastGoodSource(profileId: String, groupId: String): String? = prefs.getString(sourceKey(profileId, groupId), null)
    override fun saveLastGoodSource(profileId: String, groupId: String, sourceId: String) {
        prefs.edit().putString(sourceKey(profileId, groupId), sourceId).apply()
    }

    @Synchronized override fun loadProgressQueue(): List<QueuedProgress> {
        val decoded = ProgressQueueCodec.decode(prefs.getString("progress_queue", null) ?: "[]")
        decoded.unreadable?.let { raw ->
            prefs.edit().putString("progress_queue_corrupt", raw).apply()
        }
        return decoded.entries
    }

    // Routine writes update memory immediately and persist asynchronously.
    @Synchronized override fun saveProgressQueue(queue: List<QueuedProgress>) {
        loadProgressQueue() // Preserve malformed previous data even for a direct replacement.
        prefs.edit().putString("progress_queue", ProgressQueueCodec.encode(queue)).apply()
    }

    @Synchronized
    override fun persistNow(): Boolean =
        prefs.edit().putString("progress_queue", ProgressQueueCodec.encode(loadProgressQueue())).commit()

    @Synchronized
    override fun update(transform: (List<QueuedProgress>) -> List<QueuedProgress>) {
        saveProgressQueue(transform(loadProgressQueue()))
    }

    override fun audioLang(profileId: String): String? = prefs.getString("audio_lang:$profileId", null)
    override fun subtitleLang(profileId: String): String? = prefs.getString("subtitle_lang:$profileId", null)
    override fun saveTrackPrefs(profileId: String, audioLang: String?, subtitleLang: String?) {
        prefs.edit()
            .putString("audio_lang:$profileId", audioLang)
            .putString("subtitle_lang:$profileId", subtitleLang)
            .apply()
    }

    override fun lastTab(profileId: String): HomeTab? =
        HomeTab.entries.firstOrNull { it.storedValue == prefs.getString("last_tab:$profileId", null) }
    override fun saveLastTab(profileId: String, tab: HomeTab) {
        prefs.edit().putString("last_tab:$profileId", tab.storedValue).apply()
    }

    override fun householdId(): String? = prefs.getString("household", null)
    override fun saveHouseholdId(id: String) { prefs.edit().putString("household", id).apply() }

    override fun creds(): XtreamCreds? {
        val server = prefs.getString("server", null) ?: return null
        val user = prefs.getString("username", null) ?: return null
        val pass = prefs.getString("password", null) ?: return null
        return XtreamCreds(server, user, pass)
    }

    override fun saveCreds(c: XtreamCreds) {
        prefs.edit().putString("server", c.server).putString("username", c.username)
            .putString("password", c.password).apply()
    }

    override fun homeToken(householdId: String): String? = prefs.getString("home_token:$householdId", null)
    override fun saveHomeToken(householdId: String, token: String?) {
        prefs.edit().putString("home_token:$householdId", token).apply()
    }

    override fun lastSnapshotRev(): String? = prefs.getString("home_snapshot_rev", null)
    override fun saveLastSnapshotRev(rev: String?) { prefs.edit().putString("home_snapshot_rev", rev).apply() }
    override fun lastServerContactMs(): Long? = prefs.getLong("home_contact_ms", -1L).takeIf { it >= 0 }
    override fun saveLastServerContactMs(ms: Long) { prefs.edit().putLong("home_contact_ms", ms).apply() }

    override fun profiles(): List<Profile> = runCatching {
        json.decodeFromString(ListSerializer(Profile.serializer()), prefs.getString("profiles", null) ?: "[]")
    }.getOrDefault(emptyList())

    override fun saveProfiles(p: List<Profile>) {
        prefs.edit().putString("profiles", json.encodeToString(ListSerializer(Profile.serializer()), p)).apply()
    }

    override fun welcomeTheme(): String? = prefs.getString("welcome_theme", null)
    override fun saveWelcomeTheme(theme: String?) { prefs.edit().putString("welcome_theme", theme).apply() }
    override fun seasons(): home.iptv.shared.account.SeasonSettings? = prefs.getString("seasons", null)?.let {
        runCatching { kotlinx.serialization.json.Json.decodeFromString(home.iptv.shared.account.SeasonSettings.serializer(), it) }.getOrNull()
    }
    override fun saveSeasons(s: home.iptv.shared.account.SeasonSettings?) {
        prefs.edit().putString("seasons", s?.let { kotlinx.serialization.json.Json.encodeToString(home.iptv.shared.account.SeasonSettings.serializer(), it) }).apply()
    }
    override fun versionSplits(): Set<String> = prefs.getStringSet("version_splits", null)?.toSet().orEmpty()
    override fun saveVersionSplits(s: Collection<String>) { prefs.edit().putStringSet("version_splits", s.toSet()).apply() }
    override fun iptvExpiresAt(): Long? = prefs.getLong("iptv_expires_at", -1L).takeIf { it > 0 }
    override fun saveIptvExpiresAt(at: Long?) { prefs.edit().putLong("iptv_expires_at", at ?: -1L).apply() }

    override fun prefs(profileId: String): ProfilePrefs? = runCatching {
        json.decodeFromString(ProfilePrefs.serializer(), prefs.getString("prefs_$profileId", null) ?: return null)
    }.getOrNull()

    override fun savePrefs(profileId: String, prefs: ProfilePrefs) {
        this.prefs.edit().putString("prefs_$profileId", json.encodeToString(ProfilePrefs.serializer(), prefs)).apply()
    }
}

/** Session Supabase (anonyme) de la télé, conservée entre deux lancements. */
class PrefsSessionManager(context: Context) : SessionManager {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("iptv_home_session", Context.MODE_PRIVATE)

    override suspend fun saveSession(session: UserSession) {
        prefs.edit().putString("session", SessionCodec.encode(session)).apply()
    }

    // Le contrat de supabase-kt : lever une exception s'il n'y a pas de session (loadSessionOrNull l'attrape).
    override suspend fun loadSession(): UserSession =
        SessionCodec.decode(checkNotNull(prefs.getString("session", null)) { "aucune session" })

    override suspend fun deleteSession() {
        prefs.edit().remove("session").apply()
    }
}
