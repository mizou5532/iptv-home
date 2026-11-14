package home.iptv.tv.live

import home.iptv.shared.catalog.CatalogRepository
import home.iptv.shared.catalog.RefreshReport
import home.iptv.shared.xtream.LiveCategory
import home.iptv.shared.xtream.LiveStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** Affiche le cache tout de suite, rafraîchit en arrière-plan (une fois par démarrage), puis met à jour. */
class LiveBrowseViewModel(
    private val repo: CatalogRepository,
    private val refresh: suspend (onProgress: suspend (done: Int, total: Int) -> Unit) -> RefreshReport,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val _categories = MutableStateFlow<List<LiveCategory>>(emptyList())
    private val _streams = MutableStateFlow<List<LiveStream>>(emptyList())
    private val _selectedId = MutableStateFlow<String?>(null)
    private val _refreshing = MutableStateFlow(false)
    val categories: StateFlow<List<LiveCategory>> = _categories
    val streams: StateFlow<List<LiveStream>> = _streams
    val selectedId: StateFlow<String?> = _selectedId
    val refreshing: StateFlow<Boolean> = _refreshing

    private var started = false
    private var streamsJob: Job? = null

    fun select(categoryId: String) {
        if (categoryId == _selectedId.value && _streams.value.isNotEmpty()) return
        _selectedId.value = categoryId
        loadStreams(categoryId)
    }

    fun start() {
        if (started) return
        started = true
        scope.launch {
            reload()
            _refreshing.value = true
            try {
                withContext(io) { refresh(::onProgress) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Fournisseur ou liste des masquées injoignable : le cache reste affiché.
            } finally {
                _refreshing.value = false
            }
            reload()
        }
    }

    private var lastReloadMs = 0L
    private var lastReloadDone = 0

    /**
     * Pendant un long rafraîchissement (premier lancement : des centaines de catégories une par une),
     * relit le cache après la première catégorie, puis toutes les [RELOAD_EVERY] catégories ou ~1 s.
     */
    private suspend fun onProgress(done: Int, total: Int) {
        val now = nowMs()
        val due = lastReloadDone == 0 || done - lastReloadDone >= RELOAD_EVERY || now - lastReloadMs >= RELOAD_EVERY_MS
        if (!due) return
        lastReloadDone = done
        lastReloadMs = now
        reload()
    }

    private suspend fun reload() {
        val cats = withContext(io) { repo.categories() }
        _categories.value = cats
        val current = _selectedId.value?.takeIf { id -> cats.any { it.id == id } } ?: cats.firstOrNull()?.id
        _selectedId.value = current
        if (current == null) {
            _streams.value = emptyList()
        } else {
            _streams.value = withContext(io) { repo.streams(current) }
        }
    }

    private fun loadStreams(categoryId: String) {
        streamsJob?.cancel()
        streamsJob = scope.launch {
            val list = withContext(io) { repo.streams(categoryId) }
            if (_selectedId.value == categoryId) _streams.value = list
        }
    }
}

private const val RELOAD_EVERY = 25
private const val RELOAD_EVERY_MS = 1000L
