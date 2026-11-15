package home.iptv.shared.progress

import home.iptv.shared.sync.SupabaseSyncClient
import home.iptv.shared.sync.supabaseClient
import io.github.jan.supabase.auth.MemorySessionManager
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlinx.io.IOException
import kotlin.test.assertTrue
import kotlin.time.Instant

class ProgressClientTest {
    private val sessionJson = """{"access_token":"eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1MSJ9.c2ln","token_type":"bearer","expires_in":3600,"expires_at":4102444800,"refresh_token":"r1","user":{"id":"u1","aud":"authenticated","role":"authenticated","is_anonymous":true,"app_metadata":{},"user_metadata":{}}}"""

    private class Call(val path: String, val body: String)

    private fun client(reply: (String) -> String = { "true" }): Pair<SupabaseProgressClient, MutableList<Call>> {
        val calls = mutableListOf<Call>()
        val engine = MockEngine { req ->
            val path = req.url.encodedPath
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            if (path == "/auth/v1/signup") respond(sessionJson, HttpStatusCode.OK, json)
            else {
                val body = (req.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString() ?: ""
                calls += Call(path, body)
                respond(reply(path), HttpStatusCode.OK, json)
            }
        }
        val supabase = supabaseClient("https://x.supabase.co", "sb_publishable_test", MemorySessionManager(null), engine, false)
        runBlocking { SupabaseSyncClient(supabase).ensureSignedIn() }
        return SupabaseProgressClient(supabase) to calls
    }

    private val entry = ProgressEntry(
        itemId = "901", seriesId = "55", season = 2, episode = 3, positionS = 600, durationS = 2400,
        clientUpdatedAt = Instant.parse("2026-11-15T10:00:00Z"),
    )

    @Test
    fun recordWithoutSessionFailsBeforeAnyNetworkCall() = runBlocking {
        var requests = 0
        val engine = MockEngine { requests++; error("Unexpected network call") }
        val supabase = supabaseClient("https://x.supabase.co", "sb_publishable_test", MemorySessionManager(null), engine, false)
        val c = SupabaseProgressClient(supabase)
        assertFailsWith<IOException> { c.record("pr1", entry) }
        val store = object : ProgressStore {
            var entries = listOf(QueuedProgress("pr1", entry))
            override fun loadProgressQueue() = entries
            override fun saveProgressQueue(queue: List<QueuedProgress>) { entries = queue }
            override fun update(transform: (List<QueuedProgress>) -> List<QueuedProgress>) { entries = transform(entries) }
        }
        assertEquals(FlushResult(0, 0, true), ProgressQueue(c, store) { entry.clientUpdatedAt }.flush())
        assertEquals(0, store.entries.single().attempts)
        assertEquals(0, requests)
    }

    @Test
    fun setWatchedFalseIsSentExplicitly() = runBlocking {
        val (c, calls) = client { "1" }
        c.setWatched("pr1", listOf(WatchedItem("901", "55", 2, 3, null)), false)
        val body = Json.parseToJsonElement(calls.single().body).jsonObject
        assertEquals("false", body["watched"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, body["items"]!!.jsonArray.single().jsonObject["duration_s"])
    }

    @Test
    fun recordAndWatchedStripCoverCredentialsQueryAndFragment() = runBlocking {
        val (c, calls) = client()
        val cover = "https://demo:placeholder@images.example.test/poster.jpg?key=placeholder#section"
        c.record("pr1", entry.copy(seriesCover = cover))
        c.setWatched("pr1", listOf(WatchedItem("901", "55", 2, 3, 2400, seriesCover = cover)), true)
        val record = Json.parseToJsonElement(calls[0].body).jsonObject
        val watched = Json.parseToJsonElement(calls[1].body).jsonObject["items"]!!.jsonArray.single().jsonObject
        assertEquals("https://images.example.test/poster.jpg", record["series_cover"]!!.jsonPrimitive.content)
        assertEquals("https://images.example.test/poster.jpg", watched["series_cover"]!!.jsonPrimitive.content)
    }

    @Test
    fun recordSendsAllTwelveKeysWithExplicitNulls() = runBlocking {
        val (c, calls) = client()
        assertTrue(c.record("pr1", entry))
        val call = calls.single()
        assertEquals("/rest/v1/rpc/record_progress", call.path)
        val body = Json.parseToJsonElement(call.body).jsonObject
        val expected = setOf(
            "p", "item_type", "item_id", "position_s", "duration_s", "client_updated_at",
            "series_id", "season", "episode", "series_name", "series_cover", "series_category",
        )
        assertEquals(expected, body.keys)
        assertEquals(JsonNull, body["series_name"])
        assertEquals(JsonNull, body["series_cover"])
        assertEquals(JsonNull, body["series_category"])
        assertEquals("episode", body["item_type"]!!.jsonPrimitive.content)
        assertEquals("2026-11-15T10:00:00Z", body["client_updated_at"]!!.jsonPrimitive.content)
        assertEquals("55", body["series_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun recordSendsEnrichmentWhenKnownAndAllKeysWhenSeriesUnknown() = runBlocking {
        val (c, calls) = client { "false" }
        val full = entry.copy(seriesName = "Serie", seriesCover = "/c.jpg", seriesCategory = "9")
        assertFalse(c.record("pr1", full))
        val b = Json.parseToJsonElement(calls[0].body).jsonObject
        assertEquals("Serie", b["series_name"]!!.jsonPrimitive.content)
        assertEquals("9", b["series_category"]!!.jsonPrimitive.content)
        c.record("pr1", entry.copy(seriesId = null, season = null, episode = null))
        val b2 = Json.parseToJsonElement(calls[1].body).jsonObject
        assertEquals(12, b2.size)
        assertEquals(JsonNull, b2["series_id"])
        assertEquals(JsonNull, b2["season"])
    }

    @Test
    fun continueWatchingDecodesEnrichedRows() = runBlocking {
        val (c, calls) = client {
            """[{"profile_id":"pr1","item_type":"episode","item_id":"901","series_id":"55","season":2,"episode":3,
                "position_s":600,"duration_s":2400,"watched":false,"client_updated_at":"2026-11-15T10:00:00+00:00",
                "series_name":"Serie","series_cover":"/c.jpg","series_category":"9"}]"""
        }
        val rows = c.continueWatching("pr1", 5)
        assertEquals("/rest/v1/rpc/continue_watching", calls.single().path)
        assertEquals("""{"p":"pr1","max_rows":5}""", calls.single().body)
        val r = rows.single()
        assertEquals("Serie", r.seriesName)
        assertEquals("/c.jpg", r.seriesCover)
        assertEquals("9", r.seriesCategory)
        assertEquals(Instant.parse("2026-11-15T10:00:00Z"), r.clientUpdatedAt)
    }

    @Test
    fun setWatchedSendsItemsAndFlag() = runBlocking {
        val (c, calls) = client { "2" }
        c.setWatched("pr1", listOf(WatchedItem("901", "55", 2, 3, 2400, "Serie", null, "9")), true)
        val body = Json.parseToJsonElement(calls.single().body).jsonObject
        assertEquals("/rest/v1/rpc/set_watched", calls.single().path)
        assertEquals(setOf("p", "items", "watched"), body.keys)
        assertEquals(JsonArray::class, body["items"]!!::class)
        val item = body["items"]!!.jsonArray.single().jsonObject
        assertEquals(
            setOf("item_id", "series_id", "season", "episode", "duration_s", "series_name", "series_cover", "series_category"),
            item.keys,
        )
        assertEquals(JsonNull, item["series_cover"])
        assertEquals("true", body["watched"]!!.jsonPrimitive.content)
    }

    @Test
    fun setWatchedWithNoItemsSendsNothing() = runBlocking {
        val (c, calls) = client()
        c.setWatched("pr1", emptyList(), true)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun setSeriesListUsesOnUnderscoreParameter() = runBlocking {
        val (c, calls) = client { "null" }
        c.setSeriesList("pr1", "55", true)
        assertEquals("/rest/v1/rpc/org_set_series_list", calls.single().path)
        assertEquals("""{"p":"pr1","series_id":"55","on_":true}""", calls.single().body)
    }

    @Test
    fun seriesProgressCallsRpcAndDecodesEveryEpisode() = runBlocking {
        val (c, calls) = client {
            """[{"item_id":"901","season":1,"episode":1,"position_s":2300,"duration_s":2400,"watched":true,"client_updated_at":"2026-11-15T10:00:00Z"},
               {"item_id":"902","season":1,"episode":2,"position_s":600,"duration_s":2400,"watched":false,"client_updated_at":"2026-11-15T11:00:00Z"}]"""
        }
        val rows = c.seriesProgress("pr1", "55")
        assertEquals("/rest/v1/rpc/series_progress", calls.single().path)
        assertEquals("""{"p":"pr1","series_id":"55"}""", calls.single().body)
        assertEquals(listOf("901", "902"), rows.map { it.itemId })
        assertTrue(rows.all { it.seriesId == "55" })
        assertEquals(true, rows[0].watched)
        assertEquals(600, rows[1].positionS)
    }
}
