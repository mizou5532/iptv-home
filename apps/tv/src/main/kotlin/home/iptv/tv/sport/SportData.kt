package home.iptv.tv.sport

import android.content.Context
import home.iptv.shared.catalog.CatalogRepository
import home.iptv.shared.groups.GroupSnapshot
import home.iptv.shared.sport.*
import home.iptv.shared.sync.ProfilePrefs
import home.iptv.tv.live.groupSourceIndex
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** Mémoire locale du sport : dernières réponses des sites (hors ligne) et chaînes apprises par profil et compétition. */
interface SportStore {
    fun cached(key: String): String?
    fun saveCached(key: String, text: String, at: Long)
    fun cachedAt(): Long
    /** Oublie les réponses gardées qui ne sont plus demandées (jours passés). */
    fun prune(keep: Set<String>) {}
    fun learned(profileId: String, league: League): List<String>
    fun saveLearned(profileId: String, league: League, groups: List<String>)
    /** Chaîne préférée (étoile) d'un profil pour une équipe (clé de [preferenceKeys]). */
    fun preferred(profileId: String, teamKey: String): String? = null
    fun savePreferred(profileId: String, teamKey: String, groupId: String?) {}
}

class AndroidSportStore(context: Context) : SportStore {
    private val prefs = context.applicationContext.getSharedPreferences("iptv_home_sport", Context.MODE_PRIVATE)
    // Réponses des sites : un fichier chacune (un tableau NBA d'un jour pèse ~160 Ko ; dans les préférences,
    // chaque écriture réécrirait tout le fichier). Les anciennes réponses gardées dans les préférences sont retirées.
    private val dir = java.io.File(context.applicationContext.cacheDir, "sport").apply { mkdirs() }
    init {
        val old = prefs.all.keys.filter { it.startsWith("cache:") }
        if (old.isNotEmpty()) prefs.edit().apply { old.forEach { remove(it) } }.apply()
    }
    private fun file(key: String) = java.io.File(dir, java.security.MessageDigest.getInstance("SHA-1")
        .digest(key.toByteArray()).joinToString("") { "%02x".format(it) } + ".json")
    override fun cached(key: String) = runCatching { file(key).takeIf { it.exists() }?.readText() }.getOrNull()
    override fun saveCached(key: String, text: String, at: Long) {
        runCatching { val f = file(key); val tmp = java.io.File(dir, f.name + ".tmp"); tmp.writeText(text); tmp.renameTo(f) }
        prefs.edit().putLong("cached_at", at).apply()
    }
    override fun cachedAt() = prefs.getLong("cached_at", 0)
    override fun prune(keep: Set<String>) {
        val names = keep.mapTo(HashSet()) { file(it).name }
        dir.listFiles()?.filter { it.name !in names }?.forEach { it.delete() }
    }
    override fun learned(profileId: String, league: League) =
        prefs.getString("learned:$profileId:${league.key}", null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
    override fun saveLearned(profileId: String, league: League, groups: List<String>) {
        prefs.edit().putString("learned:$profileId:${league.key}", groups.joinToString("\n")).apply()
    }
    override fun preferred(profileId: String, teamKey: String) = prefs.getString("preferred:$profileId:$teamKey", null)
    override fun savePreferred(profileId: String, teamKey: String, groupId: String?) {
        prefs.edit().apply { if (groupId == null) remove("preferred:$profileId:$teamKey") else putString("preferred:$profileId:$teamKey", groupId) }.apply()
    }
}

private const val NHL_BASE = "https://api-web.nhle.com/v1/schedule/"
private const val ESPN_BASE = "https://site.api.espn.com/apis/site/v2/sports/"

/**
 * Adresses à lire pour 8 jours à partir de [today] : 2 semaines LNH, et pour chaque ligue ESPN un tableau PAR JOUR.
 * ESPN refuse depuis novembre 2026 les plages de dates (« dates=AAAAMMJJ-AAAAMMJJ » répond 400) : sans match NFL,
 * NBA ni soccer, seul le hockey (site de la LNH) restait affiché.
 */
fun scheduleSources(today: Instant, offsetSeconds: Int, leagues: List<League> = League.entries.filter { !it.optional }): List<Pair<League, String>> {
    fun day(at: Instant) = java.time.LocalDate.ofEpochDay(localDay(at, offsetSeconds))
    val first = day(today)
    val iso0 = first.toString()
    val iso7 = day(today + 7.days).toString()
    val days = (0L..7L).map { first.plusDays(it).toString().replace("-", "") }
    return listOf(League.NHL to "$NHL_BASE$iso0", League.NHL to "$NHL_BASE$iso7") +
        leagues.filter { it.espnPath != null }.flatMap { l -> days.map { d -> l to "$ESPN_BASE${l.espnPath}/scoreboard?dates=$d" } }
}

/** Demandes d'horaires en même temps (un tableau par ligue et par jour : une soixantaine en tout). */
const val SCHEDULE_PARALLEL = 6

/**
 * Lit les horaires (quelques sites à la fois), garde chaque réponse lisible en cache et retombe sur le cache si un
 * site ne répond pas ou répond mal. [offline] = au moins une source vient du cache faute de réponse.
 */
class SportSchedule(private val fetch: suspend (String) -> String, private val store: SportStore, private val now: () -> Instant = { Clock.System.now() }) {
    data class Result(val matches: List<Match>, val offline: Boolean, val any: Boolean)

    suspend fun load(offsetSeconds: Int, leagues: List<League> = League.entries.filter { !it.optional }): Result = coroutineScope {
        val offline = java.util.concurrent.atomic.AtomicBoolean(false)
        val any = java.util.concurrent.atomic.AtomicBoolean(false)
        val gate = kotlinx.coroutines.sync.Semaphore(SCHEDULE_PARALLEL)
        val matches = scheduleSources(now(), offsetSeconds, leagues).map { (league, url) ->
            async {
                gate.acquire()
                try {
                    val key = url.substringAfter("://")
                    val fresh = runCatching { fetch(url) }.getOrNull()?.takeIf { it.trimStart().startsWith("{") }
                    if (fresh != null) store.saveCached(key, fresh, now().toEpochMilliseconds()) else offline.set(true)
                    val text = fresh ?: store.cached(key) ?: return@async emptyList()
                    any.set(true)
                    if (league == League.NHL) parseNhlSchedule(text) else parseEspnScoreboard(league, text)
                } finally { gate.release() }
            }
        }.awaitAll().flatten()
        store.prune(scheduleSources(now(), offsetSeconds, leagues).mapTo(HashSet()) { it.second.substringAfter("://") })
        Result(matches, offline.get(), any.get())
    }
}

/** Groupes de chaînes visibles du Direct (masquées retirées), avec le nom de leur catégorie : base des candidats. */
fun visibleSportChannels(repo: CatalogRepository, prefs: ProfilePrefs?, snapshot: GroupSnapshot?): List<SportChannel> {
    if (prefs == null) return emptyList()
    val hidden = prefs.hidden
    val hiddenChannels = hidden.filter { it.itemType == "channel" }.mapTo(HashSet()) { it.itemId }
    val hiddenCategories = hidden.filter { it.itemType == "live_category" }.mapTo(HashSet()) { it.itemId }
    val names = repo.categories().associate { it.id to it.name }
    val visible = repo.allGroupSources().filter { it.id !in hiddenChannels && it.categoryId !in hiddenCategories }
    val visibleIds = visible.associateBy { it.id }
    val index = groupSourceIndex(visible, snapshot ?: GroupSnapshot(0, 1, emptyList()), prefs.groupCopies)
    return index.values.distinctBy { it.id }.mapNotNull { group ->
        val anchor = group.sources.firstOrNull { it.id in visibleIds } ?: return@mapNotNull null
        SportChannel(group.id, group.title, names[anchor.categoryId].orEmpty(), anchor.id)
    }.sortedBy { visibleIds[it.anchorSourceId]?.position ?: Int.MAX_VALUE }
}

/** Crée le modèle de l'onglet Sport pour un profil et sa session du Direct. */
typealias SportFactory = (home.iptv.shared.sync.Profile, home.iptv.tv.live.LiveSession, CoroutineScope) -> SportViewModel

data class SportUi(
    val board: SportBoard = SportBoard(emptyList(), emptyList()),
    val candidates: Map<String, SportCandidates> = emptyMap(),
    val loading: Boolean = true,
    /** « Horaires du … (hors ligne) » ou message d'erreur ; null si tout est à jour. */
    val notice: String? = null,
    /**
     * Affiche et bande du bas : le match en cours ou le prochain de mes équipes, puis ce qui se joue maintenant ou
     * aujourd'hui (mes autres matchs du jour, puis tous les autres), sans les matchs des jours suivants.
     */
    val strip: List<Match> = emptyList(),
)

const val SPORT_REFRESH_MS = 30 * 60 * 1000L
const val STRIP_MAX = 16
const val LEARN_AFTER_MS = 3 * 60 * 1000L

/** Écran Sport d'un profil : horaires, chaînes à essayer, apprentissage. Calculs et lectures hors du fil principal. */
class SportViewModel(
    private val profileId: String,
    private val schedule: SportSchedule,
    private val channels: suspend () -> List<SportChannel>,
    private val favorites: () -> List<String>,
    private val store: SportStore,
    private val prefs: StateFlow<ProfilePrefs?>,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Instant = { Clock.System.now() },
    private val offsetSeconds: () -> Int = { java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000 },
    /** Équipes favorites du profil (page iPhone) ; null = Canadiens et Barça. */
    private val teams: () -> List<home.iptv.shared.sync.SportTeam>? = { null },
) {
    private val _ui = MutableStateFlow(SportUi())
    val ui: StateFlow<SportUi> = _ui
    private var matches = emptyList<Match>()
    private var refreshing: Job? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        refresh()
        // Masquage depuis l'iPhone : candidats recalculés aussitôt (jamais de chaîne masquée proposée).
        scope.launch { prefs.drop(1).collect { rebuild() } }
    }

    fun refresh() {
        if (refreshing?.isActive == true) return
        refreshing = scope.launch(io) {
            val result = schedule.load(offsetSeconds(), leaguesFor(teams() ?: DEFAULT_TEAMS))
            matches = result.matches
            val notice = when {
                !result.any -> "Horaires indisponibles. Vérifie la connexion puis réessaie."
                result.offline -> "Horaires du ${java.text.SimpleDateFormat("d MMM 'à' HH'h'mm", java.util.Locale.CANADA_FRENCH).format(java.util.Date(store.cachedAt()))} (hors ligne)"
                else -> null
            }
            rebuild(notice)
        }
    }

    private suspend fun rebuild(notice: String? = _ui.value.notice) = withContext(io) {
        val board = buildBoard(matches, now(), offsetSeconds(), teams())
        val visible = channels()
        val fav = favorites()
        val favTeams = teams() ?: DEFAULT_TEAMS
        val cands = (board.mine + board.tonight).associate { m ->
            val preferred = preferenceKeys(m, favTeams).mapNotNull { store.preferred(profileId, it) }.distinct()
            m.id to sportCandidates(m, store.learned(profileId, m.league), visible, fav, preferred)
        }
        val today = localDay(now(), offsetSeconds())
        fun nowOrToday(m: Match) = m.state == MatchState.LIVE || localDay(m.start, offsetSeconds()) == today
        val strip = (listOfNotNull(board.mine.firstOrNull()) + board.mine.drop(1).filter(::nowOrToday) + board.tonight.filter(::nowOrToday))
            .distinctBy { it.id }.take(STRIP_MAX)
        _ui.value = SportUi(board, cands, loading = false, notice = notice, strip = strip)
    }

    /**
     * Étoile (appui long dans le choix de la chaîne) : [groupId] devient la chaîne préférée des équipes du match
     * (favorites, sinon les deux) ; si elle l'était déjà, l'étoile est retirée.
     */
    fun togglePreferred(match: Match, groupId: String) {
        scope.launch(io) {
            val keys = preferenceKeys(match, teams() ?: DEFAULT_TEAMS)
            val already = keys.all { store.preferred(profileId, it) == groupId }
            keys.forEach { store.savePreferred(profileId, it, if (already) null else groupId) }
            rebuild()
        }
    }

    /** La chaîne regardée assez longtemps depuis un match devient la première proposée pour sa compétition. */
    fun learned(match: Match, groupId: String) {
        store.saveLearned(profileId, match.league, learn(store.learned(profileId, match.league), groupId))
        scope.launch { rebuild() }
    }
}
