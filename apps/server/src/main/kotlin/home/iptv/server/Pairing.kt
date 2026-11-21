package home.iptv.server

import home.iptv.shared.sync.SyncClient
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.Instant

/** Appairage du serveur maison au foyer : il rejoint comme un appareil, avec un code saisi sur l'iPhone. */
class Pairing(
    private val sync: SyncClient,
    private val deviceName: String = "Serveur maison",
    private val pollMs: Long = 5_000,
    private val now: () -> Instant = { Clock.System.now() },
    private val log: (String) -> Unit = { System.err.println(it) },
) {
    /**
     * Rend l'identifiant du foyer. [onCode] est appelé une fois par code : au départ si l'appairage reste à faire,
     * puis à chaque renouvellement d'un code périmé.
     */
    suspend fun awaitHousehold(onCode: (String) -> Unit): String {
        sync.ensureSignedIn()
        sync.currentHousehold()?.let { return it }
        var waiting = sync.createPairingCode(deviceName)
        onCode(waiting.code)
        while (true) {
            delay(pollMs)
            val household = try {
                sync.currentHousehold()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Coupure réseau passagère : on continue d'attendre.
                log("Appairage : vérification impossible pour l'instant (${e::class.simpleName}), nouvel essai.")
                null
            }
            if (household != null) return household
            if (now() >= waiting.expiresAt) {
                try {
                    waiting = sync.createPairingCode(deviceName)
                    onCode(waiting.code)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("Appairage : renouvellement du code impossible pour l'instant (${e::class.simpleName}), nouvel essai.")
                }
            }
        }
    }
}

/** Page d'attente, rendue côté serveur : le code en grand et la marche à suivre. */
fun pairingPageHtml(code: String): String {
    val sur = code.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    return """<!doctype html>
<html lang="fr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Serveur maison : appairage</title>
<style>body{font-family:sans-serif;text-align:center;margin:3em 1em}.code{font-size:4em;letter-spacing:.2em;font-weight:bold}</style>
</head><body>
<h1>Serveur maison</h1>
<p>Ouvrez la page de configuration sur votre iPhone, Ajouter un appareil, tapez ce code :</p>
<p class="code">$sur</p>
</body></html>
"""
}

/** Route minimale de la page d'appairage (réutilisable telle quelle quand le serveur passera en TLS). */
fun Application.pairingRoute(code: () -> String?) {
    routing {
        get("/") {
            val actuel = code()
            if (actuel == null) call.respondText("Serveur maison déjà appairé.", ContentType.Text.Plain)
            else call.respondText(pairingPageHtml(actuel), ContentType.Text.Html)
        }
    }
}
