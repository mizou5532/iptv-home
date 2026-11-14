package home.iptv.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import home.iptv.shared.sync.SupabaseConfig
import home.iptv.shared.sync.SupabaseSyncClient
import home.iptv.shared.sync.supabaseClient
import home.iptv.tv.data.AndroidLocalStore
import home.iptv.tv.data.PrefsSessionManager
import home.iptv.tv.startup.StartupViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val supabase = supabaseClient(SupabaseConfig.URL, SupabaseConfig.PUBLISHABLE_KEY, PrefsSessionManager(this))
        val vm = StartupViewModel(
            sync = SupabaseSyncClient(supabase),
            store = AndroidLocalStore(this),
            configPageUrl = ConfigPage.URL,
            scope = scope,
        )
        setContent { App(vm) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
