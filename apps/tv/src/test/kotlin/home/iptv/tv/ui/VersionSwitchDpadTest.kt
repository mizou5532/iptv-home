package home.iptv.tv.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import home.iptv.shared.meta.MediaKind
import home.iptv.shared.search.SearchItem
import home.iptv.shared.versions.VersionIndexes
import home.iptv.tv.data.HomeTab
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertTrue

/**
 * Bogue du 23 nov. 2026 (South Park, profil en anglais) : sur la fiche, « Version : Anglais » → « Français (France) »
 * laissait l'écran sur « Chargement… » pour toujours (séries comme films). La fiche de la version choisie doit
 * s'afficher, et on doit pouvoir revenir à la première.
 */
@RunWith(RobolectricTestRunner::class)
class VersionSwitchDpadTest {
    @get:Rule val rule = tvRule()
    private var rig: HomeRig? = null
    @After fun tearDown() { rig?.scope?.cancel() }

    /** Deux versions du même titre : [en] dans une catégorie anglaise, [fr] dans une catégorie française. */
    private fun index(kind: MediaKind, en: Pair<String, String>, fr: Pair<String, String>) = VersionIndexes(
        items = { k -> if (k != kind) emptyList() else listOf(
            SearchItem(en.first, "EN - South Park", setOf(en.second)),
            SearchItem(fr.first, "FR - South Park", setOf(fr.second)),
        ) },
        categoryNames = { k -> if (k != kind) emptyMap() else mapOf(en.second to "EN SERIES", fr.second to "FR SERIES") },
    ).also { it.index(kind) }

    private fun pick(label: String) {
        rule.until(what = "bouton « Version : Anglais »") { rule.onAllNodesWithTextCount("Version : Anglais") > 0 }
        rule.onNode(hasText("Version : Anglais")).performSemanticsAction(SemanticsActions.OnClick)
        rule.until(what = "liste des versions") { rule.onAllNodesWithTextCount(label) > 0 }
        rule.onNode(hasText(label)).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
    }

    @Test fun serie_changer_de_langue_affiche_la_fiche_de_l_autre_version() {
        val r = HomeRig(tab = HomeTab.SERIES).also { rig = it }
        r.show(rule, versions = index(MediaKind.SERIES, "s1-1" to "s1", "s2-1" to "s2"))
        rule.until { rule.onAllNodesWithTextCount("Serie s1-1") > 0 }
        repeat(4) { if (rule.focusedTexts()?.any { it.contains("Serie s1-") } != true) rule.down() }
        assertTrue(rule.focusedTexts()?.any { it.contains("Serie s1-1") } == true, "focus : ${rule.focusedTexts()}")
        rule.ok()
        rule.until(what = "fiche anglaise") { rule.onAllNodes(hasText("Episode 1 de s1-1", substring = true)).fetchSemanticsNodes().isNotEmpty() }

        pick("Français (France)")
        rule.until(5_000, what = "fiche française (pas « Chargement… » sans fin)", detail = { "infos=${r.catalog.infoCalls}" }) {
            rule.onAllNodes(hasText("Episode 1 de s2-1", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(rule.onAllNodesWithTextCount("Chargement…") == 0)

        // Et retour à l'anglais : la fiche anglaise revient aussi.
        rule.until(what = "bouton « Version : Français (France) »") { rule.onAllNodesWithTextCount("Version : Français (France)") > 0 }
        rule.onNode(hasText("Version : Français (France)")).performSemanticsAction(SemanticsActions.OnClick)
        rule.until(what = "liste des versions") { rule.onAllNodesWithTextCount("Anglais") > 0 }
        rule.onNode(hasText("Anglais")).performSemanticsAction(SemanticsActions.OnClick)
        rule.until(5_000, what = "retour à la fiche anglaise") {
            rule.onAllNodes(hasText("Episode 1 de s1-1", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun film_changer_de_langue_affiche_la_fiche_de_l_autre_version() {
        val r = HomeRig(tab = HomeTab.MOVIES).also { rig = it }
        r.show(rule, versions = index(MediaKind.MOVIE, "c1-1" to "c1", "c2-1" to "c2"))
        rule.until { rule.onAllNodesWithTextCount("Film c1-1") > 0 }
        repeat(5) { if (rule.focusedTexts()?.any { it.contains("Film c1-") } != true) rule.down() }
        assertTrue(rule.focusedTexts()?.any { it.contains("Film c1-1") } == true, "focus : ${rule.focusedTexts()}")
        rule.ok()
        rule.until(what = "fiche anglaise") { rule.onAllNodesWithTextCount("Recommencer") > 0 }

        pick("Français (France)")
        rule.until(5_000, what = "fiche du film français (pas « Chargement… » sans fin)") {
            rule.onAllNodesWithTextCount("Film c2-1") > 0 && rule.onAllNodesWithTextCount("Recommencer") > 0
        }
        assertTrue(rule.onAllNodesWithTextCount("Chargement…") == 0)
    }
}
