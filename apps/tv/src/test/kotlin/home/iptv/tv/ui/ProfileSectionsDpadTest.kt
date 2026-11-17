package home.iptv.tv.ui

import home.iptv.tv.data.HomeTab
import home.iptv.tv.data.openingTab
import home.iptv.tv.data.visibleTabs
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Sections choisies sur l'iPhone pour un profil : seules celles-là dans la barre, ouverture sur l'une d'elles. */
@RunWith(RobolectricTestRunner::class)
class ProfileSectionsDpadTest {
    @get:Rule val rule = tvRule()
    // Profil qui s'ouvrait sur Direct, mais Direct et Sport cachés : il s'ouvre sur Séries.
    private val rig = HomeRig(tab = HomeTab.DIRECT, sections = listOf("series", "films"))

    @After fun tearDown() { rig.scope.cancel() }

    private fun focusedHas(text: String) = rule.focusedTexts()?.any { it.contains(text) } == true

    @Test fun reglage_lu() {
        assertEquals(HomeTab.entries, visibleTabs(null))
        assertEquals(HomeTab.entries, visibleTabs(listOf("inconnu")))
        assertEquals(listOf(HomeTab.SERIES, HomeTab.MOVIES), visibleTabs(listOf("films", "series")))
        assertEquals(HomeTab.SERIES, openingTab("direct", listOf("series", "films")))
        assertEquals(HomeTab.MOVIES, openingTab("films", listOf("series", "films")))
        assertEquals(HomeTab.SPORT, openingTab("sport", null))
    }

    @Test fun seulement_series_et_films_dans_la_barre() {
        rig.show(rule)
        rule.until(what = "accueil Séries affiché") { rule.onAllNodesWithTextCount("Rangee 1") > 0 }
        assertEquals(0, rule.onAllNodesWithTextCount("Direct"), "Direct caché")
        assertEquals(0, rule.onAllNodesWithTextCount("Sport"), "Sport caché")
        rule.up(); rule.up()
        repeat(6) { if (!focusedHas("Films")) rule.right() }
        assertTrue(focusedHas("Films"), "pastille Films atteignable : ${rule.focusedTexts()}")
        rule.ok()
        rule.until(what = "accueil Films affiché") { rule.onAllNodesWithTextCount("Films rangée 1") > 0 }
        repeat(4) { rule.left() }
        assertTrue(focusedHas("Séries"), "à gauche de Films : Séries, rien d'autre : ${rule.focusedTexts()}")
    }
}
