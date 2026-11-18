package home.iptv.tv.ui

import android.view.KeyEvent
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextReplacement
import home.iptv.shared.search.*
import home.iptv.shared.sync.*
import home.iptv.tv.search.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
class SearchFocusDpadTest {
    @get:Rule val rule = tvRule()
    private val scope = CoroutineScope(SupervisorJob() + testMain())
    private val prefs = MutableStateFlow<ProfilePrefs?>(NO_PREFS)
    private val changed = MutableSharedFlow<SearchSection>(extraBufferCapacity = 4)
    @Volatile private var rows = listOf(SearchItem("a", "Chaine Alpha", setOf("c")), SearchItem("b", "Chaine Beta", setOf("c")))
    private val opened = mutableListOf<String>()
    private val vm = SearchViewModel(SearchSection.LIVE, { rows }, { id -> rows.firstOrNull { it.id == id }?.let { SearchResult(id, it.title, null, "c", id) } },
        prefs, { p -> p.hidden.filter { it.itemType == "live_category" }.mapTo(HashSet()) { it.itemId } }, changed, MutableStateFlow(emptyMap()), scope, Dispatchers.IO)
    @After fun close() { scope.cancel() }
    private fun focused(text: String) = rule.focusedTexts()?.any { text in it } == true
    private fun show() {
        rule.setTvContent { SearchScreen(vm, { opened += it.id }, {}, null, null) }
        rule.typeQuery("Chaine")
        rule.until { rule.onAllNodesWithTextCount("Chaine Beta") > 0 }
        repeat(6) { if (rule.focusedTexts()?.any { it.startsWith("#kb-") } == true) rule.down() }
        rule.until { focused("Chaine Beta") }
    }
    @Test fun resultats_qui_changent_sous_le_focus_gardent_le_focus_puis_premier_si_retire() {
        show()
        rows = listOf(SearchItem("z", "Chaine Zero", setOf("c"))) + rows
        changed.tryEmit(SearchSection.LIVE)
        rule.until { rule.onAllNodesWithTextCount("Chaine Zero") > 0 }; assertTrue(focused("Chaine Beta"))
        rows = rows.filter { it.id != "b" }; changed.tryEmit(SearchSection.LIVE)
        rule.until { focused("Chaine Zero") }
    }
    @Test fun remplissage_ne_vole_pas_le_focus_au_clavier() {
        show(); rule.up(); assertTrue(focused("#kb-voir"))
        rows = rows + SearchItem("z", "Chaine Zero", setOf("c")); changed.tryEmit(SearchSection.LIVE)
        rule.until { rule.onAllNodesWithTextCount("Chaine Zero") > 0 }; assertTrue(focused("#kb-voir"))
    }
    @Test fun chaine_devenue_masquee_message_et_rien_ne_se_lance() {
        show()
        // Le masque arrive entre le dernier rendu et OK, sans laisser la recomposition retirer le résultat.
        rule.runOnUiThread {
            prefs.value = NO_PREFS.copy(rev = 2, hidden = listOf(HiddenItem("live_category", "c")))
            rule.activity.window.decorView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
            rule.activity.window.decorView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
        }
        rule.until { rule.onAllNodesWithTextCount("Chaîne indisponible.") > 0 }
        assertTrue(opened.isEmpty())
        assertNotNull(rule.focusedTexts())
    }
    @Test fun au_clavier_la_liste_montre_toujours_les_premiers_resultats() {
        // Essai TCL : après quelques lettres, la liste descendait et cachait les premiers titres.
        rows = (1..30).map { SearchItem("x$it", "Chaine Liste %02d".format(it), setOf("c")) }
        changed.tryEmit(SearchSection.LIVE)
        rule.setTvContent { SearchScreen(vm, { opened += it.id }, {}, null, null) }
        rule.typeQuery("Chaine")
        rule.until { rule.onAllNodesWithTextCount("Chaine Liste 01") > 0 }
        // De meilleurs résultats arrivent devant ceux affichés (nouvelle lettre, ou catalogue qui se remplit).
        rows = (1..12).map { SearchItem("n$it", "Chaine Avant %02d".format(it), setOf("c")) } + rows
        changed.tryEmit(SearchSection.LIVE)
        rule.until(what = "nouveaux résultats") { rule.onAllNodesWithTextCount("Chaine Avant 02") > 0 || rule.onAllNodesWithTextCount("Chaine Liste 01") == 0 }
        rule.waitForIdle()
        // Le premier résultat est vraiment à l'écran, en haut de la liste (pas seulement composé hors de la vue).
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val first = rule.onAllNodes(androidx.compose.ui.test.hasText("Chaine Avant 01", substring = true)).fetchSemanticsNodes()
        assertTrue(first.isNotEmpty() && first.first().boundsInRoot.top >= 0f && first.first().boundsInRoot.bottom <= root.bottom,
            "« Chaine Avant 01 » visible : ${first.map { it.boundsInRoot }} dans $root")
        assertTrue(rule.focusedTexts()?.any { it.startsWith("#kb-") } == true, "le focus reste au clavier")
    }
}
