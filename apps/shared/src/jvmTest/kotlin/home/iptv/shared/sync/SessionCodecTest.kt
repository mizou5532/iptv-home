package home.iptv.shared.sync

import io.github.jan.supabase.auth.user.UserSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

class SessionCodecTest {
    @Test
    fun expiresAtEgalAuDefautEstQuandMemeEcritEtRelu() {
        val now = Clock.System.now()
        // expiresAt == now + expiresIn : exactement la valeur par défaut, que l'encodage omettrait.
        val session = UserSession(
            accessToken = "a", refreshToken = "r", expiresIn = 3600, tokenType = "bearer",
            user = null, expiresAt = now + 3600.seconds,
        )
        val text = SessionCodec.encode(session)
        assertTrue("\"expiresAt\"" in text, "expiresAt absent du JSON : $text")
        assertEquals(session.expiresAt, SessionCodec.decode(text).expiresAt)
    }

    @Test
    fun laConfigurationEcritLesValeursParDefaut() {
        // Déterministe : l'omission de expiresAt dépend de l'horloge (égal à la valeur par défaut au moment de l'encodage).
        assertTrue(SessionCodec.json.configuration.encodeDefaults, "encodeDefaults doit etre actif")
        assertTrue(SessionCodec.json.configuration.ignoreUnknownKeys)
    }
}
