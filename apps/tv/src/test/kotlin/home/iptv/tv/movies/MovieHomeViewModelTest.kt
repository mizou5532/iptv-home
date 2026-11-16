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
    // I1 : le serveur de synchro suspendu ne retarde ni les catégories ni Ma liste.
    @Test fun categories_and_list_display_while_server_progress_is_suspended() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val never = CompletableDeferred<List<ProgressRow>>()
        val vm = MovieHomeViewModel(FakeMovieCatalog(), { never.await() }, { listOf(MovieListItem("listed", "Film en liste", movieCategory = "c")) }, { emptyList() }, {}, MemoryMovieState(), "p", { null }, MutableStateFlow(moviePrefs), scope, StandardTestDispatcher(testScheduler))
        vm.start(); advanceUntilIdle()
        assertFalse(never.isCompleted)
        assertEquals(listOf("c"), vm.ui.value.home.categoryRows.map { it.id })
        assertFalse(vm.ui.value.loading)
        assertEquals(listOf("listed"), vm.ui.value.home.myList.map { it.movieId })
        scope.cancel()
    }
    // I2 : une catégorie masquée connue du cache gagne sur la catégorie autorisée de la référence.
    @Test fun hidden_cached_category_wins_over_allowed_reference_on_home() = runTest {
        val cat = object : FakeMovieCatalog() {
            override suspend fun categories() = listOf(MovieCategory("a", "A"), MovieCategory("b", "B"))
            override fun cachedCategory(movieId: String): String? = "b"
        }
        val saved = MemoryMovieState().also {
            it.rows = listOf(ProgressRow("m", "movie", positionS = 100, durationS = 1000, clientUpdatedAt = kotlin.time.Instant.parse("2026-11-15T10:00:00Z"), movieName = "Film m", movieCategory = "a"))
            it.items = listOf(MovieListItem("l", "Film l", movieCategory = "a"))
        }
        val prefs = MutableStateFlow<ProfilePrefs?>(moviePrefs.copy(hidden = listOf(HiddenItem("vod_category", "b"))))
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val vm = MovieHomeViewModel(cat, { saved.rows }, { saved.items }, { emptyList() }, {}, saved, "p", { null }, prefs, scope, StandardTestDispatcher(testScheduler))
        vm.start(); advanceUntilIdle()
        assertEquals(listOf("a"), vm.ui.value.home.categoryRows.map { it.id })
        assertTrue(vm.ui.value.home.continueRow.isEmpty())
        assertTrue(vm.ui.value.home.myList.isEmpty())
        assertNull(vm.ui.value.home.resume)
        scope.cancel()
    }
    // I3 : au Retour, l'accueil relit tout de suite la copie durable et la file, sans attendre le serveur.
    @Test fun returning_home_shows_local_changes_while_server_is_suspended() = runTest {
        val saved = MemoryMovieState()
        var suspended = false
        val never = CompletableDeferred<Nothing>()
        var queued = emptyList<ProgressRow>()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val vm = MovieHomeViewModel(FakeMovieCatalog(), { if (suspended) never.await() else emptyList() }, { if (suspended) never.await() else emptyList() }, { queued }, {}, saved, "p", { null }, MutableStateFlow(moviePrefs), scope, StandardTestDispatcher(testScheduler))
        vm.start(); advanceUntilIdle()
        assertTrue(vm.ui.value.home.continueRow.isEmpty())
        suspended = true
        val at = kotlin.time.Instant.parse("2026-11-15T10:00:00Z")
        // La fiche a écrit la copie durable (film vu, Ma liste) ; la lecture a laissé une position en file.
        saved.rows = listOf(ProgressRow("seen", "movie", positionS = 1000, durationS = 1000, watched = true, clientUpdatedAt = at, movieName = "Vu", movieCategory = "c"))
        saved.items = listOf(MovieListItem("listed", "Film en liste", movieCategory = "c"))
        queued = listOf(ProgressRow("play", "movie", positionS = 60, durationS = 1000, clientUpdatedAt = at, movieName = "En cours", movieCategory = "c"),
            ProgressRow("seen", "movie", positionS = 30, durationS = 1000, clientUpdatedAt = at + kotlin.time.Duration.parse("1m"), movieName = "Vu", movieCategory = "c"))
        vm.reloadProgress(); advanceUntilIdle()
        assertEquals(listOf("play"), vm.ui.value.home.continueRow.map { it.movieId })
        assertEquals(listOf("listed"), vm.ui.value.home.myList.map { it.movieId })
        scope.cancel()
    }
    // Une ancienne réponse du serveur, arrivée après un Retour plus récent, n'écrase pas l'état.
    @Test fun stale_server_response_does_not_overwrite_newer_reload() = runTest {
        val saved = MemoryMovieState()
        val first = CompletableDeferred<List<ProgressRow>>()
        var calls = 0
        val fresh = listOf(ProgressRow("new", "movie", positionS = 60, durationS = 1000, clientUpdatedAt = kotlin.time.Instant.parse("2026-11-15T10:00:00Z"), movieName = "Nouveau", movieCategory = "c"))
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val vm = MovieHomeViewModel(FakeMovieCatalog(), { if (calls++ == 0) first.await() else fresh }, { emptyList() }, { emptyList() }, {}, saved, "p", { null }, MutableStateFlow(moviePrefs), scope, StandardTestDispatcher(testScheduler))
        vm.start(); advanceUntilIdle()
        vm.reloadProgress(); advanceUntilIdle()
        assertEquals(listOf("new"), vm.ui.value.home.continueRow.map { it.movieId })
        first.complete(emptyList()); advanceUntilIdle()
        assertEquals(listOf("new"), vm.ui.value.home.continueRow.map { it.movieId })
        assertEquals(listOf("new"), saved.rows.map { it.itemId })
        scope.cancel()
    }
}
