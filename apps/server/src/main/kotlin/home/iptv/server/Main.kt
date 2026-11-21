package home.iptv.server

import home.iptv.shared.sync.SupabaseSyncClient
import home.iptv.shared.sync.supabaseClient
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

fun main() {
    val config = try {
        Config.fromEnv(System.getenv())
    } catch (e: IllegalStateException) {
        System.err.println(e.message)
        exitProcess(1)
    }
    config.dataDir.mkdirs()
    val supabase = supabaseClient(config.supabaseUrl, config.supabaseKey, FileSessionManager(config.dataDir.resolve("session.json")))
    val pairing = Pairing(SupabaseSyncClient(supabase))

    val code = AtomicReference<String?>(null)
    // Page d'attente en HTTP simple (le TLS viendra avec le reste du serveur).
    val attente = embeddedServer(Netty, port = config.port) { pairingRoute { code.get() } }
    attente.start(wait = false)
    val household = try {
        runBlocking {
            pairing.awaitHousehold { c ->
                code.set(c)
                println("Code d'appairage : $c")
            }
        }
    } finally {
        attente.stop(500, 1_000)
    }
    println("Serveur maison appairé au foyer $household.")
}
