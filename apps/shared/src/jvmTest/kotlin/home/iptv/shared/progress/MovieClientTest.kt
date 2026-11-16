package home.iptv.shared.progress

import home.iptv.shared.sync.*
import home.iptv.shared.xtream.MovieSummary
import io.github.jan.supabase.auth.MemorySessionManager
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class MovieClientTest {
    private val sessionJson = """{"access_token":"eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1MSJ9.c2ln","token_type":"bearer","expires_in":3600,"expires_at":4102444800,"refresh_token":"r1","user":{"id":"u1","aud":"authenticated","role":"authenticated","is_anonymous":true,"app_metadata":{},"user_metadata":{}}}"""

    private class Call(val path: String, val body: String)

    private fun client(reply: (String) -> String = { "true" }): Pair<SupabaseMovieClient, MutableList<Call>> {
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
        return SupabaseMovieClient(supabase) to calls
    }

    private val movie = MovieSummary("42", "5", "Film", "https://u:p@img.test/a?token=x", null, null, null, null, null, "mkv", 5400)
    @Test fun publicationAndListAndWatchedUseExactRpcBodies() = runBlocking {
        val (c, calls) = client { "null" }
        c.publishCategories("h", listOf(PublishedCategory("5", "Drames", 0)))
        c.setList("p", movie, true)
        c.setWatched("p", movie, false)
        assertEquals(listOf("publish_movie_categories", "org_set_movie_list", "set_movie_watched"), calls.map { it.path.substringAfterLast('/') })
        assertEquals(Json.parseToJsonElement("""{"h":"h","cats":[{"id":"5","name":"Drames","position":0}]}"""), Json.parseToJsonElement(calls[0].body))
        assertEquals(Json.parseToJsonElement("""{"p":"p","movie_id":"42","on_":true,"movie_name":"Film","movie_cover":"https://img.test/a","movie_category":"5"}"""), Json.parseToJsonElement(calls[1].body))
        assertEquals(Json.parseToJsonElement("""{"p":"p","item_id":"42","watched":false,"movie_name":"Film","movie_cover":"https://img.test/a","movie_category":"5","duration_s":5400}"""), Json.parseToJsonElement(calls[2].body))
    }
    @Test fun dedicatedReadsHaveExactParametersAndDecodeMetadata() = runBlocking {
        val (c, calls) = client { path -> if(path.endsWith("movie_list")) """[{"item_id":"42","movie_name":"Film","movie_cover":null,"movie_category":"5"}]""" else """[{"item_id":"42","item_type":"movie","position_s":500,"duration_s":5400,"client_updated_at":"2026-11-15T10:00:00Z","movie_name":"Film","movie_category":"5","watched":true}]""" }
        assertEquals("movie", c.continueWatching("p", 9).single().itemType)
        assertTrue(c.progress("p", "42")!!.watched)
        assertEquals("Film", c.list("p").single().movieName)
        assertEquals(listOf("continue_movies", "movie_progress", "movie_list"), calls.map { it.path.substringAfterLast('/') })
        assertEquals(Json.parseToJsonElement("""{"p":"p","max_rows":9}"""), Json.parseToJsonElement(calls[0].body))
        assertEquals(Json.parseToJsonElement("""{"p":"p","item_id":"42"}"""), Json.parseToJsonElement(calls[1].body))
        assertEquals(Json.parseToJsonElement("""{"p":"p"}"""), Json.parseToJsonElement(calls[2].body))
    }
}
