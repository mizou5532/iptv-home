package home.iptv.server

import home.iptv.shared.xtream.EpgEntry
import home.iptv.shared.xtream.EpgSource
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class EpgRouteTest {
    private val checker = TokenChecker(check = { t -> if (t == "bon") "tele" else null }, clockMs = { 0L })
    private val calls = mutableListOf<Int>()

    private fun app(fail: Boolean = false, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        val src = EpgSource { _, limit ->
            calls += limit
            if (fail) throw IOException("http://fournisseur.example/player_api.php?username=alice&password=secret")
            listOf(EpgEntry("Journal", Instant.fromEpochMilliseconds(1_000), Instant.fromEpochMilliseconds(2_000), true))
        }
        val cache = EpgCache(src, { 0L })
        application { homeModule(checker, "9.9") { epgRoute(cache) } }
        block()
    }

    private suspend fun ApplicationTestBuilder.get(path: String, token: String = "bon") = client.get(path) {
        header("Authorization", "Bearer $token")
        header("X-Home-Protocol", "1")
    }

    @Test
    fun returnsEntriesAndCapsLimit() = app {
        val r = get("/v1/epg/42?limit=500&full=0")
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("Journal"))
        assertEquals(listOf(8), calls)
    }

    @Test
    fun garbageIs400() = app {
        for (p in listOf("/v1/epg/42?limit=abc", "/v1/epg/42?limit=0", "/v1/epg/42?limit=-1", "/v1/epg/42?full=2", "/v1/epg/a%20b", "/v1/epg/4.2")) {
            assertEquals(HttpStatusCode.BadRequest, get(p).status, p)
        }
        assertTrue(calls.isEmpty())
    }

    @Test
    fun requiresToken() = app {
        assertEquals(HttpStatusCode.Unauthorized, get("/v1/epg/42", token = "mauvais").status)
    }

    @Test
    fun providerFailureIs502WithoutLeak() = app(fail = true) {
        val r = get("/v1/epg/42")
        assertEquals(HttpStatusCode.BadGateway, r.status)
        val corps = r.bodyAsText()
        for (interdit in listOf("alice", "secret", "fournisseur")) assertFalse(corps.contains(interdit), interdit)
    }
}
