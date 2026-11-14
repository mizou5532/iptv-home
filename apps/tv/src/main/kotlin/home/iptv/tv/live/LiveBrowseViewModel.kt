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
    private val refresh: suspend () -> RefreshReport,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
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
                refresh()
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
