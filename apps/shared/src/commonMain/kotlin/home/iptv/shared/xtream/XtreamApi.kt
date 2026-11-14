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

    private fun items(root: JsonElement): List<JsonObject> =
        (root as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

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
        if (status !in 200..299) throw XtreamError.Unexpected(status)
        return try {
            Json.parseToJsonElement(body)
        } catch (e: Exception) {
            throw XtreamError.Unexpected(status)
        }
    }
}
