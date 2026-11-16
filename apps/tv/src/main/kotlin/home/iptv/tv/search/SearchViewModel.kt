package home.iptv.tv.search

import home.iptv.shared.search.*
import home.iptv.shared.sync.ProfilePrefs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class SearchResult(val id: String, val title: String, val cover: String?, val categoryId: String, val anchorSourceId: String? = null)
data class SearchUi(val section: SearchSection, val query: String = "", val results: List<SearchResult> = emptyList(), val progress: FillProgress? = null, val message: String? = null)

class SearchViewModel(
    private val section: SearchSection,
    private val items: suspend () -> List<SearchItem>,
    private val details: (String) -> SearchResult?,
    private val prefs: StateFlow<ProfilePrefs?>,
    private val hiddenFor: (ProfilePrefs) -> Set<String>,
    changed: Flow<SearchSection>,
    private val progress: StateFlow<Map<SearchSection, FillProgress>>,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val state = MutableStateFlow(SearchUi(section))
    val ui: StateFlow<SearchUi> = state.asStateFlow()
    private var generation = 0L
    private var work: Job? = null
    private var index: SearchIndex? = null

    init {
        scope.launch {
            prefs.collect {
                // Clear before any suspend: a new profile/mask must never retain old results.
                index = null
                state.value = state.value.copy(results = emptyList(), progress = progress.value[section]?.takeIf { fill -> it != null && !fill.complete })
                refresh(0)
            }
        }
        scope.launch { changed.collect { if (it == section) { index = null; refresh(0) } } }
        scope.launch {
            progress.collect { values ->
                state.value = state.value.copy(progress = values[section]?.takeIf { !it.complete && prefs.value != null })
            }
        }
    }

    fun setQuery(text: String) {
        state.value = state.value.copy(query = text, results = emptyList())
        refresh(120)
    }

    private fun message(query: String, results: List<SearchResult>, settings: ProfilePrefs?): String? = when {
        settings == null -> "Les réglages du profil ne sont pas encore chargés."
        query.isBlank() -> "Tape quelques lettres ou appuie sur Parler."
        results.isEmpty() -> "Rien trouvé pour « $query »."
        else -> null
    }

    private fun refresh(debounceMs: Long) {
        val token = ++generation
        work?.cancel()
        val settings = prefs.value
        val query = state.value.query
        state.value = state.value.copy(message = message(query, state.value.results, settings))
        if (settings == null) {
            state.value = state.value.copy(results = emptyList(), progress = null)
            return
        }
        work = scope.launch {
            delay(debounceMs)
            try {
                val hidden = hiddenFor(settings)
                val built = index ?: withContext(io) { SearchIndex(visibleSearchItems(items(), hidden)) }
                if (token != generation || prefs.value != settings) return@launch
                index = built
                val found = withContext(io) {
                    built.search(query).mapNotNull { item ->
                        details(item.id)?.takeIf { it.id == item.id && it.categoryId.isNotBlank() && it.categoryId !in hidden }
                    }
                }
                // Cancellation alone cannot protect against callbacks doing non-cooperative work.
                if (token == generation && prefs.value == settings) {
                    state.value = state.value.copy(results = found, message = message(query, found, settings))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (token == generation && prefs.value == settings) {
                    state.value = state.value.copy(results = emptyList(), message = "Recherche indisponible.")
                }
            }
        }
    }
}
