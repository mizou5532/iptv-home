package home.iptv.tv.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class IptvThemeTest {
    @Test
    fun jetons_sombres_valeurs_de_la_maquette() {
        val t = DarkTokens
        assertEquals(Color(0xFF0E1116), t.background)
        assertEquals(Color(0xFF171C24), t.surface)
        assertEquals(Color(0xFF1F2632), t.surfaceRaised)
        assertEquals(Color(0xFFF2F4F8), t.text)
        assertEquals(Color(0xFFA3ADBD), t.textMuted)
        assertEquals(Color(0xFF2C64F0), t.accent)
        assertEquals(Color.White, t.focusRing)
    }
}
