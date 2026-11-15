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
    fun shortEpgKeepsTheGuideWhenOneTitleIsNotUtf8() = runBlocking {
        // Un titre encodé en Latin-1 par le panneau faisait échouer TOUT le guide (exception non attrapée).
        val body = """{"epg_listings":[{"title":"VOls6SBtYXRpbg==","start_timestamp":"1700000000","stop_timestamp":"1700003600"},""" +
            """{"title":"Sm91cm5hbA==","start_timestamp":"1700003600","stop_timestamp":"1700007200"}]}"""
        val (api, _) = api { body }
        val epg = api.shortEpg("101", 8)
        assertEquals(listOf("Télé matin", "Journal"), epg.map { it.title })
    }

    @Test
    fun shortEpgAcceptsEndTimestampAndPlainTitles() = runBlocking {
        val body = """{"epg_listings":[{"title":"Match en direct !","start_timestamp":1700000000,"end_timestamp":1700003600}]}"""
        val (api, _) = api { body }
        val epg = api.shortEpg("101", 8)
        assertEquals(listOf(EpgEntry("Match en direct !", Instant.fromEpochSeconds(1700000000), Instant.fromEpochSeconds(1700003600))), epg)
    }

    private val t0 = 1763236800L // 2025-11-15 20:00:00 UTC, heure des fixtures
    private fun at(offsetSec: Long) = Instant.fromEpochSeconds(t0 + offsetSec)

    @Test
    fun shortEpgTypicalXuiPanel() = runBlocking {
        val (api, _) = api { fixture("short_epg_xui.json") }
        val epg = api.shortEpg("101", 8)
        assertEquals(
            listOf(
                EpgEntry("Hockey : Montréal – Toronto", at(0), at(9000)),
                EpgEntry("Après-match", at(9000), at(10800)),
            ),
            epg,
        )
    }

    @Test
    fun shortEpgWithoutTimestampsUsesServerLocalDates() = runBlocking {
        // Panneau qui ne donne que « start »/« end » à l'heure locale du serveur (ici UTC+1) : toutes les entrées
        // étaient écartées, donc « Programme indisponible » partout. Le décalage vient de server_info.
        val (api, seen) = api { req ->
            if (req.url.parameters["action"] == null) fixture("user_info_server_time.json") else fixture("short_epg_dates_only.json")
        }
        val epg = api.shortEpg("102", 8)
        assertEquals(listOf(EpgEntry("Journal du soir", at(0), at(1800)), EpgEntry("Météo", at(1800), at(2100))), epg)
        // L'heure du serveur n'est demandée qu'une fois pour ce serveur.
        api.shortEpg("102", 8)
        assertTrue(seen.count { it.url.parameters["action"] == null } <= 1)
    }

    @Test
    fun shortEpgKeyedObjectMillisecondsAndPlainTitle() = runBlocking {
        val (api, _) = api { fixture("short_epg_keyed_ms.json") }
        val epg = api.shortEpg("103", 8)
        assertEquals(
            listOf(EpgEntry("Film : La Grande Course", at(0), at(5400)), EpgEntry("Documentaire", at(5400), at(9000))),
            epg,
        )
    }

    @Test
    fun fullEpgReadsSimpleDataTableWithNowPlaying() = runBlocking {
        val (api, seen) = api { fixture("simple_data_table.json") }
        val epg = api.fullEpg("104")
        assertEquals(listOf("Matin", "Midi", "Série", "Cinéma", "Nuit"), epg.map { it.title })
        assertEquals(listOf(false, false, true, false, false), epg.map { it.nowPlaying })
        assertEquals(at(-3600), epg[2].start)
        val url = seen.single().url.toString()
        assertTrue("action=get_simple_data_table" in url && "stream_id=104" in url)
    }

    @Test
    fun epgMissingFieldsAndOddShapesNeverThrow() = runBlocking {
        for (body in listOf(
            "", "[]", "{}", """{"epg_listings":null}""", """{"epg_listings":"rien"}""", """{"epg_listings":[null,1,"x"]}""",
            """[{"title":"QQ==","start_timestamp":"1700000000","stop_timestamp":"1700003600"}]""",
            """{"epg_listings":[{"title":"","start_timestamp":"1"},{"start_timestamp":"1","stop_timestamp":"2"}]}""",
        )) {
            val (api, _) = api { body }
            api.shortEpg("1", 8) // ne lève jamais
            api.fullEpg("1")
        }
        val (api, _) = api { """[{"title":"QQ==","start_timestamp":"1700000000","stop_timestamp":"1700003600"}]""" }
        assertEquals(listOf("A"), api.shortEpg("1", 8).map { it.title }, "racine en tableau acceptée")
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

    @Test
    fun objectKeyedListIsParsed() = runBlocking {
        val (api, _) = api { """{"0":{"category_id":"1","category_name":"A"},"1":{"category_id":2,"category_name":"B"}}""" }
        assertEquals(listOf(LiveCategory("1", "A"), LiveCategory("2", "B")), api.liveCategories())
    }

    @Test
    fun errorObjectOnListIsUnexpected() = runBlocking<Unit> {
        val (api, _) = api { """{"user_info":{"auth":0}}""" }
        assertEquals(200, assertFailsWith<XtreamError.Unexpected> { api.liveStreams("1") }.status)
    }

    @Test
    fun emptyObjectOnLiveListsIsUnexpected() = runBlocking<Unit> {
        // Un panneau surchargé ou un compte suspendu répond « {} » : ce n'est pas « aucune chaîne », le cache doit rester.
        val (api, _) = api { "{}" }
        assertEquals(200, assertFailsWith<XtreamError.Unexpected> { api.liveCategories() }.status)
        assertEquals(200, assertFailsWith<XtreamError.Unexpected> { api.liveStreams("1") }.status)
    }

    @Test
    fun emptyArrayIsEmptyList() = runBlocking {
        val (api, _) = api { "[]" }
        assertEquals(emptyList(), api.liveStreams("1"))
    }

    @Test
    fun userInfoEmptyBodyIsRefused() = runBlocking<Unit> {
        val (api, _) = api { "" }
        assertFailsWith<XtreamError.Refused> { api.userInfo() }
    }

    @Test
    fun userInfoEmptyArrayIsRefused() = runBlocking<Unit> {
        val (api, _) = api { "[]" }
        assertFailsWith<XtreamError.Refused> { api.userInfo() }
    }

    @Test
    fun http403IsRefused() = runBlocking<Unit> {
        val (api, _) = api(status = HttpStatusCode.Forbidden) { "" }
        assertFailsWith<XtreamError.Refused> { api.liveCategories() }
    }

    @Test
    fun http401IsRefused() = runBlocking<Unit> {
        val (api, _) = api(status = HttpStatusCode.Unauthorized) { "" }
        assertFailsWith<XtreamError.Refused> { api.userInfo() }
    }
}
