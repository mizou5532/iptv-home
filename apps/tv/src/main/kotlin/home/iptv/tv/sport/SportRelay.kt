package home.iptv.tv.sport

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add

/**
 * Horaires et classements préparés par le serveur de la maison (fonction « sport-horaires ») : une seule demande
 * pour toutes les adresses. `null` (pas de session, pas de maison, panne) : la télé les demande elle-même.
 */
class SportRelay(
    private val http: HttpClient,
    private val baseUrl: String,
    private val publishableKey: String,
    private val token: suspend () -> String?,
    private val householdId: () -> String?,
) {
    suspend fun bodies(urls: List<String>): Map<String, String>? {
        if (urls.isEmpty()) return emptyMap()
        val t = token() ?: return null
        val h = householdId() ?: return null
        val r = http.post("$baseUrl/functions/v1/sport-horaires") {
            header("Authorization", "Bearer $t")
            header("apikey", publishableKey)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("h", h); putJsonArray("urls") { urls.forEach { add(it) } } }.toString())
        }
        if (!r.status.isSuccess()) return null
        return parseRelayAnswer(r.bodyAsText())
    }
}

/** Réponse { "bodies": { adresse: texte } } ; illisible = rien. */
fun parseRelayAnswer(text: String): Map<String, String> = runCatching {
    val bodies = Json.parseToJsonElement(text).jsonObject["bodies"] as? JsonObject ?: return emptyMap()
    bodies.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { k to it } }.toMap()
}.getOrDefault(emptyMap())
