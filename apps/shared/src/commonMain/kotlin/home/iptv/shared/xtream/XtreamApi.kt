package home.iptv.shared.xtream

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.time.Instant

/**
 * Lecture tolérante de l'API Xtream (le format varie selon les panneaux).
 * Les URL complètes (qui contiennent les identifiants) ne sont jamais écrites dans une erreur.
 */
class XtreamApi(
    private val http: HttpClient,
    private val creds: XtreamCreds,
    private val timeoutMs: Long = 15_000,
) {
    suspend fun userInfo(): UserInfo {
        val info = (fetch(null, emptyMap()) as? JsonObject)?.get("user_info") as? JsonObject
            ?: throw XtreamError.Refused
        val auth = when (val a = info["auth"]) {
            is JsonPrimitive -> a.contentOrNull.let { it == "1" || it == "true" }
            else -> false
        }
        if (!auth) throw XtreamError.Refused
        val formats = (info["allowed_output_formats"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
        return UserInfo(true, info["max_connections"].text()?.trim()?.toIntOrNull(), formats)
    }

    suspend fun liveCategories(): List<LiveCategory> =
        items(fetch("get_live_categories", emptyMap())).mapNotNull { o ->
            val id = o["category_id"].text() ?: return@mapNotNull null
            val name = o["category_name"].text() ?: return@mapNotNull null
            LiveCategory(id, name)
        }

    suspend fun liveStreams(categoryId: String): List<LiveStream> =
        items(fetch("get_live_streams", mapOf("category_id" to categoryId))).mapNotNull { o ->
            val id = o["stream_id"].text() ?: return@mapNotNull null
            val name = o["name"].text() ?: return@mapNotNull null
            LiveStream(
                id = id,
                name = name,
                categoryId = o["category_id"].text() ?: categoryId,
                icon = o["stream_icon"].text()?.takeIf { it.isNotBlank() },
                epgChannelId = o["epg_channel_id"].text()?.takeIf { it.isNotBlank() },
                number = o["num"].text()?.trim()?.toIntOrNull(),
            )
        }

    suspend fun seriesCategories(): List<SeriesCategory> =
        items(fetch("get_series_categories", emptyMap())).mapNotNull { o ->
            val id = o["category_id"].text() ?: return@mapNotNull null
            val name = o["category_name"].text() ?: return@mapNotNull null
            SeriesCategory(id, name)
        }

    suspend fun series(categoryId: String): List<SeriesSummary> =
        items(fetch("get_series", mapOf("category_id" to categoryId))).mapNotNull { o ->
            val id = o["series_id"].text()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            summaryOf(id, o, categoryId)
        }

    /**
     * Fiche d'une série. `null` si le fournisseur n'a ni fiche ni épisode (réponse vide).
     * Tolère les saisons absentes ou vides, les durées/extensions/affiches manquantes.
     */
    suspend fun seriesInfo(seriesId: String): SeriesInfo? {
        val root = fetch("get_series_info", mapOf("series_id" to seriesId))
        if (root is JsonNull) return null
        if (root !is JsonObject) {
            if (root is JsonArray && root.isEmpty()) return null
            throw XtreamError.Unexpected(200)
        }
        val info = root["info"] as? JsonObject
        val episodesRaw = root["episodes"]
        val byseason = episodesBySeason(episodesRaw)
        if (info == null && byseason.isEmpty()) return null

        val names = linkedMapOf<Int, String?>()
        (root["seasons"] as? JsonArray)?.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val n = (o["season_number"].text() ?: o["season"].text())?.trim()?.toIntOrNull() ?: return@forEach
            names[n] = o["name"].text()?.takeIf { it.isNotBlank() }
        }
        byseason.keys.forEach { names.putIfAbsent(it, null) }
        val seasons = names.entries.sortedBy { it.key }.map { (n, name) ->
            Season(n, name ?: "Saison $n", byseason[n].orEmpty())
        }
        val summary = summaryOf(seriesId, info ?: JsonObject(emptyMap()), "")
        return SeriesInfo(summary, seasons)
    }

    private fun summaryOf(id: String, o: JsonObject, defaultCategory: String): SeriesSummary {
        val release = o["releaseDate"].text() ?: o["release_date"].text()
        val year = o["year"].text()?.takeIf { it.isNotBlank() }
            ?: release?.trim()?.take(4)?.takeIf { y -> y.length == 4 && y.all { it.isDigit() } }
        return SeriesSummary(
            id = id,
            categoryId = o["category_id"].text()?.takeIf { it.isNotBlank() } ?: defaultCategory,
            name = o["name"].text() ?: o["title"].text() ?: "",
            cover = (o["cover"].text()?.takeIf { it.isNotBlank() } ?: o["movie_image"].text()?.takeIf { it.isNotBlank() }),
            year = year,
            genre = o["genre"].text()?.takeIf { it.isNotBlank() },
            plot = o["plot"].text()?.takeIf { it.isNotBlank() },
            rating = o["rating"].text()?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * `episodes` : objet indexé par numéro de saison, ou tableau (de saisons, ou à plat avec un champ `season`).
     * Les épisodes sans identifiant sont ignorés.
     */
    private fun episodesBySeason(raw: JsonElement?): Map<Int, List<Episode>> {
        val result = sortedMapOf<Int, List<Episode>>()
        fun parse(season: Int, arr: JsonArray) {
            val list = arr.mapIndexedNotNull { i, el ->
                val o = el as? JsonObject ?: return@mapIndexedNotNull null
                episodeOf(o, season, i + 1)
            }
            result[season] = result[season].orEmpty() + list
        }
        when (raw) {
            is JsonObject -> raw.forEach { (key, value) ->
                val n = key.trim().toIntOrNull() ?: return@forEach
                (value as? JsonArray)?.let { parse(n, it) }
            }
            is JsonArray -> raw.forEachIndexed { index, el ->
                when (el) {
                    is JsonArray -> parse(index + 1, el)
                    is JsonObject -> {
                        val n = el["season"].text()?.trim()?.toIntOrNull() ?: 1
                        episodeOf(el, n, index + 1)?.let { result[n] = result[n].orEmpty() + it }
                    }
                    else -> Unit
                }
            }
            else -> Unit
        }
        return result
    }

    private fun episodeOf(o: JsonObject, season: Int, fallbackNumber: Int): Episode? {
        val id = o["id"].text()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val number = o["episode_num"].text()?.trim()?.toIntOrNull() ?: fallbackNumber
        val info = o["info"] as? JsonObject
        val duration = info?.get("duration_secs").text()?.trim()?.toIntOrNull()
            ?: info?.get("duration").text()?.let(::parseDuration)
        return Episode(
            id = id,
            season = season,
            number = number,
            title = o["title"].text()?.takeIf { it.isNotBlank() } ?: "Episode $number",
            durationS = duration?.takeIf { it > 0 },
            extension = o["container_extension"].text()?.trim()?.trimStart('.')?.takeIf { it.isNotEmpty() } ?: "mkv",
            cover = info?.get("movie_image").text()?.takeIf { it.isNotBlank() },
        )
    }

    /** "HH:MM:SS" ou "MM:SS" -> secondes ; toute autre forme -> null. */
    private fun parseDuration(text: String): Int? {
        val parts = text.trim().split(":").map { it.toIntOrNull() ?: return null }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            else -> null
        }
    }

    suspend fun shortEpg(streamId: String, limit: Int = 2): List<EpgEntry> {
        val root = fetch("get_short_epg", mapOf("stream_id" to streamId, "limit" to limit.toString()))
        val listings = (root as? JsonObject)?.get("epg_listings") as? JsonArray ?: return emptyList()
        return listings.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val title = decodeTitle(o["title"].text() ?: return@mapNotNull null) ?: return@mapNotNull null
            val start = o["start_timestamp"].text()?.trim()?.toLongOrNull() ?: return@mapNotNull null
            val end = o["stop_timestamp"].text()?.trim()?.toLongOrNull() ?: return@mapNotNull null
            EpgEntry(title, Instant.fromEpochSeconds(start), Instant.fromEpochSeconds(end))
        }
    }

    private fun decodeTitle(b64: String): String? = try {
        Base64.Default.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)
            .decode(b64.trim()).decodeToString(throwOnInvalidSequence = true)
    } catch (e: IllegalArgumentException) {
        null
    }

    /** Tableau, ou objet à clés numériques (`{"0":{...}}`) ; toute autre forme est inattendue. */
    private fun items(root: JsonElement): List<JsonObject> = when {
        root is JsonArray -> root.mapNotNull { it as? JsonObject }
        root is JsonObject && root.isNotEmpty() && root.keys.all { it.toIntOrNull() != null } ->
            root.values.mapNotNull { it as? JsonObject }
        else -> throw XtreamError.Unexpected(200)
    }

    /** Nombre ou chaîne -> texte ; absent ou `null` -> null. */
    private fun JsonElement?.text(): String? =
        (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private suspend fun fetch(action: String?, params: Map<String, String>): JsonElement {
        val url = apiUrl(creds, action, params)
        val result: Pair<Int, String> = try {
            withTimeout(timeoutMs) {
                val response = http.get(url)
                response.status.value to response.bodyAsText()
            }
        } catch (e: TimeoutCancellationException) {
            throw XtreamError.Unreachable
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Le message d'origine peut contenir l'URL : on ne le propage pas.
            throw XtreamError.Unreachable
        }
        val (status, body) = result
        if (status == 401 || status == 403) throw XtreamError.Refused
        if (status !in 200..299) throw XtreamError.Unexpected(status)
        if (body.isBlank()) return JsonNull
        return try {
            Json.parseToJsonElement(body)
        } catch (e: Exception) {
            throw XtreamError.Unexpected(status)
        }
    }
}
