package home.iptv.tv.meta

import home.iptv.shared.meta.MediaMeta
import home.iptv.shared.meta.MediaRef
import home.iptv.shared.meta.metaRequestBody
import home.iptv.shared.meta.parseMetaAnswer
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess

/** Appelle la fonction serveur media-meta avec la session de la télé (jeton [token]). */
class MediaMetaClient(private val http: HttpClient, private val baseUrl: String, private val publishableKey: String, private val token: suspend () -> String?) {
    suspend fun fetch(refs: List<MediaRef>): List<MediaMeta?> {
        val t = token() ?: throw java.io.IOException("pas de session")
        val r = http.post("$baseUrl/functions/v1/media-meta") {
            header("Authorization", "Bearer $t")
            header("apikey", publishableKey)
            contentType(ContentType.Application.Json)
            setBody(metaRequestBody(refs))
        }
        if (!r.status.isSuccess()) throw java.io.IOException("media-meta ${r.status.value}")
        return parseMetaAnswer(r.bodyAsText())
    }

    /** Films et séries d'une personne (acteur, réalisateur) d'après son nom ; `null` en cas de panne. */
    suspend fun person(query: String): home.iptv.shared.search.PersonCredits? {
        val t = token() ?: return null
        val r = http.post("$baseUrl/functions/v1/media-meta") {
            header("Authorization", "Bearer $t")
            header("apikey", publishableKey)
            contentType(ContentType.Application.Json)
            setBody(kotlinx.serialization.json.buildJsonObject { put("person", kotlinx.serialization.json.JsonPrimitive(query)) }.toString())
        }
        if (!r.status.isSuccess()) return null
        return home.iptv.shared.search.parsePersonCredits(r.bodyAsText())
    }
}
