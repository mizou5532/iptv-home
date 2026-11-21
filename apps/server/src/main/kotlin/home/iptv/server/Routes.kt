package home.iptv.server

import home.iptv.shared.home.EpgReply
import home.iptv.shared.home.LiveSnapshot
import home.iptv.shared.home.SnapEpg
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /v1/live?since=<rev>` : 304 si la télé a déjà cette version, 503 si aucune liste n'existe encore, sinon la liste
 * en JSON (compressée par le plugin Compression). À déclarer dans le bloc /v1 (protégé).
 */
fun Route.liveRoute(snapshot: () -> LiveSnapshot?) {
    // La liste est longue : on garde son texte JSON tant que sa version ne change pas.
    val cache = java.util.concurrent.atomic.AtomicReference<Pair<String, String>?>(null)
    get("/live") {
        val s = snapshot()
        when {
            s == null -> call.respond(HttpStatusCode.ServiceUnavailable)
            call.request.queryParameters["since"] == s.rev -> call.respond(HttpStatusCode.NotModified)
            else -> {
                val c = cache.get()
                val texte = if (c != null && c.first == s.rev) c.second else {
                    snapshotJson.encodeToString(LiveSnapshot.serializer(), s).also { cache.set(s.rev to it) }
                }
                call.respondText(texte, ContentType.Application.Json)
            }
        }
    }
}

/**
 * `GET /v1/epg/{streamId}?limit=8&full=0|1` : guide d'une chaîne, partagé par la maison. 400 si l'identifiant ou les
 * paramètres sont invalides, 502 si le fournisseur échoue (jamais de détail : une exception Xtream peut contenir une
 * adresse avec identifiants, on ne journalise que le nom de sa classe). À déclarer dans le bloc /v1 (protégé).
 */
fun Route.epgRoute(cache: EpgCache) {
    get("/epg/{streamId}") {
        val id = call.parameters["streamId"].orEmpty()
        val limitText = call.request.queryParameters["limit"]
        val fullText = call.request.queryParameters["full"]
        val limit = if (limitText == null) 8 else limitText.takeIf { it.length <= 4 && it.all(Char::isDigit) }?.toIntOrNull()
        val full = when (fullText) { null, "0" -> false; "1" -> true; else -> null }
        if (id.isEmpty() || id.length > 32 || !id.all { it.isLetterOrDigit() || it == '_' || it == '-' } ||
            limit == null || limit < 1 || full == null
        ) {
            call.respond(HttpStatusCode.BadRequest)
            return@get
        }
        val entries = try {
            cache.entries(id, limit, full)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            System.err.println("Guide indisponible (${e::class.simpleName}).")
            call.respond(HttpStatusCode.BadGateway)
            return@get
        }
        val reply = EpgReply(entries.map { SnapEpg(it.title, it.start.toEpochMilliseconds(), it.end.toEpochMilliseconds(), it.nowPlaying) })
        call.respondText(snapshotJson.encodeToString(EpgReply.serializer(), reply), ContentType.Application.Json)
    }
}
