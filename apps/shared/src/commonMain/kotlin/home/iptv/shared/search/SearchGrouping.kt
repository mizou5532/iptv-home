package home.iptv.shared.search

import home.iptv.shared.versions.LanguagePrefs
import home.iptv.shared.versions.Version
import home.iptv.shared.versions.VersionInput
import home.iptv.shared.versions.groupVersions
import home.iptv.shared.versions.myVersions
import home.iptv.shared.versions.versionTitle

/** Résultat prêt à afficher dans la recherche (Direct, Séries ou Films). */
data class SearchResult(
    val id: String,
    val title: String,
    val cover: String?,
    val categoryId: String,
    val anchorSourceId: String? = null,
    /** Séries et films : toutes les versions du même titre (langues, 4K) ; vide pour le Direct. */
    val versions: List<Version> = emptyList(),
    /** Trouvé par la personne cherchée : « Avec Denzel Washington », « Réalisé par Denis Villeneuve » ; `null` sinon. */
    val note: String? = null,
)

/**
 * Une entrée de recherche par titre : versions (langues, 4K) réunies. Un titre seul garde son identifiant ; un groupe
 * prend « v:<titre>|<année> ». Rend les entrées à chercher et les résultats complets (avec leurs versions).
 */
fun groupSearchItems(
    visible: List<SearchItem>,
    categoryNames: Map<String, String>,
    prefs: LanguagePrefs = LanguagePrefs(),
    grouper: (List<VersionInput>) -> Map<String, List<Version>> = { groupVersions(it) },
): Pair<List<SearchItem>, Map<String, SearchResult>> {
    val groups = grouper(visible.map { item ->
        val cat = item.categoryIds.sorted().first()
        VersionInput(item.id, item.title, cat, categoryNames[cat], item.cover)
    })
    val results = HashMap<String, SearchResult>(groups.size)
    // « Seulement mes langues » : versions dans d'autres langues retirées ; un titre sans aucune version gardée disparaît.
    val grouped = groups.mapNotNull { (key, all) -> myVersions(all, prefs).takeIf { it.isNotEmpty() }?.let { key to it } }
        .map { (key, versions) ->
            val first = versions.first()
            val id = if (versions.size == 1) first.id else "v:$key"
            val title = versionTitle(first.title)
            results[id] = SearchResult(id, title, versions.firstNotNullOfOrNull { it.cover }, first.categoryId, versions = versions)
            SearchItem(id, title, versions.map { it.categoryId }.toSet(), first.cover)
        }
    return grouped to results
}
