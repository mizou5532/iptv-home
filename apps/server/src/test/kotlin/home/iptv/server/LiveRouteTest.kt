package home.iptv.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import home.iptv.shared.home.LiveSnapshot
import kotlinx.coroutines.runBlocking

class LiveRouteTest {
    private val checker = TokenChecker(check = { t -> if (t == "bon") "tele" else null }, clockMs = { 0L })

    private suspend fun builtJob(): LiveJob {
        val sync = FakeLiveSync()
        val repo = memoryRepo()
        val job = LiveJob(
            fakeRefresher(sync, repo, FakeLiveSource()), repo, sync, "h1", { "compteA" }, { 1_000L },
            file = File(createTempDirectory("liveroute").toFile().also { it.deleteOnExit() }, "live-snapshot.json"),
        )
        job.runOnce()
        return job
    }

    private fun app(snapshot: () -> LiveSnapshot?, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { homeModule(checker, "9.9") { liveRoute(snapshot) } }
        block()
    }

    private suspend fun ApplicationTestBuilder.get(path: String, gzip: Boolean = false) = client.get(path) {
        header("Authorization", "Bearer bon")
        header("X-Home-Protocol", "1")
        if (gzip) header(HttpHeaders.AcceptEncoding, "gzip")
    }

    @Test
    fun sameRevIs304() {
        val s = runBlocking { builtJob().snapshot.value }
        app({ s }) {
            val r = get("/v1/live?since=${s!!.rev}")
            assertEquals(HttpStatusCode.NotModified, r.status)
            assertEquals(HttpStatusCode.OK, get("/v1/live?since=autre").status)
            assertEquals(HttpStatusCode.OK, get("/v1/live").status)
        }
    }

    @Test
    fun noSnapshotIs503() = app({ null }) {
        assertEquals(HttpStatusCode.ServiceUnavailable, get("/v1/live").status)
    }

    @Test
    fun responseIsGzipped() {
        val s = runBlocking { builtJob().snapshot.value }
        app({ s }) {
            val r = get("/v1/live", gzip = true)
            assertEquals(HttpStatusCode.OK, r.status)
            assertEquals("gzip", r.headers[HttpHeaders.ContentEncoding])
            val clair = java.util.zip.GZIPInputStream(r.bodyAsBytes().inputStream()).readBytes().decodeToString()
            assertTrue(clair.contains(s!!.rev))
        }
    }

    @Test
    fun noCredentialInResponse() {
        val s = runBlocking { builtJob().snapshot.value }
        app({ s }) {
            val corps = get("/v1/live").bodyAsText()
            assertTrue(corps.contains(s!!.rev))
            for (interdit in listOf("username", "password", "alice", "secret", "fournisseur.example")) {
                assertFalse(corps.contains(interdit), interdit)
            }
        }
    }

    @Test
    fun routeIsProtected() = app({ null }) {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/live").status)
    }
}
