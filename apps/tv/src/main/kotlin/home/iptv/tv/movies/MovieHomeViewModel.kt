package home.iptv.tv.movies

import home.iptv.shared.progress.*
import home.iptv.shared.sync.*
import home.iptv.shared.xtream.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Une seule demande fournisseur à la fois ; rangées chargées à l'approche et relues du cache après éviction. */
data class MovieHomeUi(val home: MovieHome = MovieHome.EMPTY, val rowMovies: Map<String, List<MovieCard>> = emptyMap(), val failedRows: Set<String> = emptySet(), val loading: Boolean = true, val message: String? = null)
class MovieHomeViewModel(
    private val catalog: MovieCatalog,
    private val serverProgress: suspend () -> List<ProgressRow>,
    private val serverList: suspend () -> List<MovieListItem>,
    private val localProgress: () -> List<ProgressRow>,
    private val publishCategories: suspend (List<PublishedCategory>) -> Unit,
    private val state: MovieHomeState,
    private val profileId: String,
    private val householdId: () -> String?,
    private val prefs: StateFlow<ProfilePrefs?>,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _ui = MutableStateFlow(MovieHomeUi())
    val ui: StateFlow<MovieHomeUi> = _ui
    private val mutex = Mutex()
    private val lock = Any()
    private var categories = emptyList<MovieCategory>()
    private var progress = emptyList<ProgressRow>()
    private var list = emptyList<MovieListItem>()
    private var recent = emptyList<MovieSummary>()
    private val rows = LinkedHashMap<String, List<MovieCard>>(32, .75f, true)
    private val failed = HashSet<String>()
    private val visible = LinkedHashSet<String>()
    private var pending = true
    private var error: String? = null
    private var started = false
    private var pump: Job? = null

    fun start() {
        if (started) return
        started = true
        scope.launch(io) {
            synchronized(lock) { progress = state.progress(profileId); list = state.list(profileId) }
            prefs.filterNotNull().first()
            reloadNow()
            try {
                val cats = mutex.withLock { catalog.categories() }
                synchronized(lock) { categories = cats }
                val household = householdId()
                if (household != null && now() - state.published(household) >= 86_400_000) {
                    try { publishCategories(cats.mapIndexed { i, c -> PublishedCategory(c.id, c.name, i) }); state.savePublished(household, now()) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { error = "Catalogue indisponible. Réessaie après avoir vérifié la connexion." }
            pending = false; rebuild(); schedule()
        }
        scope.launch(io) { prefs.collect { rebuild(); schedule() } }
    }
    fun reloadProgress() { scope.launch(io) { reloadNow(); rebuild() } }
    fun retry() { scope.launch(io) {
        error = null
        try { val cats = mutex.withLock { catalog.categories() }; synchronized(lock) { categories = cats } } catch (e: CancellationException) { throw e } catch (_: Exception) { error = "Catalogue indisponible. Réessaie." }
        reloadNow(); rebuild(); schedule()
    } }
    fun loadCategory(id: String) { synchronized(lock) { visible.add(id); failed.remove(id); rows[id] }; schedule() }
    fun cancelCategory(id: String) { synchronized(lock) { visible.remove(id) } }
    private fun schedule() {
        synchronized(lock) {
            if (pump?.isActive == true) return
            pump = scope.launch(io) {
                delay(300)
                while (true) {
                    val id = synchronized(lock) { wanted().firstOrNull { it !in rows && it !in failed } } ?: break
                    try {
                        val loaded = mutex.withLock {
                            val p = prefs.value
                            if (p == null || id in p.movieHidden()) null else catalog.moviesOf(id, p.movieHidden())
                        }
                        synchronized(lock) {
                            // Revalider APRÈS la réponse ; une ancienne réponse masquée ne reste pas en mémoire.
                            if (loaded != null && prefs.value?.movieHidden()?.contains(id) == false) {
                                rows[id] = loaded.filter { it.categoryId !in prefs.value!!.movieHidden() }.distinctBy { it.id }.map { it.card() }
                                trim()
                            } else failed.add(id)
                        }
                    } catch (e: CancellationException) { throw e } catch (_: Exception) { synchronized(lock) { failed.add(id) } }
                    rebuild()
                }
                synchronized(lock) { pump = null }
            }
        }
    }
    private fun wanted(): List<String> {
        if (prefs.value == null) return emptyList()
        val order = _ui.value.home.categoryRows.map { it.id }
        val shown = order.filter { it in visible }
        val last = shown.map { order.indexOf(it) }.maxOrNull() ?: return emptyList()
        return shown + order.drop(last + 1).take(2)
    }
    private fun trim() {
        val keep = wanted().toSet()
        var count = rows.values.sumOf { it.size }
        val it = rows.entries.iterator()
        while ((rows.size > 24 || count > 40000) && it.hasNext()) {
            val next = it.next()
            if (next.key !in keep) { count -= next.value.size; it.remove() }
        }
    }
    private suspend fun reloadNow() {
        if (prefs.value == null) return
        try { val remote = serverProgress(); synchronized(lock) { progress = remote }; state.saveProgress(profileId, remote) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        try { val remote = serverList(); synchronized(lock) { list = remote }; state.saveList(profileId, remote) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        rebuild()
    }
    private fun rebuild() {
        val p = prefs.value
        val cached = if (p == null) emptyList() else catalog.recent(p.movieHidden(), MOVIE_ROW_LIMIT)
        val queued = localProgress()
        synchronized(lock) {
            recent = cached
            if (p == null) { _ui.value = MovieHomeUi(loading = false, message = "Les réglages du profil ne sont pas encore chargés."); return }
            val hidden = p.movieHidden()
            rows.keys.removeAll(hidden)
            // Le cache peut rétablir la catégorie d'une ancienne référence, sans fiche réseau non filtrable.
            val refs = (progress + queued).map { r -> if (r.movieCategory.isNullOrBlank()) r.copy(movieCategory = catalog.cachedCategory(r.itemId)) else r }
            val refsList = list.map { r -> if (r.movieCategory.isNullOrBlank()) r.copy(movieCategory = catalog.cachedCategory(r.itemId)) else r }
            val home = buildMovieHome(refs, refsList, categories, p, recent, now()/1000)
            _ui.value = MovieHomeUi(home, rows.filterKeys { it !in hidden }, failed.toSet(), pending, error ?: if (!pending && home == MovieHome.EMPTY) "Aucun film disponible pour ce profil." else null)
        }
    }
}
