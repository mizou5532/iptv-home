package home.iptv.server

import home.iptv.shared.home.HOME_PROTOCOL
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Vérifie les jetons d'appareil auprès de Supabase, avec un cache court (réponses bonnes et mauvaises)
 * pour ne pas interroger la base à chaque requête. Une révocation joue donc au plus [ttlMs] plus tard.
 */
class TokenChecker(
    private val check: suspend (token: String) -> String?,
    private val ttlMs: Long = 300_000,
    private val clockMs: () -> Long,
    private val maxEntries: Int = 64,
) {
    private class Entry(val device: String?, val expiresAt: Long)

    private val cache = LinkedHashMap<String, Entry>() // ordre d'insertion : le plus ancien part en premier
    private val mutex = Mutex()

    suspend fun deviceFor(token: String): String? {
        val now = clockMs()
        mutex.withLock {
            cache[token]?.let { if (now < it.expiresAt) return it.device }
        }
        val device = check(token) // une erreur réseau remonte telle quelle et n'est pas mise en cache
        mutex.withLock {
            cache.remove(token)
            cache.values.removeAll { now >= it.expiresAt }
            while (cache.size >= maxEntries) cache.remove(cache.keys.first())
            cache[token] = Entry(device, now + ttlMs)
        }
        return device
    }
}

/** Exige jeton et version de protocole sur la route où il est installé. Aucun détail dans les réponses d'erreur. */
fun homeAuthPlugin(checker: TokenChecker) = createRouteScopedPlugin("HomeAuth") {
    onCall { call ->
        val token = call.request.header("Authorization")
            ?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotEmpty() }
        val device = if (token == null) null else try {
            checker.deviceFor(token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Supabase injoignable : on ne laisse rien passer, sans rien dire du jeton.
            call.respond(HttpStatusCode.ServiceUnavailable)
            return@onCall
        }
        when {
            device == null -> call.respond(HttpStatusCode.Unauthorized)
            call.request.header("X-Home-Protocol") != HOME_PROTOCOL.toString() -> call.respond(HttpStatusCode.UpgradeRequired)
        }
    }
}

/** La racine et /health sont publics (rien du foyer), tout /v1 est protégé. [v1] déclare les routes protégées. */
fun Application.homeModule(checker: TokenChecker, version: String, v1: Route.() -> Unit = {}) {
    routing {
        get("/") { call.respondText("Serveur maison déjà appairé.", ContentType.Text.Plain) }
        get("/health") {
            val corps = buildJsonObject { put("ok", true); put("version", version) }
            call.respondText(corps.toString(), ContentType.Application.Json)
        }
        route("/v1") {
            install(homeAuthPlugin(checker))
            v1()
        }
    }
}
