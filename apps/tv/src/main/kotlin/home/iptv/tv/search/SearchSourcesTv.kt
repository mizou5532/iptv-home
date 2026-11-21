package home.iptv.tv.search

import home.iptv.shared.catalog.CatalogRepository
import home.iptv.shared.groups.GroupSnapshot
import home.iptv.shared.home.toSearchResult
import home.iptv.shared.movies.MovieRepository
import home.iptv.shared.search.*
import home.iptv.shared.series.SeriesRepository
import home.iptv.shared.sync.ProfilePrefs
import home.iptv.shared.groups.groupSourceIndex
import home.iptv.tv.movies.movieHidden
import home.iptv.tv.series.seriesHidden
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private fun ProfilePrefs.hiddenOf(type: String): Set<String> = hidden.filter { it.itemType == type }.mapTo(HashSet()) { it.itemId }

/**
 * Les vraies sources de la recherche : cache local seulement, aucune demande réseau.
 * Direct : une entrée par groupe de copies visibles (chaînes et catégories masquées retirées) ;
 * Séries et Films : tous les titres connus du cache, que le remplisseur complète en arrière-plan.
 */
fun searchFactory(
    live: CatalogRepository, series: SeriesRepository, movies: MovieRepository, filler: CatalogFiller, versions: home.iptv.shared.versions.VersionIndexes? = null,
    /** Films et séries d'une personne (recherche par acteur ou réalisateur) ; `null` = pas de recherche par personne. */
    person: (suspend (String) -> home.iptv.shared.search.PersonCredits?)? = null,
    /** Client serveur maison (étape 3) ; `null` = recherche locale uniquement. */
    homeSearch: home.iptv.tv.home.HomeSearchClient? = null,
    /** Empreinte du compte IPTV actuel (ignore toute réponse serveur d'un autre compte). */
    account: () -> String? = { null },
): SearchFactory =
    { section, session, scope ->
        val prefs = session.watcher.prefs
        val langs: () -> home.iptv.shared.versions.LanguagePrefs = {
            session.profile.settings.languages ?: versions?.languages ?: home.iptv.shared.versions.LanguagePrefs()
        }
        // Dernière lecture, pour retrouver l'affiche, la catégorie et la copie d'un résultat sans relire la base.
        val last = java.util.concurrent.atomic.AtomicReference<Map<String, SearchResult>>(emptyMap())
        val items: suspend () -> List<SearchItem> = {
            when (section) {
                // Vitrine et ses doublons 4K/Dolby (0.41) ; le reste est cherché à la demande ([OtherTitles]).
                SearchSection.SERIES -> series.searchItems(series.versionIds(), inside = true)
                SearchSection.MOVIES -> movies.searchItems(movies.versionIds(), inside = true)
                SearchSection.LIVE -> liveItems(live, prefs.value, session.groupsWatcher?.snapshot?.value) { last.set(it) }
            }.also { list ->
                if (section != SearchSection.LIVE) last.set(list.associate { item ->
                    item.id to SearchResult(item.id, item.title, item.cover, item.categoryIds.sorted().first())
                })
            }
        }
        val hiddenFor: (ProfilePrefs) -> Set<String> = when (section) {
            SearchSection.SERIES -> { p -> p.seriesHidden() }
            SearchSection.MOVIES -> { p -> p.movieHidden() }
            SearchSection.LIVE -> { p -> p.hiddenOf("live_category") }
        }
        // Séries et films : une entrée par titre, toutes ses versions (langues, 4K) réunies ; masquages déjà appliqués.
        val group: (List<SearchItem>) -> List<SearchItem> = when (section) {
            SearchSection.LIVE -> { list -> list }
            else -> { visible ->
                val names = runCatching { if (section == SearchSection.SERIES) series.cachedCategoryNames() else movies.cachedCategoryNames() }.getOrDefault(emptyMap())
                val kind = if (section == SearchSection.SERIES) home.iptv.shared.meta.MediaKind.SERIES else home.iptv.shared.meta.MediaKind.MOVIE
                val grouper: (List<home.iptv.shared.versions.VersionInput>) -> Map<String, List<home.iptv.shared.versions.Version>> =
                    versions?.let { v -> { inputs -> v.group(kind, inputs) } } ?: { inputs -> home.iptv.shared.versions.groupVersions(inputs) }
                groupSearchItems(visible, names, langs(), grouper).also { (_, results) -> last.set(results) }.first
            }
        }
        val changed = if (section == SearchSection.LIVE) session.vm.groupsRevision.drop(1).map { SearchSection.LIVE }
            else filler.changed
        val others = if (section == SearchSection.LIVE) null else OtherTitles(scope) {
            val inside = if (section == SearchSection.SERIES) series.versionIds() else movies.versionIds()
            val list = if (section == SearchSection.SERIES) series.searchItems(inside, inside = false) else movies.searchItems(inside, inside = false)
            val names = runCatching { if (section == SearchSection.SERIES) series.cachedCategoryNames() else movies.cachedCategoryNames() }.getOrDefault(emptyMap())
            list to names
        }
        val remoteFn: (suspend (String, ProfilePrefs, home.iptv.shared.search.PersonCredits?) -> List<SearchResult>?)? = homeSearch?.let { client ->
            val sectionKey = when (section) {
                SearchSection.LIVE -> "live"
                SearchSection.SERIES -> "series"
                SearchSection.MOVIES -> "movies"
            }
            { query, p, credits ->
                val req = home.iptv.shared.home.SearchRequest(
                    section = sectionKey,
                    query = query,
                    hiddenCategories = hiddenFor(p).toList(),
                    hiddenChannels = if (section == SearchSection.LIVE) p.hiddenOf("channel").toList() else emptyList(),
                    groupCopies = p.groupCopies,
                    languages = langs(),
                    personCredits = credits,
                )
                val reply = client.search(req)
                val acct = account()
                if (reply == null || (acct != null && reply.account != acct)) null
                else reply.results.map { it.toSearchResult() }
            }
        }
        SearchViewModel(section, items, { id -> last.get()[id] }, prefs, hiddenFor, changed, filler.progress, scope, Dispatchers.IO, group,
            person = person.takeIf { section != SearchSection.LIVE },
            more = others?.let { o -> { query, hidden -> o.search(query, hidden, langs()) } },
            remote = remoteFn,
            hideProgressWhen = {
                val c = homeSearch as? home.iptv.tv.home.HomeServerClient
                c?.state?.value is home.iptv.tv.home.HomeState.Present
            })
    }

/**
 * Titres hors vitrine (0.41) : lus dans la base seulement quand on cherche, gardés quelques minutes pour les lettres
 * suivantes, puis oubliés (la mémoire de la télé reste libre). Versions (langues, 4K) regroupées comme pour la vitrine.
 */
class OtherTitles(
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val keepMs: Long = 3 * 60_000L,
    private val load: () -> Pair<List<SearchItem>, Map<String, String>>,
) {
    private class Built(val hidden: Set<String>, val languages: home.iptv.shared.versions.LanguagePrefs, val index: SearchIndex, val results: Map<String, SearchResult>)
    @Volatile private var built: Built? = null
    private var forget: kotlinx.coroutines.Job? = null
    private val lock = Any()

    /** À appeler hors du fil principal. */
    fun search(query: String, hidden: Set<String>, languages: home.iptv.shared.versions.LanguagePrefs): List<SearchResult> {
        val b = synchronized(lock) {
            built?.takeIf { it.hidden == hidden && it.languages == languages } ?: run {
                val (list, names) = load()
                val (items, results) = groupSearchItems(visibleSearchItems(list, hidden), names, languages)
                Built(hidden, languages, SearchIndex(items), results).also { built = it }
            }
        }
        synchronized(lock) {
            forget?.cancel()
            forget = scope.launch { kotlinx.coroutines.delay(keepMs); synchronized(lock) { built = null } }
        }
        return b.index.search(query).mapNotNull { b.results[it.id] }
    }
    }

private fun liveItems(repo: CatalogRepository, prefs: ProfilePrefs?, snapshot: GroupSnapshot?, publish: (Map<String, SearchResult>) -> Unit): List<SearchItem> {
    if (prefs == null) return emptyList()
    val hiddenChannels = prefs.hiddenOf("channel")
    val hiddenCategories = prefs.hiddenOf("live_category")
    val visible = repo.allGroupSources().filter { it.id !in hiddenChannels && it.categoryId !in hiddenCategories }
    val index = groupSourceIndex(
        visible,
        snapshot ?: GroupSnapshot(0, 1, emptyList()),
        prefs.groupCopies,
        home.iptv.shared.groups.categoryCountries(repo.categories()),
        precomputed = repo.liveKeys()::get,
    )
    val groups = index.values.associateBy { it.id }
    val visibleById = visible.associateBy { it.id }
    publish(groups.values.mapNotNull { group ->
        val anchor = group.sources.firstOrNull { it.id in visibleById } ?: return@mapNotNull null
        group.id to SearchResult(group.id, group.title, null, anchor.categoryId, anchor.id)
    }.toMap())
    return liveSearchItems(visible, groups)
}

/** Délègue au code commun [home.iptv.shared.search.groupSearchItems] (conservé ici pour compatibilité des imports). */
fun groupSearchItems(
    visible: List<SearchItem>, categoryNames: Map<String, String>,
    prefs: home.iptv.shared.versions.LanguagePrefs = home.iptv.shared.versions.LanguagePrefs(),
    grouper: (List<home.iptv.shared.versions.VersionInput>) -> Map<String, List<home.iptv.shared.versions.Version>> = { home.iptv.shared.versions.groupVersions(it) },
): Pair<List<SearchItem>, Map<String, SearchResult>> =
    home.iptv.shared.search.groupSearchItems(visible, categoryNames, prefs, grouper)
