package home.iptv.tv.ui

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithTag
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import home.iptv.tv.data.HomeTab
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SearchDpadTest {
    @get:Rule val rule = tvRule()
    private var rig = HomeRig()
    @After fun close() { rig.scope.cancel() }
    private fun focused(text: String) = rule.focusedTexts()?.any { text in it } == true
    private fun open(tab: HomeTab = HomeTab.SERIES, livePerCat: Int = 4) {
        rig.scope.cancel(); rig = HomeRig(tab, livePerCat = livePerCat)
        rig.show(rule)
        rule.until { rule.onAllNodesWithTextCount("Rechercher") > 0 }
        // Le focus démarre dans le contenu ; Haut puis Droite rejoint la barre.
        rule.onNodeWithText("Rechercher").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        rule.ok()
        rule.until { rule.onAllNodesWithTextCount("Rechercher dans : ${if (tab == HomeTab.SERIES) "Séries" else if (tab == HomeTab.MOVIES) "Films" else "Direct"}") > 0 }
    }
    @Test fun recherche_s_ouvre_depuis_series() { open() }
    @Test fun recherche_s_ouvre_depuis_films() { open(HomeTab.MOVIES) }
    @Test fun recherche_s_ouvre_depuis_direct() { open(HomeTab.DIRECT) }
    @Test fun bas_va_aux_resultats_haut_revient_au_champ() {
        open(); rule.onNodeWithTag("search-query").performTextReplacement("Serie s1")
        rule.until { rule.onAllNodesWithTextCount("Serie s1-1") > 0 }
        rule.down(); assertTrue(focused("Serie s1-1"), "${rule.focusedTexts()}")
        rule.up(); assertTrue(focused("#search-query"), "${rule.focusedTexts()}")
    }
    @Test fun retour_ferme_la_recherche_focus_sur_le_bouton_rechercher() {
        open(); rule.back(); rule.until { focused("Rechercher") }
        assertTrue(rule.onAllNodesWithTextCount("Rechercher dans : Séries") == 0)
    }
    @Test fun bouton_parler_absent_sans_service_vocal() { open(); assertTrue(rule.onAllNodes(hasText("Parler", substring = false)).fetchSemanticsNodes().isEmpty()) }
    @Test fun etats_vide_et_rien_trouve_focalisables() {
        open(); rule.down(); assertTrue(focused("Tape quelques lettres"), "${rule.focusedTexts()}")
        rule.up(); rule.onNodeWithTag("search-query").performTextReplacement("zzzzzzzzzz")
        rule.until { rule.onAllNodesWithTextCount("Rien trouvé pour « zzzzzzzzzz ».") > 0 }
        rule.down(); assertTrue(focused("Rien trouvé"), "${rule.focusedTexts()}")
    }
    @Test fun ok_sur_chaine_revele_la_chaine_dans_le_direct_avec_apercu_hors_viewport() {
        open(HomeTab.DIRECT, 40)
        rule.onNodeWithTag("search-query").performTextReplacement("Chaine 2-35")
        rule.until { rule.onAllNodesWithTextCount("Chaine 2-35") > 0 }
        rule.down(); rule.until { focused("Chaine 2-35") }; rule.ok()
        rule.until(what = "chaîne retrouvée et aperçu", detail = { "${rig.log} selected=${rig.searchSession?.vm?.selectedKey?.value} loaded=${rig.searchSession?.vm?.loaded?.value?.key} reveal=${rig.searchSession?.vm?.revealTarget?.value} notice=${rig.searchSession?.vm?.revealNotice?.value} search=${rule.onAllNodesWithTextCount("Rechercher dans : Direct")}" }) { focused("Chaine 2-35") && rig.log.count { it == "play" } == 1 }
        assertTrue(rule.onAllNodesWithTextCount("Rechercher dans : Direct") == 0)
        assertTrue(rule.onAllNodesWithTextCount("Categorie 2") > 0)
        // Deuxième OK : plein écran ; Bas garde le zapping existant.
        rule.ok(); rule.until { rule.onAllNodesWithTextCount("Categorie 2") == 0 }
        rule.down(); rule.until { rig.log.count { it == "play" } == 2 }
    }

    @Test fun ok_sur_serie_ouvre_fiche_retour_garde_texte_et_focus() { detailReturn(HomeTab.SERIES, "Serie s1", "Serie s1-1") }
    @Test fun ok_sur_film_ouvre_fiche_retour_garde_texte_et_focus() { detailReturn(HomeTab.MOVIES, "Film c1", "Film c1-1") }
    private fun detailReturn(tab: HomeTab, query: String, title: String) {
        open(tab); rule.onNodeWithTag("search-query").performTextReplacement(query)
        rule.until { rule.onAllNodesWithTextCount(title) > 0 }; rule.down(); rule.right()
        val selected = rule.focusedTexts().orEmpty().first { !it.startsWith("#") }
        rule.ok(); rule.until { rule.onAllNodesWithTextCount("Rechercher dans : ${if (tab == HomeTab.SERIES) "Séries" else "Films"}") == 0 }
        rule.back(); rule.until { focused(selected) }
        rule.onNodeWithTag("search-query").assertExists()
        assertTrue(rig.log.none { it.startsWith("play") })
    }
}
