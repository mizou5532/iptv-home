package home.iptv.tv.ui

import home.iptv.tv.startup.WhoIsWatchingScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/** « Qui regarde ? » au thème Aurore (choisi sur l'iPhone) : choisir un profil, Bas vers Redémarrer. */
@RunWith(RobolectricTestRunner::class)
class AuroreDpadTest {
    @get:Rule val rule = tvRule()

    @Test fun aurore_ok_choisit_le_profil_et_bas_mene_a_redemarrer() {
        val picked = mutableListOf<String>(); var restarts = 0
        rule.setTvContent { WhoIsWatchingScreen(listOf(PROFILE), offline = false, onRestart = { restarts++ }, theme = "aurore") { picked += it.id } }
        rule.until(what = "titre Aurore") { rule.onAllNodes(androidx.compose.ui.test.hasText("Qui regarde ?", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.until(what = "profil focalisé") { rule.focusedTexts() != null && rule.focusedTexts()?.none { it.contains("Redémarrer") } == true }
        rule.ok(); rule.until { picked == listOf(PROFILE.id) }
        rule.down(); rule.until(what = "Redémarrer") { rule.focusedTexts()?.any { it.contains("Redémarrer") } == true }
        rule.ok(); rule.until { restarts == 1 }
        assertEquals(1, restarts)
    }
}
