package home.iptv.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import home.iptv.shared.catalog.XtreamLiveSource
import home.iptv.shared.series.SeriesSource
import home.iptv.shared.xtream.StreamKind
import home.iptv.shared.xtream.XtreamApi
import home.iptv.shared.xtream.streamUrl
import home.iptv.tv.live.LiveRefresher
import home.iptv.tv.live.LiveSessions
import home.iptv.tv.live.epg.EpgSource
import home.iptv.tv.player.ExoPlayerPlayer
import home.iptv.tv.player.PlayerController
import home.iptv.tv.series.RepoSeriesCatalog
import home.iptv.tv.series.SeriesDetailViewModel
import home.iptv.tv.series.SeriesHomeViewModel
import home.iptv.tv.series.queuedAsRows
import home.iptv.tv.startup.StartupViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: PlayerController? = null
    private var liveSessions: LiveSessions? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as IptvApp
        val sync = app.sync
        val store = app.store
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
        setContent {
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
                seriesDetail = { profile, prefs, seriesId, categoryId, detailScope ->
                    SeriesDetailViewModel(
                        seriesId = seriesId, categoryId = categoryId, catalog = seriesCatalog, progress = app.progress,
                        profileId = profile.id,
                        // Le serveur ne rend que la dernière ligne de chaque série : voir le rapport de la tâche 5.
                        serverProgress = { app.progress.continueWatching(profile.id, 200).filter { it.seriesId == seriesId } },
                        localProgress = { queuedAsRows(store.loadProgressQueue(), profile.id).filter { it.seriesId == seriesId } },
                        prefs = prefs, scope = detailScope,
                    )
                },
            )
        }
    }

    // Pas d'interrogation du serveur en arrière-plan ; elle reprend quand l'écran revient.
    override fun onStart() {
        super.onStart()
        liveSessions?.resume()
    }

    // Une seule connexion chez le fournisseur : le lecteur est libéré dès que l'app quitte l'écran.
    override fun onStop() {
        player?.close()
        liveSessions?.stop()
        super.onStop()
    }

    override fun onDestroy() {
        player?.close()
        scope.cancel()
        super.onDestroy()
    }
}
