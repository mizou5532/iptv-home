package home.iptv.shared.xtream

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class XtreamApiTest {
    private val creds = XtreamCreds("http://panel.test:8080", "alice-user", "s3cret-pass")

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/xtream/$name")) { name }.readText()

    private fun api(
        timeoutMs: Long = 15_000,
        status: HttpStatusCode = HttpStatusCode.OK,
        handler: suspend (HttpRequestData) -> String,
    ): Pair<XtreamApi, MutableList<HttpRequestData>> {
        val seen = mutableListOf<HttpRequestData>()
        val engine = MockEngine { req ->
            seen += req
            respond(handler(req), status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return XtreamApi(HttpClient(engine), creds, timeoutMs) to seen
    }

    @Test
    fun userInfoOk() = runBlocking {
        val (api, _) = api { fixture("user_info_ok.json") }
        assertEquals(UserInfo(true, 1, listOf("m3u8", "ts")), api.userInfo())
    }

    @Test
    fun userInfoRefused() = runBlocking<Unit> {
        val (api, _) = api { fixture("user_info_refused.json") }
        assertFailsWith<XtreamError.Refused> { api.userInfo() }
    }

    @Test
    fun authAcceptsStringAndBoolean() = runBlocking {
        val (a, _) = api { """{"user_info":{"auth":"1"}}""" }
        assertTrue(a.userInfo().auth)
        val (b, _) = api { """{"user_info":{"auth":true,"max_connections":2}}""" }
        assertEquals(2, b.userInfo().maxConnections)
    }

    @Test
    fun categoriesStringAndNumberIds() = runBlocking {
        val (api, _) = api { fixture("live_categories.json") }
        assertEquals(listOf(LiveCategory("12", "Sports"), LiveCategory("13", "Films")), api.liveCategories())
    }

    @Test
    fun liveStreamsQueryAndTolerance() = runBlocking {
        val (api, seen) = api { fixture("live_streams.json") }
        val streams = api.liveStreams("12")
        val url = seen.single().url.toString()
        assertTrue("action=get_live_streams" in url && "category_id=12" in url, "requête")
        assertEquals(2, streams.size)
        assertEquals(LiveStream("101", "RDS", "12", "http://x/i.png", "rds.ca", 1), streams[0])
        assertNull(streams[1].number)
        assertNull(streams[1].icon)
        assertEquals("102", streams[1].id)
    }

    @Test
    fun shortEpgDecodesBase64() = runBlocking {
        val (api, seen) = api { fixture("short_epg.json") }
        val epg = api.shortEpg("101", 2)
        assertEquals(
            listOf(EpgEntry("Hockey LNH", Instant.fromEpochSeconds(1700000000), Instant.fromEpochSeconds(1700003600))),
            epg,
        )
        val url = seen.single().url.toString()
        assertTrue("action=get_short_epg" in url && "stream_id=101" in url && "limit=2" in url)
    }

    @Test
    fun timeoutIsUnreachableWithoutSecrets() = runBlocking {
        val (api, _) = api(timeoutMs = 100) { delay(5_000); "[]" }
        val e = assertFailsWith<XtreamError.Unreachable> { api.liveCategories() }
        val text = e.toString() + e.message
        assertFalse("alice-user" in text || "s3cret-pass" in text)
    }

    @Test
    fun networkErrorIsUnreachable() = runBlocking<Unit> {
        val (api, _) = api { throw java.io.IOException("boom http://panel.test/x?password=s3cret-pass") }
        val e = assertFailsWith<XtreamError.Unreachable> { api.liveCategories() }
        assertFalse("s3cret-pass" in e.toString() + e.message)
    }

    @Test
    fun httpErrorIsUnexpected() = runBlocking<Unit> {
        val (api, _) = api(status = HttpStatusCode.InternalServerError) { "" }
        assertEquals(500, assertFailsWith<XtreamError.Unexpected> { api.liveCategories() }.status)
    }
}
