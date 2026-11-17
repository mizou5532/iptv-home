package home.iptv.shared.meta

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Film ou série, tel que le fournisseur le nomme (le serveur nettoie le nom : « FR| The Batman (2022) »). */
enum class MediaKind(val wire: String) { MOVIE("movie"), SERIES("series") }

data class MediaRef(val kind: MediaKind, val title: String, val year: String? = null) {
    val key: String get() = "${kind.wire}|$title|${year.orEmpty()}"
}

/**
 * Fiche TMDB d'un titre (fonction serveur media-meta) : images (chemins TMDB), titre et résumé français, note IMDb.
 * [status] : « ok », « not_found » (rien de sûr trouvé : on garde les images du fournisseur) ou « pending ».
 */
@Serializable
data class MediaMeta(
    val status: String,
    val title: String? = null,
    val overview: String? = null,
    val year: Int? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    @SerialName("imdb_rating") val imdbRating: Double? = null,
    @SerialName("tmdb_id") val tmdbId: Int? = null,
    /** Heure de réception (ms) : la fiche est redemandée après un moment (note à jour). */
    val at: Long = 0,
) {
    val found: Boolean get() = status == "ok"
}

/** Adresse d'une image TMDB à la taille voulue (« w780 », « w1280 », « w500 »…). */
fun tmdbImage(path: String?, size: String): String? = path?.takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/$size$it" }

/** « IMDb 8,4 ». */
fun imdbLabel(rating: Double?): String? = rating?.takeIf { it > 0 }?.let { "IMDb " + (kotlin.math.round(it * 10) / 10).toString().replace('.', ',') }


private val metaJson = Json { ignoreUnknownKeys = true; explicitNulls = false }
private val metaMap = MapSerializer(String.serializer(), MediaMeta.serializer())

fun encodeMetaMap(all: Map<String, MediaMeta>): String = metaJson.encodeToString(metaMap, all)
fun decodeMetaMap(text: String): Map<String, MediaMeta> = metaJson.decodeFromString(metaMap, text)

@Serializable private data class Ask(val kind: String, val title: String, val year: String? = null)
@Serializable private data class AskBody(val items: List<Ask>)
@Serializable private data class Answer(val items: List<MediaMeta?> = emptyList())

/** Corps de la demande à media-meta. */
fun metaRequestBody(refs: List<MediaRef>): String = metaJson.encodeToString(AskBody.serializer(), AskBody(refs.map { Ask(it.kind.wire, it.title, it.year) }))
/** Réponse de media-meta, alignée sur la demande. */
fun parseMetaAnswer(text: String): List<MediaMeta?> = metaJson.decodeFromString(Answer.serializer(), text).items
