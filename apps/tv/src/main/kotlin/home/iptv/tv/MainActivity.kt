package home.iptv.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import home.iptv.shared.catalog.XtreamLiveSource
import home.iptv.shared.xtream.StreamKind
import home.iptv.shared.xtream.XtreamApi
import home.iptv.shared.xtream.streamUrl
import home.iptv.tv.live.LiveBrowseViewModel
import home.iptv.tv.live.LiveRefresher
import home.iptv.tv.player.ExoPlayerPlayer
import home.iptv.tv.player.PlayerController
import home.iptv.tv.startup.StartupViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: PlayerController? = null

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
        val live = LiveBrowseViewModel(repo, { onProgress -> refresher.run(onProgress) }, scope)
        val controller = PlayerController { ExoPlayerPlayer(this) }
        player = controller
        setContent {
            App(vm, live, controller) { s ->
                store.creds()?.let { streamUrl(it, StreamKind.LIVE, s.id, refresher.format) }
            }
        }
    }

    // Une seule connexion chez le fournisseur : le lecteur est libéré dès que l'app quitte l'écran.
    override fun onStop() {
        player?.close()
        super.onStop()
    }

    override fun onDestroy() {
        player?.close()
        scope.cancel()
        super.onDestroy()
    }
}
