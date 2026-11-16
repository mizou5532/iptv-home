package home.iptv.tv

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
    val progress: ProgressClient by lazy { SupabaseProgressClient(supabase) }
    /** File des positions à envoyer (une seule pour toute l'app) ; l'horloge peut être fausse : voir `isPlausibleStamp`. */
    val progressQueue: ProgressQueue by lazy { ProgressQueue(progress, store) { Clock.System.now() } }
    val movies: home.iptv.shared.progress.MovieClient by lazy { home.iptv.shared.progress.SupabaseMovieClient(supabase) }
    val movieHomeState: home.iptv.tv.movies.MovieHomeState by lazy { home.iptv.tv.movies.AndroidMovieHomeState(this) }
    fun movieRepository(source: home.iptv.shared.movies.MovieSource) = home.iptv.shared.movies.MovieRepository(catalogDb, source)
    val seriesHomeState: SeriesHomeState by lazy { AndroidSeriesHomeState(this) }
    fun seriesRepository(source: SeriesSource) = SeriesRepository(catalogDb, source)
    val http: HttpClient by lazy { HttpClient(OkHttp) }
    /** Portée du processus (remplissage de la recherche) : survit à une recréation de l'activité. */
    val appScope by lazy { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default) }
    /** Un seul remplisseur de recherche par processus ; créé par la première activité (elle connaît le fournisseur). */
    var searchRuntime: home.iptv.tv.search.SearchRuntime? = null
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
            .build()
}
