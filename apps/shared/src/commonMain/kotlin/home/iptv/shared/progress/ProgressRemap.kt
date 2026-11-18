package home.iptv.shared.progress

import home.iptv.shared.search.SearchItem
import home.iptv.shared.versions.versionCore
import home.iptv.shared.versions.versionYear

/**
 * Progression retrouvée après un changement de fournisseur (demande du propriétaire : « j'ai peur de perdre où j'étais
 * rendu »). Les numéros des films et des séries changent d'un fournisseur à l'autre ; la progression garde leurs noms.
 * Ici : quelles lignes ne correspondent plus au catalogue actuel, et à quel titre du nouveau catalogue elles vont.
 */

/** Nom d'un titre réduit à l'essentiel (sans langue, qualité, année) ; vide si inutilisable. */
private fun core(title: String?): String = title?.let(::versionCore).orEmpty()

/**
 * Ligne de film à déplacer : son numéro n'existe plus, ou désigne maintenant un autre film (un numéro peut être réutilisé
 * par le nouveau fournisseur). `false` sans nom gardé : on ne peut rien retrouver.
 */
fun movieIsStale(row: ProgressRow, currentTitle: String?): Boolean {
    val name = core(row.movieName)
    if (name.isEmpty()) return false
    return currentTitle == null || core(currentTitle) != name
}

/** Même chose pour une série (ligne d'épisode : son numéro de série). */
fun seriesIsStale(row: ProgressRow, currentTitle: String?): Boolean {
    val name = core(row.seriesName)
    if (name.isEmpty() || row.seriesId == null || row.season == null || row.episode == null) return false
    return currentTitle == null || core(currentTitle) != name
}

/**
 * Titre du catalogue pour un nom gardé : même nom nettoyé ; si plusieurs (remakes, versions), celui de la même année
 * (à un an près), sinon le premier. `null` si aucun.
 */
fun findByName(items: List<SearchItem>, name: String?, year: String? = null): SearchItem? {
    val wanted = core(name)
    if (wanted.isEmpty()) return null
    val same = items.filter { core(it.title) == wanted }
    if (same.size <= 1) return same.firstOrNull()
    val y = (year ?: name?.let { versionYear(it) })?.toIntOrNull() ?: return same.first()
    return same.firstOrNull { item -> versionYear(item.title)?.toIntOrNull()?.let { kotlin.math.abs(it - y) <= 1 } == true } ?: same.first()
}

/** Films : nouvelles lignes (mêmes position, « vu » et date, nouveau numéro). */
fun remapMovies(rows: List<ProgressRow>, movies: List<SearchItem>): List<ProgressRow> {
    val byId = movies.associateBy { it.id }
    return rows.filter { it.itemType == "movie" && movieIsStale(it, byId[it.itemId]?.title) }.mapNotNull { row ->
        val target = findByName(movies, row.movieName) ?: return@mapNotNull null
        if (target.id == row.itemId) null else row.copy(itemId = target.id, movieCategory = target.categoryIds.minOrNull() ?: row.movieCategory)
    }
}

/** Séries à retrouver : ancien numéro de série → nouveau titre (les épisodes se retrouvent ensuite par saison et numéro). */
fun remapSeries(rows: List<ProgressRow>, series: List<SearchItem>): Map<String, SearchItem> {
    val byId = series.associateBy { it.id }
    val out = LinkedHashMap<String, SearchItem>()
    for (row in rows) {
        if (row.itemType != "episode" || !seriesIsStale(row, byId[row.seriesId]?.title)) continue
        val old = row.seriesId ?: continue
        if (old in out) continue
        findByName(series, row.seriesName)?.takeIf { it.id != old }?.let { out[old] = it }
    }
    return out
}

/** Épisodes d'une série retrouvée : (saison, numéro) → nouveau numéro d'épisode. */
fun remapEpisodes(rows: List<ProgressRow>, oldSeriesId: String, newSeries: SearchItem, episodes: Map<Pair<Int, Int>, String>): List<ProgressRow> =
    rows.filter { it.itemType == "episode" && it.seriesId == oldSeriesId && it.season != null && it.episode != null }.mapNotNull { row ->
        val id = episodes[row.season!! to row.episode!!] ?: return@mapNotNull null
        row.copy(itemId = id, seriesId = newSeries.id, seriesCategory = newSeries.categoryIds.minOrNull() ?: row.seriesCategory)
    }
