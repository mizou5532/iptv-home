package home.iptv.tv

import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import home.iptv.shared.catalog.XtreamLiveSource
import androidx.compose.runtime.CompositionLocalProvider
import home.iptv.shared.progress.coverWithoutServer
import home.iptv.shared.series.SeriesSource
import home.iptv.shared.xtream.StreamKind
import home.iptv.shared.xtream.XtreamApi
import home.iptv.shared.xtream.streamUrl
import home.iptv.tv.live.LiveRefresher
import home.iptv.tv.live.LiveSessions
import home.iptv.tv.live.epg.EpgSource
import home.iptv.tv.player.ExoPlayerPlayer
import home.iptv.tv.data.LocalStore
import home.iptv.tv.player.PlayerController
import home.iptv.tv.series.EpisodePlayback
import home.iptv.tv.series.LocalCoverServer
import home.iptv.tv.series.EpisodePlaybackFactory
import home.iptv.tv.series.ProgressFlusher
import home.iptv.tv.series.RepoSeriesCatalog
import home.iptv.tv.series.SeriesDetailViewModel
import home.iptv.tv.series.SeriesHomeViewModel
import home.iptv.tv.series.queuedAsRows
import home.iptv.tv.startup.StartupViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.time.Clock

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: PlayerController? = null
    private var liveSessions: LiveSessions? = null
    private var store: LocalStore? = null
    private var flusher: ProgressFlusher? = null
    private var playback: EpisodePlayback? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as IptvApp
        val sync = app.sync
        val store = app.store
        this.store = store
        val vm = StartupViewModel(sync = sync, store = store, configPageUrl = ConfigPage.URL, scope = scope)

        // Le catalogue et les identifiants sont lus au moment de l'appel : le démarrage les met à jour avant « Qui regarde ? ».
        val repo = app.repo
        val http = app.http
        fun api() = XtreamApi(http, checkNotNull(store.creds()) { "identifiants absents" })
        val refresher = LiveRefresher(
            sync = sync,
            repo = repo,
            householdId = { store.householdId() },
            userInfo = { api().userInfo() },
            source = { XtreamLiveSource(api()) },
        )
        // Le direct suit l'organisation du profil choisi : une session (affichage + observateur) à la fois.
        val sessions = LiveSessions(
            parentScope = scope, sync = sync, store = store, repo = repo,
            groupsClient = app.groups,
            epgSource = EpgSource { id, n -> api().shortEpg(id, n) },
            householdId = { store.householdId() },
            refresh = { onProgress -> refresher.run(onProgress) },
            publish = { report -> refresher.publish(report) },
        )
        liveSessions = sessions
        // Séries : même fournisseur, même base de catalogue ; les identifiants sont lus au moment de l'appel.
        val seriesRepo = app.seriesRepository(object : SeriesSource {
            override suspend fun seriesCategories() = api().seriesCategories()
            override suspend fun series(categoryId: String) = api().series(categoryId)
            override suspend fun seriesInfo(seriesId: String) = api().seriesInfo(seriesId)
        })
        val seriesCatalog = RepoSeriesCatalog(seriesRepo)
        val controller = PlayerController { ExoPlayerPlayer(this) }
        player = controller
        // Envoi des positions : jamais sur le fil principal, un seul à la fois (voir ProgressFlusher).
        val progressFlusher = ProgressFlusher(app.progressQueue, scope)
        flusher = progressFlusher
        val episodePlayback: EpisodePlaybackFactory = { profile, prefs ->
            EpisodePlayback(
                profileId = profile.id, controller = controller, queue = app.progressQueue, flusher = progressFlusher,
                store = store, clock = { Clock.System.now() }, scope = scope,
                // Un épisode sans extension chez le fournisseur : « mkv » (trois quarts du catalogue).
                urlFor = { e -> store.creds()?.let { streamUrl(it, StreamKind.SERIES, e.id, e.extension.ifBlank { "mkv" }) } },
                loadInfo = { id, category -> seriesCatalog.info(id, category) },
                cachedCategory = { id -> seriesCatalog.cachedCategory(id) },
                coverFor = { c -> coverWithoutServer(c, store.creds()?.server) },
                // Préférences inconnues : null, donc rien n'est lu (masqué gagne).
                hiddenCategories = {
                    prefs.value?.hidden.orEmpty().filter { it.itemType == "series_category" }.mapTo(HashSet()) { it.itemId }
                },
                io = Dispatchers.IO,
            ).also { playback = it }
        }
        setContent {
          CompositionLocalProvider(LocalCoverServer provides { store.creds()?.server }) {
            App(
                vm, sessions, controller,
                urlFor = { s -> store.creds()?.let { streamUrl(it, StreamKind.LIVE, s.id, refresher.format) } },
                store = store,
                seriesHome = { profile, prefs, seriesScope ->
                    SeriesHomeViewModel(
                        catalog = seriesCatalog,
                        serverProgress = { app.progress.continueWatching(profile.id) },
                        localProgress = { queuedAsRows(store.loadProgressQueue(), profile.id) },
                        publishCategories = { cats -> store.householdId()?.let { sync.publishSeriesCategories(it, cats) } },
                        state = app.seriesHomeState,
                        profileId = profile.id,
                        householdId = { store.householdId() },
                        prefs = prefs,
                        scope = seriesScope,
                    )
                },
                episodePlayback = episodePlayback,
                requestFlush = { progressFlusher.request() },
                seriesDetail = { profile, prefs, seriesId, categoryId, detailScope ->
                    SeriesDetailViewModel(
                        seriesId = seriesId, categoryId = categoryId, catalog = seriesCatalog, progress = app.progress,
                        profileId = profile.id,
                        serverProgress = { app.progress.seriesProgress(profile.id, seriesId) },
                        localProgress = { queuedAsRows(store.loadProgressQueue(), profile.id).filter { it.seriesId == seriesId } },
                        dropQueued = { ids -> store.update { q -> q.filterNot { it.profileId == profile.id && it.entry.itemId in ids } } },
                        prefs = prefs, scope = detailScope,
                        coverFor = { c -> coverWithoutServer(c, store.creds()?.server) },
                    )
                },
            )
          }
        }
    }

    // Pas d'interrogation du serveur en arrière-plan ; elle reprend quand l'écran revient.
    override fun onStart() {
        super.onStart()
        liveSessions?.resume()
        flusher?.request() // positions gardées pendant une absence ou un redémarrage
        // Retour du réseau : les positions gardées pendant la coupure partent.
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { flusher?.request() }
        }
        runCatching { getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
    }

    // Une seule connexion chez le fournisseur : le lecteur est libéré dès que l'app quitte l'écran.
    override fun onStop() {
        networkCallback?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) } }
        networkCallback = null
        // La position de l'épisode en cours est mise dans la file avant que le lecteur soit fermé.
        playback?.stop()
        player?.close()
        liveSessions?.stop()
        // La file doit être sur le disque avant que le système puisse tuer l'app : écriture synchrone (quelques dizaines
        // de Ko au plus), refaite une fois si elle échoue.
        store?.let { s -> if (!s.persistNow()) s.persistNow() }
        super.onStop()
    }

    override fun onDestroy() {
        player?.close()
        scope.cancel()
        super.onDestroy()
    }

}
