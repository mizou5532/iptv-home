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
fun searchFactory(live: CatalogRepository, series: SeriesRepository, movies: MovieRepository, filler: CatalogFiller): SearchFactory =
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
        val changed = if (section == SearchSection.LIVE) session.vm.groupsRevision.drop(1).map { SearchSection.LIVE }
            else filler.changed
        SearchViewModel(section, items, { id -> last.get()[id] }, prefs, hiddenFor, changed, filler.progress, scope, Dispatchers.IO)
    }

private fun liveItems(repo: CatalogRepository, prefs: ProfilePrefs?, snapshot: GroupSnapshot?, publish: (Map<String, SearchResult>) -> Unit): List<SearchItem> {
    if (prefs == null) return emptyList()
    val hiddenChannels = prefs.hiddenOf("channel")
    val hiddenCategories = prefs.hiddenOf("live_category")
    val visible = repo.allGroupSources().filter { it.id !in hiddenChannels && it.categoryId !in hiddenCategories }
    val index = groupSourceIndex(visible, snapshot ?: GroupSnapshot(0, 1, emptyList()), prefs.groupCopies)
    val groups = index.values.associateBy { it.id }
    val visibleById = visible.associateBy { it.id }
    publish(groups.values.mapNotNull { group ->
        val anchor = group.sources.firstOrNull { it.id in visibleById } ?: return@mapNotNull null
        group.id to SearchResult(group.id, group.title, null, anchor.categoryId, anchor.id)
    }.toMap())
    return liveSearchItems(visible, groups)
}
