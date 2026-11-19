package home.iptv.tv.search

import home.iptv.shared.search.*
import home.iptv.shared.sync.ProfilePrefs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class SearchResult(
    val id: String, val title: String, val cover: String?, val categoryId: String, val anchorSourceId: String? = null,
    /** Séries et films : toutes les versions du même titre (langues, 4K) ; vide pour le Direct. */
    val versions: List<home.iptv.shared.versions.Version> = emptyList(),
    /** Trouvé par la personne cherchée : « Avec Denzel Washington », « Réalisé par Denis Villeneuve » ; `null` sinon. */
    val note: String? = null,
)
data class SearchUi(val section: SearchSection, val query: String = "", val results: List<SearchResult> = emptyList(), val progress: FillProgress? = null, val message: String? = null)

/** [scope] et [setQuery] sont confinés au fil principal ; les lectures et calculs utilisent [io]. */
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
    /** Après les masquages : regroupe les versions d'un même titre (séries et films) ; tel quel pour le Direct. */
    private val group: (List<SearchItem>) -> List<SearchItem> = { it },
    /** Films et séries d'une personne (acteur, réalisateur) ; `null` = pas de recherche par personne (Direct). */
    private val person: (suspend (String) -> PersonCredits?)? = null,
    /**
     * Titres hors vitrine (0.41) : cherchés à la demande, après ceux de la vitrine, avec les masquages [hidden] du
     * profil. `null` = rien d'autre (Direct, tests).
     */
    private val more: (suspend (query: String, hidden: Set<String>) -> List<SearchResult>)? = null,
) {
    /** Résultats hors vitrine affichés (OK les ouvre sans passer par l'index de la vitrine). */
    @Volatile private var extras: Map<String, SearchResult> = emptyMap()
    /** Titres visibles de l'index courant (pour retrouver ceux d'une personne). */
    private var indexItems: List<SearchItem> = emptyList()
    private val state = MutableStateFlow(SearchUi(section))
    val ui: StateFlow<SearchUi> = state.asStateFlow()
    private var generation = 0L
    private var work: Job? = null
    private var index: SearchIndex? = null
    /** Masques avec lesquels les résultats affichés ont été calculés : mêmes masques = on les garde (pas de clignotement). */
    private var resultsHidden: Set<String>? = null

    init {
        scope.launch {
            prefs.collect {
                // Masques changés (ou profil inconnu) : effacer avant toute suspension, aucun ancien résultat ne reste.
                // Mêmes masques (préférences relues, premier envoi tardif) : garder la liste, elle sera recalculée.
                index = null
                val hidden = it?.let(hiddenFor)
                if (hidden == null || hidden != resultsHidden) {
                    resultsHidden = null
                    state.value = state.value.copy(results = emptyList())
                }
                state.value = state.value.copy(progress = progress.value[section]?.takeIf { fill -> it != null && !fill.complete })
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

    /** Relecture locale complète au OK ; aucune fiche ni lecture si les masques ont changé. */
    suspend fun currentResult(id: String): SearchResult? {
        val settings = prefs.value ?: return null
        val hidden = hiddenFor(settings)
        extras[id]?.let { extra -> return extra.takeIf { it.categoryId.isNotBlank() && it.categoryId !in hidden && prefs.value == settings } }
        val result = withContext(io) {
            val visible = group(visibleSearchItems(items(), hidden)).any { it.id == id }
            if (!visible) null else details(id)?.takeIf { it.categoryId.isNotBlank() && it.categoryId !in hidden }
        }
        return result?.takeIf { prefs.value == settings }
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
        // Recherche en cours : pas de « Rien trouvé » prématuré (l'écran affiche « Recherche… »).
        state.value = state.value.copy(message = if (settings == null || query.isBlank()) message(query, state.value.results, settings) else null)
        if (settings == null) {
            state.value = state.value.copy(results = emptyList(), progress = null)
            return
        }
        work = scope.launch {
            delay(debounceMs)
            try {
                val hidden = hiddenFor(settings)
                val built = index ?: withContext(io) { group(visibleSearchItems(items(), hidden)).let { list -> indexItems = list; SearchIndex(list) } }
                if (token != generation || prefs.value != settings) return@launch
                index = built
                val pool = indexItems
                val found = withContext(io) {
                    built.search(query).mapNotNull { item ->
                        details(item.id)?.takeIf { it.id == item.id && it.categoryId.isNotBlank() && it.categoryId !in hidden }
                    }
                }
                // Cancellation alone cannot protect against callbacks doing non-cooperative work.
                var shown = found
                // Hors vitrine (0.41) : ajoutés sous ceux de la vitrine, sans doublon.
                val others = more
                if (others != null && query.count { it.isLetterOrDigit() } >= 2 && found.size < SEARCH_LIMIT) {
                    val already = found.flatMapTo(HashSet()) { r -> listOf(r.id) + r.versions.map { it.id } }
                    val extra = withContext(io) { others(query, hidden) }
                        .filter { r -> r.id !in already && r.versions.none { it.id in already } && r.categoryId.isNotBlank() && r.categoryId !in hidden }
                        .take(SEARCH_LIMIT - found.size)
                    if (token != generation || prefs.value != settings) return@launch
                    extras = extra.associateBy { it.id }
                    shown = found + extra
                } else extras = emptyMap()
                if (token == generation && prefs.value == settings) {
                    resultsHidden = hidden
                    state.value = state.value.copy(results = shown, message = message(query, shown, settings))
                }
                // Recherche par personne (4 lettres au moins, après une courte pause) : ses titres du catalogue s'ajoutent
                // sous les résultats, avec « Avec … » ou « Réalisé par … ». Masqué gagne comme pour les autres résultats.
                val lookup = person
                if (lookup != null && query.count { it.isLetter() } >= 4) {
                    delay(PERSON_PAUSE_MS)
                    val credits = lookup(query.trim()) ?: return@launch
                    val name = credits.person?.name ?: return@launch
                    val kind = if (section == SearchSection.SERIES) "series" else "movie"
                    val roles = credits.credits.filter { it.kind == kind }
                    val already = shown.mapTo(HashSet()) { it.id }
                    val extra = withContext(io) {
                        matchPersonCredits(pool, roles, kind).filter { it.id !in already }.mapNotNull { item ->
                            val directed = roles.any { it.role == "realisateur" && sameTitle(it, item) } && roles.none { it.role == "acteur" && sameTitle(it, item) }
                            details(item.id)?.takeIf { it.id == item.id && it.categoryId.isNotBlank() && it.categoryId !in hidden }
                                ?.copy(note = if (directed) "Réalisé par $name" else "Avec $name")
                        }
                    }
                    if (extra.isNotEmpty() && token == generation && prefs.value == settings) {
                        val all = state.value.results + extra
                        state.value = state.value.copy(results = all, message = message(query, all, settings))
                    }
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

/** Pause avant de demander les titres d'une personne : seulement quand on arrête de taper. */
private const val PERSON_PAUSE_MS = 500L

private fun sameTitle(c: PersonCredit, item: SearchItem): Boolean {
    val core = home.iptv.shared.versions.versionCore(item.title)
    return listOfNotNull(c.title, c.originalTitle).any { home.iptv.shared.versions.versionCore(it) == core }
}
