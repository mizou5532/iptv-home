package home.iptv.tv.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.focus.FocusRequester
import home.iptv.tv.feature.Feature
import home.iptv.tv.feature.FeatureHero
import home.iptv.tv.series.EpisodeRef
import home.iptv.tv.series.ResumeItem
import home.iptv.tv.series.ResumeState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Bannière d'accueil à la télécommande : boutons, file « À l'affiche ensuite ». */
@RunWith(RobolectricTestRunner::class)
class FeatureHeroDpadTest {
    @get:Rule val rule = tvRule()
    private val played = mutableListOf<String>()
    private val opened = mutableListOf<String>()

    private fun focused(text: String) = rule.focusedTexts()?.any { it.contains(text) } == true

    private fun show() {
        val resume = ResumeItem("a", "c", "Alpha", null, ResumeState.InProgress, EpisodeRef("e", 1, 2, "Deux"), 60, 600, null)
        val features = listOf(
            Feature("a", "c", "Alpha", "REPRENDRE", "S1 · É2", null, null, null, 0.1f, resume),
            Feature("b", "c", "Bravo", "DANS TA LISTE", null, null, null, null),
            Feature("m", "c", "Mike", "NOUVEAU", null, null, null, null, isMovie = true),
        )
        val first = FocusRequester()
        rule.setTvContent {
            Column { FeatureHero(features, first, onPlay = { played += it.itemId }, onOpen = { opened += it.itemId }) }
        }
        rule.runOnIdle { first.requestFocus() }
        rule.until(what = "Reprendre focalisé") { focused("Reprendre") }
    }

    @Test fun ok_reprend_et_droite_va_aux_episodes() {
        show()
        assertTrue(rule.onAllNodesWithTextCount("Alpha") > 0)
        rule.ok(); rule.until { played == listOf("a") }
        rule.right(); assertTrue(focused("Tous les épisodes"), "${rule.focusedTexts()}")
        rule.ok(); rule.until { opened == listOf("a") }
    }

    @Test fun la_file_affiche_les_suivantes_et_ok_y_passe() {
        show()
        assertTrue(rule.onAllNodesWithTextCount("À L'AFFICHE ENSUITE") > 0)
        // Droite mène à la file (près des boutons, donc en bas) ; Haut y remonte jusqu'à Bravo.
        repeat(3) { if (!focused("Bravo") && !focused("Mike")) rule.right() }
        repeat(2) { if (!focused("Bravo")) rule.up() }
        assertTrue(focused("Bravo"), "${rule.focusedTexts()}")
        rule.ok()
        // Bravo devient la suggestion affichée : une série sans reprise propose « Voir la série ».
        rule.until(what = "Bravo à l'affiche") { rule.onAllNodesWithTextCount("Voir la série") > 0 }
        assertEquals(emptyList(), played)
    }
}
