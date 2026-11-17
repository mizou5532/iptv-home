package home.iptv.tv.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
        val sœur = tokensFor("#F4B6C8", "clair")
        assertTrue(sœur.light)
        assertEquals(Color(0xFFF4B6C8), sœur.accent)
        assertTrue(sœur.background.luminance() > 0.85f, "fond très pâle")
        assertTrue(sœur.text.luminance() < 0.1f, "texte foncé sur fond clair")
        assertTrue(tokensFor("#F4B6C8", "light").light)
        val moi = tokensFor("#3366FF", "sombre")
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

    @Test fun themes_complets_choisis_sur_l_iphone() {
        // Sans thème choisi : exactement comme avant (colonne `theme`).
        assertEquals(tokensFor("#F4B6C8", "clair"), tokensFor("#F4B6C8", "clair", home.iptv.shared.sync.ThemeLook()))
        assertEquals(darkTokens(Color(0xFF8FB4FF)), tokensFor("#8FB4FF", "sombre", home.iptv.shared.sync.ThemeLook(preset = "nuit")))
        // Un thème choisi l'emporte sur la colonne.
        val foret = tokensFor("#4CD964", "clair", home.iptv.shared.sync.ThemeLook(preset = "foret"))
        assertFalse(foret.light); assertEquals(Color(0xFF0E1F18), foret.background)
        val sable = tokensFor("#AF1E2D", "sombre", home.iptv.shared.sync.ThemeLook(preset = "sable"))
        assertTrue(sable.light); assertEquals(Color.White, sable.surface)
        assertTrue(tokensFor("#AF1E2D", "sombre", home.iptv.shared.sync.ThemeLook(preset = "doux")).light)
        // Fond libre : clair ou sombre selon sa couleur ; fond presque blanc : cartes grisées pour rester visibles.
        val blanc = tokensFor("#2F6BFF", "sombre", home.iptv.shared.sync.ThemeLook(preset = "perso", background = "#FFFFFF"))
        assertTrue(blanc.light); assertTrue(blanc.surface != Color.White)
        assertFalse(tokensFor("#2F6BFF", "clair", home.iptv.shared.sync.ThemeLook(preset = "perso", background = "#3A0D0D")).light)
        // Fond libre invalide ou thème inconnu : retour à la colonne.
        assertTrue(tokensFor("#2F6BFF", "clair", home.iptv.shared.sync.ThemeLook(preset = "perso", background = "rouge")).light)
        assertFalse(tokensFor("#2F6BFF", "sombre", home.iptv.shared.sync.ThemeLook(preset = "inconnu")).light)
        // Coins.
        assertEquals(8.dp, tokensFor(null, null, home.iptv.shared.sync.ThemeLook(corners = "carres")).radiusCard)
        assertEquals(26.dp, tokensFor(null, null, home.iptv.shared.sync.ThemeLook(corners = "ronds")).radiusCard)
        assertEquals(16.dp, tokensFor(null, null, home.iptv.shared.sync.ThemeLook()).radiusCard)
    }
}
