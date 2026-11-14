package home.iptv.tv

import android.app.Application
import home.iptv.shared.catalog.CatalogRepository
import home.iptv.shared.catalog.catalogRepository
import home.iptv.shared.sync.SupabaseConfig
import home.iptv.shared.sync.SupabaseSyncClient
import home.iptv.shared.sync.SyncClient
import home.iptv.shared.sync.supabaseClient
import home.iptv.tv.data.AndroidLocalStore
import home.iptv.tv.data.LocalStore
import home.iptv.tv.data.PrefsSessionManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

/**
 * Les objets à état unique du processus : une seule instance d'authentification Supabase (sinon deux
 * instances réutilisent le même jeton de rafraîchissement), un seul client HTTP, une seule base de catalogue.
 * Une recréation d'activité ne les recrée donc pas.
 */
class IptvApp : Application() {
    val sync: SyncClient by lazy {
        SupabaseSyncClient(supabaseClient(SupabaseConfig.URL, SupabaseConfig.PUBLISHABLE_KEY, PrefsSessionManager(this)))
    }
    val store: LocalStore by lazy { AndroidLocalStore(this) }
    val repo: CatalogRepository by lazy { catalogRepository(this) }
    val http: HttpClient by lazy { HttpClient(OkHttp) }
}
