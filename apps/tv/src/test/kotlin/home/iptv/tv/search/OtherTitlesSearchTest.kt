package home.iptv.tv.search

import home.iptv.shared.search.*
import home.iptv.shared.sync.*
import home.iptv.tv.series.seriesHidden
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

/**
 * Vitrine (0.41) : les titres hors vitrine ne sont pas dans l'index en mémoire, mais la recherche les trouve (lus à la
 * demande), sous ceux de la vitrine, sans doublon ; OK les ouvre ; une catégorie masquée les retire ; oubliés après
 * quelques minutes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OtherTitlesSearchTest {
    private val known = ProfilePrefs(1, emptyList(), emptyList(), emptyList(), null)

    private class Fixture(val test: TestScope) {
        val prefs = MutableStateFlow<ProfilePrefs?>(ProfilePrefs(1, emptyList(), emptyList(), emptyList(), null))
        val showcase = listOf(SearchItem("a", "Simpsons", setOf("vit")))
        val others = listOf(SearchItem("x", "Simpsons Specials", setOf("autre")), SearchItem("y", "Obscure Drama", setOf("autre")),
            SearchItem("a", "Simpsons", setOf("autre")))
        var loads = 0
        val other = OtherTitles(test.backgroundScope, keepMs = 1_000) { loads++; others to mapOf("vit" to "ENGLISH SERIES", "autre" to "AMAZON SERIES") }
        val vm = SearchViewModel(SearchSection.SERIES, { showcase }, { id -> showcase.find { it.id == id }?.let { SearchResult(id, it.title, null, it.categoryIds.first()) } },
            prefs, { it.seriesHidden() }, MutableSharedFlow(), MutableStateFlow(emptyMap()), test.backgroundScope, StandardTestDispatcher(test.testScheduler),
            more = { q, hidden -> other.search(q, hidden, home.iptv.shared.versions.LanguagePrefs()) })
        suspend fun settle() { test.runCurrent(); test.advanceTimeBy(121); test.runCurrent() }
    }

    @Test fun hors_vitrine_trouve_apres_la_vitrine_et_ouvrable() = runTest {
        val f = Fixture(this); f.settle()
        assertEquals(0, f.loads, "rien de lu tant qu'on ne cherche pas")
        f.vm.setQuery("simpsons"); f.settle()
        assertEquals(listOf("a", "x"), f.vm.ui.value.results.map { it.id }, "vitrine d'abord, puis le reste, sans doublon")
        assertEquals("x", f.vm.currentResult("x")?.id, "OK ouvre un titre hors vitrine")
        f.vm.setQuery("obscure"); f.settle()
        assertEquals(listOf("y"), f.vm.ui.value.results.map { it.id })
        assertEquals(1, f.loads, "lu une seule fois pour les lettres suivantes")
    }

    @Test fun categorie_masquee_retire_les_titres_hors_vitrine() = runTest {
        val f = Fixture(this); f.settle()
        f.prefs.value = known.copy(hidden = listOf(HiddenItem("series_category", "autre"))); runCurrent()
        f.vm.setQuery("simpsons"); f.settle()
        assertEquals(listOf("a"), f.vm.ui.value.results.map { it.id })
        assertNull(f.vm.currentResult("x"))
    }

    @Test fun oublies_apres_un_moment() = runTest {
        val f = Fixture(this); f.settle()
        f.vm.setQuery("obscure"); f.settle(); assertEquals(1, f.loads)
        advanceTimeBy(2_000); runCurrent()
        f.vm.setQuery("drama"); f.settle()
        assertEquals(2, f.loads, "relu après l'oubli")
    }
}
