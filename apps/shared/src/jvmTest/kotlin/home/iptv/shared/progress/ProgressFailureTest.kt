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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class ProgressFailureTest {
    private val now = Instant.parse("2026-11-15T10:00:00Z")
    private class Store : ProgressStore {
        var entries = emptyList<QueuedProgress>()
        override fun loadProgressQueue() = entries
        override fun saveProgressQueue(queue: List<QueuedProgress>) { entries = queue }
        override fun update(transform: (List<QueuedProgress>) -> List<QueuedProgress>) { entries = transform(entries) }
    }
    private suspend fun sdkClient(status: Int, code: String): Pair<ProgressClient, MutableList<String>> {
        val calls = mutableListOf<String>()
        val engine = MockEngine { request ->
            val headers = headersOf(HttpHeaders.ContentType, "application/json")
            if (request.url.encodedPath == "/auth/v1/signup") {
                respond("""{"access_token":"eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1MSJ9.c2ln","token_type":"bearer","expires_in":3600,"expires_at":4102444800,"refresh_token":"r1","user":{"id":"u1","aud":"authenticated","role":"authenticated","is_anonymous":true,"app_metadata":{},"user_metadata":{}}}""", HttpStatusCode.OK, headers)
            } else {
                val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                calls += body
                if ("bad" in body) respond("""{"code":"$code","message":"acces-refuse","details":null,"hint":null}""", HttpStatusCode.fromValue(status), headers)
                else respond("true", HttpStatusCode.OK, headers)
            }
        }
        val supabase = supabaseClient("https://x.supabase.co", "sb_publishable_test", MemorySessionManager(null), engine, false)
        SupabaseSyncClient(supabase).ensureSignedIn()
        return SupabaseProgressClient(supabase) to calls
    }
    private fun entry(id: String) = ProgressEntry(id, "s1", 1, 1, 10, 100, now)

    @Test
    fun permanentFailureDroppedOnFifthAttempt() = runBlocking {
        val (client, calls) = sdkClient(400, "P0001")
        val store = Store()
        val queue = ProgressQueue(client, store) { now }
        queue.enqueue("p1", entry("bad"))
        repeat(4) { queue.flush(); assertEquals(1, store.entries.size) }
        queue.flush()
        assertEquals(5, calls.size)
        assertEquals(emptyList(), store.entries)
    }

    @Test
    fun permanentHeadDoesNotBlockFollowingEntry() = runBlocking {
        val (client, calls) = sdkClient(403, "42501")
        val store = Store()
        val queue = ProgressQueue(client, store) { now }
        queue.enqueue("p1", entry("bad"))
        queue.enqueue("p1", entry("good"))
        queue.flush()
        assertEquals(2, calls.size)
        assertEquals(listOf("bad"), store.entries.map { it.entry.itemId })
    }
}
