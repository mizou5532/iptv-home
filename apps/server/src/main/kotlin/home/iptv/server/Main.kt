package home.iptv.server

import home.iptv.shared.account.AccountProbe
import home.iptv.shared.account.ExtraAccountsCheck
import home.iptv.shared.catalog.CatalogDatabase
import home.iptv.shared.catalog.CatalogRepository
import home.iptv.shared.catalog.LiveRefresher
import home.iptv.shared.catalog.XtreamLiveSource
import home.iptv.shared.groups.GroupsClient
import home.iptv.shared.home.accountFingerprint
import home.iptv.shared.movies.MovieRepository
import home.iptv.shared.movies.MovieSource
import home.iptv.shared.progress.SupabaseMovieClient
import home.iptv.shared.series.SeriesRepository
import home.iptv.shared.series.SeriesSource
import home.iptv.shared.sync.SupabaseSyncClient
import home.iptv.shared.sync.SyncClient
import home.iptv.shared.sync.supabaseClient
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import home.iptv.shared.xtream.EpgSource
import home.iptv.shared.xtream.MovieCategory
import home.iptv.shared.xtream.MovieInfo
import home.iptv.shared.xtream.MovieSummary
import home.iptv.shared.xtream.SeriesCategory
import home.iptv.shared.xtream.SeriesInfo
import home.iptv.shared.xtream.SeriesSummary
import home.iptv.shared.xtream.XtreamApi
import home.iptv.shared.xtream.XtreamCreds
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
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
    val db = CatalogDatabase(driver)
    val repo = CatalogRepository(db)
    val http = HttpClient(OkHttp)
    val creds = AtomicReference<XtreamCreds?>(null)
    val credsLock = kotlinx.coroutines.sync.Mutex()
    fun api() = XtreamApi(http, checkNotNull(creds.get()) { "identifiants absents" })
    suspend fun refreshCreds() = credsLock.withLock {
        creds.set(checkNotNull(sync.credentials(household)) { "identifiants IPTV absents" })
    }
    suspend fun ensureCreds() {
        if (creds.get() != null) return
        credsLock.withLock {
            if (creds.get() == null) creds.set(checkNotNull(sync.credentials(household)) { "identifiants IPTV absents" })
        }
    }
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
        beforeRun = ::refreshCreds,
    )
    // Films, séries, versions, publication iPhone et comptes supplémentaires.
    val movieSource = object : MovieSource {
        override suspend fun movieCategories(): List<MovieCategory> = api().movieCategories()
        override suspend fun movies(categoryId: String): List<MovieSummary> = api().movies(categoryId)
        override suspend fun movieInfo(movieId: String): MovieInfo? = api().movieInfo(movieId)
    }
    val seriesSource = object : SeriesSource {
        override suspend fun seriesCategories(): List<SeriesCategory> = api().seriesCategories()
        override suspend fun series(categoryId: String): List<SeriesSummary> = api().series(categoryId)
        override suspend fun seriesInfo(seriesId: String): SeriesInfo? = api().seriesInfo(seriesId)
    }
    val movieRepo = MovieRepository(db, movieSource)
    val seriesRepo = SeriesRepository(db, seriesSource)
    val movieClient = SupabaseMovieClient(supabase)
    val extraProbe = object : AccountProbe {
        override suspend fun userInfo(creds: XtreamCreds) = XtreamApi(http, creds).userInfo()
        override suspend fun liveCategories(creds: XtreamCreds) = XtreamApi(http, creds).liveCategories()
        override suspend fun liveStreams(creds: XtreamCreds, categoryId: String) = XtreamApi(http, creds).liveStreams(categoryId)
        override suspend fun movieCategoryIds(creds: XtreamCreds) = XtreamApi(http, creds).movieCategories().map { it.id }.toSet()
        override suspend fun seriesCategoryIds(creds: XtreamCreds) = XtreamApi(http, creds).seriesCategories().map { it.id }.toSet()
    }
    val extraAccountsCheck = ExtraAccountsCheck(
        probe = extraProbe,
        main = { creds.get() },
        accounts = { sync.extraAccounts(household) },
        hiddenForEveryone = {
            sync.globalHiddenCategories(household).filter { it.itemType == "live_category" }.map { it.itemId }.toSet()
        },
        report = { id, v -> sync.reportExtraAccount(household, id, v.ok, v.reason, v.expiresAt) },
    )
    val vodJob = VodJob(
        movies = movieRepo,
        series = seriesRepo,
        sync = sync,
        publishMovieCategories = movieClient::publishCategories,
        householdId = household,
        account = { creds.get()?.let { accountFingerprint(it.server, it.username) } },
        serverHost = { creds.get()?.server },
        clockMs = { System.currentTimeMillis() },
        manifestFile = config.dataDir.resolve("vod-manifests.json"),
        titlesStateFile = config.dataDir.resolve("titles-published.json"),
        extraAccountsCheck = extraAccountsCheck,
        beforeRun = ::refreshCreds,
    )
    val infoCache = InfoCache(
        movies = movieRepo,
        series = seriesRepo,
        hiddenMovieCats = { vodJob.hiddenMovieCategories.value },
        hiddenSeriesCats = { vodJob.hiddenSeriesCategories.value },
        beforeFetch = ::ensureCreds,
    )
    // Groupes manuels du Direct (iPhone) + moteur de recherche partagé (étape 3).
    val groupsCache = ServerGroupsCache(GroupsClient(supabase), household)
    val searchService = SearchService(
        liveRepo = repo,
        moviesRepo = movieRepo,
        seriesRepo = seriesRepo,
        liveSnapshot = { liveJob.snapshot.value },
        movieManifest = { vodJob.movieManifest.value },
        seriesManifest = { vodJob.seriesManifest.value },
        hiddenMovies = { vodJob.hiddenMovieCategories.value },
        hiddenSeries = { vodJob.hiddenSeriesCategories.value },
        hiddenLoaded = { vodJob.hiddenLoaded },
        splits = { vodJob.currentSplits() },
        splitsVersion = { vodJob.splitsVersion() },
        manualGroups = { groupsCache.snapshot.value },
        showcaseMovies = { vodJob.currentShowcaseMovies() },
        showcaseSeries = { vodJob.currentShowcaseSeries() },
    )
    // Guide partagé par la maison ; mêmes identifiants (en mémoire) que le Direct, chargés à la demande si le conteneur
    // vient de redémarrer avec une liste de moins de 6 h sur le disque.
    val epgCache = EpgCache(EpgSource.of(::api), { System.currentTimeMillis() }, beforeFetch = ::ensureCreds)
    // Mémoire partagée des versions retenues (films et séries) après « Essayer une autre version ».
    val versionMemoryStore = VersionMemoryStore(
        file = config.dataDir.resolve("version-memory.json"),
        account = { creds.get()?.let { accountFingerprint(it.server, it.username) } },
    )
    // Fiabilité partagée des copies du Direct (constats réels des télés, zéro sondage fournisseur).
    val reliabilityStore = ReliabilityStore(
        file = config.dataDir.resolve("copy-reliability.json"),
        account = { creds.get()?.let { accountFingerprint(it.server, it.username) } },
        clockMs = { System.currentTimeMillis() },
    )
    // Sport partagé par toute la maison (étape 4) : horaires LNH/ESPN, chaînes Sport dédiées, mémoire partagée.
    val sportService = SportService(
        liveRepo = repo,
        sync = sync,
        householdId = household,
        liveSnapshot = { liveJob.snapshot.value },
        manualGroups = { groupsCache.snapshot.value },
        fetchUrl = { url ->
            val r = http.get(url)
            if (r.status.value !in 200..299) throw java.io.IOException("sport HTTP ${r.status.value}")
            r.bodyAsText()
        },
        liveCategories = { api().liveCategories() },
        liveStreams = { catId -> api().liveStreams(catId) },
        guideFor = { streamId -> epgCache.entries(streamId, 6, full = false) },
        beforeProviderFetch = ::ensureCreds,
    )
    // Relais vidéo HLS (FFmpeg) pour regarder le Direct, les Films, les Séries et le Sport sur téléphone (iPhone/Android).
    val streamRelay = StreamRelay(
        sync = sync,
        householdId = household,
        mainCreds = { ensureCreds(); creds.get() },
        infoCache = infoCache,
        workDir = java.io.File("/tmp/iptv-streams"),
        clockMs = { System.currentTimeMillis() },
    )
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
        module = {
            homeModule(
                checker = checker,
                version = SERVER_VERSION,
                publicRoutes = { streamPublicRoutes(streamRelay) },
            ) {
                liveRoute { liveJob.snapshot.value }
                epgRoute(epgCache)
                vodRoutes(vodJob, infoCache)
                versionMemoryRoutes(versionMemoryStore)
                reliabilityRoutes(reliabilityStore)
                searchRoute(searchService)
                sportRoutes(sportService)
                streamProtectedRoutes(streamRelay)
            }
        },
    )
    https.start(wait = false)
    println("Serveur HTTPS démarré sur le port ${config.port}.")
    runBlocking {
        launch { liveJob.loop() }
        launch { vodJob.loop() }
        launch { groupsCache.loop() }
        launch { sportService.loop() }
        launch { streamRelay.loop() }
        // Pré-construit les index de recherche des profils du foyer après l'initialisation des manifestes.
        launch {
            delay(5_000L)
            searchService.warmProfiles(sync, household)
        }
        launch { publishTunnelUrlLoop(sync, household, http) }
        registerEvery10Minutes(sync, household, config.lanUrl, pin)
    }
}

const val SERVER_VERSION = "0.6.2"

private val TUNNEL_RE = Regex("""userHostname="(https://[a-zA-Z0-9.-]+\.trycloudflare\.com)"""")

/** Découvre l'adresse HTTPS publique du tunnel Cloudflare local (port 20241) et la publie dans `home_server.public_url`. */
private suspend fun publishTunnelUrlLoop(sync: SyncClient, household: String, http: HttpClient) {
    var lastPublished: String? = null
    while (true) {
        try {
            val metricsUrl = System.getenv("TUNNEL_METRICS_URL")?.takeIf { it.isNotBlank() } ?: "http://127.0.0.1:20241/metrics"
            val explicitUrl = System.getenv("PUBLIC_STREAM_URL")?.takeIf { it.startsWith("https://") }
            val publicUrl = explicitUrl ?: runCatching {
                val text = http.get(metricsUrl).bodyAsText()
                TUNNEL_RE.find(text)?.groupValues?.getOrNull(1)
            }.getOrNull()
            if (publicUrl != null && publicUrl != lastPublished) {
                sync.registerHomeServerPublicUrl(household, publicUrl)
                lastPublished = publicUrl
                println("Adresse HTTPS publique pour téléphone publiée : $publicUrl")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Non bloquant : nouvel essai dans 30 s.
        }
        delay(30_000L)
    }
}

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
