package home.iptv.tv.search

import home.iptv.shared.search.FillProgress
import home.iptv.shared.search.PersonCredit
import home.iptv.shared.search.PersonCredits
import home.iptv.shared.search.PersonInfo
import home.iptv.shared.search.SearchItem
import home.iptv.shared.search.SearchSection
import home.iptv.shared.sync.HiddenItem
import home.iptv.shared.sync.ProfilePrefs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HomeSearchTest {
    private val basePrefs = ProfilePrefs(1, emptyList(), emptyList(), emptyList(), null)

    @Test
    fun remoteSearchAvoidsBuildingLocalIndexAndSupportsCurrentResult() = runTest {
        val disp = StandardTestDispatcher(testScheduler)
        var localReads = 0
        var moreReads = 0
        val prefs = MutableStateFlow<ProfilePrefs?>(basePrefs)
        val vm = SearchViewModel(
            section = SearchSection.MOVIES,
            items = { localReads++; listOf(SearchItem("m1", "Local Dune", setOf("1"))) },
            details = { SearchResult(it, "Local", null, "1") },
            prefs = prefs,
            hiddenFor = { p -> p.hidden.filter { it.itemType == "vod_category" }.mapTo(HashSet()) { it.itemId } },
            changed = MutableSharedFlow(),
            progress = MutableStateFlow(emptyMap()),
            scope = backgroundScope,
            io = disp,
            more = { _, _ -> moreReads++; emptyList() },
            remote = { q, _, _ ->
                if (q == "dune") listOf(
                    SearchResult("101", "Dune", null, "1"),
                    SearchResult("999", "Interdit", null, "secret"),
                ) else emptyList()
            },
        )
        runCurrent()
        // Requête vide : aucun index local construit !
        assertEquals(0, localReads)

        // Masquage défensif côté télé : "secret" est filtré même si le serveur l'avait renvoyé.
        prefs.value = basePrefs.copy(hidden = listOf(HiddenItem("vod_category", "secret")))
        vm.setQuery("dune")
        advanceTimeBy(150); runCurrent()

        assertEquals(listOf("101"), vm.ui.value.results.map { it.id })
        assertEquals(0, localReads)
        assertEquals(0, moreReads)
        assertNotNull(vm.currentResult("101"))
        assertEquals(0, localReads)

        // Si la catégorie devient masquée entre temps : currentResult rend null aussitôt !
        prefs.value = basePrefs.copy(hidden = listOf(HiddenItem("vod_category", "secret"), HiddenItem("vod_category", "1")))
        runCurrent()
        assertTrue(vm.ui.value.results.isEmpty())
        assertNull(vm.currentResult("101"))
    }

    @Test
    fun remoteNullFallsBackToLocalSearchAndPersonSearchUsesServer() = runTest {
        val disp = StandardTestDispatcher(testScheduler)
        var localReads = 0
        val prefs = MutableStateFlow<ProfilePrefs?>(basePrefs)
        var serverUp = false
        val vm = SearchViewModel(
            section = SearchSection.MOVIES,
            items = { localReads++; listOf(SearchItem("m1", "Dune Local", setOf("1"))) },
            details = { SearchResult(it, "Dune Local", null, "1") },
            prefs = prefs,
            hiddenFor = { emptySet() },
            changed = MutableSharedFlow(),
            progress = MutableStateFlow(emptyMap()),
            scope = backgroundScope,
            io = disp,
            person = {
                PersonCredits(
                    person = PersonInfo("Denis Villeneuve", "Directing"),
                    credits = listOf(PersonCredit("movie", "Sicario", "Sicario", 2015, "realisateur")),
                )
            },
            remote = { _, _, credits ->
                if (!serverUp) null
                else if (credits != null) listOf(SearchResult("m2", "Sicario", null, "1", note = "Réalisé par Denis Villeneuve"))
                else emptyList()
            },
        )
        runCurrent()

        // Serveur absent -> secours local transparent.
        vm.setQuery("dune")
        advanceTimeBy(150); runCurrent()
        assertEquals(listOf("m1"), vm.ui.value.results.map { it.id })
        assertEquals(1, localReads)

        // Serveur revient -> recherche par personne servie par le serveur sans relire le local.
        serverUp = true
        vm.setQuery("denis")
        advanceTimeBy(700); runCurrent()
        assertEquals(listOf("m2"), vm.ui.value.results.map { it.id })
        assertEquals("Réalisé par Denis Villeneuve", vm.ui.value.results.first().note)
        assertEquals(1, localReads)
    }
}
