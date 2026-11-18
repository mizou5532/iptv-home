package home.iptv.tv.ui

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import home.iptv.shared.meta.MediaKind
import home.iptv.shared.search.SearchItem
import home.iptv.tv.data.HomeTab
import home.iptv.tv.feature.VersionIndexes
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertTrue

/**
 * Changer de version (langue) sur une fiche : la fiche de l'autre version doit se charger (avant 0.32.2, elle restait
 * sur « Chargement… » pour toujours, car la nouvelle fiche n'était jamais démarrée).
 */
@RunWith(RobolectricTestRunner::class)
class VersionSwitchDpadTest {
    @get:Rule val rule = tvRule()
    private var rig: HomeRig? = null

    @After fun tearDown() { rig?.scope?.cancel() }

    /** Deux versions du même titre : l'une dans une catégorie anglaise, l'autre dans une catégorie française. */
    private fun versions(kind: MediaKind, a: Pair<String, String>, b: Pair<String, String>, title: String) = VersionIndexes(
        items = { k -> if (k != kind) emptyList() else listOf(SearchItem(a.first, title, setOf(a.second)), SearchItem(b.first, title, setOf(b.second))) },
        categoryNames = { k -> if (k != kind) emptyMap() else mapOf(a.second to "EN| Anglais", b.second to "FR| Français") },
    ).also { it.index(kind) }

    private fun pickOtherVersion() {
        rule.until(what = "bouton Version") { rule.onAllNodes(hasText("Version :", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasText("Version :", substring = true))[0].performClick()
        rule.until(what = "liste des versions") { rule.onAllNodes(hasText("Choisir la version")).fetchSemanticsNodes().isNotEmpty() }
        // La version affichée porte « ✓ » : on prend l'autre.
        val other = listOf("Anglais", "Français (France)").first { label ->
            rule.onAllNodes(hasText("✓ $label")).fetchSemanticsNodes().isEmpty()
        }
        rule.onAllNodes(hasText(other))[0].performClick()
    }

    @Test
    fun series_changer_de_langue_charge_l_autre_version() {
        val r = HomeRig(tab = HomeTab.SERIES).also { rig = it }
        r.versions = versions(MediaKind.SERIES, "s1-1" to "s1", "s2-1" to "s2", "Les Simpsons")
        r.show(rule)
        rule.until { rule.onAllNodesWithTextCount("Serie s1-2") > 0 }
        val opened = listOf("s1-1", "s2-1").first { rule.onAllNodesWithTextCount("Serie $it") > 0 }
        rule.onAllNodes(hasText("Serie $opened"))[0].performClick()
        rule.until(what = "fiche ouverte") { rule.onAllNodes(hasText("Episode 1 de", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        val first = listOf("s1-1", "s2-1").first { rule.onAllNodes(hasText("Episode 1 de $it", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        val second = if (first == "s1-1") "s2-1" else "s1-1"
        pickOtherVersion()
        rule.until(10_000, what = "épisodes de l'autre version ($second)", detail = { "appels fiche ${r.catalog.infoCalls}" }) {
            rule.onAllNodes(hasText("Episode 1 de $second", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        // Et on peut revenir à la première.
        pickOtherVersion()
        rule.until(10_000, what = "retour à la première version ($first)") {
            rule.onAllNodes(hasText("Episode 1 de $first", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun films_changer_de_langue_charge_l_autre_version() {
        val r = HomeRig(tab = HomeTab.MOVIES).also { rig = it }
        r.versions = versions(MediaKind.MOVIE, "c1-1" to "c1", "c2-1" to "c2", "Oppenheimer")
        r.show(rule)
        rule.until { rule.onAllNodesWithTextCount("Film c1-1") + rule.onAllNodesWithTextCount("Film c2-1") > 0 }
        val opened = listOf("c1-1", "c2-1").first { rule.onAllNodesWithTextCount("Film $it") > 0 }
        rule.onAllNodes(hasText("Film $opened"))[0].performClick()
        rule.until(what = "fiche ouverte") { rule.onAllNodesWithTextCount("Recommencer") > 0 }
        pickOtherVersion()
        // La nouvelle fiche se charge (bouton Recommencer revenu, plus de « Chargement »).
        rule.until(10_000, what = "fiche de l'autre version chargée") {
            rule.onAllNodesWithTextCount("Recommencer") > 0 && rule.onAllNodes(hasText("Choisir la version")).fetchSemanticsNodes().isEmpty()
        }
        Thread.sleep(300); rule.waitForIdle()
        assertTrue(rule.onAllNodesWithTextCount("Recommencer") > 0, "la fiche du film est restée en chargement")
    }
}
