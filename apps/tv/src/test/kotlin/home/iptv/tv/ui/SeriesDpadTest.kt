package home.iptv.tv.ui

import androidx.compose.ui.test.hasText
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SeriesDpadTest {
    @get:Rule val rule = tvRule()
    private val rig = HomeRig()

    @After fun tearDown() { rig.scope.cancel() }

    private fun focusedHas(text: String) = rule.focusedTexts()?.any { it.contains(text) } == true

    /** Accueil Séries : Bas depuis la barre jusqu'aux affiches de la première rangée. */
    private fun reachFirstPoster() {
        rig.show(rule)
        rule.until { rule.onAllNodesWithTextCount("Serie s1-1") > 0 }
        repeat(4) { if (!focusedHas("Serie s")) rule.down() }
        assertTrue(focusedHas("Serie s1-"), "focus sur une affiche de la 1re rangée : ${rule.focusedTexts()}")
    }

    @Test
    fun bas_parcourt_toutes_les_rangees_de_categories() {
        reachFirstPoster()
        val seen = mutableSetOf<String>()
        repeat(8) {
            rule.down()
            rule.until(3_000) { rule.focusedTexts()?.any { t -> t.startsWith("Serie s") } == true }
            rule.focusedTexts()?.firstOrNull { it.startsWith("Serie s") }?.let { seen += it.removePrefix("Serie ").substringBefore('-') }
        }
        assertTrue(seen.containsAll(listOf("s2", "s3", "s4", "s5", "s6")), "rangées atteintes : $seen, appels ${rig.catalog.seriesCalls}")
    }

    @Test
    fun ok_sur_un_episode_lance_la_lecture_et_la_garde() {
        reachFirstPoster()
        rule.ok()
        rule.until(what = "fiche ouverte") { rule.onAllNodes(hasText("Episode 1 de", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.until(what = "focus sur l'épisode 1") { rule.focusedTexts()?.any { it.contains("Episode 1 de") } == true }
        rule.ok()
        rule.until(15_000, what = "lecture lancée", detail = { "${rig.log} etat ${rig.lastPlayback?.state?.value}" }) { rig.log.any { it.startsWith("play") } }
        // La lecture doit rester ouverte (pas de retour immédiat à la fiche).
        Thread.sleep(500)
        rule.waitForIdle()
        assertTrue(rig.player.active.value != null, "lecteur fermé aussitôt ouvert : ${rig.log}")
        // L'écran de lecture est par-dessus la fiche (qui reste composée dessous) et garde la télécommande.
        assertTrue(rule.onAllNodes(hasText("Connexion…")).fetchSemanticsNodes().isNotEmpty(), "écran de lecture absent")
        assertTrue(rule.focusedTexts()?.any { it.contains("Episode") } != true, "le focus est resté sur la fiche : ${rule.focusedTexts()}")
    }
}
