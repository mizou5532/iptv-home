package home.iptv.server

import home.iptv.shared.home.LiveSnapshot
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
