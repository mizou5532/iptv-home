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
        rule.until(15_000, what = "lecture lancée", detail = { "${rig.log} etat ${rig.lastPlayback?.state?.value} ${debugFields(rig.lastPlayback)} infos=${rig.catalog.infoCalls}" }) { rig.log.any { it.startsWith("play") } }
        // La lecture doit rester ouverte (pas de retour immédiat à la fiche).
        Thread.sleep(500)
        rule.waitForIdle()
        assertTrue(rig.player.active.value != null, "lecteur fermé aussitôt ouvert : ${rig.log}")
        // L'écran de lecture est par-dessus la fiche (qui reste composée dessous) et garde la télécommande.
        assertTrue(rule.onAllNodes(hasText("Connexion…")).fetchSemanticsNodes().isNotEmpty(), "écran de lecture absent")
        val focused = rule.focusedTexts()
        assertTrue(focused != null, "plus rien n'a le focus pendant la lecture")
        assertTrue(focused.none { it.contains("Episode") }, "le focus est resté sur la fiche : $focused")
    }

    @Test
    fun panneau_audio_et_sous_titres_prend_le_focus_et_retour_le_ferme() {
        reachFirstPoster()
        rule.ok()
        rule.until(what = "focus sur l'épisode 1") { rule.focusedTexts()?.any { it.contains("Episode 1 de") } == true }
        rule.ok()
        rule.until(15_000, what = "lecture lancée") { rig.log.any { it.startsWith("play") } }
        rule.until(what = "l'écran de lecture a la télécommande") { rule.focusedTexts()?.isEmpty() == true }
        rule.ok() // commandes
        rule.until(what = "commandes, focus sur Pause") { rule.focusedTexts()?.contains("Pause") == true }
        rule.right()
        assertTrue(rule.focusedTexts()?.contains("Audio et sous-titres") == true, "Droite : ${rule.focusedTexts()}")
        rule.ok() // panneau
        rule.until(what = "le panneau des pistes prend le focus") { rule.focusedTexts()?.any { it == "Aucun" } == true }
        rule.down()
        assertTrue(rule.focusedTexts()?.contains("Fermer") == true, "Bas dans le panneau : ${rule.focusedTexts()}")
        rule.back() // un seul appui : le panneau se ferme (il ne doit pas seulement envoyer le focus sur le fond)
        rule.until(what = "panneau fermé, la lecture garde la télécommande", detail = { "pistes=${rule.onAllNodesWithTextCount("Sous-titres")} fermer=${rule.onAllNodesWithTextCount("Fermer")} lecteur=${rig.player.active.value != null} etat=${rig.lastPlayback?.state?.value}" }) {
            rule.onAllNodesWithTextCount("Sous-titres") == 0 && rule.focusedTexts() != null
        }
        assertTrue(rig.player.active.value != null, "Retour sur le panneau ne doit pas fermer la lecture")
    }
}

private fun debugFields(o: Any?): String = o?.let { obj ->
    listOf("generation", "loadJob").joinToString { n ->
        val f = obj.javaClass.getDeclaredField(n).apply { isAccessible = true }
        "$n=${f.get(obj)}"
    }
} ?: "null"
