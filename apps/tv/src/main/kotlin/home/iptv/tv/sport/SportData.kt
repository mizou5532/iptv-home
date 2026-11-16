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
    fun learned(profileId: String, league: League): List<String>
    fun saveLearned(profileId: String, league: League, groups: List<String>)
}

class AndroidSportStore(context: Context) : SportStore {
    private val prefs = context.applicationContext.getSharedPreferences("iptv_home_sport", Context.MODE_PRIVATE)
    override fun cached(key: String) = prefs.getString("cache:$key", null)
    override fun saveCached(key: String, text: String, at: Long) { prefs.edit().putString("cache:$key", text).putLong("cached_at", at).apply() }
    override fun cachedAt() = prefs.getLong("cached_at", 0)
    override fun learned(profileId: String, league: League) =
        prefs.getString("learned:$profileId:${league.key}", null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
    override fun saveLearned(profileId: String, league: League, groups: List<String>) {
        prefs.edit().putString("learned:$profileId:${league.key}", groups.joinToString("\n")).apply()
    }
}

private const val NHL_BASE = "https://api-web.nhle.com/v1/schedule/"
private const val ESPN_BASE = "https://site.api.espn.com/apis/site/v2/sports/"

/** Adresses à lire pour 7 jours à partir de [today] (AAAA-MM-JJ) : 2 semaines LNH, une plage par ligue ESPN. */
fun scheduleSources(today: Instant, offsetSeconds: Int): List<Pair<League, String>> {
    fun day(at: Instant): Pair<String, String> {
        val d = java.time.LocalDate.ofEpochDay(localDay(at, offsetSeconds))
        return d.toString() to d.toString().replace("-", "")
    }
    val (iso0, compact0) = day(today)
    val (iso7, compact7) = day(today + 7.days)
    return listOf(League.NHL to "$NHL_BASE$iso0", League.NHL to "$NHL_BASE$iso7") +
        League.entries.filter { it.espnPath != null }.map { it to "$ESPN_BASE${it.espnPath}/scoreboard?dates=$compact0-$compact7" }
}

/**
 * Lit les horaires (un site à la fois), garde chaque réponse lisible en cache et retombe sur le cache si un site ne
 * répond pas ou répond mal. [offline] = au moins une source vient du cache faute de réponse.
 */
class SportSchedule(private val fetch: suspend (String) -> String, private val store: SportStore, private val now: () -> Instant = { Clock.System.now() }) {
    data class Result(val matches: List<Match>, val offline: Boolean, val any: Boolean)

    suspend fun load(offsetSeconds: Int): Result {
        var offline = false
        var any = false
        val matches = scheduleSources(now(), offsetSeconds).flatMap { (league, url) ->
            val key = url.substringAfter("://")
            val fresh = runCatching { fetch(url) }.getOrNull()?.takeIf { it.trimStart().startsWith("{") }
            if (fresh != null) store.saveCached(key, fresh, now().toEpochMilliseconds()) else offline = true
            val text = fresh ?: store.cached(key) ?: return@flatMap emptyList()
            any = true
            if (league == League.NHL) parseNhlSchedule(text) else parseEspnScoreboard(league, text)
        }
        return Result(matches, offline, any)
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
)

const val SPORT_REFRESH_MS = 30 * 60 * 1000L
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
            val result = schedule.load(offsetSeconds())
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
        val board = buildBoard(matches, now(), offsetSeconds())
        val visible = channels()
        val fav = favorites()
        val cands = (board.mine + board.tonight).associate { m -> m.id to sportCandidates(m, store.learned(profileId, m.league), visible, fav) }
        _ui.value = SportUi(board, cands, loading = false, notice = notice)
    }

    /** La chaîne regardée assez longtemps depuis un match devient la première proposée pour sa compétition. */
    fun learned(match: Match, groupId: String) {
        store.saveLearned(profileId, match.league, learn(store.learned(profileId, match.league), groupId))
        scope.launch { rebuild() }
    }
}
