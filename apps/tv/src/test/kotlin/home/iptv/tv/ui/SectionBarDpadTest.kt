package home.iptv.tv.ui

import home.iptv.tv.data.HomeTab
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertTrue

/** Barre des sections (Direct / Séries) de l'accueil d'un profil, à la télécommande. */
@RunWith(RobolectricTestRunner::class)
class SectionBarDpadTest {
    @get:Rule val rule = tvRule()
    private val rig = HomeRig(tab = HomeTab.DIRECT)

    @After fun tearDown() { rig.scope.cancel() }

    private fun focusedHas(text: String) = rule.focusedTexts()?.any { it.contains(text) } == true

    @Test
    fun du_direct_aux_series_et_retour_sans_jamais_perdre_le_focus() {
        rig.show(rule)
        rule.until(what = "focus sur la première catégorie") { focusedHas("Categorie 1") }
        rule.up()
        assertTrue(rule.focusedTexts() != null, "Haut depuis la première catégorie : focus perdu")
        // Jusqu'à la pastille « Séries » de la barre.
        repeat(6) { if (!focusedHas("Séries")) rule.left() }
        repeat(6) { if (!focusedHas("Séries")) rule.right() }
        assertTrue(focusedHas("Séries"), "pastille Séries atteignable : ${rule.focusedTexts()}")
        rule.ok()
        rule.until(what = "accueil Séries affiché") { rule.onAllNodesWithTextCount("Rangee 1") > 0 }
        assertTrue(focusedHas("Séries"), "après le changement de section, le focus est sur sa pastille : ${rule.focusedTexts()}")
        rule.down()
        // Première rangée encore en chargement : la carte d'attente a le focus, puis la première affiche.
        rule.until(what = "focus sur une affiche") { focusedHas("Serie s") }
        assertTrue(focusedHas("Serie s1-"), "Bas depuis la barre jusqu'aux affiches : ${rule.focusedTexts()}")
        // Retour : d'abord la barre des sections.
        rule.back()
        assertTrue(focusedHas("Séries"), "Retour ramène sur la barre : ${rule.focusedTexts()}")
        rule.left()
        assertTrue(focusedHas("Direct"), "Gauche sur la barre : ${rule.focusedTexts()}")
        rule.ok()
        rule.until(what = "direct affiché") { rule.onAllNodesWithTextCount("Categorie 1") > 0 }
        repeat(3) { if (!focusedHas("Categorie")) rule.down() }
        assertTrue(focusedHas("Categorie"), "Bas depuis la barre jusqu'aux catégories : ${rule.focusedTexts()}")
    }
}
