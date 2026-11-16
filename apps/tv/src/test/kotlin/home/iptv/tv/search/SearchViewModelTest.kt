package home.iptv.tv.search

import home.iptv.shared.search.*
import home.iptv.shared.sync.*
import home.iptv.tv.series.seriesHidden
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val known = ProfilePrefs(1, emptyList(), emptyList(), emptyList(), null)
    private val first = SearchItem("a", "Canadiens", setOf("c"))
    private val second = SearchItem("b", "Cinema", setOf("d"))
    private class Fixture(val test: TestScope) {
        val prefs = MutableStateFlow<ProfilePrefs?>(ProfilePrefs(1, emptyList(), emptyList(), emptyList(), null))
        val changed = MutableSharedFlow<SearchSection>()
        val progress = MutableStateFlow<Map<SearchSection, FillProgress>>(emptyMap())
        var rows = listOf(SearchItem("a", "Canadiens", setOf("c")), SearchItem("b", "Cinema", setOf("d")))
        var reads = 0
        var source: suspend () -> List<SearchItem> = { reads++; rows }
        var detail: (String) -> SearchResult? = { id -> rows.find { it.id == id }?.let { SearchResult(id, it.title, null, it.categoryIds.first()) } }
        val vm = SearchViewModel(SearchSection.SERIES, { source() }, { detail(it) }, prefs, { it.seriesHidden() }, changed, progress, test.backgroundScope, StandardTestDispatcher(test.testScheduler))
        suspend fun settle() { test.runCurrent(); test.advanceTimeBy(121); test.runCurrent() }
    }
    @Test fun frappe_rapide_seul_le_dernier_resultat() = runTest {
        val f = Fixture(this); f.settle()
        f.vm.setQuery("c"); advanceTimeBy(40); f.vm.setQuery("ca"); advanceTimeBy(40); f.vm.setQuery("can"); f.settle()
        assertEquals("can", f.vm.ui.value.query); assertEquals(listOf("a"), f.vm.ui.value.results.map { it.id })
    }
    @Test fun index_reconstruit_quand_le_cache_change() = runTest {
        val f = Fixture(this); f.vm.setQuery("can"); f.settle()
        f.rows = f.rows + SearchItem("new", "Canada", setOf("c")); f.changed.emit(SearchSection.SERIES); f.settle()
        assertEquals(setOf("a", "new"), f.vm.ui.value.results.map { it.id }.toSet()); assertEquals("can", f.vm.ui.value.query)
    }
    @Test fun masquage_pendant_la_recherche_retire_le_resultat() = runTest {
        val f = Fixture(this); f.vm.setQuery("can"); f.settle(); assertEquals(1, f.vm.ui.value.results.size)
        val gate = CompletableDeferred<List<SearchItem>>(); f.source = { gate.await() }
        f.prefs.value = known.copy(hidden = listOf(HiddenItem("series_category", "c"))); runCurrent()
        assertTrue(f.vm.ui.value.results.isEmpty()); gate.complete(listOf(first)); f.settle(); assertTrue(f.vm.ui.value.results.isEmpty())
    }
    @Test fun prefs_inconnues_aucun_resultat_et_message() = runTest {
        val f = Fixture(this); f.prefs.value = null; f.vm.setQuery("can"); f.settle()
        assertEquals(0, f.reads); assertTrue(f.vm.ui.value.results.isEmpty())
        assertEquals("Les réglages du profil ne sont pas encore chargés.", f.vm.ui.value.message)
        f.prefs.value = known; f.settle(); assertEquals(1, f.vm.ui.value.results.size)
        f.prefs.value = null; runCurrent(); assertTrue(f.vm.ui.value.results.isEmpty())
    }
    @Test fun messages_exacts() = runTest {
        val f = Fixture(this); f.settle(); assertEquals("Tape quelques lettres ou appuie sur Parler.", f.vm.ui.value.message)
        f.vm.setQuery("introuvable"); f.settle(); assertEquals("Rien trouvé pour « introuvable ».", f.vm.ui.value.message)
        f.vm.setQuery("can"); f.settle(); assertNull(f.vm.ui.value.message)
        f.vm.setQuery("   "); f.settle(); assertEquals("Tape quelques lettres ou appuie sur Parler.", f.vm.ui.value.message)
    }
    @Test fun avancement_affiche_tant_que_incomplet() = runTest {
        val f = Fixture(this); f.progress.value = mapOf(SearchSection.SERIES to FillProgress(1, 3), SearchSection.MOVIES to FillProgress(2, 4)); f.settle()
        assertEquals(FillProgress(1, 3), f.vm.ui.value.progress)
        f.progress.value = mapOf(SearchSection.SERIES to FillProgress(3, 3)); runCurrent(); assertNull(f.vm.ui.value.progress)
    }
    @Test fun ancien_calcul_non_cooperatif_ne_publie_pas() = runTest {
        val f = Fixture(this); val gate = CompletableDeferred<List<SearchItem>>()
        f.source = { withContext(NonCancellable) { gate.await() } }; f.vm.setQuery("cin"); runCurrent(); advanceTimeBy(121); runCurrent()
        f.source = { listOf(first) }; f.vm.setQuery("can"); f.settle()
        gate.complete(listOf(second)); f.settle()
        assertEquals("can", f.vm.ui.value.query); assertEquals(listOf("a"), f.vm.ui.value.results.map { it.id })
    }
    @Test fun details_absents_omis_et_union_masquee_gagne() = runTest {
        val f = Fixture(this); f.rows = listOf(first.copy(categoryIds = setOf("c", "hidden")), second)
        f.prefs.value = known.copy(hidden = listOf(HiddenItem("series_category", "hidden"))); f.detail = { null }
        f.vm.setQuery("c"); f.settle(); assertTrue(f.vm.ui.value.results.isEmpty())
        f.detail = { SearchResult(it, "Cinema", null, "d") }; f.changed.emit(SearchSection.SERIES); f.settle()
        assertEquals(listOf("b"), f.vm.ui.value.results.map { it.id })
    }
    @Test fun section_fixe_et_autre_section_ignoree() = runTest {
        val f = Fixture(this); f.settle(); val before = f.reads
        f.changed.emit(SearchSection.MOVIES); f.settle(); assertEquals(before, f.reads); assertEquals(SearchSection.SERIES, f.vm.ui.value.section)
    }
    @Test fun avancement_reapparait_apres_preferences_chargees_sans_nouvelle_emission() = runTest {
        val f = Fixture(this); f.progress.value = mapOf(SearchSection.SERIES to FillProgress(1, 3))
        f.prefs.value = null; f.settle(); assertNull(f.vm.ui.value.progress)
        f.prefs.value = known; f.settle(); assertEquals(FillProgress(1, 3), f.vm.ui.value.progress)
    }
    @Test fun extraction_series_hidden_ignore_autres_types() {
        assertEquals(setOf("s"), known.copy(hidden = listOf(HiddenItem("series_category", "s"), HiddenItem("vod_category", "m"))).seriesHidden())
    }
}
