package home.iptv.tv.movies

import home.iptv.shared.progress.*
import home.iptv.shared.sync.*
import home.iptv.shared.xtream.*
import home.iptv.tv.series.resumePosition
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant

data class PlayMovieRequest(val movieId: String, val categoryId: String?, val restart: Boolean = false, val startS: Int = 0)
typealias MovieDetailFactory = (Profile, StateFlow<ProfilePrefs?>, String, String?, CoroutineScope) -> MovieDetailViewModel

data class MovieDetailState(val loading: Boolean = true, val movie: MovieSummary? = null, val positionS: Int = 0, val watched: Boolean = false, val inMyList: Boolean = false, val masked: Boolean = false, val busy: Boolean = false, val message: String? = null)
class MovieDetailViewModel(
    private val movieId: String,
    private val categoryId: String?,
    private val catalog: MovieCatalog,
    private val client: MovieClient,
    private val profile: String,
    private val saved: MovieHomeState,
    private val localProgress: () -> List<ProgressRow>,
    private val dropQueued: () -> Unit,
    private val prefs: StateFlow<ProfilePrefs?>,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val withPausedFlush: suspend (suspend () -> Unit) -> Unit = { action -> action() },
    private val coverFor: (String?) -> String? = { it },
) {
    private val _state = MutableStateFlow(MovieDetailState())
    val state: StateFlow<MovieDetailState> = _state
    private val actions = Mutex()
    private val lock = Any()
    private var info: MovieSummary? = null
    private var row: ProgressRow? = null
    private var list = emptyList<MovieListItem>()
    private var category: String? = categoryId?.takeIf { it.isNotBlank() }
    private var loading = true
    private var message: String? = null
    private var busy = false
    private var started = false
    fun start() {
        if (started) return
        started = true
        reload()
        scope.launch(io) { prefs.collect { rebuild() } }
    }
    fun reload() { scope.launch(io) {
        prefs.filterNotNull().first()
        synchronized(lock) {
            row = best(saved.progress(profile) + localProgress())
            list = saved.list(profile)
            category = category ?: catalog.cachedCategory(movieId)?.takeIf { it.isNotBlank() } ?: row?.movieCategory?.takeIf { it.isNotBlank() } ?: list.firstOrNull { it.itemId == movieId }?.movieCategory?.takeIf { it.isNotBlank() }
        }
        rebuild()
        try {
            val remote = client.progress(profile, movieId)
            synchronized(lock) { row = best(listOfNotNull(row, remote) + localProgress()); category = category ?: row?.movieCategory?.takeIf { it.isNotBlank() } }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        if (allowed()) {
            try {
                val loaded = catalog.info(movieId, category!!)?.summary
                synchronized(lock) {
                    if (allowed() && loaded != null && loaded.categoryId !in prefs.value!!.movieHidden()) info = loaded
                    else if (loaded == null) message = "Fiche indisponible. Réessaie."
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { message = "Fiche indisponible. Réessaie après avoir vérifié la connexion." }
        } else if (category == null) message = "Film indisponible : parcours d'abord sa catégorie."
        try { val remote = client.list(profile); synchronized(lock) { list = remote }; saved.saveList(profile, remote) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        loading = false; rebuild()
    } }
    fun playbackClosed() { synchronized(lock) { row = best(listOfNotNull(row) + localProgress()) }; rebuild(); reload() }
    fun play(restart: Boolean): PlayMovieRequest? = synchronized(lock) {
        if (!allowed() || info == null || busy) null else PlayMovieRequest(movieId, category, restart, if (restart) 0 else resumePosition(row?.positionS, row?.durationS))
    }
    fun toggleMyList() = action { m ->
        val on = !_state.value.inMyList
        client.setList(profile, m.copy(cover = coverFor(m.cover)), on)
        synchronized(lock) { list = list.filterNot { it.itemId == movieId } + if (on) listOf(MovieListItem(movieId, m.name, coverFor(m.cover), category)) else emptyList() }
        saved.saveList(profile, list)
    }
    fun markWatched(watched: Boolean) = action { m ->
        // Sérialiser l'action avec les autres actions de fiche ; retirer les anciennes positions avant tout futur envoi.
        withPausedFlush {
            client.setWatched(profile, m.copy(cover = coverFor(m.cover)), watched)
            dropQueued()
        }
        synchronized(lock) {
            val old = row
            row = ProgressRow(itemId = movieId, itemType = "movie", positionS = if (watched) m.durationS ?: old?.durationS ?: 0 else 0, durationS = m.durationS ?: old?.durationS ?: 0, watched = watched,
                // Un marquage manuel n'est pas une lecture : conserver sa récence, même pour une première marque.
                clientUpdatedAt = old?.clientUpdatedAt ?: Instant.DISTANT_PAST, movieName = m.name, movieCover = coverFor(m.cover), movieCategory = category)
            saved.saveProgress(profile, saved.progress(profile).filterNot { it.itemId == movieId && it.itemType == "movie" } + row!!)
        }
    }
    private fun action(block: suspend (MovieSummary) -> Unit) {
        if (!allowed() || busy) return
        val m = info ?: return
        busy = true; rebuild()
        scope.launch(io) { actions.withLock {
            try { block(m); message = null } catch (e: CancellationException) { throw e } catch (_: Exception) { message = "Modification impossible. Vérifie la connexion puis réessaie." }
            finally { busy = false; rebuild() }
        } }
    }
    private fun best(rows: List<ProgressRow>) = rows.filter { it.itemType == "movie" && it.itemId == movieId }.maxByOrNull { it.clientUpdatedAt }
    private fun allowed(): Boolean = prefs.value?.let { p -> category != null && category !in p.movieHidden() && info?.categoryId !in p.movieHidden() } == true
    private fun rebuild() = synchronized(lock) {
        if (prefs.value == null) { _state.value = MovieDetailState(message = "Les réglages du profil ne sont pas encore chargés."); return@synchronized }
        val masked = category in prefs.value!!.movieHidden() || info?.categoryId in prefs.value!!.movieHidden()
        _state.value = if (masked) MovieDetailState(loading = false, masked = true, message = "Ce film est masqué.") else MovieDetailState(loading, info, row?.positionS ?: 0, row?.watched == true, list.any { it.itemId == movieId }, false, busy, message)
    }
}
