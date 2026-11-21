package home.iptv.server

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import home.iptv.shared.catalog.CatalogDatabase
import home.iptv.shared.movies.MovieRepository
import home.iptv.shared.movies.MovieSource
import home.iptv.shared.series.SeriesRepository
import home.iptv.shared.series.SeriesSource
import home.iptv.shared.xtream.Episode
import home.iptv.shared.xtream.MovieCategory
import home.iptv.shared.xtream.MovieInfo
import home.iptv.shared.xtream.MovieSummary
import home.iptv.shared.xtream.Season
import home.iptv.shared.xtream.SeriesCategory
import home.iptv.shared.xtream.SeriesInfo
import home.iptv.shared.xtream.SeriesSummary
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Clock
import kotlin.time.Instant

class InfoCacheTest {
    private var nowMs = 1_000_000L
    private val clock = object : Clock {
        override fun now() = Instant.fromEpochMilliseconds(nowMs)
    }

    private fun newDb(): CatalogDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CatalogDatabase.Schema.create(driver)
        return CatalogDatabase(driver)
    }

    private fun sampleMovie(id: String, cat: String) = MovieSummary(
        id, cat, "Film $id", null, "2024", "Action", "Résumé", "7.5", 1_700_000_000L, "mkv", 7200, null,
    )

    private fun sampleSeries(id: String, cat: String) = SeriesSummary(
        id, cat, "Série $id", null, "2023", "Drame", "Histoire", "8.0", null,
    )

    @Test
    fun secondCallUsesSqliteCacheWithoutCallingProvider() = runTest {
        val db = newDb()
        var movieCalls = 0
        var seriesCalls = 0
        var beforeCalls = 0
        val movies = MovieRepository(db, object : MovieSource {
            override suspend fun movieCategories() = emptyList<MovieCategory>()
            override suspend fun movies(categoryId: String) = emptyList<MovieSummary>()
            override suspend fun movieInfo(movieId: String): MovieInfo {
                movieCalls++
                return MovieInfo(sampleMovie(movieId, "10"))
            }
        }, clock)
        val series = SeriesRepository(db, object : SeriesSource {
            override suspend fun seriesCategories() = emptyList<SeriesCategory>()
            override suspend fun series(categoryId: String) = emptyList<SeriesSummary>()
            override suspend fun seriesInfo(seriesId: String): SeriesInfo {
                seriesCalls++
                return SeriesInfo(
                    sampleSeries(seriesId, "20"),
                    listOf(Season(1, "Saison 1", listOf(Episode("e1", 1, 1, "Pilote", 2400, "mkv", null)))),
                )
            }
        }, clock)
        val cache = InfoCache(movies, series, beforeFetch = { beforeCalls++ })

        assertEquals("Film m1", cache.movieInfo("m1")?.summary?.name)
        assertEquals("Film m1", cache.movieInfo("m1")?.summary?.name)
        assertEquals(1, movieCalls)
        assertEquals(1, beforeCalls)

        assertEquals("Série s1", cache.seriesInfo("s1")?.summary?.name)
        assertEquals("Série s1", cache.seriesInfo("s1")?.summary?.name)
        assertEquals(1, seriesCalls)
        assertEquals(2, beforeCalls)
    }

    @Test
    fun hiddenCategoryRefusedWithoutCallingProvider() = runTest {
        val db = newDb()
        var providerCalled = false
        val movies = MovieRepository(db, object : MovieSource {
            override suspend fun movieCategories() = emptyList<MovieCategory>()
            override suspend fun movies(categoryId: String) = emptyList<MovieSummary>()
            override suspend fun movieInfo(movieId: String): MovieInfo {
                providerCalled = true
                return MovieInfo(sampleMovie(movieId, "99"))
            }
        }, clock)
        val series = SeriesRepository(db, object : SeriesSource {
            override suspend fun seriesCategories() = emptyList<SeriesCategory>()
            override suspend fun series(categoryId: String) = emptyList<SeriesSummary>()
            override suspend fun seriesInfo(seriesId: String): SeriesInfo {
                providerCalled = true
                return SeriesInfo(sampleSeries(seriesId, "88"), emptyList())
            }
        }, clock)
        movies.replaceCategoryMovies("99", listOf(sampleMovie("m99", "99")), nowMs)
        series.replaceCategorySeries("88", listOf(sampleSeries("s88", "88")), nowMs)

        val cache = InfoCache(movies, series, hiddenMovieCats = { setOf("99") }, hiddenSeriesCats = { setOf("88") })

        assertFailsWith<HiddenCategoryException> { cache.movieInfo("m99") }
        assertFailsWith<HiddenCategoryException> { cache.seriesInfo("s88") }
        assertEquals(false, providerCalled)
    }

    @Test
    fun limitsConcurrentProviderFetchesToThree() = runTest {
        val db = newDb()
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val movies = MovieRepository(db, object : MovieSource {
            override suspend fun movieCategories() = emptyList<MovieCategory>()
            override suspend fun movies(categoryId: String) = emptyList<MovieSummary>()
            override suspend fun movieInfo(movieId: String): MovieInfo {
                val cur = active.incrementAndGet()
                peak.accumulateAndGet(cur, ::maxOf)
                delay(50)
                active.decrementAndGet()
                return MovieInfo(sampleMovie(movieId, "10"))
            }
        }, clock)
        val series = SeriesRepository(db, object : SeriesSource {
            override suspend fun seriesCategories() = emptyList<SeriesCategory>()
            override suspend fun series(categoryId: String) = emptyList<SeriesSummary>()
            override suspend fun seriesInfo(seriesId: String): SeriesInfo? = null
        }, clock)
        val cache = InfoCache(movies, series)

        (1..8).map { i -> async { cache.movieInfo("m$i") } }.awaitAll()
        assertEquals(3, peak.get())
    }
}
