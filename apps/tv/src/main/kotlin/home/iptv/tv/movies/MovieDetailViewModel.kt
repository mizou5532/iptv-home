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

/** [exact] : reprendre à [startS] tel quel, même près de la fin (« Réessayer » après une coupure). */
data class PlayMovieRequest(val movieId: String, val categoryId: String?, val restart: Boolean = false, val startS: Int = 0, val exact: Boolean = false)
typealias MovieDetailFactory = (Profile, StateFlow<ProfilePrefs?>, String, String?, CoroutineScope) -> MovieDetailViewModel

data class MovieDetailState(val loading: Boolean = true, val movie: MovieSummary? = null, val positionS: Int = 0, val watched: Boolean = false, val inMyList: Boolean = false, val masked: Boolean = false, val busy: Boolean = false, val message: String? = null,
    /** Qualité réelle du fichier (« 4K · HDR10 · HEVC · 5.1 Dolby Digital Plus ») si le fournisseur l'a analysée. */
    val quality: String? = null)
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
    /** Identifiants des autres versions du même film (langues, 4K) : leur position sert de reprise. */
    private val siblings: () -> Set<String> = { emptySet() },
) {
    /** Position reprise d'une autre version, tant que ce film n'a pas la sienne. */
    private var carried: ProgressRow? = null
    private val _state = MutableStateFlow(MovieDetailState())
    val state: StateFlow<MovieDetailState> = _state
    private val actions = Mutex()
    private val lock = Any()
    private var info: MovieSummary? = null
    private var quality: String? = null
    private var row: ProgressRow? = null
    private var list = emptyList<MovieListItem>()
    private var category: String? = categoryId?.takeIf { it.isNotBlank() }
    private var cachedCategory: String? = null
    private var loading = true
    private var message: String? = null
    private var busy = false
    private var started = false
    private var manualGen = 0
    private var listGen = 0
    fun start() {
        if (started) return
        started = true
        reload()
        scope.launch(io) { prefs.collect { rebuild() } }
    }
    fun reload() { scope.launch(io) {
        prefs.filterNotNull().first()
        synchronized(lock) {
            // Le statut déjà connu de cette fiche (appris du serveur) reste une référence : un rechargement ne l'oublie pas.
            val all = mergeMovieProgress(saved.progress(profile) + listOfNotNull(row), localProgress())
            row = best(all)
            carried = if (row == null) home.iptv.shared.versions.carriedMovieRow(all, movieId, runCatching { siblings() }.getOrDefault(emptySet())) else null
            list = saved.list(profile)
            cachedCategory = catalog.cachedCategory(movieId)?.takeIf { it.isNotBlank() }
            category = category ?: cachedCategory ?: row?.movieCategory?.takeIf { it.isNotBlank() } ?: list.firstOrNull { it.itemId == movieId }?.movieCategory?.takeIf { it.isNotBlank() }
        }
        rebuild()
        // La synchro part à part : la fiche locale ou du fournisseur ne l'attend pas.
        val gen = synchronized(lock) { manualGen }
        val remoteProgress = launch {
            try {
                val remote = client.progress(profile, movieId) ?: return@launch
                synchronized(lock) {
                    // Une action manuelle plus récente que la demande fait foi.
                    if (gen != manualGen) return@launch
                    row = best(mergeMovieProgress(listOfNotNull(row, remote), localProgress()))
                    category = category ?: row?.movieCategory?.takeIf { it.isNotBlank() }
                    // Copie durable : le statut appris survit à Recommencer puis à une panne du serveur.
                    saved.saveProgress(profile, saved.progress(profile).filterNot { it.itemId == movieId && it.itemType == "movie" } + remote)
                }
                rebuild()
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
        val lg = synchronized(lock) { listGen }
        launch {
            try {
                val remote = client.list(profile)
                // Un ajout/retrait fait depuis la demande fait foi : l'ancienne réponse est ignorée.
                synchronized(lock) { if (lg != listGen) return@launch; list = remote; saved.saveList(profile, remote) }
                rebuild()
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
        // Seule une ancienne référence sans catégorie doit attendre le serveur pour l'apprendre.
        if (synchronized(lock) { category } == null) withTimeoutOrNull(8_000) { remoteProgress.join() }
        if (allowed()) {
            try {
                val fiche = catalog.info(movieId, category!!)
                val loaded = fiche?.summary
                synchronized(lock) {
                    if (allowed() && loaded != null && loaded.categoryId !in prefs.value!!.movieHidden()) { info = loaded; quality = fiche.tech?.label(); message = null; loading = false }
                    else if (loaded == null) message = "Fiche indisponible. Réessaie."
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { message = "Fiche indisponible. Réessaie après avoir vérifié la connexion." }
        } else if (category == null) message = "Film indisponible : parcours d'abord sa catégorie."
        loading = false; rebuild()
    } }
    fun playbackClosed() { synchronized(lock) { row = best(mergeMovieProgress(listOfNotNull(row), localProgress())) }; rebuild(); reload() }
    fun play(restart: Boolean): PlayMovieRequest? = synchronized(lock) {
        if (!allowed() || info == null || busy) null else PlayMovieRequest(movieId, category, restart, if (restart) 0 else (row ?: carried).let { r -> resumePosition(r?.positionS, r?.durationS) })
    }
    fun toggleMyList() = action { m ->
        val on = !_state.value.inMyList
        client.setList(profile, m.copy(cover = coverFor(m.cover)), on)
        synchronized(lock) {
            listGen++
            list = list.filterNot { it.itemId == movieId } + if (on) listOf(MovieListItem(movieId, m.name, coverFor(m.cover), category)) else emptyList()
            saved.saveList(profile, list)
        }
    }
    fun markWatched(watched: Boolean) = action { m ->
        // Sérialiser l'action avec les autres actions de fiche ; retirer les anciennes positions avant tout futur envoi.
        withPausedFlush {
            client.setWatched(profile, m.copy(cover = coverFor(m.cover)), watched)
            dropQueued()
        }
        synchronized(lock) {
            manualGen++
            val old = row
            carried = null
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
    private fun categories() = listOfNotNull(categoryId, category, cachedCategory, info?.categoryId, row?.movieCategory, list.firstOrNull { it.itemId == movieId }?.movieCategory).filter { it.isNotBlank() }
    private fun allowed(): Boolean = prefs.value?.let { p -> category != null && categories().none { it in p.movieHidden() } } == true
    private fun rebuild() = synchronized(lock) {
        if (prefs.value == null) { _state.value = MovieDetailState(message = "Les réglages du profil ne sont pas encore chargés."); return@synchronized }
        val masked = categories().any { it in prefs.value!!.movieHidden() }
        _state.value = if (masked) MovieDetailState(loading = false, masked = true, message = "Ce film est masqué.") else MovieDetailState(loading, info, (row ?: carried)?.positionS ?: 0, (row ?: carried)?.watched == true, list.any { it.itemId == movieId }, false, busy, message, quality)
    }
}
