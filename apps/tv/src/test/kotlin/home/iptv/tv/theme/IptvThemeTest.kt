package home.iptv.tv.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Style « Verre » choisi par le propriétaire (16 nov.) ; profil clair « Doux » pour sa sœur. */
class IptvThemeTest {
    @Test
    fun jetons_sombres_valeurs_de_la_maquette() {
        val t = DarkTokens
        assertEquals(Color(0xFF0B0F17), t.background)
        assertEquals(Color(0xFFF2F4F8), t.text)
        assertEquals(Color(0xFFA3ADBD), t.textMuted)
        assertEquals(Color(0xFF8FB4FF), t.accent)
        assertEquals(Color.White, t.focusRing)
        assertTrue(t.surface.luminance() > t.background.luminance() && t.surfaceRaised.luminance() > t.surface.luminance())
        assertFalse(t.light)
    }

    @Test
    fun theme_du_profil_couleur_et_fond() {
        val sœur = tokensFor("#F4B6C8", "light")
        assertTrue(sœur.light)
        assertEquals(Color(0xFFF4B6C8), sœur.accent)
        assertTrue(sœur.background.luminance() > 0.85f, "fond très pâle")
        assertTrue(sœur.text.luminance() < 0.1f, "texte foncé sur fond clair")
        val moi = tokensFor("#3366FF", "dark")
        assertFalse(moi.light)
        assertEquals(Color(0xFF3366FF), moi.accent)
        // Thème inconnu ou couleur invalide : sombre, accent par défaut.
        assertFalse(tokensFor("pas une couleur", null).light)
        assertEquals(Color(0xFF8FB4FF), tokensFor(null, "n'importe quoi").accent)
    }

    @Test
    fun texte_lisible_sur_l_accent() {
        assertEquals(Color(0xFF0B0F17), tokensFor("#F4B6C8", "dark").onAccent) // accent clair : texte foncé
        assertEquals(Color.White, tokensFor("#1F3A93", "dark").onAccent) // accent foncé : texte blanc
    }
}
