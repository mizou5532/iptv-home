package home.iptv.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PairingPageTest {
    @Test
    fun pageShowsCodeInFrench() = testApplication {
        application { pairingRoute { "ABC123" } }
        val r = client.get("/")
        assertEquals(ContentType.Text.Html, r.contentType()?.withoutParameters())
        val html = r.bodyAsText()
        assertTrue("ABC123" in html)
        assertTrue("Ajouter un appareil" in html)
    }
}
