package home.iptv.shared.sync

import home.iptv.shared.xtream.XtreamCreds
import io.github.jan.supabase.auth.MemorySessionManager
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.github.jan.supabase.auth.user.UserSession
import kotlin.time.Duration.Companion.hours
import kotlinx.io.IOException
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

    private fun expiredSession() = UserSession(
        accessToken = "old", refreshToken = "r0", expiresIn = 3600, tokenType = "bearer", user = null,
        expiresAt = Clock.System.now() - 2.hours,
    )

    private fun harness(
        status: HttpStatusCode = HttpStatusCode.OK,
        stored: UserSession? = null,
        autoRefresh: Boolean = false,
        token: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData? = { null },
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
            else if (path == "/auth/v1/token") token(this, req) ?: respond(sessionJson, HttpStatusCode.OK, json)
            else respond(rest(req), status, json)
        }
        val supabase = supabaseClient(
            "https://x.supabase.co", "sb_publishable_test", MemorySessionManager(stored), engine, autoRefresh,
        )
        return Harness(SupabaseSyncClient(supabase), seen, bodies)
    }

    private suspend fun Harness.signedIn(): Harness { client.ensureSignedIn(); return this }

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
        val h = harness { "[]" }.signedIn()
        assertNull(h.client.currentHousehold())
        assertTrue("POST /rest/v1/rpc/my_households" in h.paths())
    }

    @Test
    fun currentHouseholdReturnsFirstId() = runBlocking {
        val h = harness { """[{"id":"h1","name":"Maison","owner_id":"o1"},{"id":"h2"}]""" }.signedIn()
        assertEquals("h1", h.client.currentHousehold())
    }

    @Test
    fun createPairingCodeSendsNameAndReturnsCode() = runBlocking {
        val h = harness { "\"AB3K7M\"" }.signedIn()
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
        }.signedIn()
        val e = assertFailsWith<Exception> { h.client.createPairingCode("") }
        assertTrue(e.message.orEmpty().contains("nom-appareil-invalide"), e.message)
    }

    @Test
    fun credentialsReadsFilteredRow() = runBlocking {
        val h = harness { """[{"household_id":"h1","server":"http://p.test:80","username":"u","password":"p"}]""" }.signedIn()
        val c = h.client.credentials("h1")
        assertEquals(XtreamCreds("http://p.test:80", "u", "p"), c)
        val r = h.seen.first { it.url.encodedPath == "/rest/v1/xtream_credentials" }
        assertEquals(HttpMethod.Get, r.method)
        assertEquals("eq.h1", r.url.parameters["household_id"])
    }

    @Test
    fun credentialsNullWhenNoRow() = runBlocking {
        assertNull(harness { "[]" }.signedIn().client.credentials("h1"))
    }

    @Test
    fun profilesSortedByPosition() = runBlocking {
        val h = harness {
            """[{"id":"b","name":"Bob","color":"#fff","home":"live","theme":"dark","position":2},
                {"id":"a","name":"Ann","color":"#000","home":"movies","theme":"dark","position":1}]"""
        }.signedIn()
        assertEquals(listOf("a", "b"), h.client.profiles("h1").map { it.id })
        val r = h.seen.first { it.url.encodedPath == "/rest/v1/profiles" }
        assertEquals("eq.h1", r.url.parameters["household_id"])
    }

    @Test
    fun globalHiddenCategoriesUsesRpcAndReturnsAllIds() = runBlocking {
        val ids = (1..1500).map { it.toString() }
        val h = harness { ids.joinToString(prefix = "[", postfix = "]") { "\"$it\"" } }.signedIn()
        assertEquals(ids.map { HiddenItem("live_category", it) }, h.client.globalHiddenCategories("h1"))
        val i = h.paths().indexOf("POST /rest/v1/rpc/global_hidden_live_categories")
        assertTrue(i >= 0, h.paths().toString())
        assertEquals("""{"h":"h1"}""", h.bodies[i])
        assertTrue(h.paths().none { it.endsWith("/rest/v1/hidden_items") }, h.paths().toString())
    }

    @Test
    fun sessionExpireeEtRafraichissementEnErreur500LaTeleNeDevientPasUnNouvelUtilisateur() = runBlocking {
        val h = harness(
            stored = expiredSession(), autoRefresh = true,
            token = { respond("""{"error":"boom"}""", HttpStatusCode.InternalServerError, headersOf(HttpHeaders.ContentType, "application/json")) },
        )
        assertFailsWith<IOException> { h.client.ensureSignedIn() }
        assertEquals(0, h.paths().count { it == "POST /auth/v1/signup" }, h.paths().toString())
    }

    @Test
    fun sessionExpireeEtReseauCoupeLaTeleNeDevientPasUnNouvelUtilisateur() = runBlocking {
        val h = harness(stored = expiredSession(), autoRefresh = true, token = { throw IOException("reseau coupe") })
        assertFailsWith<IOException> { h.client.ensureSignedIn() }
        assertEquals(0, h.paths().count { it == "POST /auth/v1/signup" }, h.paths().toString())
    }

    @Test
    fun sessionExpireeValideEstRafraichieSansNouvelleInscription() = runBlocking {
        val h = harness(stored = expiredSession(), autoRefresh = true)
        h.client.ensureSignedIn()
        assertEquals(0, h.paths().count { it == "POST /auth/v1/signup" })
        assertEquals(1, h.paths().count { it == "POST /auth/v1/token" })
    }

    @Test
    fun sansSessionLesLecturesLevent_etNAtteignentJamaisLeREST() = runBlocking {
        val h = harness { """[{"item_type":"live_category","item_id":"12"}]""" }
        assertFailsWith<IOException> { h.client.globalHiddenCategories("h1") }
        assertFailsWith<IOException> { h.client.currentHousehold() }
        assertFailsWith<IOException> { h.client.credentials("h1") }
        assertFailsWith<IOException> { h.client.profiles("h1") }
        assertTrue(h.paths().none { it.contains("/rest/v1/") }, h.paths().toString())
    }

    @Test
    fun profilePrefsDecodesExampleJson() = runBlocking {
        val h = harness {
            """{"rev":7,"hidden":[{"item_type":"live_category","item_id":"3"}],"favorites":["a","b"],
                "playlists":[{"id":"p1","name":"Foot","items":["a"]}],"order":["2","1"]}"""
        }.signedIn()
        val p = h.client.profilePrefs("pr1")
        assertEquals(
            ProfilePrefs(7, listOf(HiddenItem("live_category", "3")), listOf("a", "b"), listOf(PlaylistPrefs("p1", "Foot", listOf("a"))), listOf("2", "1")),
            p,
        )
        val i = h.paths().indexOf("POST /rest/v1/rpc/profile_prefs")
        assertTrue(i >= 0)
        assertEquals("""{"p":"pr1"}""", h.bodies[i])
    }

    @Test
    fun profilePrefsOrderNull() = runBlocking {
        val h = harness { """{"rev":0,"hidden":[],"favorites":[],"playlists":[],"order":null}""" }.signedIn()
        assertNull(h.client.profilePrefs("pr1").order)
    }

    @Test
    fun prefsRevisionReadsNumber() = runBlocking {
        val h = harness { "42" }.signedIn()
        assertEquals(42L, h.client.prefsRevision("h1"))
        val i = h.paths().indexOf("POST /rest/v1/rpc/prefs_rev")
        assertEquals("""{"h":"h1"}""", h.bodies[i])
    }

    @Test
    fun publishLiveCategoriesReturnsFingerprints() = runBlocking {
        val h = harness { """{"1":"abc","2":null}""" }.signedIn()
        val m = h.client.publishLiveCategories("h1", listOf(PublishedCategory("1", "Sport", 0), PublishedCategory("2", "Films", 1)))
        assertEquals(mapOf<String, String?>("1" to "abc", "2" to null), m)
        val i = h.paths().indexOf("POST /rest/v1/rpc/publish_live_categories")
        assertEquals("""{"h":"h1","cats":[{"id":"1","name":"Sport","position":0},{"id":"2","name":"Films","position":1}]}""", h.bodies[i])
    }

    @Test
    fun publishLiveCategoriesReturnsMoreThan1000Fingerprints() = runBlocking {
        val h = harness { (0 until 1500).joinToString(prefix = "{", postfix = "}") { "\"$it\":" + (if (it % 2 == 0) "\"f$it\"" else "null") } }.signedIn()
        val m = h.client.publishLiveCategories("h1", listOf(PublishedCategory("0", "Sport", 0)))
        assertEquals(1500, m.size)
        assertEquals("f1498", m["1498"])
        assertNull(m["1499"])
    }

    @Test
    fun publishLiveChannelsSendsChunkAndNoIcon() = runBlocking {
        val h = harness { "null" }.signedIn()
        h.client.publishLiveChannels("h1", "1", "ff", 2, true, listOf(PublishedChannel("a", "Tele", "tele", null, 0)))
        val i = h.paths().indexOf("POST /rest/v1/rpc/publish_live_channels")
        assertTrue(i >= 0)
        val body = h.bodies[i]
        assertTrue(""""chunk":2""" in body, body)
        assertTrue(""""is_last":true""" in body, body)
        assertTrue(""""category":"1"""" in body && """"fingerprint":"ff"""" in body && """"h":"h1"""" in body, body)
        assertTrue("icon" !in body && "epg" !in body, body)
    }

    @Test
    fun nouvellesFonctionsExigentUneSession() = runBlocking {
        val h = harness { "[]" }
        assertFailsWith<IOException> { h.client.profilePrefs("p") }
        assertFailsWith<IOException> { h.client.prefsRevision("h") }
        assertFailsWith<IOException> { h.client.publishLiveCategories("h", emptyList()) }
        assertFailsWith<IOException> { h.client.publishLiveChannels("h", "1", "f", 0, true, emptyList()) }
        assertTrue(h.paths().none { it.contains("/rest/v1/") }, h.paths().toString())
    }

    @Test
    fun profilePrefsOldJsonWithoutSeriesKeysUsesDefaults() = runBlocking {
        val h = harness { """{"rev":3,"hidden":[],"favorites":[],"playlists":[],"order":null}""" }.signedIn()
        val p = h.client.profilePrefs("pr1")
        assertNull(p.seriesOrder)
        assertEquals(emptyList(), p.seriesList)
    }

    @Test
    fun profilePrefsDecodesSeriesKeys() = runBlocking {
        val h = harness { """{"rev":3,"hidden":[],"favorites":[],"playlists":[],"order":null,"series_order":["2","1"],"series_list":["55"]}""" }.signedIn()
        val p = h.client.profilePrefs("pr1")
        assertEquals(listOf("2", "1"), p.seriesOrder)
        assertEquals(listOf("55"), p.seriesList)
    }

    @Test
    fun publishSeriesCategoriesSendsRpc() = runBlocking {
        val h = harness { "null" }.signedIn()
        h.client.publishSeriesCategories("h1", listOf(PublishedCategory("7", "Drames", 0)))
        val i = h.paths().indexOf("POST /rest/v1/rpc/publish_series_categories")
        assertTrue(i >= 0)
        assertEquals("""{"h":"h1","cats":[{"id":"7","name":"Drames","position":0}]}""", h.bodies[i])
    }
}
