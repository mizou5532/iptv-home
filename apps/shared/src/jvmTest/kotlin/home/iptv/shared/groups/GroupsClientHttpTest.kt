package home.iptv.shared.groups

import home.iptv.shared.sync.SupabaseSyncClient
import home.iptv.shared.sync.supabaseClient
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.user.UserSession
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

class GroupsClientHttpTest {
    @Test fun authenticated_rpc_pages_use_exact_backend_contract() = runBlocking {
        val requests = mutableListOf<JsonObject>()
        val engine = MockEngine { request ->
            assertEquals("/rest/v1/rpc/live_group_page", request.url.encodedPath)
            assertEquals("Bearer token-test", request.headers[HttpHeaders.Authorization])
            val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            val data = Json.parseToJsonElement(body).jsonObject
            requests += data
            val content = if (data["after_id"] == JsonNull)
                """{"rev":4,"rules_version":1,"groups":[{"id":"a","title":"Sport","source_ids":["1"],"blocked_auto_keys":["sport"]}],"next_id":"a"}"""
            else """{"rev":4,"rules_version":1,"groups":[{"id":"b","title":"Cinema","source_ids":["2"],"blocked_auto_keys":[]}],"next_id":null}"""
            respond(content, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val stored = UserSession("token-test", "refresh-test", expiresIn = 3600, tokenType = "bearer", user = null,
            expiresAt = Instant.fromEpochSeconds(4102444800))
        val supabase = supabaseClient("https://example.invalid", "sb_publishable_test", MemorySessionManager(stored), engine)
        try {
            SupabaseSyncClient(supabase).ensureSignedIn()
            val snapshot = GroupsClient(supabase).snapshot("household-test")
            assertEquals(4, snapshot.rev)
            assertEquals(listOf("a", "b"), snapshot.groups.map { it.id })
            assertEquals(listOf("sport"), snapshot.groups.first().blockedAutoKeys)
            assertEquals(JsonPrimitive("household-test"), requests.first()["h"])
            assertEquals(JsonNull, requests.first()["after_id"])
            assertEquals(JsonPrimitive("a"), requests.last()["after_id"])
            assertTrue(requests.all { it["lim"] == JsonPrimitive(500) })
        } finally { supabase.close() }
    }
}
