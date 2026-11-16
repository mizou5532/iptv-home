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
    override fun featured(hidden: Set<String>): MovieSummary? = null
    override suspend fun info(movieId: String, categoryId: String): MovieInfo? { calls++; gate?.await(); return MovieInfo(movie(movieId, categoryId)) }
}
@OptIn(ExperimentalCoroutinesApi::class)
class MovieHomeViewModelTest {
    @Test fun cached_progress_and_list_display_while_server_is_suspended_even_if_prefs_observer_wins_startup() = runTest {
        val jobs = ArrayDeque<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { jobs.addLast(block) }
        }
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val saved = MemoryMovieState().also {
            it.rows = listOf(ProgressRow("m", "movie", positionS = 100, durationS = 1000, clientUpdatedAt = kotlin.time.Instant.parse("2026-11-15T10:00:00Z"), movieName = "Film m", movieCategory = "c"))
            it.items = listOf(MovieListItem("listed", "Film en liste", movieCategory = "c"))
        }
        val network = CompletableDeferred<List<ProgressRow>>()
        val vm = MovieHomeViewModel(FakeMovieCatalog(), { network.await() }, { emptyList() }, { emptyList() }, {}, saved, "p", { null }, MutableStateFlow(moviePrefs), scope, dispatcher)
        vm.start()
        // Deux lancements IO indépendants : l'observateur de préférences peut démarrer avant la lecture disque.
        jobs.removeLast().run()
        jobs.removeFirst().run()
        assertFalse(network.isCompleted)
        assertEquals("m", vm.ui.value.home.resume?.movieId)
        assertEquals(listOf("listed"), vm.ui.value.home.myList.map { it.movieId })
        scope.cancel()
        while (jobs.isNotEmpty()) jobs.removeFirst().run()
    }
    @Test fun known_cached_movie_without_added_date_is_featured_but_never_recent() = runTest {
        val cat = object : FakeMovieCatalog() {
            override fun featured(hidden: Set<String>) = movie("undated").copy(addedAt = null).takeIf { "c" !in hidden }
        }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val vm = MovieHomeViewModel(cat, { emptyList() }, { emptyList() }, { emptyList() }, {}, MemoryMovieState(), "p", { null }, MutableStateFlow(moviePrefs), scope, StandardTestDispatcher(testScheduler))
        vm.start(); advanceUntilIdle()
        assertEquals("undated", vm.ui.value.home.resume?.movieId)
        assertTrue(vm.ui.value.home.recent.isEmpty())
        scope.cancel()
    }
    @Test fun retry_does_not_request_content_while_preferences_unknown() = runTest {
        val cat = FakeMovieCatalog()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val vm = MovieHomeViewModel(cat, { emptyList() }, { emptyList() }, { emptyList() }, {}, MemoryMovieState(), "p", { null }, MutableStateFlow(null), scope, StandardTestDispatcher(testScheduler))
        vm.retry(); advanceUntilIdle()
        assertEquals(0, cat.calls)
        scope.cancel()
    }
    @Test fun no_content_request_before_preferences_and_hidden_inflight_response_discarded() = runTest {
        val prefs = MutableStateFlow<ProfilePrefs?>(null)
        val cat = FakeMovieCatalog()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val vm = MovieHomeViewModel(cat, { emptyList() }, { emptyList() }, { emptyList() }, {}, MemoryMovieState(), "p", { null }, prefs, scope, StandardTestDispatcher(testScheduler))
        vm.start(); vm.loadCategory("c"); advanceUntilIdle()
        assertEquals(0, cat.calls)
        cat.gate = CompletableDeferred()
        prefs.value = moviePrefs; advanceUntilIdle()
        assertEquals(2, cat.calls) // catégorie puis liste retenue en vol
        assertTrue(vm.ui.value.rowMovies.isEmpty())
        prefs.value = moviePrefs.copy(hidden = listOf(HiddenItem("vod_category", "c")))
        runCurrent(); cat.gate!!.complete(Unit); advanceUntilIdle()
        assertTrue(vm.ui.value.rowMovies.isEmpty())
        assertTrue(vm.ui.value.home.categoryRows.isEmpty())
        scope.cancel()
    }
}
