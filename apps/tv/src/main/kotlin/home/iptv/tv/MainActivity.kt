package home.iptv.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import home.iptv.shared.catalog.XtreamLiveSource
import home.iptv.shared.catalog.catalogRepository
import home.iptv.shared.sync.SupabaseConfig
import home.iptv.shared.sync.SupabaseSyncClient
import home.iptv.shared.sync.supabaseClient
import home.iptv.shared.xtream.StreamKind
import home.iptv.shared.xtream.XtreamApi
import home.iptv.shared.xtream.streamUrl
import home.iptv.tv.data.AndroidLocalStore
import home.iptv.tv.data.PrefsSessionManager
import home.iptv.tv.live.LiveBrowseViewModel
import home.iptv.tv.live.LiveRefresher
import home.iptv.tv.player.ExoPlayerPlayer
import home.iptv.tv.player.PlayerController
import home.iptv.tv.startup.StartupViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: PlayerController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val supabase = supabaseClient(SupabaseConfig.URL, SupabaseConfig.PUBLISHABLE_KEY, PrefsSessionManager(this))
        val sync = SupabaseSyncClient(supabase)
        val store = AndroidLocalStore(this)
        val vm = StartupViewModel(sync = sync, store = store, configPageUrl = ConfigPage.URL, scope = scope)

        // Le catalogue et les identifiants sont lus au moment de l'appel : le démarrage les met à jour avant « Qui regarde ? ».
        val repo = catalogRepository(this)
        val http = HttpClient(OkHttp)
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
