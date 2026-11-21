package home.iptv.server

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import home.iptv.shared.catalog.CatalogDatabase
import home.iptv.shared.catalog.CatalogRepository
import home.iptv.shared.home.LiveSnapshot
import home.iptv.shared.home.SnapCategory
import home.iptv.shared.home.SnapStream
import home.iptv.shared.home.SportBoardReply
import home.iptv.shared.home.SportBoardRequest
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

class SportRouteTest {
    private val checker = TokenChecker(check = { t -> if (t == "bon") "tele" else null }, clockMs = { 0L })

    private fun app(ready: Boolean = true, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CatalogDatabase.Schema.create(driver)
        val repo = CatalogRepository(CatalogDatabase(driver))
        val snap = LiveSnapshot("lrev1", "acct1", 10L, listOf(SnapCategory("1", "CA| SPORT", 0)), listOf(SnapStream("s1", "1", "CA: RDS", null, null, 1, 0, "rds", "RDS")), emptyList())
        repo.replaceLive(snap)
        val svc = SportService(
            liveRepo = repo,
            sync = SportFakeSync(),
            householdId = "h1",
            liveSnapshot = { if (ready) snap else null },
            fetchUrl = { """{"gameWeek":[],"events":[],"standings":[]}""" },
            liveCategories = { emptyList() },
            liveStreams = { emptyList() },
            clockMs = { 1_795_220_000_000L },
        )
        application { homeModule(checker, "0.4.0") { sportRoutes(svc) } }
        block()
    }

    private suspend fun ApplicationTestBuilder.postSport(path: String, body: String = "{}") = client.post(path) {
        header("Authorization", "Bearer bon")
        header("X-Home-Protocol", "1")
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    @Test
    fun returns200Invalidate204AndRejectsBadBody() = app {
        val req = snapshotJson.encodeToString(SportBoardRequest.serializer(), SportBoardRequest("p1", -18000))
        val r = postSport("/v1/sport/board", req)
        assertEquals(HttpStatusCode.OK, r.status)
        val body = r.bodyAsText()
        for (interdit in listOf("username", "password", "player_api")) assertFalse(body.contains(interdit))
        val parsed = snapshotJson.decodeFromString(SportBoardReply.serializer(), body)
        assertEquals("acct1", parsed.account)

        assertEquals(HttpStatusCode.NoContent, postSport("/v1/sport/invalidate?profileId=p1").status)
        assertEquals(HttpStatusCode.BadRequest, postSport("/v1/sport/board", "{pas json").status)
    }

    @Test
    fun returns503WhenNotReadyAnd401WithoutToken() = app(ready = false) {
        val req = snapshotJson.encodeToString(SportBoardRequest.serializer(), SportBoardRequest("p1", -18000))
        assertEquals(HttpStatusCode.ServiceUnavailable, postSport("/v1/sport/board", req).status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/sport/board").status)
    }
}
