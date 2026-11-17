package home.iptv.tv.movies

import home.iptv.shared.progress.*
import home.iptv.shared.sync.ProfilePrefs
import home.iptv.shared.xtream.*
import home.iptv.tv.series.CategoryRow

const val MOVIE_ROW_LIMIT = 20

data class MovieCard(val movieId: String, val categoryId: String, val title: String, val cover: String?, val positionS: Int = 0, val durationS: Int = 0)
data class MovieHome(val resume: MovieCard?, val continueRow: List<MovieCard>, val myList: List<MovieCard>, val recent: List<MovieCard>, val categoryRows: List<CategoryRow>) {
    companion object { val EMPTY = MovieHome(null, emptyList(), emptyList(), emptyList(), emptyList()) }
}

fun ProfilePrefs.movieHidden(): Set<String> = hidden.filter { it.itemType == "vod_category" }.mapTo(HashSet()) { it.itemId }
fun MovieSummary.card() = MovieCard(id, categoryId, name, cover)

fun buildMovieHome(progress: List<ProgressRow>, list: List<MovieListItem>, categories: List<MovieCategory>, prefs: ProfilePrefs?, cached: List<MovieSummary>, nowS: Long = System.currentTimeMillis() / 1000): MovieHome {
    if (prefs == null) return MovieHome.EMPTY
    val hidden = prefs.movieHidden()
    val known = cached.associateBy { it.id }
    fun category(id: String, reference: String?): String? = reference?.takeIf { it.isNotBlank() } ?: known[id]?.categoryId?.takeIf { it.isNotBlank() }
    fun visible(id: String, reference: String?): Boolean = category(id, reference)?.let { it !in hidden } == true && known[id]?.categoryId !in hidden
    val continuing = progress.filter { it.itemType == "movie" }.groupBy { it.itemId }.values.map { rows -> rows.maxBy { it.clientUpdatedAt } }
        .sortedByDescending { it.clientUpdatedAt }.filter { !it.watched && it.positionS > 0 && !(it.durationS > 0 && it.positionS.toLong() * 10 >= it.durationS.toLong() * 9) && visible(it.itemId, it.movieCategory) }
        .take(MOVIE_ROW_LIMIT).map { MovieCard(it.itemId, category(it.itemId, it.movieCategory)!!, it.movieName ?: known[it.itemId]?.name ?: "Film", it.movieCover ?: known[it.itemId]?.cover, it.positionS, it.durationS) }
    val mine = list.distinctBy { it.itemId }.filter { visible(it.itemId, it.movieCategory) }.take(MOVIE_ROW_LIMIT).map { MovieCard(it.itemId, category(it.itemId, it.movieCategory)!!, it.movieName ?: known[it.itemId]?.name ?: "Film", it.movieCover ?: known[it.itemId]?.cover) }
    val recent = cached.filter { it.categoryId.isNotBlank() && it.categoryId !in hidden && it.addedAt?.let { at -> at > 0 && at <= nowS + 86400 } == true }.sortedByDescending { it.addedAt }.distinctBy { it.id }.take(MOVIE_ROW_LIMIT).map { it.card() }
    val cats = categories.filter { it.id.isNotBlank() && it.id !in hidden }.distinctBy { it.id }.associateBy { it.id }
    val ordered = prefs.movieOrder.orEmpty().mapNotNull { cats[it] }.distinctBy { it.id }
    val ids = ordered.mapTo(HashSet()) { it.id }
    return MovieHome(continuing.firstOrNull() ?: recent.firstOrNull() ?: mine.firstOrNull(), continuing, mine, recent, (ordered + cats.values.filter { it.id !in ids }).map { CategoryRow(it.id, it.name) })
}

/**
 * Une carte par titre (versions en d'autres langues ou en 4K réunies) : « Reprendre » garde la plus récente, Ma liste
 * et les ajouts la meilleure version du profil ; « Seulement mes langues » retire des ajouts les autres langues.
 */
fun MovieHome.groupVersions(index: home.iptv.shared.versions.VersionIndex, prefs: home.iptv.shared.versions.LanguagePrefs): MovieHome {
    if (index.groups.isEmpty()) return this
    val cont = continueRow.distinctBy { index.keyOf(it.movieId) ?: "#" + it.movieId }
    val mine = home.iptv.shared.versions.collapseVersions(myList, index, prefs) { it.movieId }
    val added = home.iptv.shared.versions.collapseVersions(home.iptv.shared.versions.hideOtherLanguages(recent, index, prefs) { it.movieId }, index, prefs) { it.movieId }
    return copy(continueRow = cont, myList = mine, recent = added, resume = resume?.let { r -> cont.firstOrNull { index.same(it.movieId, r.movieId) } ?: r })
}
