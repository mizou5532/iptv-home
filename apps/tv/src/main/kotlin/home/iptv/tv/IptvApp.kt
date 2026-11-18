package home.iptv.tv

import io.ktor.http.isSuccess

import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable

import io.ktor.client.statement.bodyAsText

import io.ktor.client.request.get
import io.ktor.client.request.prepareGet

import android.app.Application
import home.iptv.shared.catalog.CatalogRepository
import home.iptv.shared.catalog.CatalogDatabase
import home.iptv.shared.catalog.catalogDriver
import home.iptv.shared.progress.ProgressClient
import home.iptv.shared.progress.ProgressQueue
import home.iptv.shared.progress.SupabaseProgressClient
import home.iptv.shared.series.SeriesRepository
import home.iptv.shared.series.SeriesSource
import home.iptv.tv.series.AndroidSeriesHomeState
import home.iptv.tv.series.SeriesHomeState
import home.iptv.shared.sync.SupabaseConfig
import home.iptv.shared.sync.SupabaseSyncClient
import home.iptv.shared.sync.SyncClient
import home.iptv.shared.sync.supabaseClient
import io.github.jan.supabase.auth.auth
import home.iptv.tv.data.AndroidLocalStore
import home.iptv.tv.data.LocalStore
import home.iptv.tv.data.PrefsSessionManager
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlin.time.Clock

/**
 * Les objets à état unique du processus : une seule instance d'authentification Supabase (sinon deux
 * instances réutilisent le même jeton de rafraîchissement), un seul client HTTP, une seule base de catalogue.
 * Une recréation d'activité ne les recrée donc pas.
 */
class IptvApp : Application(), SingletonImageLoader.Factory {
    private val supabase by lazy { supabaseClient(SupabaseConfig.URL, SupabaseConfig.PUBLISHABLE_KEY, PrefsSessionManager(this)) }
    val groups by lazy { home.iptv.shared.groups.GroupsClient(supabase) }
    val sync: SyncClient by lazy { SupabaseSyncClient(supabase) }
    val store: LocalStore by lazy { AndroidLocalStore(this) }
    /** Une seule base de catalogue pour le direct et les séries. */
    private val catalogDb by lazy { CatalogDatabase(catalogDriver(this)) }
    val repo: CatalogRepository by lazy { CatalogRepository(catalogDb) }

    /** Vide le catalogue gardé sur la télé (Direct, séries, films) : il se recharge depuis le fournisseur actuel. */
    fun wipeCatalog() {
        val q = catalogDb.catalogQueries
        catalogDb.transaction {
            q.wipeLiveStreams(); q.wipeLiveCategories()
            q.wipeSeriesEpisodes(); q.wipeSeriesSeasons(); q.wipeSeriesDetails(); q.wipeSeriesItems(); q.wipeSeriesCategories(); q.wipeSeriesFetch()
            q.wipeMovieDetails(); q.wipeMovieItems(); q.wipeMovieCategories(); q.wipeMovieFetch()
        }
        liveCatalogDone = null
    }

    /** Suivi du remplissage qui déclenche la recherche de progression (un seul par processus). */
    var remapWatch: kotlinx.coroutines.Job? = null

    /** Retrouver la progression après un changement de fournisseur (état gardé sur la télé). */
    val remapTrigger by lazy {
        val prefs = getSharedPreferences("progress_remap", MODE_PRIVATE)
        home.iptv.tv.progress.RemapTrigger({ prefs.getString(it, null) }, { k, v -> prefs.edit().apply { if (v == null) remove(k) else putString(k, v) }.apply() })
    }

    /** Empreinte du compte IPTV (serveur + nom d'utilisateur), jamais les identifiants eux-mêmes. */
    fun accountFingerprint(): String? = store.creds()?.let { c ->
        java.security.MessageDigest.getInstance("SHA-256").digest((c.server.trim().lowercase() + "|" + c.username.trim()).toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)
    }
    val progress: ProgressClient by lazy { SupabaseProgressClient(supabase) }
    /** File des positions à envoyer (une seule pour toute l'app) ; l'horloge peut être fausse : voir `isPlausibleStamp`. */
    val progressQueue: ProgressQueue by lazy { ProgressQueue(progress, store) { Clock.System.now() } }
    val movies: home.iptv.shared.progress.MovieClient by lazy { home.iptv.shared.progress.SupabaseMovieClient(supabase) }
    val movieHomeState: home.iptv.tv.movies.MovieHomeState by lazy { home.iptv.tv.movies.AndroidMovieHomeState(this) }
    fun movieRepository(source: home.iptv.shared.movies.MovieSource) = home.iptv.shared.movies.MovieRepository(catalogDb, source)
    val seriesHomeState: SeriesHomeState by lazy { AndroidSeriesHomeState(this) }
    fun seriesRepository(source: SeriesSource) = SeriesRepository(catalogDb, source)
    val http: HttpClient by lazy { HttpClient(OkHttp) }
    /** Fiches TMDB (images, titres, résumés) et notes IMDb, par la fonction serveur media-meta. */
    val mediaMetaClient by lazy {
        home.iptv.tv.meta.MediaMetaClient(http, SupabaseConfig.URL, SupabaseConfig.PUBLISHABLE_KEY) {
            runCatching { supabase.auth.currentAccessTokenOrNull() }.getOrNull()
        }
    }
    /** Horaires du sport préparés par le serveur de la maison (fonction sport-horaires). */
    val sportRelay by lazy {
        home.iptv.tv.sport.SportRelay(http, SupabaseConfig.URL, SupabaseConfig.PUBLISHABLE_KEY,
            token = { runCatching { supabase.auth.currentAccessTokenOrNull() }.getOrNull() },
            householdId = { store.householdId() })
    }
    val mediaMeta by lazy {
        home.iptv.tv.meta.MediaMetaRepository({ mediaMetaClient.fetch(it) }, home.iptv.tv.meta.FileMediaMetaStore(this), appScope)
    }
    /** Recherche par personne, gardée en mémoire (même nom tapé = pas de nouvel appel). */
    private val personCache = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, home.iptv.shared.search.PersonCredits>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, home.iptv.shared.search.PersonCredits>?) = size > 30
    })
    suspend fun personCredits(query: String): home.iptv.shared.search.PersonCredits? {
        val key = query.trim().lowercase()
        personCache[key]?.let { return it }
        return runCatching { mediaMetaClient.person(key) }.getOrNull()?.also { personCache[key] = it }
    }
    /** Portée du processus (remplissage de la recherche) : survit à une recréation de l'activité. */
    val appScope by lazy { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default) }
    /** Un seul remplisseur de recherche par processus ; créé par la première activité (elle connaît le fournisseur). */
    var searchRuntime: home.iptv.tv.search.SearchRuntime? = null

    /** Profil ouvert, pour « Maha regarde » quand tous les comptes IPTV sont pris. */
    @Volatile var currentProfileId: String? = null
    private val deviceName by lazy { home.iptv.shared.sync.defaultDeviceName(android.os.Build.MANUFACTURER, android.os.Build.MODEL) }
    /** Identifiant de cette installation (aléatoire, gardé sur la télé) : une télé ne prend jamais deux comptes à la fois. */
    private val deviceKey by lazy {
        val prefs = getSharedPreferences("installation", MODE_PRIVATE)
        prefs.getString("device_key", null) ?: java.util.UUID.randomUUID().toString().also { prefs.edit().putString("device_key", it).apply() }
    }
    /** Comptes IPTV supplémentaires connus (relus quand le serveur donne un compte inconnu ou que les réglages changent). */
    @Volatile private var extras: List<home.iptv.shared.sync.ExtraAccount> = emptyList()
    suspend fun loadExtraAccounts(): List<home.iptv.shared.sync.ExtraAccount> {
        val h = store.householdId() ?: return emptyList()
        return sync.extraAccounts(h).also { extras = it }
    }
    private suspend fun extraCreds(position: Int): home.iptv.shared.xtream.XtreamCreds? {
        val main = store.creds() ?: return null
        fun find(list: List<home.iptv.shared.sync.ExtraAccount>) = list.firstOrNull { it.position == position && it.status == "ok" }
        val a = find(extras) ?: find(loadExtraAccounts())
        return a?.let { home.iptv.shared.xtream.XtreamCreds(main.server, it.username, it.password) }
    }

    /** Bilan de santé (écran « État de l'app ») : mesures réelles, jamais d'adresse ni d'identifiant dans le texte. */
    val healthCheck by lazy {
        home.iptv.tv.health.HealthCheck(object : home.iptv.tv.health.HealthProbes {
            private suspend fun <T> timed(block: suspend () -> T): Pair<T, Long> { val t0 = System.nanoTime(); val r = block(); return r to (System.nanoTime() - t0) / 1_000_000 }
            override suspend fun internetMs(): Long = timed {
                val r = http.get(ConfigPage.URL.trimEnd('/') + "/version.json")
                if (!r.status.isSuccess()) throw java.io.IOException("internet")
                r.bodyAsText()
            }.second
            override suspend fun downloadMbps(): Double {
                // Trois mégaoctets du fichier de l'app (public), comme une vidéo qui démarre.
                val bytes = 3 * 1024 * 1024
                val t0 = System.nanoTime()
                // Lecture au fil de l'eau (prepareGet) : jamais plus que la taille voulue, même si le serveur ignorait Range.
                val got = http.prepareGet(ConfigPage.URL.trimEnd('/') + "/iptv-home.apk") {
                    headers.append("Range", "bytes=0-${bytes - 1}")
                }.execute { r ->
                    val channel = r.bodyAsChannel()
                    var n = 0
                    val buf = ByteArray(64 * 1024)
                    while (n < bytes) { val k = channel.readAvailable(buf, 0, minOf(buf.size, bytes - n)); if (k <= 0) break; n += k }
                    n
                }
                val s = (System.nanoTime() - t0) / 1e9
                if (got < 256 * 1024 || s <= 0) throw java.io.IOException("mesure")
                return got * 8 / 1e6 / s
            }
            override suspend fun homeServerMs(): Long = timed { sync.prefsRevision(checkNotNull(store.householdId()) { "foyer" }) }.second
            override suspend fun provider(): Pair<home.iptv.shared.xtream.UserInfo, Long> =
                timed { home.iptv.shared.xtream.XtreamApi(http, checkNotNull(store.creds()) { "identifiants" }).userInfo() }
            override suspend fun extraAccounts() = loadExtraAccounts()
            override fun catalog() = home.iptv.tv.diag.Diagnostics.refresh
            override fun version(): String = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
            override fun progressRemap(): String? = when {
                remapTrigger.pending -> "Nouveau fournisseur : progression en cours de recherche (après la lecture du catalogue)"
                else -> remapTrigger.last
            }
        })
    }

    /** Fiabilité des copies des chaînes (une par télé, gardée sur la télé). */
    val copyReliability by lazy {
        val prefs = getSharedPreferences("copies", MODE_PRIVATE)
        home.iptv.tv.live.CopyReliability({ prefs.getString("scores", null) }, { prefs.edit().putString("scores", it).apply() })
    }

    /** Alerte « panne » : plusieurs vidéos qui échouent de suite → internet ou fournisseur vérifié, message clair. */
    val providerWatch by lazy {
        home.iptv.tv.health.ProviderWatch(appScope, check = {
            val online = runCatching { kotlinx.coroutines.withTimeout(8_000) { http.get(ConfigPage.URL.trimEnd('/') + "/version.json").status.isSuccess() } }.getOrDefault(false)
            if (!online) home.iptv.tv.health.Outage.NO_INTERNET
            else {
                val creds = store.creds()
                val answered = creds != null && runCatching {
                    kotlinx.coroutines.withTimeout(8_000) { home.iptv.shared.xtream.XtreamApi(http, creds).userInfo() }; true
                }.getOrElse { it is home.iptv.shared.xtream.XtreamError.Refused }
                if (answered) null else home.iptv.tv.health.Outage.PROVIDER_DOWN
            }
        }).also { home.iptv.tv.health.PlaybackSignals.watch = it }
    }

    /** Un compte IPTV par vidéo en même temps dans le foyer (voir [home.iptv.tv.player.AccountLeases]). */
    val accountLeases by lazy {
        home.iptv.tv.player.AccountLeases(
            scope = appScope,
            claim = { label, takeover ->
                val h = checkNotNull(store.householdId()) { "foyer inconnu" }
                sync.claimAccount(h, currentProfileId, deviceName, label, deviceKey, takeover)
            },
            heartbeat = { id, label -> sync.heartbeatStream(id, label) },
            release = { id -> sync.releaseStream(id) },
            account = { position -> extraCreds(position) },
            takenOver = { id -> sync.streamTakeover(id) },
            takeoverDone = { id -> sync.takeoverDone(id) },
            // Toutes les 5 s : une télé dont on prend la main s'arrête vite (le fournisseur n'accepte qu'une vidéo par compte).
            heartbeatMs = 5_000,
        )
    }

    /** Vérification des comptes ajoutés sur l'iPhone (voir [home.iptv.tv.player.ExtraAccountsCheck]). */
    val extraAccountsCheck by lazy {
        home.iptv.tv.player.ExtraAccountsCheck(
            probe = object : home.iptv.tv.player.AccountProbe {
                override suspend fun userInfo(creds: home.iptv.shared.xtream.XtreamCreds) = home.iptv.shared.xtream.XtreamApi(http, creds).userInfo()
                override suspend fun liveCategories(creds: home.iptv.shared.xtream.XtreamCreds) = home.iptv.shared.xtream.XtreamApi(http, creds).liveCategories()
                override suspend fun liveStreams(creds: home.iptv.shared.xtream.XtreamCreds, categoryId: String) = home.iptv.shared.xtream.XtreamApi(http, creds).liveStreams(categoryId)
                override suspend fun movieCategoryIds(creds: home.iptv.shared.xtream.XtreamCreds) = home.iptv.shared.xtream.XtreamApi(http, creds).movieCategories().mapTo(HashSet()) { it.id }
                override suspend fun seriesCategoryIds(creds: home.iptv.shared.xtream.XtreamCreds) = home.iptv.shared.xtream.XtreamApi(http, creds).seriesCategories().mapTo(HashSet()) { it.id }
            },
            main = { store.creds() },
            accounts = { loadExtraAccounts() },
            hiddenForEveryone = {
                val h = checkNotNull(store.householdId()) { "foyer inconnu" }
                sync.globalHiddenCategories(h).filter { it.itemType == "live_category" }.mapTo(HashSet()) { it.itemId }
            },
            report = { id, v ->
                val h = checkNotNull(store.householdId()) { "foyer inconnu" }
                sync.reportExtraAccount(h, id, v.ok, v.reason, v.expiresAt)
            },
        )
    }
    /**
     * Fin de la dernière mise à jour réussie des chaînes du Direct (temps monotone), pour tout le processus : l'app
     * rouverte sans avoir été fermée par Android (retour puis relance) ne refait pas toute la mise à jour. Avec
     * l'empreinte du compte IPTV (jamais enregistrée) : un autre compte refait la mise à jour.
     */
    @Volatile var liveCatalogDone: LiveCatalogDone? = null
    /** Versions des titres (voir [home.iptv.tv.feature.VersionIndexes]) ; créé par la première activité. */
    var versionIndexes: home.iptv.tv.feature.VersionIndexes? = null
    /**
     * Compte IPTV : demande au fournisseur la fin de l'abonnement, la garde sur la télé (alerte de « Qui regarde ? »)
     * et l'envoie à la page iPhone. Rend la date gardée ; jamais d'erreur (hors ligne : la dernière date connue).
     */
    suspend fun checkIptvAccount(): Long? {
        val creds = store.creds() ?: return store.iptvExpiresAt()
        val info = runCatching { home.iptv.shared.xtream.XtreamApi(http, creds).userInfo() }.getOrNull() ?: return store.iptvExpiresAt()
        store.saveIptvExpiresAt(info.expiresAt)
        store.householdId()?.let { h -> runCatching { sync.reportIptvAccount(h, info.expiresAt, info.status, info.maxConnections, info.activeConnections, info.message) } }
        return info.expiresAt
    }

    /**
     * Relevé de tous les comptes (principal et supplémentaires) pour la page iPhone : vidéos ouvertes en ce moment sur
     * chacun (y compris par un appareil hors de la maison), message du fournisseur, date de fin. Jamais d'erreur.
     */
    suspend fun refreshAccountsStatus() {
        checkIptvAccount()
        val main = store.creds() ?: return
        val h = store.householdId() ?: return
        val list = runCatching { loadExtraAccounts() }.getOrNull() ?: return
        for (a in list) {
            val info = runCatching {
                home.iptv.shared.xtream.XtreamApi(http, home.iptv.shared.xtream.XtreamCreds(main.server, a.username, a.password)).userInfo()
            }.getOrNull() ?: continue
            runCatching { sync.reportExtraStatus(h, a.id, info.activeConnections, info.message, info.expiresAt) }
        }
    }
    /** Bouton « Mise à jour » : une seule vérification/installation par processus, hors du fil principal. */
    val updater by lazy {
        home.iptv.tv.update.Updater(
            home.iptv.tv.update.AndroidUpdatePlatform(this, http),
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO),
        ).also { home.iptv.tv.update.Updates.updater = it }
    }

    /** Logos des chaînes : cache mémoire modeste (10 % de la mémoire de l'appli). */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.10).build() }
            // Photos de profil animées (GIF, WebP animé) : décodeur du système (Android 9+), sinon celui de Coil.
            .components {
                if (android.os.Build.VERSION.SDK_INT >= 28) add(coil3.gif.AnimatedImageDecoder.Factory())
                else add(coil3.gif.GifDecoder.Factory())
            }
            .build()
}

/**
 * Dernière mise à jour réussie des chaînes : compte IPTV (empreinte en mémoire), heure (temps monotone) et catégories
 * masquées pour tout le monde à ce moment (pour remettre à jour si l'une est réaffichée sur l'iPhone).
 */
data class LiveCatalogDone(val account: Int, val atMs: Long, val hidden: Set<String>?)
