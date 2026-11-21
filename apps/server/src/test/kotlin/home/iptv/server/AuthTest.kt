package home.iptv.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AuthTest {
    private var now = 0L
    private var verdict: String? = "iphone"
    private var calls = 0
    private val checker = TokenChecker(check = { t -> calls++; if (t == "bon") verdict else null }, clockMs = { now })

    private fun app(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) = testApplication {
        application { homeModule(checker, "9.9") { get("/ping") { call.respondText("pong") } } }
        block()
    }

    @Test
    fun missingTokenIs401() = app {
        val r = client.get("/v1/ping") { header("X-Home-Protocol", "1") }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
        assertEquals("", r.bodyAsText())
    }

    @Test
    fun wrongProtocolIs426() = app {
        val r = client.get("/v1/ping") { header("Authorization", "Bearer bon"); header("X-Home-Protocol", "2") }
        assertEquals(HttpStatusCode.UpgradeRequired, r.status)
        assertEquals("", r.bodyAsText())
        assertEquals(HttpStatusCode.UpgradeRequired, client.get("/v1/ping") { header("Authorization", "Bearer bon") }.status)
    }

    @Test
    fun validTokenReachesRoute() = app {
        val r = client.get("/v1/ping") { header("Authorization", "Bearer bon"); header("X-Home-Protocol", "1") }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("pong", r.bodyAsText())
        val mauvais = client.get("/v1/ping") { header("Authorization", "Bearer mauvais"); header("X-Home-Protocol", "1") }
        assertEquals(HttpStatusCode.Unauthorized, mauvais.status)
    }

    @Test
    fun healthAndRootArePublic() = app {
        val h = client.get("/health")
        assertEquals(HttpStatusCode.OK, h.status)
        assertEquals("""{"ok":true,"version":"9.9"}""", h.bodyAsText())
        assertEquals(HttpStatusCode.OK, client.get("/").status)
    }

    @Test
    fun revokedTokenRejectedAfterCacheExpiry() = runTest {
        assertEquals("iphone", checker.deviceFor("bon"))
        verdict = null
        now = 299_000
        assertEquals("iphone", checker.deviceFor("bon"))
        now = 301_000
        assertNull(checker.deviceFor("bon"))
    }

    @Test
    fun negativeAnswersAreCachedAndCacheIsBounded() = runTest {
        assertNull(checker.deviceFor("x"))
        assertNull(checker.deviceFor("x"))
        assertEquals(1, calls)
        repeat(100) { checker.deviceFor("t$it") }
        calls = 0
        checker.deviceFor("t0")
        assertEquals(1, calls) // évincé : 64 jetons au plus
    }
}
