package home.iptv.tv.ui

import home.iptv.shared.catalog.RefreshReport
import home.iptv.shared.progress.ProgressClient
import home.iptv.shared.progress.ProgressEntry
import home.iptv.shared.progress.ProgressQueue
import home.iptv.shared.progress.ProgressRow
import home.iptv.shared.progress.WatchedItem
import home.iptv.shared.sync.HiddenItem
import home.iptv.shared.sync.PairingState
import home.iptv.shared.sync.Profile
import home.iptv.shared.sync.ProfilePrefs
import home.iptv.shared.sync.PublishedCategory
import home.iptv.shared.sync.PublishedChannel
import home.iptv.shared.sync.SyncClient
import home.iptv.shared.xtream.Episode
import home.iptv.shared.xtream.Season
import home.iptv.shared.xtream.SeriesCategory
import home.iptv.shared.xtream.SeriesInfo
import home.iptv.shared.xtream.SeriesSummary
import home.iptv.shared.xtream.XtreamCreds
import home.iptv.tv.movies.*
import home.iptv.shared.xtream.MovieCategory
import home.iptv.shared.xtream.MovieInfo
import home.iptv.shared.xtream.MovieSummary
import home.iptv.tv.HomeForProfile
import home.iptv.tv.data.HomeTab
import home.iptv.tv.data.LocalStore
import home.iptv.tv.live.LiveSessions
import home.iptv.tv.live.PrefsStore
import home.iptv.tv.player.PlayerController
import home.iptv.tv.series.EpisodePlayback
import home.iptv.tv.series.EpisodePlaybackFactory
import home.iptv.tv.series.ProgressFlusher
import home.iptv.tv.series.SeriesCatalog
import home.iptv.tv.series.SeriesDetailViewModel
import home.iptv.tv.series.SeriesHomeState
import home.iptv.tv.series.SeriesHomeViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.time.Clock
import kotlin.time.Instant

internal val NO_PREFS = ProfilePrefs(1, emptyList(), emptyList(), emptyList(), null)

internal class UiSync(var prefs: ProfilePrefs = NO_PREFS) : SyncClient {
    override suspend fun publishSeriesCategories(householdId: String, cats: List<PublishedCategory>) {}
    override suspend fun ensureSignedIn() {}
    override suspend fun currentHousehold(): String? = "h1"
    override suspend fun createPairingCode(deviceName: String): PairingState.Waiting = error("inutile")
    override suspend fun credentials(householdId: String): XtreamCreds? = null
    override suspend fun profiles(householdId: String): List<Profile> = emptyList()
    override suspend fun globalHiddenCategories(householdId: String): List<HiddenItem> = emptyList()
    override suspend fun publishLiveCategories(householdId: String, cats: List<PublishedCategory>): Map<String, String?> = emptyMap()
    override suspend fun publishLiveChannels(householdId: String, categoryId: String, fingerprint: String, chunk: Int, isLast: Boolean, channels: List<PublishedChannel>) {}
    override suspend fun profilePrefs(profileId: String): ProfilePrefs = prefs
    override suspend fun prefsRevision(householdId: String): Long = prefs.rev
}

/** Magasin local : préférences connues d'avance et dernier onglet choisi. */
internal class UiStore(prefs: ProfilePrefs?, var tab: HomeTab?, private val base: PrefsStore = PrefsStore(prefs)) : LocalStore by base {
    override fun lastTab(profileId: String): HomeTab? = tab
    override fun saveLastTab(profileId: String, tab: HomeTab) { this.tab = tab }
}

internal class UiProgress : ProgressClient {
    val recorded = mutableListOf<ProgressEntry>()
    override suspend fun record(p: String, entry: ProgressEntry): Boolean { recorded += entry; return true }
    override suspend fun continueWatching(p: String, max: Int): List<ProgressRow> = emptyList()
    override suspend fun setWatched(p: String, items: List<WatchedItem>, watched: Boolean) {}
    override suspend fun setSeriesList(p: String, seriesId: String, on: Boolean) {}
    override suspend fun seriesProgress(p: String, seriesId: String): List<ProgressRow> = emptyList()
}

internal class UiSeriesState : SeriesHomeState {
    override fun savedProgress(profileId: String): List<ProgressRow> = emptyList()
    override fun saveProgress(profileId: String, rows: List<ProgressRow>) {}
    override fun lastNewCheck(profileId: String): Long = 0
    override fun newEpisodeIds(profileId: String): Set<String> = emptySet()
    override fun saveNewCheck(profileId: String, atMs: Long, ids: Set<String>) {}
    override fun lastPublish(householdId: String): Long = Long.MAX_VALUE / 2
    override fun savePublished(householdId: String, atMs: Long) {}
}

/** Catalogue de séries inventé : [cats] catégories de [perCat] séries, chaque série a une saison de 3 épisodes. */
internal class UiCatalog(cats: Int = 6, perCat: Int = 30) : SeriesCatalog {
    val categoriesList = (1..cats).map { SeriesCategory("s$it", "Rangee $it") }
    private val series = categoriesList.associate { c ->
        c.id to (1..perCat).map { n -> SeriesSummary("${c.id}-$n", c.id, "Serie ${c.id}-$n", null, null, null, null, null) }
    }
    val seriesCalls = mutableListOf<String>()
    val infoCalls = java.util.Collections.synchronizedList(mutableListOf<String>())
    override suspend fun categories() = categoriesList
    override suspend fun seriesOf(categoryId: String, hidden: Set<String>): List<SeriesSummary> {
        seriesCalls += categoryId
        return if (categoryId in hidden) emptyList() else series[categoryId].orEmpty()
    }
    override fun cachedCategory(seriesId: String): String? = seriesId.substringBefore('-')
    override suspend fun info(seriesId: String, categoryId: String?): SeriesInfo? {
        infoCalls += "$seriesId@${Thread.currentThread().name}"
        val summary = series.values.flatten().firstOrNull { it.id == seriesId } ?: return null
        return SeriesInfo(summary, listOf(Season(1, "Saison 1", (1..3).map { e ->
            Episode("$seriesId-e$e", 1, e, "Episode $e de $seriesId", 1_200, "mkv", null)
        })))
    }
}

/** Tout ce qu'il faut pour afficher l'accueil d'un profil, avec de faux services et un faux lecteur. */
internal class HomeRig(
    private val tab: HomeTab = HomeTab.SERIES,
    prefs: ProfilePrefs = NO_PREFS,
    val catalog: UiCatalog = UiCatalog(),
    livePerCat: Int = 4,
    /** Sections du profil (réglage iPhone) ; null = toutes. */
    private val sections: List<String>? = null,
) {
    val log = mutableListOf<String>()
    val scope = CoroutineScope(SupervisorJob() + testMain())
    val store = UiStore(prefs, tab)
    val sync = UiSync(prefs)
    val progress = UiProgress()
    val repo = liveRepo(perCat = livePerCat)
    val player = PlayerController { FakePlayer(log) }
    val sessions = LiveSessions(
        parentScope = scope, sync = sync, store = store, repo = repo, householdId = { "h1" },
        refresh = { RefreshReport(0, 0, emptyList(), emptyList()) }, publish = {},
    )
    val queue = ProgressQueue(progress, store) { Clock.System.now() }
    val flusher = ProgressFlusher(queue, scope)
    val movieState = MemoryMovieState()
    val movieClient = FakeMovieClient()
    val movieCatalog = object : FakeMovieCatalog() {
        override suspend fun categories() = (1..8).map { MovieCategory("c$it", "Films rangée $it") }
        override suspend fun moviesOf(categoryId: String, hidden: Set<String>) = (1..30).map { movie("$categoryId-$it", categoryId) }
        override fun cachedCategory(movieId: String) = movieId.substringBefore('-')
        override suspend fun info(movieId: String, categoryId: String) = MovieInfo(movie(movieId, categoryId))
    }
    var searchSession: home.iptv.tv.live.LiveSession? = null
    var lastMoviePlayback: MoviePlayback? = null
    var lastPlayback: EpisodePlayback? = null
    val playbackFactory: EpisodePlaybackFactory = { profile, p ->
        EpisodePlayback(
            profileId = profile.id, controller = player, queue = queue, flusher = flusher, store = store,
            clock = { Clock.System.now() }, scope = scope,
            urlFor = { e -> "fake://episode/${e.id}" },
            loadInfo = { id, category -> catalog.info(id, category) },
            cachedCategory = { id -> catalog.cachedCategory(id) },
            hiddenCategories = { p.value?.hidden.orEmpty().filter { it.itemType == "series_category" }.mapTo(HashSet()) { it.itemId } },
            io = Dispatchers.IO,
        ).also { lastPlayback = it }
    }

    fun show(rule: TvRule, versions: home.iptv.shared.versions.VersionIndexes? = null, sportFactory: home.iptv.tv.sport.SportFactory? = null) {
        rule.setTvContent {
          androidx.compose.runtime.CompositionLocalProvider(home.iptv.tv.feature.LocalVersionIndexes provides versions) {
            HomeForProfile(
                PROFILE.copy(home = if (tab == HomeTab.MOVIES) "films" else tab.storedValue, settings = PROFILE.settings.copy(sections = sections)), sessions, player, urlFor = { "fake://live/${it.id}" }, store = store,
                seriesHome = { profile, p, s ->
                    SeriesHomeViewModel(
                        catalog = catalog, serverProgress = { emptyList() }, localProgress = { emptyList() },
                        publishCategories = {}, state = UiSeriesState(), profileId = profile.id, householdId = { "h1" },
                        prefs = p, scope = s,
                    )
                },
                seriesDetail = { profile, p, seriesId, categoryId, s ->
                    SeriesDetailViewModel(
                        seriesId = seriesId, categoryId = categoryId, catalog = catalog, progress = progress,
                        profileId = profile.id, serverProgress = { emptyList() }, localProgress = { emptyList() },
                        dropQueued = {}, prefs = p, scope = s,
                    )
                },
                episodePlayback = playbackFactory,
                requestFlush = {},
                movieHome = { profile, p, s -> MovieHomeViewModel(movieCatalog, { emptyList() }, { emptyList() }, { emptyList() }, {}, movieState, profile.id, { "h1" }, p, s) },
                movieDetail = { profile, p, id, cat, s -> MovieDetailViewModel(id, cat, movieCatalog, movieClient, profile.id, movieState, { emptyList() }, {}, p, s) },
                moviePlayback = { profile, p -> MoviePlayback(profile.id, player, queue, flusher, store, { Clock.System.now() }, scope,
                    { "fake://movie/${it.id}" }, { id, cat -> movieCatalog.info(id, cat) }, { p.value?.movieHidden() }, Dispatchers.IO, movieCatalog::cachedCategory).also { lastMoviePlayback = it } },
                searchFactory = { section, session, searchScope ->
                    searchSession = session
                    val results = when (section) {
                        home.iptv.shared.search.SearchSection.SERIES -> (1..3).map { home.iptv.tv.search.SearchResult("s1-$it", "Serie s1-$it", null, "s1") }
                        home.iptv.shared.search.SearchSection.MOVIES -> (1..3).map { home.iptv.tv.search.SearchResult("c1-$it", "Film c1-$it", null, "c1") }
                        else -> home.iptv.shared.groups.buildGroups(repo.allGroupSources(), home.iptv.shared.groups.GroupSnapshot(0, 1, emptyList())).map {
                            home.iptv.tv.search.SearchResult(it.id, it.title, null, it.sources.first().categoryId, it.sources.first().id)
                        }
                    }
                    home.iptv.tv.search.SearchViewModel(section, { results.map { home.iptv.shared.search.SearchItem(it.id, it.title, setOf(it.categoryId)) } },
                        { id -> results.firstOrNull { it.id == id } }, session.watcher.prefs,
                        { p -> p.hidden.filter { it.itemType == "series_category" || it.itemType == "movie_category" || it.itemType == "live_category" }.mapTo(HashSet()) { it.itemId } },
                        kotlinx.coroutines.flow.emptyFlow(), kotlinx.coroutines.flow.MutableStateFlow(emptyMap()), searchScope, Dispatchers.IO)
                },
                sportFactory = sportFactory,
                onChangeProfile = {},
            )
          }
        }
    }
}

internal fun Instant.Companion.zero() = fromEpochSeconds(0)
