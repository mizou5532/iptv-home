package home.iptv.server

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import home.iptv.shared.catalog.CatalogDatabase
import home.iptv.shared.catalog.CatalogRepository
import home.iptv.shared.groups.GroupSnapshot
import home.iptv.shared.groups.ManualGroup
import home.iptv.shared.home.LiveSnapshot
import home.iptv.shared.home.SearchRequest
import home.iptv.shared.home.SnapCategory
import home.iptv.shared.home.SnapStream
import home.iptv.shared.home.SnapVodCategory
import home.iptv.shared.home.VodManifest
import home.iptv.shared.movies.MovieRepository
import home.iptv.shared.movies.MovieSource
import home.iptv.shared.search.PersonCredit
import home.iptv.shared.search.PersonCredits
import home.iptv.shared.search.PersonInfo
import home.iptv.shared.series.SeriesRepository
import home.iptv.shared.series.SeriesSource
import home.iptv.shared.versions.LanguagePrefs
import home.iptv.shared.xtream.MovieCategory
import home.iptv.shared.xtream.MovieInfo
import home.iptv.shared.xtream.MovieSummary
import home.iptv.shared.xtream.SeriesCategory
import home.iptv.shared.xtream.SeriesInfo
import home.iptv.shared.xtream.SeriesSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SearchServiceTest {
    private fun setup(
        splits: Set<String> = emptySet(),
        manual: GroupSnapshot = GroupSnapshot(0, 1, emptyList()),
        hiddenMovies: Set<String> = setOf("m_hidden"),
        ready: Boolean = true,
    ): SearchService {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CatalogDatabase.Schema.create(driver)
        val db = CatalogDatabase(driver)
        val liveRepo = CatalogRepository(db)
        val snap = LiveSnapshot(
            rev = "lrev1", account = "acct1", builtAt = 100L,
            categories = listOf(SnapCategory("1", "CA| SPORT", 0), SnapCategory("2", "CA| INFO", 1)),
            streams = listOf(
                SnapStream("s1", "1", "CA: RDS HD", null, null, 1, 0, "rds", "RDS"),
                SnapStream("s2", "1", "CA: RDS FHD", null, null, 2, 1, "rds", "RDS"),
                SnapStream("s3", "2", "CA: Canadiens TV", null, null, 3, 0, "canadiens tv", "Canadiens TV"),
            ),
            hidden = listOf("99"),
        )
        liveRepo.replaceLive(snap)

        val moviesRepo = MovieRepository(db, object : MovieSource {
            override suspend fun movieCategories() = emptyList<MovieCategory>()
            override suspend fun movies(categoryId: String) = emptyList<MovieSummary>()
            override suspend fun movieInfo(movieId: String): MovieInfo? = null
        })
        moviesRepo.replaceCategories(
            listOf(
                MovieCategory("m1", "FR - Action"),
                MovieCategory("m2", "QC - Action"),
                MovieCategory("m3", "AR - Drame"), // hors vitrine
                MovieCategory("m_hidden", "ADULTE"),
            ),
            100L,
        )
        moviesRepo.replaceCategoryMovies(
            "m1",
            listOf(
                MovieSummary("101", "m1", "FR - Dune (2021)", "http://img/1.jpg", "2021", "SF", null, null, null, "mkv", null),
                MovieSummary("104", "m1", "FR - Sicario (2015)", null, "2015", "Thriller", null, null, null, "mkv", null),
            ),
            100L,
        )
        moviesRepo.replaceCategoryMovies(
            "m2",
            listOf(MovieSummary("102", "m2", "QC - Dune (2021)", "http://img/2.jpg", "2021", "SF", null, null, null, "mkv", null)),
            100L,
        )
        moviesRepo.replaceCategoryMovies(
            "m3",
            listOf(MovieSummary("103", "m3", "AR - Dune Mirage (2020)", null, "2020", "Drame", null, null, null, "mkv", null)),
            100L,
        )
        moviesRepo.replaceCategoryMovies(
            "m_hidden",
            listOf(MovieSummary("109", "m_hidden", "Dune Secret", null, "2021", null, null, null, null, "mkv", null)),
            100L,
        )

        val seriesRepo = SeriesRepository(db, object : SeriesSource {
            override suspend fun seriesCategories() = emptyList<SeriesCategory>()
            override suspend fun series(categoryId: String) = emptyList<SeriesSummary>()
            override suspend fun seriesInfo(seriesId: String): SeriesInfo? = null
        })
        seriesRepo.replaceCategories(listOf(SeriesCategory("sc1", "QUÉBEC SÉRIES")), 100L)
        seriesRepo.replaceCategorySeries(
            "sc1",
            listOf(SeriesSummary("201", "sc1", "QC - Série Québécoise", null, "2023", null, null, null)),
            100L,
        )

        val mManifest = VodManifest("mrev1", "acct1", 100L, listOf(SnapVodCategory("m1", "FR - Action", 0, "r1")), hiddenMovies.toList())
        val sManifest = VodManifest("srev1", "acct1", 100L, listOf(SnapVodCategory("sc1", "QUÉBEC SÉRIES", 0, "r1")), emptyList())

        return SearchService(
            liveRepo = liveRepo,
            moviesRepo = moviesRepo,
            seriesRepo = seriesRepo,
            liveSnapshot = { if (ready) snap else null },
            movieManifest = { if (ready) mManifest else null },
            seriesManifest = { if (ready) sManifest else null },
            hiddenMovies = { hiddenMovies },
            hiddenSeries = { emptySet() },
            hiddenLoaded = { ready },
            splits = { splits },
            splitsVersion = { 1L },
            manualGroups = { manual },
        )
    }

    @Test
    fun moviesGroupsVersionsShowcaseFirstAndRespectsHiddenAndOnlyMine() {
        val svc = setup()
        val r = svc.search(SearchRequest("movies", "dune"))
        assertEquals("acct1", r.account)
        // Vitrine groupée en 1er (101 + 102 réunis), puis hors vitrine (103), jamais m_hidden (109).
        assertEquals(2, r.results.size)
        assertEquals("Dune", r.results[0].title)
        assertEquals(setOf("101", "102"), r.results[0].versions.map { it.id }.toSet())
        assertEquals("103", r.results[1].id)

        // Masquage par profil : masquer m1 retire 101 mais laisse 102 visible !
        val profileMasked = svc.search(SearchRequest("movies", "dune", hiddenCategories = listOf("m1")))
        assertEquals(listOf("102"), profileMasked.results[0].versions.map { it.id })

        // Seulement mes langues (qc, fr) : AR (langue connue hors liste) disparaît.
        val onlyMine = svc.search(
            SearchRequest("movies", "dune", languages = LanguagePrefs(order = listOf("qc", "fr"), onlyMine = true)),
        )
        assertEquals(1, onlyMine.results.size)
        assertEquals("Dune", onlyMine.results[0].title)
    }

    @Test
    fun moviesSplitSeparatesVersionsAndPersonSearchWorks() {
        val svc = setup(splits = setOf("movie:102"))
        val r = svc.search(SearchRequest("movies", "dune"))
        val duneIds = r.results.filter { it.title == "Dune" }.map { it.id }.toSet()
        assertEquals(setOf("101", "102"), duneIds)

        val person = svc.search(
            SearchRequest(
                section = "movies",
                query = "denis villeneuve",
                personCredits = PersonCredits(
                    person = PersonInfo("Denis Villeneuve", "Directing"),
                    credits = listOf(
                        PersonCredit("movie", "Dune", "Dune", 2021, "realisateur"),
                        PersonCredit("movie", "Sicario", "Sicario", 2015, "realisateur"),
                    ),
                ),
            ),
        )
        assertTrue(person.results.any { it.title == "Sicario" && it.note == "Réalisé par Denis Villeneuve" })
        assertTrue(person.results.none { it.categoryId == "m_hidden" })
    }

    @Test
    fun liveSupportsNicknamesHiddenChannelsAndManualGroups() {
        val manual = GroupSnapshot(
            rev = 5, rulesVersion = 1,
            groups = listOf(ManualGroup("m-rds", "Mon RDS Perso", listOf("s1", "s2"), emptyList())),
        )
        val svc = setup(manual = manual)

        // Surnom habs -> Canadiens TV.
        val habs = svc.search(SearchRequest("live", "habs"))
        assertEquals(listOf("Canadiens TV"), habs.results.map { it.title })

        // Groupe manuel respecté.
        val rds = svc.search(SearchRequest("live", "perso"))
        assertEquals(listOf("manual:m-rds"), rds.results.map { it.id })

        // Chaîne masquée retirée.
        val hidden = svc.search(SearchRequest("live", "habs", hiddenChannels = listOf("s3")))
        assertTrue(hidden.results.isEmpty())
    }

    @Test
    fun accentsAndTyposMatchAndNotReadyThrows() {
        val svc = setup()
        val res = svc.search(SearchRequest("series", "quebecoise"))
        assertEquals(listOf("201"), res.results.map { it.id })

        val notReady = setup(ready = false)
        assertFailsWith<SearchNotReadyException> { notReady.search(SearchRequest("movies", "dune")) }
    }
}
