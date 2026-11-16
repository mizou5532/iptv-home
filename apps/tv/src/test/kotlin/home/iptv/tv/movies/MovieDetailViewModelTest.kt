package home.iptv.tv.movies

import home.iptv.shared.progress.*
import home.iptv.shared.xtream.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*
import kotlin.time.Instant

internal open class FakeMovieClient : MovieClient {
    var watchedCalls = 0
    override suspend fun publishCategories(h: String, cats: List<home.iptv.shared.sync.PublishedCategory>) {}
    override suspend fun continueWatching(p: String, max: Int) = emptyList<ProgressRow>()
    override suspend fun progress(p: String, id: String): ProgressRow? = null
    override suspend fun list(p: String) = emptyList<MovieListItem>()
    override suspend fun setList(p: String, movie: MovieSummary, on: Boolean) {}
    override suspend fun setWatched(p: String, movie: MovieSummary, watched: Boolean) { watchedCalls++ }
}
@OptIn(ExperimentalCoroutinesApi::class)
class MovieDetailViewModelTest {
    @Test fun old_unknown_category_reference_never_fetches_info() = runTest {
        val cat = object : FakeMovieCatalog() { override fun cachedCategory(movieId: String): String? = null }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val vm = MovieDetailViewModel("m", null, cat, FakeMovieClient(), "p", MemoryMovieState(), { emptyList() }, {}, MutableStateFlow(moviePrefs), scope, StandardTestDispatcher(testScheduler))
        vm.start(); advanceUntilIdle()
        assertEquals(0, cat.calls)
        assertNull(vm.play(false))
        assertNotNull(vm.state.value.message)
        scope.cancel()
    }
    @Test fun manual_unwatched_drops_pending_movie_and_keeps_original_recency() = runTest {
        val at = Instant.parse("2026-11-15T10:00:00Z")
        val pending = mutableListOf(ProgressRow("m", "movie", positionS = 500, durationS = 1000, clientUpdatedAt = at, movieCategory = "c"))
        val saved = MemoryMovieState()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val vm = MovieDetailViewModel("m", "c", FakeMovieCatalog(), FakeMovieClient(), "p", saved, { pending.toList() }, { pending.clear() }, MutableStateFlow(moviePrefs), scope, StandardTestDispatcher(testScheduler))
        vm.start(); advanceUntilIdle()
        assertEquals(500, vm.play(false)?.startS)
        vm.markWatched(false); advanceUntilIdle()
        assertEquals(0, vm.play(false)?.startS)
        assertTrue(pending.isEmpty())
        assertEquals(at, saved.rows.single().clientUpdatedAt)
        assertFalse(saved.rows.single().watched)
        scope.cancel()
    }
}
