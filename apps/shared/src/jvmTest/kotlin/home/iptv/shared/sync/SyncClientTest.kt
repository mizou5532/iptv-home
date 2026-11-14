package home.iptv.shared.sync

import home.iptv.shared.xtream.XtreamCreds
import io.github.jan.supabase.auth.MemorySessionManager
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.core.readText
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class SyncClientTest {
    private val sessionJson = """{"access_token":"eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1MSJ9.c2ln","token_type":"bearer","expires_in":3600,"expires_at":4102444800,"refresh_token":"r1","user":{"id":"u1","aud":"authenticated","role":"authenticated","is_anonymous":true,"app_metadata":{},"user_metadata":{}}}"""

    private class Harness(val client: SupabaseSyncClient, val seen: MutableList<HttpRequestData>, val bodies: MutableList<String>)

    private fun harness(
        status: HttpStatusCode = HttpStatusCode.OK,
        rest: (HttpRequestData) -> String = { "[]" },
    ): Harness {
        val seen = mutableListOf<HttpRequestData>()
        val bodies = mutableListOf<String>()
        val engine = MockEngine { req ->
            seen += req
            bodies += (req.body as? io.ktor.http.content.OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString() ?: ""
            val path = req.url.encodedPath
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            if (path == "/auth/v1/signup") respond(sessionJson, HttpStatusCode.OK, json)
            else respond(rest(req), status, json)
        }
        val supabase = supabaseClient("https://x.supabase.co", "sb_publishable_test", MemorySessionManager(), engine)
        return Harness(SupabaseSyncClient(supabase), seen, bodies)
    }

    private fun Harness.paths() = seen.map { it.method.value + " " + it.url.encodedPath }

    @Test
    fun ensureSignedInSignsUpOnceAnonymously() = runBlocking {
        val h = harness()
        h.client.ensureSignedIn()
        h.client.ensureSignedIn()
        assertEquals(1, h.paths().count { it == "POST /auth/v1/signup" })
    }

    @Test
    fun currentHouseholdNullWhenEmpty() = runBlocking {
        val h = harness { "[]" }
        assertNull(h.client.currentHousehold())
        assertTrue("POST /rest/v1/rpc/my_households" in h.paths())
    }

    @Test
    fun currentHouseholdReturnsFirstId() = runBlocking {
        val h = harness { """[{"id":"h1","name":"Maison","owner_id":"o1"},{"id":"h2"}]""" }
        assertEquals("h1", h.client.currentHousehold())
    }

    @Test
    fun createPairingCodeSendsNameAndReturnsCode() = runBlocking {
        val h = harness { "\"AB3K7M\"" }
        val before = Clock.System.now()
        val w = h.client.createPairingCode("Télé du salon")
        val after = Clock.System.now()
        assertEquals("AB3K7M", w.code)
        assertTrue(w.expiresAt >= before + kotlin.time.Duration.parse("9m59s"))
        assertTrue(w.expiresAt <= after + kotlin.time.Duration.parse("10m"))
        val i = h.paths().indexOf("POST /rest/v1/rpc/create_pairing_code")
        assertTrue(i >= 0)
        assertEquals("""{"device_name":"Télé du salon"}""", h.bodies[i])
    }

    @Test
    fun createPairingCodeSurfacesDatabaseError() = runBlocking {
        val h = harness(HttpStatusCode.BadRequest) {
            """{"code":"P0001","details":null,"hint":null,"message":"nom-appareil-invalide"}"""
        }
        val e = assertFailsWith<Exception> { h.client.createPairingCode("") }
        assertTrue(e.message.orEmpty().contains("nom-appareil-invalide"), e.message)
    }

    @Test
    fun credentialsReadsFilteredRow() = runBlocking {
        val h = harness { """[{"household_id":"h1","server":"http://p.test:80","username":"u","password":"p"}]""" }
        val c = h.client.credentials("h1")
        assertEquals(XtreamCreds("http://p.test:80", "u", "p"), c)
        val r = h.seen.first { it.url.encodedPath == "/rest/v1/xtream_credentials" }
        assertEquals(HttpMethod.Get, r.method)
        assertEquals("eq.h1", r.url.parameters["household_id"])
    }

    @Test
    fun credentialsNullWhenNoRow() = runBlocking {
        assertNull(harness { "[]" }.client.credentials("h1"))
    }

    @Test
    fun profilesSortedByPosition() = runBlocking {
        val h = harness {
            """[{"id":"b","name":"Bob","color":"#fff","home":"live","theme":"dark","position":2},
                {"id":"a","name":"Ann","color":"#000","home":"movies","theme":"dark","position":1}]"""
        }
        assertEquals(listOf("a", "b"), h.client.profiles("h1").map { it.id })
        val r = h.seen.first { it.url.encodedPath == "/rest/v1/profiles" }
        assertEquals("eq.h1", r.url.parameters["household_id"])
    }

    @Test
    fun globalHiddenCategoriesFilters() = runBlocking {
        val h = harness { """[{"item_type":"live_category","item_id":"12"}]""" }
        assertEquals(listOf(HiddenItem("live_category", "12")), h.client.globalHiddenCategories("h1"))
        val r = h.seen.first { it.url.encodedPath == "/rest/v1/hidden_items" }
        assertEquals("is.null", r.url.parameters["profile_id"])
        assertEquals("eq.live_category", r.url.parameters["item_type"])
        assertEquals("eq.h1", r.url.parameters["household_id"])
    }
}
