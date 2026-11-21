package home.iptv.shared.groups

import home.iptv.shared.xtream.LiveCategory

/** Pays de chaque catégorie (« US| SPORTS » → US) : les copies ne sont regroupées qu'entre chaînes du même pays. */
fun categoryCountries(categories: List<LiveCategory>): Map<String, String> =
    categories.mapNotNull { c -> home.iptv.shared.text.countryOf(c.name)?.let { c.id to it } }.toMap()

/** Clés de regroupement déjà calculées (nom + pays → clé) : l'analyse d'un nom coûte cher (expressions régulières). */
private val autoKeys = HashMap<Pair<String, String?>, Any>()
private val NO_KEY = Any()
private const val AUTO_KEYS_MAX = 200_000
private val autoKeysLock = Any()

private val titles = HashMap<String, String>()
private val titlesLock = Any()

/** [baseTitle] gardé en mémoire, comme [cachedAutoKey]. */
fun cachedTitle(name: String): String =
    withGroupLock(titlesLock) { titles[name] } ?: baseTitle(name).also {
        withGroupLock(titlesLock) {
            if (titles.size >= AUTO_KEYS_MAX) titles.clear()
            titles[name] = it
        }
    }

/**
 * [autoKey] gardée en mémoire : le même regroupement est refait souvent (Direct, Sport, recherche, chaque mise à jour
 * du catalogue) ; 56 000 chaînes analysées à chaque fois prenaient plusieurs secondes sur la télé (essai 0.32.0 :
 * « chaînes 6385 ms », Direct vide près d'une minute). Résultat identique.
 */
fun cachedAutoKey(name: String, country: String?): String? {
    val k = name to country
    val hit = withGroupLock(autoKeysLock) { autoKeys[k] }
    if (hit != null) return hit as? String
    val key = autoKey(name, country)
    withGroupLock(autoKeysLock) {
        if (autoKeys.size >= AUTO_KEYS_MAX) autoKeys.clear()
        autoKeys[k] = key ?: NO_KEY
    }
    return key
}

/** Clé et titre déjà connus d'une chaîne (calculés ailleurs, par le serveur) : remplacent l'analyse du nom. */
data class PrecomputedKey(val autoKey: String?, val title: String)

/**
 * Construit en IO seulement quand catalogue, corrections ou visibilite changent. [countries] : pays des catégories
 * ([categoryCountries]) ; le même partout (Direct, recherche, Sport) pour que les groupes aient les mêmes identifiants.
 * [precomputed] : clé et titre déjà calculés d'une source ; s'il rend une valeur, elle remplace [cachedAutoKey] et
 * [cachedTitle] pour cette source.
 */
fun groupSourceIndex(
    visibleSources: List<GroupSource>,
    snapshot: GroupSnapshot,
    enabled: Boolean,
    countries: Map<String, String> = emptyMap(),
    precomputed: (sourceId: String) -> PrecomputedKey? = { null },
): Map<String, LiveGroup> {
    val groups = if (enabled) {
        buildGroups(
            visibleSources, snapshot,
            keyOf = { s ->
                val p = precomputed(s.id)
                if (p != null) p.autoKey else cachedAutoKey(s.name, countries[s.categoryId])
            },
            titleOf = ::cachedTitle,
        ).map { g ->
            // Le titre d'un groupe automatique est celui de sa première copie : le titre précalculé, s'il existe.
            val title = if (g.id.startsWith("auto:")) precomputed(g.sources.first().id)?.title else null
            if (title != null) g.copy(title = title) else g
        }
    } else visibleSources.map { LiveGroup("source:${it.id}", it.name, listOf(it)) }
    return buildMap { groups.forEach { group -> group.sources.forEach { put(it.id, group) } } }
}
