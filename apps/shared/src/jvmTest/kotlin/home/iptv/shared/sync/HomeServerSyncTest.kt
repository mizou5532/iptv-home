package home.iptv.shared.sync

import io.github.jan.supabase.auth.MemorySessionManager
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeServerSyncTest {
    private val sessionJson = """{"access_token":"eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1MSJ9.c2ln","token_type":"bearer","expires_in":3600,"expires_at":4102444800,"refresh_token":"r1","user":{"id":"u1","aud":"authenticated","role":"authenticated","is_anonymous":true,"app_metadata":{},"user_metadata":{}}}"""

    private class H(val client: SupabaseSyncClient, val seen: MutableList<HttpRequestData>, val bodies: MutableList<String>) {
        fun index(path: String) = seen.indexOfFirst { it.url.encodedPath == path }
    }

    private suspend fun harness(rest: String): H {
        val seen = mutableListOf<HttpRequestData>()
        val bodies = mutableListOf<String>()
        val engine = MockEngine { req ->
            seen += req
            bodies += (req.body as? io.ktor.http.content.OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString() ?: ""
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            if (req.url.encodedPath == "/auth/v1/signup") respond(sessionJson, HttpStatusCode.OK, json)
            else respond(rest, HttpStatusCode.OK, json)
        }
        val client = SupabaseSyncClient(supabaseClient("https://x.supabase.co", "sb_publishable_test", MemorySessionManager(null), engine, false))
        client.ensureSignedIn()
        return H(client, seen, bodies)
    }

    @Test
    fun registerHomeServerCallsRpc() = runBlocking {
        val h = harness("")
        h.client.registerHomeServer("h1", "https://maison.example", "ab12", "1.0")
        val i = h.index("/rest/v1/rpc/register_home_server")
        assertTrue(i >= 0)
        assertEquals("""{"h":"h1","url":"https://maison.example","pin":"ab12","version":"1.0"}""", h.bodies[i])
    }

    @Test
    fun homeServerReadsTableAndIsNullWhenEmpty() = runBlocking {
        val h = harness("""[{"household_id":"h1","url":"https://maison.example","pin":"ab12","version":"1.0","last_seen":"2026-11-21T10:00:00+00:00"}]""")
        val info = h.client.homeServer("h1")!!
        assertEquals("https://maison.example", info.url)
        assertEquals("ab12", info.pin)
        assertEquals("1.0", info.version)
        assertEquals(kotlin.time.Instant.parse("2026-11-21T10:00:00Z").toEpochMilliseconds(), info.lastSeenMs)
        assertTrue(h.seen.any { it.url.encodedPath == "/rest/v1/home_server" && it.url.toString().contains("household_id=eq.h1") })
        assertNull(harness("[]").homeServerOf("h1"))
    }

    private suspend fun H.homeServerOf(id: String) = client.homeServer(id)

    @Test
    fun issueHomeServerTokenReturnsToken() = runBlocking {
        val h = harness("\"tok-fake\"")
        assertEquals("tok-fake", h.client.issueHomeServerToken("h1"))
        assertEquals("""{"h":"h1"}""", h.bodies[h.index("/rest/v1/rpc/issue_home_server_token")])
    }

    @Test
    fun checkHomeServerTokenReturnsDeviceOrNull() = runBlocking {
        val ok = harness("\"dev-1\"")
        assertEquals("dev-1", ok.client.checkHomeServerToken("h1", "tok-fake"))
        assertEquals("""{"h":"h1","token":"tok-fake"}""", ok.bodies[ok.index("/rest/v1/rpc/check_home_server_token")])
        assertNull(harness("null").client.checkHomeServerToken("h1", "mauvais"))
    }
}
