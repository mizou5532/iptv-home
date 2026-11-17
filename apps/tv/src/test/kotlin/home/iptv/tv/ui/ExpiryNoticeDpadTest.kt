package home.iptv.tv.ui

import androidx.compose.ui.test.hasText
import home.iptv.tv.startup.WhoIsWatchingScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Fin d'abonnement proche : bandeau en haut de « Qui regarde ? » (les deux thèmes), sans voler le focus du profil. */
@RunWith(RobolectricTestRunner::class)
class ExpiryNoticeDpadTest {
    @get:Rule val rule = tvRule()
    private val text = "L'abonnement IPTV se termine dans 5 jours (23 nov.). Pense à le renouveler."

    private fun check(theme: String?) {
        val picked = mutableListOf<String>()
        rule.setTvContent { WhoIsWatchingScreen(listOf(PROFILE), offline = false, theme = theme, notice = text) { picked += it.id } }
        rule.until(what = "bandeau") { rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
        rule.until(what = "profil focalisé") { rule.focusedTexts() != null }
        assertTrue(rule.focusedTexts()!!.none { it.contains("abonnement") })
        rule.ok(); rule.until { picked == listOf(PROFILE.id) }
        assertEquals(listOf(PROFILE.id), picked)
    }

    @Test fun bandeau_theme_doux() = check(null)
    @Test fun bandeau_theme_aurore() = check("aurore")

    @Test fun sans_alerte_pas_de_bandeau() {
        rule.setTvContent { WhoIsWatchingScreen(listOf(PROFILE), offline = false) {} }
        rule.until(what = "titre") { rule.onAllNodes(hasText("Qui regarde ?", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(rule.onAllNodes(hasText("abonnement", substring = true)).fetchSemanticsNodes().isEmpty())
    }
}
