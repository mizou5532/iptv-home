package home.iptv.tv.search

import home.iptv.shared.catalog.CatalogRepository
import home.iptv.shared.groups.GroupSnapshot
import home.iptv.shared.movies.MovieRepository
import home.iptv.shared.search.*
import home.iptv.shared.series.SeriesRepository
import home.iptv.shared.sync.ProfilePrefs
import home.iptv.tv.live.groupSourceIndex
import home.iptv.tv.movies.movieHidden
import home.iptv.tv.series.seriesHidden
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map

private fun ProfilePrefs.hiddenOf(type: String): Set<String> = hidden.filter { it.itemType == type }.mapTo(HashSet()) { it.itemId }

/**
 * Les vraies sources de la recherche : cache local seulement, aucune demande réseau.
 * Direct : une entrée par groupe de copies visibles (chaînes et catégories masquées retirées) ;
 * Séries et Films : tous les titres connus du cache, que le remplisseur complète en arrière-plan.
 */
fun searchFactory(live: CatalogRepository, series: SeriesRepository, movies: MovieRepository, filler: CatalogFiller, versions: home.iptv.tv.feature.VersionIndexes? = null): SearchFactory =
    { section, session, scope ->
        val prefs = session.watcher.prefs
        // Dernière lecture, pour retrouver l'affiche, la catégorie et la copie d'un résultat sans relire la base.
        val last = java.util.concurrent.atomic.AtomicReference<Map<String, SearchResult>>(emptyMap())
        val items: suspend () -> List<SearchItem> = {
            when (section) {
                SearchSection.SERIES -> series.searchItems()
                SearchSection.MOVIES -> movies.searchItems()
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
                groupSearchItems(visible, names, versions?.languages ?: home.iptv.shared.versions.LanguagePrefs(), grouper).also { (_, results) -> last.set(results) }.first
            }
        }
        val changed = if (section == SearchSection.LIVE) session.vm.groupsRevision.drop(1).map { SearchSection.LIVE }
            else filler.changed
        SearchViewModel(section, items, { id -> last.get()[id] }, prefs, hiddenFor, changed, filler.progress, scope, Dispatchers.IO, group)
    }

private fun liveItems(repo: CatalogRepository, prefs: ProfilePrefs?, snapshot: GroupSnapshot?, publish: (Map<String, SearchResult>) -> Unit): List<SearchItem> {
    if (prefs == null) return emptyList()
    val hiddenChannels = prefs.hiddenOf("channel")
    val hiddenCategories = prefs.hiddenOf("live_category")
    val visible = repo.allGroupSources().filter { it.id !in hiddenChannels && it.categoryId !in hiddenCategories }
    val index = groupSourceIndex(visible, snapshot ?: GroupSnapshot(0, 1, emptyList()), prefs.groupCopies, home.iptv.tv.live.categoryCountries(repo.categories()))
    val groups = index.values.associateBy { it.id }
    val visibleById = visible.associateBy { it.id }
    publish(groups.values.mapNotNull { group ->
        val anchor = group.sources.firstOrNull { it.id in visibleById } ?: return@mapNotNull null
        group.id to SearchResult(group.id, group.title, null, anchor.categoryId, anchor.id)
    }.toMap())
    return liveSearchItems(visible, groups)
}

/**
 * Une entrée de recherche par titre : versions (langues, 4K) réunies. Un titre seul garde son identifiant ; un groupe
 * prend « v:<titre>|<année> ». Rend les entrées à chercher et les résultats complets (avec leurs versions).
 */
fun groupSearchItems(
    visible: List<SearchItem>, categoryNames: Map<String, String>,
    prefs: home.iptv.shared.versions.LanguagePrefs = home.iptv.shared.versions.LanguagePrefs(),
    grouper: (List<home.iptv.shared.versions.VersionInput>) -> Map<String, List<home.iptv.shared.versions.Version>> = { home.iptv.shared.versions.groupVersions(it) },
): Pair<List<SearchItem>, Map<String, SearchResult>> {
    val groups = grouper(visible.map { item ->
        val cat = item.categoryIds.sorted().first()
        home.iptv.shared.versions.VersionInput(item.id, item.title, cat, categoryNames[cat], item.cover)
    })
    val results = HashMap<String, SearchResult>(groups.size)
    // « Seulement mes langues » : versions dans d'autres langues retirées ; un titre sans aucune version gardée disparaît.
    val grouped = groups.mapNotNull { (key, all) -> home.iptv.shared.versions.myVersions(all, prefs).takeIf { it.isNotEmpty() }?.let { key to it } }.map { (key, versions) ->
        val first = versions.first()
        val id = if (versions.size == 1) first.id else "v:$key"
        val title = home.iptv.shared.versions.versionTitle(first.title)
        results[id] = SearchResult(id, title, versions.firstNotNullOfOrNull { it.cover }, first.categoryId, versions = versions)
        SearchItem(id, title, versions.map { it.categoryId }.toSet(), first.cover)
    }
    return grouped to results
}
