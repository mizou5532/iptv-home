package home.iptv.shared.search

import home.iptv.shared.series.SeriesRepository
import home.iptv.shared.movies.MovieRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

enum class SearchSection { LIVE, SERIES, MOVIES }

data class FillProgress(val done: Int, val total: Int) {
    val complete: Boolean get() = done >= total
}

/**
 * Un remplisseur à partager par processus. Les callbacks lisent les préférences du profil actif :
 * null interdit toute demande dans la section. Ils sont relus avant chaque opération fournisseur.
 * [start] est idempotent pendant l'exécution ; [stop] annule le travail, sans effacer le cache.
 * Les demandes des écrans doivent passer par [screenFirst] sur cette même instance.
 */
class CatalogFiller(
    private val series: SeriesRepository,
    private val movies: MovieRepository,
    private val scope: CoroutineScope,
    private val pause: Duration = 500.milliseconds,
    /**
     * Attendu avant chaque demande de fond au fournisseur (télé : pas pendant qu'on navigue, voir 0.40). Ne bloque
     * jamais les demandes des écrans ([screenFirst]).
     */
    private val beforeWork: suspend () -> Unit = {},
    /**
     * Catégories en vitrine d'une section (0.41) ; null = toutes. Elles sont remplies d'abord, et seules elles
     * annoncent un changement ([changed]) : les autres ne touchent ni l'index de recherche en mémoire ni l'index des
     * versions (avant, chaque catégorie remplie faisait tout recalculer, des centaines de fois).
     */
    private val showcase: (SearchSection) -> Set<String>? = { null },
) {
    private data class Settings(
        val seriesHidden: () -> Set<String>?,
        val movieHidden: () -> Set<String>?,
        val priority: () -> SearchSection,
    ) {
        fun hidden(section: SearchSection) =
            (if (section == SearchSection.SERIES) seriesHidden() else movieHidden())?.toSet()
    }

    private val settings = MutableStateFlow<Settings?>(null)
    private val requestGate = Mutex()
    private val screensWaiting = MutableStateFlow(0)
    private var worker: Job? = null
    private var cooling: Job? = null
    private val mutableProgress = MutableStateFlow(
        mapOf(SearchSection.SERIES to FillProgress(0, 0), SearchSection.MOVIES to FillProgress(0, 0)),
    )
    val progress: StateFlow<Map<SearchSection, FillProgress>> = mutableProgress.asStateFlow()
    private val mutableChanged = MutableSharedFlow<SearchSection>(extraBufferCapacity = 2)
    /** Invalidation du cache après une catégorie effectivement rafraîchie ; pas de rejeu. */
    val changed: SharedFlow<SearchSection> = mutableChanged.asSharedFlow()

    fun start(seriesHidden: () -> Set<String>?, movieHidden: () -> Set<String>?, priority: () -> SearchSection) {
        settings.value = Settings(seriesHidden, movieHidden, priority)
        if (worker?.isActive == true) return
        worker = scope.launch { fill() }
    }

    fun stop() { worker?.cancel(); worker = null }

    /** Une demande déjà en vol peut finir ; les écrans passent avant la prochaine demande de fond. */
    suspend fun <T> screenFirst(block: suspend () -> T): T {
        screensWaiting.update { it + 1 }
        try { return requestGate.withLock { block() } }
        finally { screensWaiting.update { it - 1 } }
    }

    private fun fresh(section: SearchSection, id: String) =
        if (section == SearchSection.SERIES) series.isCategoryFresh(id) else movies.isCategoryFresh(id)

    /**
     * Catégories fraîches d'une section en UNE lecture de la base (0.40) : avant, chaque catégorie était lue à part,
     * plusieurs fois par seconde et sans fin (des centaines de lectures par seconde, devant celles de l'écran).
     */
    private fun freshSet(section: SearchSection): Set<String> =
        if (section == SearchSection.SERIES) series.freshCategoryIds() else movies.freshCategoryIds()

    /** La pause reste hors du verrou : aucun écran n'attend le repos du remplisseur. */
    private suspend fun background(block: suspend () -> Unit) {
        while (currentCoroutineContext().isActive) {
            beforeWork()
            cooling?.join()
            screensWaiting.first { it == 0 }
            val ran = requestGate.withLock {
                if (screensWaiting.value != 0) false else { block(); true }
            }
            if (ran) return
        }
        currentCoroutineContext().ensureActive()
    }

    private suspend fun supplier(block: suspend () -> Unit) {
        try { block() }
        finally { cooling = scope.launch { delay(pause) } }
    }

    private suspend fun fill(): Unit = coroutineScope {
        // Une panne, y compris le repli silencieux du dépôt, ne se réessaie qu'au prochain start.
        val attempted = mutableSetOf<Pair<SearchSection, String>>()
        val failedLists = MutableStateFlow<Set<SearchSection>>(emptySet())
        val lists = MutableStateFlow<Map<SearchSection, List<String>>>(emptyMap())
        fun publish() {
            val cfg = settings.value ?: return
            mutableProgress.value = listOf(SearchSection.SERIES, SearchSection.MOVIES).associateWith { section ->
                val hidden = cfg.hidden(section)
                val ids = if (hidden == null) emptyList() else lists.value[section].orEmpty().filter { it !in hidden }
                if (hidden != null && section in failedLists.value && section !in lists.value) FillProgress(0, 1)
                else if (ids.isEmpty()) FillProgress(0, 0)
                else freshSet(section).let { f -> FillProgress(ids.count { it in f }, ids.size) }
            }
        }
        launch {
            while (isActive) {
                publish()
                delay(pause.coerceAtLeast(1.milliseconds))
            }
        }
        while (currentCoroutineContext().isActive) {
            val ordered = if (settings.value?.priority?.invoke() == SearchSection.MOVIES)
                listOf(SearchSection.MOVIES, SearchSection.SERIES)
            else listOf(SearchSection.SERIES, SearchSection.MOVIES)
            var filled = false
            for (section in ordered) {
                if (settings.value?.hidden(section) == null || section in failedLists.value) continue
                background {
                    if (settings.value?.hidden(section) == null) return@background
                    try {
                        val read: suspend () -> Unit = {
                            // Les métadonnées des catégories sont globales chez le fournisseur.
                            // Garder tous les IDs permet de recalculer aussitôt un nouveau profil ;
                            // seuls les titres autorisés sont demandés ci-dessous.
                            val ids = if (section == SearchSection.SERIES)
                                series.categories(emptySet()).map { it.id }
                            else movies.categories(emptySet()).map { it.id }
                            lists.update { it + (section to ids) }
                        }
                        val cached = if (section == SearchSection.SERIES) series.areCategoriesFresh() else movies.areCategoriesFresh()
                        if (cached) read() else supplier { read() }
                        val nowFresh = if (section == SearchSection.SERIES) series.areCategoriesFresh() else movies.areCategoriesFresh()
                        if (!nowFresh) failedLists.update { it + section }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { failedLists.update { it + section } }
                }
                publish()
                if (section in failedLists.value) continue
                val hidden = settings.value?.hidden(section) ?: continue
                val freshNow = freshSet(section)
                val front = runCatching { showcase(section) }.getOrNull()
                val waiting = lists.value[section].orEmpty().filter {
                    it !in hidden && it !in freshNow && (section to it) !in attempted
                }
                val id = (if (front == null) waiting.firstOrNull() else waiting.firstOrNull { it in front } ?: waiting.firstOrNull())
                    ?: continue
                var refreshed = false
                background {
                    val currentHidden = settings.value?.hidden(section) ?: return@background
                    if (id in currentHidden || fresh(section, id)) return@background
                    attempted += section to id
                    try {
                        supplier {
                            if (section == SearchSection.SERIES) series.seriesOf(id, currentHidden)
                            else movies.moviesOf(id, currentHidden)
                        }
                        refreshed = fresh(section, id)
                        // Une réussite peut expirer pendant cette session ; seuls les échecs restent bloqués.
                        if (refreshed) attempted -= section to id
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* Retenter seulement au prochain démarrage. */ }
                    filled = true
                }
                publish()
                if (refreshed && (front == null || id in front)) mutableChanged.emit(section)
                if (filled) break
            }
            publish()
            // Même terminé, suivre les changements de profil/masquage et les expirations du cache (une vérification ne
            // coûte plus qu'une lecture de la base par section, au lieu d'une par catégorie).
            if (!filled) delay(pause.coerceAtLeast(1.milliseconds))
        }
    }
}
