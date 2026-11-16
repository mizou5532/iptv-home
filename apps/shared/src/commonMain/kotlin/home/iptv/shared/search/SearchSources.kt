package home.iptv.shared.search

import home.iptv.shared.groups.GroupSource
import home.iptv.shared.groups.LiveGroup

/** Masqué gagne ; les catégories inconnues (vides) ne rendent jamais un titre visible. */
fun visibleSearchItems(items: List<SearchItem>, hidden: Set<String>): List<SearchItem> =
    items.filter { item -> item.categoryIds.any { it.isNotBlank() } && item.categoryIds.none { it in hidden } }

/** Un titre stable par id, avec l'union de toutes ses catégories de liste et de fiche. */
internal fun mergeSearchItems(items: List<SearchItem>): List<SearchItem> =
    items.groupBy { it.id }.map { (_, copies) ->
        copies.first().copy(
            categoryIds = copies.flatMap { it.categoryIds }.filter { it.isNotBlank() }.toSet(),
            cover = copies.firstNotNullOfOrNull { it.cover?.takeIf(String::isNotBlank) },
        )
    }

/**
 * [sources] = copies déjà filtrées par les masquages (chaînes et catégories). Comme dans la liste du Direct, un
 * groupe reste trouvable grâce à ses copies visibles ; une copie dans une catégorie masquée ne le cache pas.
 * Un groupe sans copie visible ne produit aucun résultat.
 */
fun liveSearchItems(sources: List<GroupSource>, groups: Map<String, LiveGroup>): List<SearchItem> {
    val visibleById = sources.associateBy { it.id }
    return groups.values.mapNotNull { group ->
        val copies = group.sources.mapNotNull { visibleById[it.id] }
        if (copies.isEmpty()) null else SearchItem(
            group.id, group.title, copies.map { it.categoryId }.filter { it.isNotBlank() }.toSet(),
        )
    }
}
