package home.iptv.server

import home.iptv.shared.home.HOME_PROTOCOL
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.gzip
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

    // Ordre d'accès (LRU) ; clé = SHA-256 du jeton, pour ne pas garder les jetons en clair en mémoire.
    private val cache = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private val mutex = Mutex()

    private fun cle(token: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }

    suspend fun deviceFor(token: String): String? {
        val key = cle(token)
        val now = clockMs()
        mutex.withLock {
            cache[key]?.let { if (now < it.expiresAt) return it.device }
        }
        val device = check(token) // une erreur réseau remonte telle quelle et n'est pas mise en cache
        mutex.withLock {
            cache.remove(key)
            cache.values.removeAll { now >= it.expiresAt }
            if (cache.size >= maxEntries) {
                // Les refus partent d'abord (le plus ancien d'abord) : une rafale de faux jetons n'évince jamais un bon jeton.
                val refus = cache.entries.firstOrNull { it.value.device == null }
                if (refus != null) cache.remove(refus.key)
                else if (device == null) return device // plein de bons jetons : le refus n'est pas mis en cache
                else cache.remove(cache.keys.first())
            }
            cache[key] = Entry(device, now + ttlMs)
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

/** La racine et /health sont publics (rien du foyer), tout /v1 est protégé. [v1] déclare les routes protégées : toute route protégée doit être déclarée dans ce bloc /v1, jamais en dehors. */
fun Application.homeModule(checker: TokenChecker, version: String, v1: Route.() -> Unit = {}) {
    install(Compression) { gzip() }
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
