package home.iptv.shared.progress

import home.iptv.shared.sync.PublishedCategory
import home.iptv.shared.sync.requireAuthenticated
import home.iptv.shared.xtream.MovieSummary
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Référence de Ma liste, avec sa catégorie connue : aucune requête fournisseur nécessaire pour l'afficher. */
@Serializable
data class MovieListItem(
    @SerialName("item_id") val itemId: String,
    @SerialName("movie_name") val movieName: String? = null,
    @SerialName("movie_cover") val movieCover: String? = null,
    @SerialName("movie_category") val movieCategory: String? = null,
)

interface MovieClient {
    suspend fun publishCategories(h: String, cats: List<PublishedCategory>)
    suspend fun continueWatching(p: String, max: Int = 20): List<ProgressRow>
    /** État de la fiche, films vus compris ; null si aucune progression. */
    suspend fun progress(p: String, id: String): ProgressRow?
    suspend fun list(p: String): List<MovieListItem>
    suspend fun setList(p: String, movie: MovieSummary, on: Boolean)
    suspend fun setWatched(p: String, movie: MovieSummary, watched: Boolean)
}

/** RPC dédiés de la migration 0012. Les métadonnées films n'utilisent jamais les champs series_*. */
class SupabaseMovieClient(private val supabase: SupabaseClient) : MovieClient {
    override suspend fun publishCategories(h: String, cats: List<PublishedCategory>) {
        supabase.requireAuthenticated()
        supabase.postgrest.rpc("publish_movie_categories", buildJsonObject {
            put("h", h)
            put("cats", JsonArray(cats.map { c -> buildJsonObject {
                put("id", c.id); put("name", c.name); put("position", c.position)
            } }))
        })
    }

    override suspend fun continueWatching(p: String, max: Int): List<ProgressRow> {
        supabase.requireAuthenticated()
        return supabase.postgrest.rpc("continue_movies", buildJsonObject {
            put("p", p); put("max_rows", max)
        }).decodeList<ProgressRow>()
    }

    override suspend fun progress(p: String, id: String): ProgressRow? {
        supabase.requireAuthenticated()
        return supabase.postgrest.rpc("movie_progress", buildJsonObject {
            put("p", p); put("item_id", id)
        }).decodeList<ProgressRow>().firstOrNull()
    }

    override suspend fun list(p: String): List<MovieListItem> {
        supabase.requireAuthenticated()
        return supabase.postgrest.rpc("movie_list", buildJsonObject { put("p", p) }).decodeList<MovieListItem>()
    }

    override suspend fun setList(p: String, movie: MovieSummary, on: Boolean) {
        supabase.requireAuthenticated()
        supabase.postgrest.rpc("org_set_movie_list", buildJsonObject {
            put("p", p); put("movie_id", movie.id); put("on_", on)
            put("movie_name", movie.name); put("movie_cover", cleanSeriesCover(movie.cover)); put("movie_category", movie.categoryId.takeIf { it.isNotBlank() })
        })
    }

    override suspend fun setWatched(p: String, movie: MovieSummary, watched: Boolean) {
        supabase.requireAuthenticated()
        supabase.postgrest.rpc("set_movie_watched", buildJsonObject {
            put("p", p); put("item_id", movie.id); put("watched", watched)
            put("movie_name", movie.name); put("movie_cover", cleanSeriesCover(movie.cover)); put("movie_category", movie.categoryId.takeIf { it.isNotBlank() })
            put("duration_s", movie.durationS)
        })
    }
}
