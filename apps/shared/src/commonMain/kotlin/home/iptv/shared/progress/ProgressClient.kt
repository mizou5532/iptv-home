package home.iptv.shared.progress

import home.iptv.shared.sync.requireAuthenticated
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Instant

/**
 * Position d'un épisode. Aucune adresse de flux ni identifiant IPTV : l'identifiant de l'épisode suffit,
 * l'adresse se recompose à la lecture. [seriesCover] est une affiche (image), jamais un flux.
 */
@Serializable
data class ProgressEntry(
    val itemId: String,
    val seriesId: String?,
    val season: Int?,
    val episode: Int?,
    val positionS: Int,
    val durationS: Int,
    val clientUpdatedAt: Instant,
    val seriesName: String? = null,
    val seriesCover: String? = null,
    val seriesCategory: String? = null,
)

/** Ligne de `continue_watching` (table `watch_progress`). */
@Serializable
data class ProgressRow(
    @SerialName("item_id") val itemId: String,
    @SerialName("item_type") val itemType: String = "episode",
    @SerialName("series_id") val seriesId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    @SerialName("position_s") val positionS: Int,
    @SerialName("duration_s") val durationS: Int,
    val watched: Boolean = false,
    @SerialName("client_updated_at") val clientUpdatedAt: Instant,
    @SerialName("series_name") val seriesName: String? = null,
    @SerialName("series_cover") val seriesCover: String? = null,
    @SerialName("series_category") val seriesCategory: String? = null,
)

/** Épisode à marquer vu / non vu (`set_watched`). */
data class WatchedItem(
    val itemId: String,
    val seriesId: String,
    val season: Int?,
    val episode: Int?,
    val durationS: Int?,
    val seriesName: String? = null,
    val seriesCover: String? = null,
    val seriesCategory: String? = null,
)

interface ProgressClient {
    /** `true` si le serveur a écrit la ligne, `false` s'il garde une position plus récente d'un autre appareil. */
    suspend fun record(p: String, entry: ProgressEntry): Boolean
    suspend fun continueWatching(p: String, max: Int = 20): List<ProgressRow>
    suspend fun setWatched(p: String, items: List<WatchedItem>, watched: Boolean)
    suspend fun setSeriesList(p: String, seriesId: String, on: Boolean)
}

class SupabaseProgressClient(private val supabase: SupabaseClient) : ProgressClient {

    /**
     * `record_progress` (version à 12 arguments) n'a AUCUNE valeur par défaut : toutes les clés doivent être
     * envoyées, avec un `null` explicite pour l'inconnu, sinon PostgREST retombe sur l'ancienne signature
     * et le nom, l'affiche et la catégorie sont perdus sans erreur.
     */
    override suspend fun record(p: String, entry: ProgressEntry): Boolean {
        supabase.requireAuthenticated()
        return supabase.postgrest.rpc("record_progress", recordBody(p, entry)).decodeAs<Boolean>()
    }

    override suspend fun continueWatching(p: String, max: Int): List<ProgressRow> {
        supabase.requireAuthenticated()
        return supabase.postgrest
            .rpc("continue_watching", buildJsonObject { put("p", p); put("max_rows", max) })
            .decodeList<ProgressRow>()
    }

    override suspend fun setWatched(p: String, items: List<WatchedItem>, watched: Boolean) {
        if (items.isEmpty()) return
        supabase.requireAuthenticated()
        supabase.postgrest.rpc("set_watched", buildJsonObject {
            put("p", p)
            put("items", JsonArray(items.map(::watchedJson)))
            put("watched", watched)
        })
    }

    override suspend fun setSeriesList(p: String, seriesId: String, on: Boolean) {
        supabase.requireAuthenticated()
        supabase.postgrest.rpc("org_set_series_list", buildJsonObject {
            put("p", p)
            put("series_id", seriesId)
            put("on_", on)
        })
    }

    internal companion object {
        fun recordBody(p: String, e: ProgressEntry): JsonObject = buildJsonObject {
            put("p", p)
            put("item_type", "episode")
            put("item_id", e.itemId)
            put("position_s", e.positionS)
            put("duration_s", e.durationS)
            put("client_updated_at", e.clientUpdatedAt.toString())
            put("series_id", e.seriesId)
            put("season", e.season)
            put("episode", e.episode)
            put("series_name", e.seriesName)
            put("series_cover", cleanSeriesCover(e.seriesCover))
            put("series_category", e.seriesCategory)
        }

        private fun watchedJson(i: WatchedItem): JsonObject = buildJsonObject {
            put("item_id", i.itemId)
            put("series_id", i.seriesId)
            put("season", i.season)
            put("episode", i.episode)
            put("duration_s", i.durationS)
            put("series_name", i.seriesName)
            put("series_cover", cleanSeriesCover(i.seriesCover))
            put("series_category", i.seriesCategory)
        }
    }
}
