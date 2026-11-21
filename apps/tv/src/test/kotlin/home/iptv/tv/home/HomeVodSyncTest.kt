package home.iptv.tv.home

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import home.iptv.shared.catalog.CatalogDatabase
import home.iptv.shared.home.MovieCategoryReply
import home.iptv.shared.home.SeriesCategoryReply
import home.iptv.shared.home.SnapMovie
import home.iptv.shared.home.SnapSeries
import home.iptv.shared.home.SnapVersion
import home.iptv.shared.home.SnapVersionTag
import home.iptv.shared.home.SnapVodCategory
import home.iptv.shared.home.VersionsReply
import home.iptv.shared.home.VodManifest
import home.iptv.shared.meta.MediaKind
import home.iptv.shared.movies.MovieRepository
import home.iptv.shared.movies.MovieSource
import home.iptv.shared.search.SearchItem
import home.iptv.shared.series.SeriesRepository
import home.iptv.shared.series.SeriesSource
import home.iptv.shared.versions.VersionIndexes
import home.iptv.shared.xtream.MovieCategory
import home.iptv.shared.xtream.MovieInfo
import home.iptv.shared.xtream.MovieSummary
import home.iptv.shared.xtream.SeriesCategory
import home.iptv.shared.xtream.SeriesInfo
import home.iptv.shared.xtream.SeriesSummary
import home.iptv.tv.data.LocalStore
import home.iptv.tv.live.PrefsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

private class FakeHomeVodClient : HomeVodClient {
    override val state: MutableStateFlow<HomeState> = MutableStateFlow(HomeState.Present("0.2.0"))
    var movieManifestFn: (String?) -> VodManifestResult = { VodManifestResult.Unavailable }
    var seriesManifestFn: (String?) -> VodManifestResult = { VodManifestResult.Unavailable }
    var movieCategoryFn: (String, String?) -> MovieCategoryResult = { _, _ -> MovieCategoryResult.Unavailable }
    var seriesCategoryFn: (String, String?) -> SeriesCategoryResult = { _, _ -> SeriesCategoryResult.Unavailable }
    var movieInfoFn: (String) -> MovieInfo? = { null }
    var seriesInfoFn: (String) -> SeriesInfo? = { null }
    var versionsFn: (MediaKind, List<String>) -> VersionsReply? = { _, _ -> null }
    var invalidateCalls = 0
    val movieCategoryCalls = mutableListOf<String>()
    val versionCalls = mutableListOf<Pair<MediaKind, List<String>>>()

    override suspend fun movieManifest(since: String?) = movieManifestFn(since)
    override suspend fun seriesManifest(since: String?) = seriesManifestFn(since)
    override suspend fun movieCategory(id: String, since: String?): MovieCategoryResult {
        movieCategoryCalls += id
        return movieCategoryFn(id, since)
    }
    override suspend fun seriesCategory(id: String, since: String?) = seriesCategoryFn(id, since)
    override suspend fun movieInfo(id: String) = movieInfoFn(id)
    override suspend fun seriesInfo(id: String) = seriesInfoFn(id)
    override suspend fun versions(kind: MediaKind, ids: List<String>): VersionsReply? {
        versionCalls += kind to ids
        return versionsFn(kind, ids)
    }
    override suspend fun invalidateVersions() { invalidateCalls++ }
}

private class MemVodStore : LocalStore by PrefsStore() {
    var contactMs: Long? = null
    val manifests = HashMap<String, String?>()
    val catRevs = HashMap<String, Map<String, String>>()
    override fun lastServerContactMs(): Long? = contactMs
    override fun saveLastServerContactMs(ms: Long) { contactMs = ms }
    override fun lastVodManifestRev(kind: String): String? = manifests[kind]
    override fun saveLastVodManifestRev(kind: String, rev: String?) { manifests[kind] = rev }
    override fun vodCategoryRevs(kind: String): Map<String, String> = catRevs[kind].orEmpty()
    override fun saveVodCategoryRevs(kind: String, revs: Map<String, String>) { catRevs[kind] = revs }
    override fun clearVodRevs() { manifests.clear(); catRevs.clear() }
}

class HomeVodSyncTest {
    private var nowMs = 1_000_000_000L
    private val clock = object : Clock { override fun now() = Instant.fromEpochMilliseconds(nowMs) }

    private fun newDb(): CatalogDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CatalogDatabase.Schema.create(driver)
        return CatalogDatabase(driver)
    }

    @Test
    fun vodManifestFromOtherAccountIgnored() = runTest {
        val db = newDb()
        val store = MemVodStore()
        val home = FakeHomeVodClient()
        home.movieManifestFn = {
            VodManifestResult.Fresh(VodManifest("r1", "AUTRE_COMPTE", nowMs, listOf(SnapVodCategory("1", "Action", 0, "cr1")), emptyList()))
        }
        val movies = MovieRepository(db, object : MovieSource {
            override suspend fun movieCategories() = emptyList<MovieCategory>()
            override suspend fun movies(categoryId: String) = emptyList<MovieSummary>()
            override suspend fun movieInfo(movieId: String): MovieInfo? = null
        }, clock)
        val series = SeriesRepository(db, object : SeriesSource {
            override suspend fun seriesCategories() = emptyList<SeriesCategory>()
            override suspend fun series(categoryId: String) = emptyList<SeriesSummary>()
            override suspend fun seriesInfo(seriesId: String): SeriesInfo? = null
        }, clock)

        val sync = HomeVodSync(home, movies, series, store, { "MON_COMPTE" }, { nowMs })
        val out = sync.syncMovies()

        assertFalse(out.fromServer)
        assertEquals(0, out.downloadedCategories)
        assertTrue(movies.cachedCategories().isEmpty())
        assertTrue(home.movieCategoryCalls.isEmpty())
    }

    @Test
    fun onlyChangedCategoriesDownloaded() = runTest {
        val db = newDb()
        val store = MemVodStore()
        val home = FakeHomeVodClient()
        val movies = MovieRepository(db, object : MovieSource {
            override suspend fun movieCategories() = emptyList<MovieCategory>()
            override suspend fun movies(categoryId: String) = emptyList<MovieSummary>()
            override suspend fun movieInfo(movieId: String): MovieInfo? = null
        }, clock)
        val series = SeriesRepository(db, object : SeriesSource {
            override suspend fun seriesCategories() = emptyList<SeriesCategory>()
            override suspend fun series(categoryId: String) = emptyList<SeriesSummary>()
            override suspend fun seriesInfo(seriesId: String): SeriesInfo? = null
        }, clock)

        val m1 = VodManifest(
            "mrev1", "acct1", nowMs,
            listOf(SnapVodCategory("1", "FR - Action", 0, "c1-v1"), SnapVodCategory("2", "FR - Comédie", 1, "c2-v1")),
            emptyList(),
        )
        home.movieManifestFn = { since -> if (since == "mrev1") VodManifestResult.Unchanged else VodManifestResult.Fresh(m1) }
        home.movieCategoryFn = { id, _ ->
            MovieCategoryResult.Fresh(MovieCategoryReply(id, "c$id-v1", "acct1", listOf(SnapMovie("f$id", id, "Film $id"))))
        }

        val sync = HomeVodSync(home, movies, series, store, { "acct1" }, { nowMs })
        val first = sync.syncMovies()
        assertEquals(VodSyncOutcome(true, 2), first)
        assertEquals(listOf("1", "2"), home.movieCategoryCalls)

        // Aucun changement -> 304, 0 téléchargement.
        home.movieCategoryCalls.clear()
        val unchanged = sync.syncMovies()
        assertEquals(VodSyncOutcome(true, 0), unchanged)
        assertTrue(home.movieCategoryCalls.isEmpty())

        // Seule la catégorie 2 change -> seule la catégorie 2 est téléchargée !
        val m2 = VodManifest(
            "mrev2", "acct1", nowMs + 1000,
            listOf(SnapVodCategory("1", "FR - Action", 0, "c1-v1"), SnapVodCategory("2", "FR - Comédie", 1, "c2-v2")),
            emptyList(),
        )
        home.movieManifestFn = { VodManifestResult.Fresh(m2) }
        home.movieCategoryFn = { id, _ ->
            MovieCategoryResult.Fresh(MovieCategoryReply(id, "c$id-v2", "acct1", listOf(SnapMovie("f$id-new", id, "Nouveau $id"))))
        }
        val second = sync.syncMovies()
        assertEquals(VodSyncOutcome(true, 1), second)
        assertEquals(listOf("2"), home.movieCategoryCalls)
        assertEquals(listOf("f2-new"), movies.cachedMoviesOf("2").map { it.id })
        assertEquals(listOf("f1"), movies.cachedMoviesOf("1").map { it.id })
    }

    @Test
    fun infoFallsBackToProvider() = runTest {
        val db = newDb()
        val store = MemVodStore()
        val home = FakeHomeVodClient()
        var providerInfoCalls = 0
        val fallback = object : MovieSource {
            override suspend fun movieCategories() = emptyList<MovieCategory>()
            override suspend fun movies(categoryId: String) = emptyList<MovieSummary>()
            override suspend fun movieInfo(movieId: String): MovieInfo {
                providerInfoCalls++
                return MovieInfo(MovieSummary(movieId, "1", "Fournisseur $movieId", null, null, null, null, null, null, "mkv", null))
            }
        }
        lateinit var repo: MovieRepository
        val source = homeMovieSource(home, fallback, { repo }, store, { "acct1" }, { nowMs })
        repo = MovieRepository(db, source, clock)

        // Serveur maison répond -> fournisseur jamais appelé.
        home.movieInfoFn = { id -> MovieInfo(MovieSummary(id, "1", "Maison $id", null, null, null, null, null, null, "mkv", null)) }
        assertEquals("Maison m1", source.movieInfo("m1")?.summary?.name)
        assertEquals(0, providerInfoCalls)

        // Serveur maison rend null -> secours transparent chez le fournisseur.
        home.movieInfoFn = { null }
        assertEquals("Fournisseur m2", source.movieInfo("m2")?.summary?.name)
        assertEquals(1, providerInfoCalls)
    }

    @Test
    fun versionsServedFromHomeWithoutBuildingLocalIndex() = runTest {
        val home = FakeHomeVodClient()
        var localBuilt = false
        val local = VersionIndexes(
            items = { localBuilt = true; emptyList() },
            categoryNames = { emptyMap() },
        )
        home.versionsFn = { _, _ ->
            VersionsReply(
                "vrev1",
                mapOf(
                    "dune|2021" to listOf(
                        SnapVersion("101", "1", "FR - Dune (2021)", SnapVersionTag("fr")),
                        SnapVersion("102", "2", "QC - Dune (2021)", SnapVersionTag("qc")),
                    ),
                ),
            )
        }
        val store = HomeVersionStore(home, local)

        val idx = store.resolve(MediaKind.MOVIE, listOf("101"))
        assertEquals(setOf("101", "102"), idx.versionsOf("101").map { it.id }.toSet())
        assertFalse(localBuilt, "L'index local ne doit pas être construit quand le serveur maison répond")
        assertEquals(1, home.versionCalls.size)

        // Deuxième demande pour 102 (déjà dans le cache LRU) : 0 nouvel appel réseau.
        val cached = store.versionsOf(MediaKind.MOVIE, "102")
        assertEquals(setOf("101", "102"), cached.map { it.id }.toSet())
        assertEquals(1, home.versionCalls.size)
    }

    @Test
    fun versionsFallBackToLocalWhenServerAbsent() = runTest {
        val home = FakeHomeVodClient()
        home.state.value = HomeState.Absent
        home.versionsFn = { _, _ -> null }
        val local = VersionIndexes(
            items = {
                listOf(
                    SearchItem("101", "FR - Dune (2021)", setOf("1"), null),
                    SearchItem("102", "QC - Dune (2021)", setOf("2"), null),
                )
            },
            categoryNames = { mapOf("1" to "FR - SF", "2" to "QC - SF") },
        )
        val store = HomeVersionStore(home, local)

        val vs = store.versionsOf(MediaKind.MOVIE, "101")
        assertEquals(setOf("101", "102"), vs.map { it.id }.toSet())
    }

    @Test
    fun splitInvalidatesServerAndTvVersions() = runTest {
        val home = FakeHomeVodClient()
        var savedSplit: Pair<String, Boolean>? = null
        val local = VersionIndexes(
            items = { emptyList() },
            categoryNames = { emptyMap() },
            saveSplit = { key, on -> savedSplit = key to on },
        )
        home.versionsFn = { _, _ ->
            VersionsReply("v1", mapOf("dune|2021" to listOf(SnapVersion("101", "1", "FR - Dune", SnapVersionTag("fr")))))
        }
        val store = HomeVersionStore(home, local)
        store.resolve(MediaKind.MOVIE, listOf("101"))
        assertEquals(1, store.current(MediaKind.MOVIE).groups.size)

        store.split(MediaKind.MOVIE, "101")

        assertEquals("movie:101" to true, savedSplit)
        assertEquals(1, home.invalidateCalls)
        assertTrue(store.current(MediaKind.MOVIE).groups.isEmpty())
    }

    @Test
    fun homeServerActiveSuppressesFallbackWithin24h() {
        val home = FakeHomeVodClient()
        val store = MemVodStore()

        home.state.value = HomeState.Present("0.2.0")
        assertTrue(isHomeServerActive(home, store) { nowMs })

        // Serveur absent depuis 2 h -> toujours considéré actif (pas de double publication iPhone).
        home.state.value = HomeState.Absent
        store.contactMs = nowMs - 2L * 3_600_000L
        assertTrue(isHomeServerActive(home, store) { nowMs })

        // Serveur absent depuis 25 h -> bascule en secours.
        store.contactMs = nowMs - 25L * 3_600_000L
        assertFalse(isHomeServerActive(home, store) { nowMs })
    }
}
