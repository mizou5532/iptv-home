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
    @Test fun restarting_watched_movie_keeps_watched_while_progress_is_pending() = runTest {
        val at = Instant.parse("2026-11-15T10:00:00Z")
        val saved = MemoryMovieState().also { it.rows = listOf(ProgressRow("m", "movie", positionS = 1000, durationS = 1000, watched = true, clientUpdatedAt = at, movieCategory = "c")) }
        val pending = listOf(ProgressRow("m", "movie", positionS = 60, durationS = 1000, watched = false, clientUpdatedAt = at + kotlin.time.Duration.parse("1m"), movieCategory = "c"))
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val vm = MovieDetailViewModel("m", "c", FakeMovieCatalog(), FakeMovieClient(), "p", saved, { pending }, {}, MutableStateFlow(moviePrefs), scope, StandardTestDispatcher(testScheduler))
        vm.start(); advanceUntilIdle()
        assertTrue(vm.state.value.watched)
        scope.cancel()
    }
    @Test fun hidden_cached_category_wins_over_allowed_reference_before_info_request() = runTest {
        val cat = FakeMovieCatalog()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val prefs = MutableStateFlow(moviePrefs.copy(hidden = listOf(home.iptv.shared.sync.HiddenItem("vod_category", "c"))))
        val vm = MovieDetailViewModel("m", "allowed", cat, FakeMovieClient(), "p", MemoryMovieState(), { emptyList() }, {}, prefs, scope, StandardTestDispatcher(testScheduler))
        vm.start(); advanceUntilIdle()
        assertEquals(0, cat.calls)
        assertTrue(vm.state.value.masked)
        assertNull(vm.play(false))
        scope.cancel()
    }
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
    // I1 : la fiche connue s'affiche même si le serveur de synchro ne répond pas.
    /** 0.41.1 (« Cars 3 » : rien pendant ~10 s) : le film déjà connu s'affiche avant la réponse du fournisseur. */
    @Test fun film_connu_affiche_avant_la_fiche_du_fournisseur() = runTest {
        val cat = object : FakeMovieCatalog() {
            override fun cachedMovie(movieId: String) = movie(movieId, "c").copy(name = "Cars 3")
        }.apply { gate = CompletableDeferred() }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val vm = MovieDetailViewModel("m", "c", cat, FakeMovieClient(), "p", MemoryMovieState(), { emptyList() }, {}, MutableStateFlow(moviePrefs), scope, StandardTestDispatcher(testScheduler))
        vm.start(); runCurrent()
        assertEquals("Cars 3", vm.state.value.movie?.name, "affiché sans attendre le fournisseur")
        assertFalse(vm.state.value.loading)
        cat.gate!!.complete(Unit); advanceUntilIdle()
        assertEquals("Film m", vm.state.value.movie?.name, "complété par la fiche du fournisseur")
        scope.cancel()
    }

    @Test fun film_connu_masque_jamais_affiche() = runTest {
        val cat = object : FakeMovieCatalog() {
            override fun cachedMovie(movieId: String) = movie(movieId, "cachee")
        }.apply { gate = CompletableDeferred() }
        val prefs = MutableStateFlow(moviePrefs.copy(hidden = listOf(home.iptv.shared.sync.HiddenItem("vod_category", "cachee"))))
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val vm = MovieDetailViewModel("m", "c", cat, FakeMovieClient(), "p", MemoryMovieState(), { emptyList() }, {}, prefs, scope, StandardTestDispatcher(testScheduler))
        vm.start(); runCurrent()
        assertNull(vm.state.value.movie)
        scope.cancel()
    }

    @Test fun known_movie_detail_displays_while_sync_server_is_suspended() = runTest {
        val never = CompletableDeferred<Nothing>()
        val client = object : FakeMovieClient() {
            override suspend fun progress(p: String, id: String): ProgressRow? = never.await()
            override suspend fun list(p: String): List<MovieListItem> = never.await()
        }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val vm = MovieDetailViewModel("m", "c", FakeMovieCatalog(), client, "p", MemoryMovieState(), { emptyList() }, {}, MutableStateFlow(moviePrefs), scope, StandardTestDispatcher(testScheduler))
        vm.start(); advanceUntilIdle()
        assertFalse(vm.state.value.loading)
        assertEquals("m", vm.state.value.movie?.id)
        assertNotNull(vm.play(false))
        scope.cancel()
    }
    // Une ancienne référence sans catégorie attend toujours la catégorie apprise du serveur avant la fiche.
    @Test fun unknown_category_learned_from_server_progress_then_fetches_info() = runTest {
        val cat = object : FakeMovieCatalog() { override fun cachedCategory(movieId: String): String? = null }
        val client = object : FakeMovieClient() {
            override suspend fun progress(p: String, id: String) = ProgressRow("m", "movie", positionS = 10, durationS = 1000, clientUpdatedAt = Instant.parse("2026-11-15T10:00:00Z"), movieCategory = "c")
        }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val vm = MovieDetailViewModel("m", null, cat, client, "p", MemoryMovieState(), { emptyList() }, {}, MutableStateFlow(moviePrefs), scope, StandardTestDispatcher(testScheduler))
        vm.start(); advanceUntilIdle()
        assertEquals("m", vm.state.value.movie?.id)
        assertEquals("c", vm.play(false)?.categoryId)
        scope.cancel()
    }
    // I4 : parcours réel — « vu » appris par la fiche, Recommencer, puis serveur muet au retour de lecture.
    @Test fun watched_learned_from_server_survives_restart_when_server_goes_silent() = runTest {
        val at = Instant.parse("2026-11-15T10:00:00Z")
        var silent = false
        val never = CompletableDeferred<Nothing>()
        val client = object : FakeMovieClient() {
            override suspend fun progress(p: String, id: String): ProgressRow? = if (silent) never.await() else ProgressRow("m", "movie", positionS = 1000, durationS = 1000, watched = true, clientUpdatedAt = at, movieCategory = "c")
        }
        val saved = MemoryMovieState()
        var pending = emptyList<ProgressRow>()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val vm = MovieDetailViewModel("m", "c", FakeMovieCatalog(), client, "p", saved, { pending }, {}, MutableStateFlow(moviePrefs), scope, StandardTestDispatcher(testScheduler))
        vm.start(); advanceUntilIdle()
        assertTrue(vm.state.value.watched)
        assertNotNull(vm.play(true))
        silent = true
        pending = listOf(ProgressRow("m", "movie", positionS = 60, durationS = 1000, watched = false, clientUpdatedAt = at + kotlin.time.Duration.parse("1m"), movieCategory = "c"))
        vm.playbackClosed(); advanceUntilIdle()
        assertTrue(vm.state.value.watched)
        assertEquals(60, vm.state.value.positionS)
        // Le statut appris est aussi dans la copie durable : une fiche rouverte hors ligne le retrouve.
        assertTrue(saved.rows.single { it.itemId == "m" }.watched)
        val reopened = MovieDetailViewModel("m", "c", FakeMovieCatalog(), client, "p", saved, { pending }, {}, MutableStateFlow(moviePrefs), scope, StandardTestDispatcher(testScheduler))
        reopened.start(); advanceUntilIdle()
        assertTrue(reopened.state.value.watched)
        scope.cancel()
    }
    // Marquer non vu reste gagnant, même si une ancienne réponse du serveur arrive après.
    @Test fun explicit_unwatched_wins_over_stale_server_progress() = runTest {
        val at = Instant.parse("2026-11-15T10:00:00Z")
        val late = CompletableDeferred<ProgressRow?>()
        var calls = 0
        val client = object : FakeMovieClient() {
            override suspend fun progress(p: String, id: String): ProgressRow? = if (calls++ == 0) ProgressRow("m", "movie", positionS = 1000, durationS = 1000, watched = true, clientUpdatedAt = at, movieCategory = "c") else late.await()
        }
        val saved = MemoryMovieState()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val vm = MovieDetailViewModel("m", "c", FakeMovieCatalog(), client, "p", saved, { emptyList() }, {}, MutableStateFlow(moviePrefs), scope, StandardTestDispatcher(testScheduler))
        vm.start(); advanceUntilIdle()
        assertTrue(vm.state.value.watched)
        vm.reload(); advanceUntilIdle()
        vm.markWatched(false); advanceUntilIdle()
        assertFalse(vm.state.value.watched)
        late.complete(ProgressRow("m", "movie", positionS = 1000, durationS = 1000, watched = true, clientUpdatedAt = at, movieCategory = "c")); advanceUntilIdle()
        assertFalse(vm.state.value.watched)
        assertFalse(saved.rows.single { it.itemId == "m" }.watched)
        scope.cancel()
    }
    // N1 : une réponse Ma liste partie avant « Ajouter à Ma liste » ne doit pas annuler l'ajout.
    @Test fun stale_list_response_does_not_undo_my_list_toggle() = runTest {
        val late = CompletableDeferred<List<MovieListItem>>()
        val client = object : FakeMovieClient() { override suspend fun list(p: String) = late.await() }
        val saved = MemoryMovieState()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val vm = MovieDetailViewModel("m", "c", FakeMovieCatalog(), client, "p", saved, { emptyList() }, {}, MutableStateFlow(moviePrefs), scope, StandardTestDispatcher(testScheduler))
        vm.start(); advanceUntilIdle()
        vm.toggleMyList(); advanceUntilIdle()
        assertTrue(vm.state.value.inMyList)
        late.complete(emptyList()); advanceUntilIdle()
        assertTrue(vm.state.value.inMyList)
        assertEquals(listOf("m"), saved.items.map { it.itemId })
        scope.cancel()
    }
}
