package home.iptv.tv.search

import home.iptv.shared.search.*
import home.iptv.shared.sync.*
import home.iptv.tv.movies.movieHidden
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PersonSearchViewModelTest {
    private val rows = listOf(
        SearchItem("1", "FR - Training Day (2001)", setOf("c")),
        SearchItem("2", "EN - Flight (2012)", setOf("c")),
        SearchItem("3", "FR - Glory (1989)", setOf("cachee")),
        SearchItem("4", "Denzel le documentaire", setOf("c")),
    )
    private val credits = PersonCredits(PersonInfo("Denzel Washington", "Acting"), listOf(
        PersonCredit("movie", "Training Day", year = 2001),
        PersonCredit("movie", "Glory", year = 1989),
        PersonCredit("movie", "Flight", year = 2012),
        PersonCredit("movie", "Denzel le documentaire", role = "realisateur"),
    ))

    @Test fun nom_tape_titres_de_la_personne_ajoutes_sous_les_resultats_masque_gagne() = runTest {
        val prefs = MutableStateFlow<ProfilePrefs?>(ProfilePrefs(1, listOf(HiddenItem("vod_category", "cachee")), emptyList(), emptyList(), null))
        var asked = 0
        val vm = SearchViewModel(SearchSection.MOVIES, { rows }, { id -> rows.find { it.id == id }?.let { SearchResult(id, it.title, null, it.categoryIds.first()) } },
            prefs, { it.movieHidden() }, MutableSharedFlow(), MutableStateFlow(emptyMap()), backgroundScope, StandardTestDispatcher(testScheduler),
            person = { asked++; credits })
        runCurrent(); advanceTimeBy(121); runCurrent()
        vm.setQuery("denzel")
        advanceTimeBy(121); runCurrent()
        // Titres par nom d'abord (« Denzel le documentaire »), puis ceux de la personne après la pause.
        assertEquals(listOf("4"), vm.ui.value.results.map { it.id })
        advanceTimeBy(501); runCurrent()
        assertEquals(listOf("4", "1", "2"), vm.ui.value.results.map { it.id }, "Glory est dans une catégorie masquée")
        assertEquals("Avec Denzel Washington", vm.ui.value.results[1].note)
        assertEquals(1, asked)
        // Moins de 4 lettres : pas de recherche par personne.
        vm.setQuery("den"); advanceTimeBy(800); runCurrent()
        assertEquals(1, asked)
    }
}
