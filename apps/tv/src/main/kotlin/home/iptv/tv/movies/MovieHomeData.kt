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
    fun recent(hidden: Set<String>, limit: Int): List<MovieSummary>
    suspend fun info(movieId: String, categoryId: String): MovieInfo?
}
class RepoMovieCatalog(private val repo: MovieRepository) : MovieCatalog {
    override suspend fun categories() = repo.categories(emptySet())
    override suspend fun moviesOf(categoryId: String, hidden: Set<String>) = repo.moviesOf(categoryId, hidden)
    override fun cachedCategory(movieId: String) = repo.cachedCategory(movieId)
    override fun recent(hidden: Set<String>, limit: Int) = repo.recentlyAdded(hidden, limit)
    override suspend fun info(movieId: String, categoryId: String) = repo.info(movieId, categoryId)
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
