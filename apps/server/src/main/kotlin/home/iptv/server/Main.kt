package home.iptv.server

import home.iptv.shared.catalog.CatalogDatabase
import home.iptv.shared.catalog.CatalogRepository
import home.iptv.shared.catalog.LiveRefresher
import home.iptv.shared.catalog.XtreamLiveSource
import home.iptv.shared.home.accountFingerprint
import home.iptv.shared.sync.SupabaseSyncClient
import home.iptv.shared.sync.SyncClient
import home.iptv.shared.sync.supabaseClient
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import home.iptv.shared.xtream.EpgSource
import home.iptv.shared.xtream.XtreamApi
import home.iptv.shared.xtream.XtreamCreds
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    val sessions = FileSessionManager(config.dataDir.resolve("session.json"))
    try {
        sessions.verify()
    } catch (e: CorruptSessionException) {
        System.err.println(e.message)
        exitProcess(1)
    }
    val supabase = supabaseClient(config.supabaseUrl, config.supabaseKey, sessions)
    val sync = SupabaseSyncClient(supabase)
    val pairing = Pairing(sync)

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

    // Une fois appairé : HTTPS sur le même port (la page d'attente est arrêtée plus haut).
    val keyStore = loadOrCreateKeyStore(config.dataDir)
    val pin = spkiPin(keyStore.getCertificate(KEY_ALIAS) as java.security.cert.X509Certificate)
    val checker = TokenChecker(
        check = { token -> sync.checkHomeServerToken(household, token) },
        clockMs = { System.currentTimeMillis() },
    )
    val motDePasse = keyStorePassword(config.dataDir)

    // Le Direct : cache SQLite sur le disque du serveur, identifiants IPTV lus dans Supabase à chaque mise à jour (en mémoire seulement).
    val cacheFile = config.dataDir.resolve("catalog.db")
    val driver = JdbcSqliteDriver("jdbc:sqlite:${cacheFile.absolutePath}")
    val version = driver.executeQuery(null, "PRAGMA user_version", { c ->
        QueryResult.Value(if (c.next().value) c.getLong(0) ?: 0L else 0L)
    }, 0).value
    if (version == 0L) CatalogDatabase.Schema.create(driver)
    else if (version < CatalogDatabase.Schema.version) CatalogDatabase.Schema.migrate(driver, version, CatalogDatabase.Schema.version)
    driver.execute(null, "PRAGMA user_version = ${CatalogDatabase.Schema.version}", 0)
    val repo = CatalogRepository(CatalogDatabase(driver))
    val http = HttpClient(OkHttp)
    val creds = AtomicReference<XtreamCreds?>(null)
    fun api() = XtreamApi(http, checkNotNull(creds.get()) { "identifiants absents" })
    val refresher = LiveRefresher(
        sync = sync,
        repo = repo,
        householdId = { household },
        userInfo = { api().userInfo() },
        source = { XtreamLiveSource(api()) },
    )
    val liveJob = LiveJob(
        refresher = refresher,
        repo = repo,
        sync = sync,
        householdId = household,
        account = { creds.get()?.let { accountFingerprint(it.server, it.username) } },
        clockMs = { System.currentTimeMillis() },
        file = config.dataDir.resolve("live-snapshot.json"),
        beforeRun = { creds.set(checkNotNull(sync.credentials(household)) { "identifiants IPTV absents" }) },
    )
    // Guide partagé par la maison ; mêmes identifiants (en mémoire) que le Direct.
    val epgCache = EpgCache(EpgSource.of(::api), { System.currentTimeMillis() })
    val https = embeddedServer(
        Netty,
        applicationEnvironment { },
        configure = {
            sslConnector(
                keyStore = keyStore,
                keyAlias = KEY_ALIAS,
                keyStorePassword = { motDePasse },
                privateKeyPassword = { motDePasse },
            ) { port = config.port }
        },
        module = { homeModule(checker, SERVER_VERSION) {
            liveRoute { liveJob.snapshot.value }
            epgRoute(epgCache)
        } },
    )
    https.start(wait = false)
    println("Serveur HTTPS démarré sur le port ${config.port}.")
    runBlocking {
        launch { liveJob.loop() }
        registerEvery10Minutes(sync, household, config.lanUrl, pin)
    }
}

const val SERVER_VERSION = "0.1.0"

/** Annonce l'adresse et l'empreinte au foyer maintenant puis toutes les 10 min (les erreurs passagères sont tolérées). */
private suspend fun registerEvery10Minutes(sync: SyncClient, household: String, lanUrl: String, pin: String) {
    while (true) {
        try {
            sync.registerHomeServer(household, lanUrl, pin, SERVER_VERSION)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            System.err.println("Enregistrement impossible pour l'instant (${e::class.simpleName}), nouvel essai dans 10 min.")
        }
        delay(10 * 60_000L)
    }
}
