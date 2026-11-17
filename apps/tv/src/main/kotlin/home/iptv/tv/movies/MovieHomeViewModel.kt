package home.iptv.tv.movies

import home.iptv.shared.versions.collapseVersions
import home.iptv.tv.feature.pinnedMovieFeature
import home.iptv.tv.feature.FEATURE_ROTATE_MS
import home.iptv.tv.feature.Feature
import home.iptv.tv.feature.Pick
import home.iptv.tv.feature.pickFrom
import home.iptv.tv.feature.ratingOf
import home.iptv.tv.feature.buildMovieFeatures
import home.iptv.tv.feature.MAX_FEATURES
import home.iptv.shared.progress.*
import home.iptv.shared.sync.*
import home.iptv.shared.xtream.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Une seule demande fournisseur à la fois ; rangées chargées à l'approche et relues du cache après éviction. */
data class MovieHomeUi(
    val home: MovieHome = MovieHome.EMPTY, val rowMovies: Map<String, List<MovieCard>> = emptyMap(), val failedRows: Set<String> = emptySet(), val loading: Boolean = true, val message: String? = null,
    /** Bannière d'accueil (voir [buildMovieFeatures]) et images de fond connues par film. */
    val features: List<Feature> = emptyList(), val backdrops: Map<String, String> = emptyMap(),
    /** Rangées de l'accueil dans l'ordre (réglage du profil, voir [HomeRows.MOVIES]) et vitesse de la bannière. */
    val rows: List<String> = HomeRows.MOVIES, val rotateMs: Long = FEATURE_ROTATE_MS,
)
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
    /** Réglages de l'accueil Films du profil connus à l'ouverture (bannière, rangées). */
    initialView: MediaKindView = MediaKindView(),
    /** Relit les réglages du profil sur le serveur (changés depuis l'iPhone) ; null = pas de réponse. */
    private val loadView: suspend () -> MediaKindView? = { null },
    /** Retire un film de « Reprendre » sur le serveur (`dismiss_continue`). */
    private val dismissOnServer: suspend (String) -> Unit = {},
    /** Versions des titres (langues, 4K), construites au besoin hors du fil principal. */
    private val versionIndex: () -> home.iptv.shared.versions.VersionIndex = { home.iptv.shared.versions.VersionIndex.EMPTY },
    private val languages: home.iptv.shared.versions.LanguagePrefs = home.iptv.shared.versions.LanguagePrefs(),
) {
    @Volatile private var versions = home.iptv.shared.versions.VersionIndex.EMPTY
    /** Lit (ou construit) l'index des versions puis redessine : une carte par titre dans les rangées. */
    private fun loadVersions() { scope.launch(io) {
        val v = try { versionIndex() } catch (e: CancellationException) { throw e } catch (_: Exception) { return@launch }
        if (v !== versions) { versions = v; rebuild() }
    } }
    @Volatile private var view: MediaKindView = initialView
    /** Films retirés de « Reprendre » sur cette télé : les lignes jusqu'à cette heure sont écartées. */
    private val dismissed = HashMap<String, kotlin.time.Instant>()
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
    private var progressGen = 0
    private var listGen = 0

    fun start() {
        if (started) return
        started = true
        scope.launch(io) {
            synchronized(lock) { progress = state.progress(profileId); list = state.list(profileId) }
            prefs.filterNotNull().first()
            // Publier la copie locale avant toute suspension réseau, quel que soit l’ordre des tâches IO.
            rebuild()
            refreshView()
            loadVersions()
            // La synchro part à part : un serveur lent ne retarde jamais le catalogue local.
            sync()
            try {
                val cats = mutex.withLock { catalog.categories() }
                synchronized(lock) { categories = cats }
                val household = householdId()
                if (household != null && now() - state.published(household) >= 86_400_000) {
                    try { publishCategories(cats.mapIndexed { i, c -> PublishedCategory(c.id, c.name, i) }); state.savePublished(household, now()) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { error = "Catalogue indisponible. Réessaie après avoir vérifié la connexion." }
            pending = false; rebuild(); schedule()
            loadPicks()
        }
        scope.launch(io) { prefs.collect { rebuild(); schedule() } }
    }

    /** Réglages changés depuis l'iPhone : relus à chaque ouverture de l'onglet, après le premier affichage. */
    private fun refreshView() { scope.launch(io) {
        val fresh = try { loadView() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        if (fresh != null && fresh != view) { view = fresh; rebuild(); loadPinned() }
    } }

    /** Retire un film de « Reprendre » (appui long) : tout de suite ici, puis sur le serveur ; réapparaît en cas d'échec. */
    fun dismiss(movieId: String) { scope.launch(io) {
        synchronized(lock) {
            // La dernière lecture connue compte aussi : une horloge de télé en retard ne fait pas revenir le film.
            val last = (progress + localProgress()).filter { it.itemType == "movie" && it.itemId == movieId }.maxOfOrNull { it.clientUpdatedAt }
            val n = kotlin.time.Instant.fromEpochMilliseconds(now())
            dismissed[movieId] = if (last != null && last > n) last else n
        }
        rebuild()
        try {
            dismissOnServer(movieId)
            val kept = synchronized(lock) { progress.filterNot { it.itemType == "movie" && it.itemId == movieId }.also { progress = it } }
            state.saveProgress(profileId, kept)
        } catch (e: CancellationException) { throw e } catch (_: Exception) {
            synchronized(lock) { dismissed.remove(movieId) }
            rebuild()
        }
    } }

    /** Fiches des films épinglés (titre, résumé, image) : une demande chacun, une seule fois. */
    private suspend fun loadPinned() {
        val p = prefs.value ?: return
        val hidden = p.movieHidden()
        for (pin in view.bannerPinned) {
            if (synchronized(lock) { pin.id in infos }) continue
            val category = pin.category?.takeIf { it.isNotBlank() } ?: catalog.cachedCategory(pin.id) ?: continue
            if (category in hidden) continue
            try {
                val info = mutex.withLock { catalog.info(pin.id, category) } ?: continue
                synchronized(lock) { infos[pin.id] = info.summary }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
        rebuild()
    }
    /** Au Retour : relire tout de suite la copie durable (écrite par la fiche) et la file, puis réconcilier avec le serveur. */
    fun reloadProgress() { scope.launch(io) {
        val savedProgress = state.progress(profileId); val savedList = state.list(profileId)
        // Une réponse encore en vol date d'avant ces changements : elle ne doit plus rien écraser.
        synchronized(lock) { progress = savedProgress; list = savedList; progressGen++; listGen++ }
        rebuild(); sync(); loadVersions()
    } }
    fun retry() { scope.launch(io) {
        if (prefs.value == null) return@launch
        error = null
        try { val cats = mutex.withLock { catalog.categories() }; synchronized(lock) { categories = cats } } catch (e: CancellationException) { throw e } catch (_: Exception) { error = "Catalogue indisponible. Réessaie." }
        sync(); rebuild(); schedule()
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
                    refreshPicks()
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
    /** Progression et Ma liste demandées séparément, sans bloquer l'appelant ; seule la dernière demande est retenue. */
    private fun sync() {
        if (prefs.value == null) return
        val pg = synchronized(lock) { ++progressGen }
        scope.launch(io) {
            try {
                val remote = serverProgress()
                // Le serveur ne renvoie que les films non vus : garder « vu » pour un film relancé dont la position attend encore dans la file.
                val waiting = localProgress().mapTo(HashSet()) { it.itemId }
                val kept = synchronized(lock) {
                    if (pg != progressGen) false else {
                        val ids = remote.mapTo(HashSet()) { it.itemId }
                        progress = remote + progress.filter { it.itemType == "movie" && it.watched && it.itemId in waiting && it.itemId !in ids }
                        state.saveProgress(profileId, progress); true
                    }
                }
                if (kept) rebuild()
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
        val lg = synchronized(lock) { ++listGen }
        scope.launch(io) {
            try {
                val remote = serverList()
                val kept = synchronized(lock) { if (lg == listGen) { list = remote; state.saveList(profileId, remote); true } else false }
                if (kept) rebuild()
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }
    /** Suggestions de la bannière (catégories des films en cours) et fiches lues pour leurs images de fond. */
    private var picks = emptyList<Pick<MovieSummary>>()
    private val infos = HashMap<String, MovieSummary>()

    private suspend fun loadPicks() {
        loadPinned()
        refreshPicks()
        rebuild()
        // Les listes de films n'ont presque jamais d'image de fond : la fiche de chaque suggestion la donne (5 au plus).
        for (f in _ui.value.features.filter { it.backdrop == null }.take(MAX_FEATURES)) {
            try {
                val info = mutex.withLock { catalog.info(f.itemId, f.categoryId.orEmpty()) } ?: continue
                synchronized(lock) { infos[f.itemId] = info.summary }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
        rebuild()
    }

    /** Suggestions tirées du cache local seul : jamais de demande au fournisseur en plus des rangées. */
    private fun refreshPicks() {
        val p = prefs.value ?: return
        if (synchronized(lock) { picks.size >= 3 }) return
        val home = _ui.value.home
        val hidden = p.movieHidden()
        val watched = home.continueRow.mapNotNull { c -> c.categoryId.takeIf { it.isNotBlank() && it !in hidden }?.let { it to c.title } }
        val exclude = (home.continueRow + home.myList).mapTo(HashSet()) { it.movieId }
        val chosen = runCatching {
            pickFrom(watched, { catalog.cachedMoviesOf(it) }, { it.id }, { ratingOf(it.rating) }, exclude, home.categoryRows.firstOrNull()?.id)
        }.getOrDefault(emptyList())
        synchronized(lock) { picks = chosen }
    }

    private fun rebuild() {
        val p = prefs.value
        val cached = if (p == null) emptyList() else catalog.recent(p.movieHidden(), MOVIE_ROW_LIMIT)
        val featured = p?.let { catalog.featured(it.movieHidden()) }
        val queued = localProgress()
        synchronized(lock) {
            recent = cached
            if (p == null) { _ui.value = MovieHomeUi(loading = false, message = "Les réglages du profil ne sont pas encore chargés."); return }
            val hidden = p.movieHidden()
            rows.keys.removeAll(hidden)
            // Le cache peut rétablir la catégorie d'une ancienne référence, sans fiche réseau non filtrable.
            // Masqué gagne : une référence est écartée dès qu'UNE de ses catégories connues (la sienne ou celle du cache) est masquée.
            val known = HashMap<String, String>()
            fun cachedOf(id: String): String? = known.getOrPut(id) { catalog.cachedCategory(id).orEmpty() }.takeIf { it.isNotBlank() }
            val refs = mergeMovieProgress(progress, queued).filter { r -> dismissed[r.itemId]?.let { r.clientUpdatedAt > it } ?: true }
                .filter { cachedOf(it.itemId) !in hidden }.map { r -> if (r.movieCategory.isNullOrBlank()) r.copy(movieCategory = cachedOf(r.itemId)) else r }
            val refsList = list.filter { cachedOf(it.itemId) !in hidden }.map { r -> if (r.movieCategory.isNullOrBlank()) r.copy(movieCategory = cachedOf(r.itemId)) else r }
            val built = buildMovieHome(refs, refsList, categories, p, recent, now()/1000).groupVersions(versions, languages)
            val fallback = featured?.takeIf { it.categoryId.isNotBlank() && it.categoryId !in hidden }?.card()
            val home = built.copy(resume = built.resume ?: fallback)
            val v = view
            // Films épinglés : seuls dans la bannière (ceux qu'on connaît déjà) ; sinon la bannière automatique.
            val pinnedFeatures = v.bannerPinned.mapNotNull { pin ->
                val info = infos[pin.id]
                val category = info?.categoryId?.takeIf { it.isNotBlank() } ?: pin.category ?: cachedOf(pin.id)
                if (category != null && category in hidden) null else pinnedMovieFeature(home, pin.id, info)
            }
            val auto = pinnedFeatures.ifEmpty { buildMovieFeatures(home, picks.filter { it.item.categoryId !in hidden }, v) { infos[it] } }
            val features = if (v.fixed) auto.take(1) else auto
            val backdrops = infos.mapNotNull { (id, m) -> m.backdrop?.let { id to it } }.toMap()
            val vs = versions
            _ui.value = MovieHomeUi(home, rows.filterKeys { it !in hidden }.mapValues { (_, cards) -> collapseVersions(cards, vs, languages) { it.movieId } }, failed.toSet(), pending, error ?: if (!pending && home == MovieHome.EMPTY) "Aucun film disponible pour ce profil." else null, features, backdrops,
                v.visibleRows(HomeRows.MOVIES), v.rotateMs)
        }
    }
}
