package home.iptv.tv.movies

import home.iptv.shared.progress.*
import home.iptv.shared.sync.*
import home.iptv.shared.xtream.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

internal fun movie(id: String = "m", cat: String = "c") = MovieSummary(id, cat, "Film $id", null, "2026", null, null, null, 100, "mkv", 1000)
internal val moviePrefs = ProfilePrefs(1, emptyList(), emptyList(), emptyList(), null)
internal class MemoryMovieState : MovieHomeState {
    var rows = emptyList<ProgressRow>(); var items = emptyList<MovieListItem>()
    override fun progress(profile: String) = rows
    override fun saveProgress(profile: String, rows: List<ProgressRow>) { this.rows = rows }
    override fun list(profile: String) = items
    override fun saveList(profile: String, rows: List<MovieListItem>) { items = rows }
    override fun published(household: String) = 0L
    override fun savePublished(household: String, at: Long) {}
}
internal open class FakeMovieCatalog : MovieCatalog {
    var calls = 0; var gate: CompletableDeferred<Unit>? = null
    override suspend fun categories(): List<MovieCategory> { calls++; return listOf(MovieCategory("c", "C")) }
    override suspend fun moviesOf(categoryId: String, hidden: Set<String>): List<MovieSummary> { calls++; gate?.await(); return listOf(movie()) }
    override fun cachedCategory(movieId: String): String? = "c"
    override fun recent(hidden: Set<String>, limit: Int) = emptyList<MovieSummary>()
    override suspend fun info(movieId: String, categoryId: String): MovieInfo? { calls++; gate?.await(); return MovieInfo(movie(movieId, categoryId)) }
}
@OptIn(ExperimentalCoroutinesApi::class)
class MovieHomeViewModelTest {
    @Test fun no_content_request_before_preferences_and_hidden_inflight_response_discarded() = runTest {
        val prefs = MutableStateFlow<ProfilePrefs?>(null)
        val cat = FakeMovieCatalog()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val vm = MovieHomeViewModel(cat, { emptyList() }, { emptyList() }, { emptyList() }, {}, MemoryMovieState(), "p", { null }, prefs, scope, StandardTestDispatcher(testScheduler))
        vm.start(); vm.loadCategory("c"); advanceUntilIdle()
        assertEquals(0, cat.calls)
        prefs.value = moviePrefs; advanceUntilIdle()
        cat.gate = CompletableDeferred()
        vm.loadCategory("c"); advanceUntilIdle()
        prefs.value = moviePrefs.copy(hidden = listOf(HiddenItem("vod_category", "c")))
        runCurrent(); cat.gate!!.complete(Unit); advanceUntilIdle()
        assertTrue(vm.ui.value.rowMovies.isEmpty())
        assertTrue(vm.ui.value.home.categoryRows.isEmpty())
        scope.cancel()
    }
}
