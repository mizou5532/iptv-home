package home.iptv.tv.movies

import android.content.Context
import home.iptv.shared.movies.MovieRepository
import home.iptv.shared.progress.*
import home.iptv.shared.xtream.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

interface MovieCatalog {
    suspend fun categories(): List<MovieCategory>
    suspend fun moviesOf(categoryId: String, hidden: Set<String>): List<MovieSummary>
    fun cachedCategory(movieId: String): String?
    fun featured(hidden: Set<String>): MovieSummary? = null
    fun recent(hidden: Set<String>, limit: Int): List<MovieSummary>
    suspend fun info(movieId: String, categoryId: String): MovieInfo?
}
/** Les demandes de l'écran passent devant le remplissage de la recherche ([filler]), jamais en même temps. */
class RepoMovieCatalog(private val repo: MovieRepository, private val filler: home.iptv.shared.search.CatalogFiller? = null) : MovieCatalog {
    private suspend fun <T> first(block: suspend () -> T): T = filler?.screenFirst(block) ?: block()
    override suspend fun categories() = first { repo.categories(emptySet()) }
    override suspend fun moviesOf(categoryId: String, hidden: Set<String>) = first { repo.moviesOf(categoryId, hidden) }
    override fun cachedCategory(movieId: String) = repo.cachedCategory(movieId)
    override fun featured(hidden: Set<String>) = repo.featuredMovie(hidden)
    override fun recent(hidden: Set<String>, limit: Int) = repo.recentlyAdded(hidden, limit)
    override suspend fun info(movieId: String, categoryId: String) = first { repo.info(movieId, categoryId) }
}
interface MovieHomeState {
    fun progress(profile: String): List<ProgressRow>
    fun saveProgress(profile: String, rows: List<ProgressRow>)
    fun list(profile: String): List<MovieListItem>
    fun saveList(profile: String, rows: List<MovieListItem>)
    fun published(household: String): Long
    fun savePublished(household: String, at: Long)
}
class AndroidMovieHomeState(context: Context) : MovieHomeState {
    private val prefs = context.applicationContext.getSharedPreferences("iptv_home_movies", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    override fun progress(profile: String) = runCatching { json.decodeFromString(ListSerializer(ProgressRow.serializer()), prefs.getString("progress:$profile", "[]")!!) }.getOrDefault(emptyList())
    override fun saveProgress(profile: String, rows: List<ProgressRow>) { prefs.edit().putString("progress:$profile", json.encodeToString(ListSerializer(ProgressRow.serializer()), rows)).apply() }
    override fun list(profile: String) = runCatching { json.decodeFromString(ListSerializer(MovieListItem.serializer()), prefs.getString("list:$profile", "[]")!!) }.getOrDefault(emptyList())
    override fun saveList(profile: String, rows: List<MovieListItem>) { prefs.edit().putString("list:$profile", json.encodeToString(ListSerializer(MovieListItem.serializer()), rows)).apply() }
    override fun published(household: String) = prefs.getLong("published:$household", 0)
    override fun savePublished(household: String, at: Long) { prefs.edit().putLong("published:$household", at).apply() }
}
fun queuedMovieRows(queue: List<QueuedProgress>, profile: String): List<ProgressRow> = queue.filter { it.profileId == profile && it.entry.itemType == "movie" }.map { q -> q.entry.let { ProgressRow(itemId = it.itemId, itemType = "movie", positionS = it.positionS, durationS = it.durationS, watched = it.durationS > 0 && it.positionS.toLong()*10 >= it.durationS.toLong()*9, clientUpdatedAt = it.clientUpdatedAt, movieName = it.movieName, movieCover = it.movieCover, movieCategory = it.movieCategory) } }

/** Une reprise d'un film vu garde « vu », comme record_movie_progress ; une action non vu remplace la copie durable. */
fun mergeMovieProgress(known: List<ProgressRow>, queued: List<ProgressRow>): List<ProgressRow> {
    val remote = known.filter { it.itemType == "movie" }.groupBy { it.itemId }.mapValues { (_, rows) -> rows.maxBy { it.clientUpdatedAt } }
    return (known + queued).filter { it.itemType == "movie" }.groupBy { it.itemId }.map { (id, rows) ->
        val latest = rows.maxBy { it.clientUpdatedAt }
        val pending = queued.any { it.itemType == "movie" && it.itemId == id && it.clientUpdatedAt >= latest.clientUpdatedAt }
        latest.copy(watched = latest.watched || pending && remote[id]?.watched == true,
            movieName = latest.movieName ?: rows.firstNotNullOfOrNull { it.movieName },
            movieCover = latest.movieCover ?: rows.firstNotNullOfOrNull { it.movieCover },
            movieCategory = latest.movieCategory ?: rows.firstNotNullOfOrNull { it.movieCategory })
    }
}
