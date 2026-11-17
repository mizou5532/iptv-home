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
import home.iptv.tv.movies.*
import home.iptv.tv.series.EpisodePlayback
import home.iptv.tv.series.LocalCoverServer
import home.iptv.tv.series.EpisodePlaybackFactory
import home.iptv.tv.series.ProgressFlusher
import home.iptv.tv.series.RepoSeriesCatalog
import home.iptv.tv.series.SeriesDetailViewModel
import home.iptv.tv.series.seriesHidden
import home.iptv.tv.series.SeriesHomeViewModel
import home.iptv.tv.series.queuedAsRows
import home.iptv.tv.series.dropQueuedEpisodes
import home.iptv.tv.startup.StartupViewModel
import kotlinx.coroutines.launch
import io.ktor.http.isSuccess
import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.time.Clock

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: PlayerController? = null
    private var searchRuntime: home.iptv.tv.search.SearchRuntime? = null
    private var liveSessions: LiveSessions? = null
    private var store: LocalStore? = null
    private var flusher: ProgressFlusher? = null
    private var moviePlayback: MoviePlayback? = null
    private var playback: EpisodePlayback? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val combo = home.iptv.tv.diag.SecretCombo()

    /**
     * Calque de diagnostic caché : Info, Menu ou 0 trois fois, ou ↑↑↓↓←→←→ (voir [home.iptv.tv.diag.Diagnostics]).
     * RestrictedApi : fausse alerte connue de l'analyse Android sur `ComponentActivity.dispatchKeyEvent` (méthode publique d'Activity).
     */
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0 && combo.onKeyDown(event.keyCode, event.eventTime)) {
            home.iptv.tv.diag.Diagnostics.visible = !home.iptv.tv.diag.Diagnostics.visible
            // Les flèches gardent leur effet habituel ; Info, Menu et 0 n'ont pas d'autre usage ici.
            if (event.keyCode !in DPAD) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as IptvApp
        val sync = app.sync
        val store = app.store
        this.store = store
        val vm = StartupViewModel(sync = sync, store = store, configPageUrl = ConfigPage.URL, deviceName = home.iptv.shared.sync.defaultDeviceName(android.os.Build.MANUFACTURER, android.os.Build.MODEL), scope = scope)

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
        // Sport : horaires, chaînes d'événements et mémoire partagés par l'écran Sport et sa préparation (G).
        val sport = home.iptv.tv.sport.SportPrep(
            store = home.iptv.tv.sport.AndroidSportStore(this),
            fetch = { url ->
                val r = http.get(url)
                if (!r.status.isSuccess()) throw java.io.IOException("sport ${r.status.value}")
                r.bodyAsText()
            },
            sources = home.iptv.tv.sport.AndroidSportSources(
                dir = java.io.File(cacheDir, "sport-extra"),
                // TOUTES les catégories du fournisseur : celles du catalogue local excluent les masquées du Direct
                // (0.19.0 : aucune catégorie Sport masquée n'était lue, donc aucune chaîne d'événement).
                categories = { api().liveCategories() },
                streams = { id -> api().liveStreams(id) },
                epg = { id -> api().shortEpg(id, 6) },
                chosen = { store.householdId()?.let { sync.householdSettings(it).sportCategories } },
                readVotes = { store.householdId()?.let { sync.sportVotes(it) }.orEmpty() },
                writeVote = { team, ch, d -> store.householdId()?.let { sync.sportVote(it, team, ch, d) } },
            ),
        )
        // Le direct suit l'organisation du profil choisi : une session (affichage + observateur) à la fois.
        val sessions = LiveSessions(
            parentScope = scope, sync = sync, store = store, repo = repo,
            groupsClient = app.groups,
            epgSource = EpgSource.of(::api),
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
        val movieRepo = app.movieRepository(object : home.iptv.shared.movies.MovieSource {
            override suspend fun movieCategories() = api().movieCategories()
            override suspend fun movies(categoryId: String) = api().movies(categoryId)
            override suspend fun movieInfo(movieId: String) = api().movieInfo(movieId)
        })
        // Recherche : un remplisseur par processus ; les demandes des écrans passent devant lui (même verrou).
        val runtime = app.searchRuntime ?: home.iptv.tv.search.SearchRuntime(
            home.iptv.shared.search.CatalogFiller(seriesRepo, movieRepo, app.appScope), app.appScope,
        ).also { r ->
            app.searchRuntime = r
            // Titres du catalogue pour la page iPhone (recherche « Ajouter à Ma liste », noms de la bannière) :
            // une fois par jour au plus, quand le remplissage de la recherche est complet.
            home.iptv.tv.search.CatalogTitlesPublisher(
                r.filler.progress,
                series = { seriesRepo.cachedSeries() },
                movies = { movieRepo.cachedMovies().map { m -> home.iptv.shared.catalog.CatalogTitleSource(m.id, m.name, m.cover, m.categoryId, m.year, m.rating) } },
                server = { store.creds()?.server },
                household = { store.householdId() },
                publish = { h, kind, gen, rows, done -> sync.publishCatalogTitles(h, kind, gen, rows, done) },
                state = home.iptv.tv.search.AndroidCatalogTitlesState(this),
                scope = app.appScope,
            ).start()
        }
        searchRuntime = runtime
        // Versions (langues, 4K) de tous les titres du cache : une seule fois par processus, refaites quand le catalogue change.
        val versions = app.versionIndexes ?: home.iptv.tv.feature.VersionIndexes(
            items = { k -> if (k == home.iptv.shared.meta.MediaKind.MOVIE) movieRepo.searchItems() else seriesRepo.searchItems() },
            categoryNames = { k -> if (k == home.iptv.shared.meta.MediaKind.MOVIE) movieRepo.cachedCategoryNames() else seriesRepo.cachedCategoryNames() },
            tmdb = { app.mediaMeta.known().mapNotNull { (key, m) -> m.tmdbId?.takeIf { m.found }?.let { key to it } }.toMap() },
            splits = { store.versionSplits() },
            saveSplit = { key, on ->
                val h = store.householdId() ?: throw java.io.IOException("pas de maison")
                sync.setVersionSplit(h, key.substringBefore(':'), key.substringAfter(':'), on)
                store.saveVersionSplits(if (on) store.versionSplits() + key else store.versionSplits() - key)
            },
        ).also { v ->
            app.versionIndexes = v
            app.appScope.launch { runtime.filler.changed.collect { v.invalidate() } }
        }
        val seriesCatalog = RepoSeriesCatalog(seriesRepo, runtime.filler)
        val movieCatalog = RepoMovieCatalog(movieRepo, runtime.filler)
        val controller = PlayerController { ExoPlayerPlayer(this) }
        player = controller
        // Envoi des positions : jamais sur le fil principal, un seul à la fois (voir ProgressFlusher).
        val progressFlusher = ProgressFlusher(app.progressQueue, scope)
        flusher = progressFlusher
        val episodePlayback: EpisodePlaybackFactory = { profile, prefs ->
            moviePlayback?.stop()
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
                    prefs.value?.seriesHidden().orEmpty()
                },
                io = Dispatchers.IO,
                languages = { (profile.settings.languages ?: home.iptv.shared.versions.LanguagePrefs()) to (profile.settings.languages != null) },
            ).also { playback = it }
        }
        // Toutes les 6 h tant que l'app reste ouverte (une télé peut rester allumée longtemps).
        scope.launch { while (true) { kotlinx.coroutines.delay(home.iptv.tv.update.CHECK_EVERY_MS); app.updater.check(force = true) } }
        setContent {
          CompositionLocalProvider(LocalCoverServer provides { store.creds()?.server }, home.iptv.tv.update.LocalUpdater provides app.updater, home.iptv.tv.meta.LocalMediaMeta provides app.mediaMeta, home.iptv.tv.feature.LocalVersionIndexes provides versions) {
            App(
                vm, sessions, controller,
                urlFor = { s -> store.creds()?.let { streamUrl(it, StreamKind.LIVE, s.id, refresher.format) } },
                store = store,
                movieHome = { profile, prefs, movieScope -> MovieHomeViewModel(
                    movieCatalog, { app.movies.continueWatching(profile.id) }, { app.movies.list(profile.id) },
                    { queuedMovieRows(store.loadProgressQueue(), profile.id) },
                    { cats -> store.householdId()?.let { app.movies.publishCategories(it, cats) } },
                    app.movieHomeState, profile.id, { store.householdId() }, prefs, movieScope,
                    initialView = profile.settings.moviesView(),
                    loadView = { store.householdId()?.let { h -> sync.profiles(h).firstOrNull { it.id == profile.id }?.settings?.moviesView() } },
                    dismissOnServer = { id -> app.movies.dismissContinue(profile.id, listOf(id)) },
                    versionIndex = { versions.index(home.iptv.shared.meta.MediaKind.MOVIE) },
                    languages = (profile.settings.languages ?: home.iptv.shared.versions.LanguagePrefs()).also { versions.languages = it },
                ) },
                movieDetail = { profile, prefs, id, category, detailScope -> MovieDetailViewModel(
                    id, category, movieCatalog, app.movies, profile.id, app.movieHomeState,
                    { queuedMovieRows(store.loadProgressQueue(), profile.id) },
                    { store.update { q -> q.filterNot { it.profileId == profile.id && it.entry.itemType == "movie" && it.entry.itemId == id } } },
                    prefs, detailScope, withPausedFlush = { action -> app.progressQueue.withPausedFlush(action) },
                    coverFor = { c -> coverWithoutServer(c, store.creds()?.server) },
                    siblings = { versions.current(home.iptv.shared.meta.MediaKind.MOVIE).versionsOf(id).mapTo(HashSet()) { it.id } },
                ) },
                moviePlayback = { profile, prefs ->
                    playback?.stop()
                    moviePlayback?.stop()
                    MoviePlayback(profile.id, controller, app.progressQueue, progressFlusher, store, { Clock.System.now() }, scope,
                        { m -> store.creds()?.let { streamUrl(it, StreamKind.MOVIE, m.id, m.extension.ifBlank { "mkv" }) } },
                        { id, category -> movieCatalog.info(id, category) },
                        { prefs.value?.movieHidden() }, Dispatchers.IO, movieCatalog::cachedCategory,
                        { c -> coverWithoutServer(c, store.creds()?.server) },
                        languages = { (profile.settings.languages ?: home.iptv.shared.versions.LanguagePrefs()) to (profile.settings.languages != null) },
                    ).also { moviePlayback = it }
                },
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
                        initialView = profile.settings.seriesView(),
                        loadView = { store.householdId()?.let { h -> sync.profiles(h).firstOrNull { it.id == profile.id }?.settings?.seriesView() } },
                        dismissOnServer = { id -> app.progress.dismissContinue(profile.id, listOf(id)) },
                        versionIndex = { versions.index(home.iptv.shared.meta.MediaKind.SERIES) },
                        languages = (profile.settings.languages ?: home.iptv.shared.versions.LanguagePrefs()).also { versions.languages = it },
                    )
                },
                episodePlayback = episodePlayback,
                requestFlush = { progressFlusher.request() },
                // Pas de recherche vocale pour l'instant (décision du propriétaire, 16 nov.).
                searchFactory = home.iptv.tv.search.searchFactory(repo, seriesRepo, movieRepo, runtime.filler, versions),
                searchProfile = runtime::bind,
                // Sport : horaires LNH/ESPN lus par la télé, chaînes apprises sur place (approche A).
                sportFactory = { profile, session, sportScope ->
                    home.iptv.tv.sport.SportViewModel(
                        profileId = profile.id,
                        schedule = sport.schedule,
                        // Gardées tant que préférences, regroupements et catalogue (révision des groupes) sont les mêmes.
                        channels = run {
                            var memo: Pair<List<Any?>, List<home.iptv.shared.sport.SportChannel>>? = null
                            val lambda: suspend () -> List<home.iptv.shared.sport.SportChannel> = {
                                val key = listOf(session.watcher.prefs.value, session.groupsWatcher?.snapshot?.value, session.vm.groupsRevision.value)
                                memo?.takeIf { it.first == key }?.second
                                    ?: home.iptv.tv.sport.visibleSportChannels(repo, session.watcher.prefs.value, session.groupsWatcher?.snapshot?.value)
                                        .also { memo = key to it }
                            }
                            lambda
                        },
                        favorites = { session.watcher.prefs.value?.favorites.orEmpty() },
                        store = sport.store, prefs = session.watcher.prefs, scope = sportScope,
                        teams = { profile.settings.sportTeams },
                        view = { profile.settings.sportView },
                        sources = sport.sources,
                    )
                },
                sportPrewarm = { profiles -> sport.prewarm(profiles, scope) },
                seriesDetail = { profile, prefs, seriesId, categoryId, detailScope ->
                    SeriesDetailViewModel(
                        seriesId = seriesId, categoryId = categoryId, catalog = seriesCatalog, progress = app.progress,
                        profileId = profile.id,
                        serverProgress = { app.progress.seriesProgress(profile.id, seriesId) },
                        localProgress = { queuedAsRows(store.loadProgressQueue(), profile.id).filter { it.seriesId == seriesId } },
                        dropQueued = { ids -> store.update { q -> dropQueuedEpisodes(q, profile.id, ids) } },
                        prefs = prefs, scope = detailScope,
                        coverFor = { c -> coverWithoutServer(c, store.creds()?.server) },
                        // Reprise d'une version à l'autre : progression des autres versions du titre (6 au plus).
                        siblingProgress = {
                            val others = versions.current(home.iptv.shared.meta.MediaKind.SERIES).versionsOf(seriesId).map { it.id }.filter { it != seriesId }.take(6)
                            val queued = queuedAsRows(store.loadProgressQueue(), profile.id).filter { it.seriesId in others }
                            others.flatMap { o -> runCatching { app.progress.seriesProgress(profile.id, o) }.getOrDefault(emptyList()) } + queued
                        },
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
        searchRuntime?.start()
        flusher?.request() // positions gardées pendant une absence ou un redémarrage
        // Mise à jour : vérifiée au retour (au plus une fois par heure) ; retour du réglage « applis inconnues ».
        (application as IptvApp).updater.let { it.check(); it.permissionMaybeGranted() }
        // Retour du réseau : les positions gardées pendant la coupure partent.
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { flusher?.request() }
        }
        runCatching { getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
    }

    // Une seule connexion chez le fournisseur : le lecteur est libéré dès que l'app quitte l'écran.
    override fun onStop() {
        searchRuntime?.stop()
        moviePlayback?.stop()
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
        moviePlayback?.stop()
        playback?.stop()
        player?.close()
        scope.cancel()
        super.onDestroy()
    }

}

private val DPAD = setOf(
    android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN,
    android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
)
