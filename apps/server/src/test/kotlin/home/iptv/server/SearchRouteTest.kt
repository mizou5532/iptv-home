package home.iptv.server

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import home.iptv.shared.catalog.CatalogDatabase
import home.iptv.shared.catalog.CatalogRepository
import home.iptv.shared.home.LiveSnapshot
import home.iptv.shared.home.SearchReply
import home.iptv.shared.home.SearchRequest
import home.iptv.shared.home.SnapCategory
import home.iptv.shared.home.SnapStream
import home.iptv.shared.movies.MovieRepository
import home.iptv.shared.movies.MovieSource
import home.iptv.shared.series.SeriesRepository
import home.iptv.shared.series.SeriesSource
import home.iptv.shared.xtream.MovieCategory
import home.iptv.shared.xtream.MovieInfo
import home.iptv.shared.xtream.MovieSummary
import home.iptv.shared.xtream.SeriesCategory
import home.iptv.shared.xtream.SeriesInfo
import home.iptv.shared.xtream.SeriesSummary
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SearchRouteTest {
    private val checker = TokenChecker(check = { t -> if (t == "bon") "tele" else null }, clockMs = { 0L })

    private fun app(ready: Boolean = true, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CatalogDatabase.Schema.create(driver)
        val db = CatalogDatabase(driver)
        val liveRepo = CatalogRepository(db)
        val snap = LiveSnapshot(
            "lrev1", "acct1", 10L,
            listOf(SnapCategory("1", "CA| SPORT", 0)),
            listOf(SnapStream("s1", "1", "CA: TSN 1", null, null, 1, 0, "tsn 1", "TSN 1")),
            emptyList(),
        )
        liveRepo.replaceLive(snap)
        val moviesRepo = MovieRepository(db, object : MovieSource {
            override suspend fun movieCategories() = emptyList<MovieCategory>()
            override suspend fun movies(categoryId: String) = emptyList<MovieSummary>()
            override suspend fun movieInfo(movieId: String): MovieInfo? = null
        })
        val seriesRepo = SeriesRepository(db, object : SeriesSource {
            override suspend fun seriesCategories() = emptyList<SeriesCategory>()
            override suspend fun series(categoryId: String) = emptyList<SeriesSummary>()
            override suspend fun seriesInfo(seriesId: String): SeriesInfo? = null
        })
        val svc = SearchService(
            liveRepo, moviesRepo, seriesRepo,
            liveSnapshot = { if (ready) snap else null },
            movieManifest = { null },
            seriesManifest = { null },
            hiddenMovies = { emptySet() },
            hiddenSeries = { emptySet() },
            hiddenLoaded = { ready },
            splits = { emptySet() },
            splitsVersion = { 1L },
        )
        application { homeModule(checker, "0.3.0") { searchRoute(svc) } }
        block()
    }

    private suspend fun ApplicationTestBuilder.postSearch(body: String) = client.post("/v1/search") {
        header("Authorization", "Bearer bon")
        header("X-Home-Protocol", "1")
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    @Test
    fun liveSearchReturns200AndRejectsBadInputOrNotReady() = app {
        val req = snapshotJson.encodeToString(SearchRequest.serializer(), SearchRequest("live", "tsn"))
        val r = postSearch(req)
        assertEquals(HttpStatusCode.OK, r.status)
        val body = r.bodyAsText()
        for (interdit in listOf("username", "password", "player_api")) assertFalse(body.contains(interdit))
        val parsed = snapshotJson.decodeFromString(SearchReply.serializer(), body)
        assertEquals(listOf("TSN 1"), parsed.results.map { it.title })

        // Section films non prête -> 503.
        val mReq = snapshotJson.encodeToString(SearchRequest.serializer(), SearchRequest("movies", "dune"))
        assertEquals(HttpStatusCode.ServiceUnavailable, postSearch(mReq).status)

        // Section invalide ou JSON cassé -> 400.
        assertEquals(HttpStatusCode.BadRequest, postSearch("{pas json").status)
        val badSec = snapshotJson.encodeToString(SearchRequest.serializer(), SearchRequest("autre", "a"))
        assertEquals(HttpStatusCode.BadRequest, postSearch(badSec).status)
    }

    @Test
    fun requiresToken() = app {
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/search").status)
    }
}
