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
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlinx.coroutines.flow.conflate

/** Intervalle minimal entre deux reconstructions de l'index des versions pendant le remplissage de la recherche. */
private const val VERSIONS_REBUILD_MS = 60_000L

/** Rythme du relevé des comptes IPTV pour la page iPhone. */
private const val ACCOUNTS_STATUS_MS = 10 * 60_000L

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
    /** Vérification des mises à jour toutes les 6 h, seulement quand l'app est à l'écran (rien pendant la veille). */
    private var updateLoop: kotlinx.coroutines.Job? = null
    /** Relevé des comptes IPTV (connexions en cours, message du fournisseur), seulement quand l'app est à l'écran. */
    private var statusLoop: kotlinx.coroutines.Job? = null
    private val combo = home.iptv.tv.diag.SecretCombo()
    private val repeatGate = home.iptv.tv.nav.KeyRepeatGate()


    /**
     * Calque de diagnostic caché : Info, Menu ou 0 trois fois, ou ↑↑↓↓←→←→ (voir [home.iptv.tv.diag.Diagnostics]).
     * RestrictedApi : fausse alerte connue de l'analyse Android sur `ComponentActivity.dispatchKeyEvent` (méthode publique d'Activity).
     */
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        // Rapports de lenteur : délai entre l'appui et son traitement, puis durée du traitement.
        // Travail de fond en pause pendant qu'on navigue (voir UserActivity).
        home.iptv.tv.diag.UserActivity.touched()
        if (event.action != android.view.KeyEvent.ACTION_DOWN || event.repeatCount != 0) return dispatchKeyEventMeasured(event)
        val startedAt = android.os.SystemClock.uptimeMillis()
        val handled = dispatchKeyEventMeasured(event)
        home.iptv.tv.diag.PerfMonitor.key(event.keyCode, event.eventTime, startedAt, android.os.SystemClock.uptimeMillis())
        return handled
    }

    @android.annotation.SuppressLint("RestrictedApi")
    private fun dispatchKeyEventMeasured(event: android.view.KeyEvent): Boolean {
        // Flèche maintenue : répétitions ralenties (la sélection ne file plus) ; un appui simple passe toujours.
        if (event.action == android.view.KeyEvent.ACTION_DOWN && event.keyCode in DPAD &&
            !repeatGate.allow(event.keyCode, event.repeatCount, event.eventTime)) return true
        if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0 && combo.onKeyDown(event.keyCode, event.eventTime)) {
            home.iptv.tv.diag.Diagnostics.visible = !home.iptv.tv.diag.Diagnostics.visible
            // Les flèches gardent leur effet habituel ; Info, Menu et 0 n'ont pas d'autre usage ici.
            if (event.keyCode !in DPAD) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        home.iptv.tv.diag.PerfMonitor.watchFrames(this)
        val app = application as IptvApp
        val sync = app.sync
        val store = app.store
        this.store = store
        val vm = StartupViewModel(sync = sync, store = store, configPageUrl = ConfigPage.URL, deviceName = home.iptv.shared.sync.defaultDeviceName(android.os.Build.MANUFACTURER, android.os.Build.MODEL), scope = scope)

        // Le catalogue et les identifiants sont lus au moment de l'appel : le démarrage les met à jour avant « Qui regarde ? ».
        val repo = app.repo
        val http = app.http
        // Empreinte du compte IPTV, en mémoire seulement (jamais enregistrée ni affichée).
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
            batch = { urls -> app.sportRelay.bodies(urls) },
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
            refresh = { onProgress ->
                // Après Séries, Films et Sport (0.41), sauf si aucune liste n'est encore sur la télé (premier lancement).
                val order = home.iptv.tv.diag.StartupOrder.app
                if (runCatching { repo.categories().isNotEmpty() }.getOrDefault(false)) order.turn(home.iptv.tv.diag.StartupOrder.Step.DIRECT)
                try { home.iptv.tv.diag.PerfMonitor.work("Mise à jour des chaînes du Direct") { refresher.run(onProgress) } }
                finally { order.done(home.iptv.tv.diag.StartupOrder.Step.DIRECT) }
            },
            publish = { report -> refresher.publish(report) },
            // Écran relancé sans nouvelle mise à jour : les masquées retenues pour le processus.
            hiddenAtRefresh = { refresher.lastHidden ?: app.liveCatalogDone?.takeIf { it.account == app.accountFingerprint() }?.hidden },
            doneBefore = { app.liveCatalogDone?.takeIf { it.account == app.accountFingerprint() }?.atMs },
            onDone = { app.liveCatalogDone = app.accountFingerprint()?.let { fp -> home.iptv.tv.LiveCatalogDone(fp, it, refresher.lastHidden) } },
            onProfile = { p -> app.currentProfileId = p.id; app.appScope.launch { app.extraAccountsCheck.runPending() } },
            // Réglages du foyer changés (un compte ajouté sur l'iPhone, par exemple) : comptes relus et vérifiés.
            reliability = app.copyReliability,
            onAnyRevision = { app.appScope.launch { runCatching { app.loadExtraAccounts() }; app.extraAccountsCheck.runPending() } },
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
            // 0.40 : fils de priorité basse, et pas de demande de fond pendant qu'on navigue.
            home.iptv.shared.search.CatalogFiller(seriesRepo, movieRepo,
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + home.iptv.tv.diag.backgroundDispatcher),
                // En dernier au démarrage (0.41), puis jamais pendant qu'on navigue.
                beforeWork = { home.iptv.tv.diag.StartupOrder.app.turn(home.iptv.tv.diag.StartupOrder.Step.SEARCH); home.iptv.tv.diag.UserActivity.awaitIdle() },
                // Vitrine d'abord (0.41) ; les autres catégories ne refont plus les index en mémoire.
                showcase = { section -> if (section == home.iptv.shared.search.SearchSection.SERIES) seriesRepo.showcaseIds() else movieRepo.showcaseIds() }), app.appScope,
        ).also { r ->
            app.searchRuntime = r
            // Titres du catalogue pour la page iPhone (recherche « Ajouter à Ma liste », noms de la bannière) :
            // une fois par jour au plus, quand le remplissage de la recherche est complet.
            home.iptv.tv.search.CatalogTitlesPublisher(
                r.filler.progress,
                series = { seriesRepo.cachedSeries() },
                // Sans les résumés (0.40) : lire tout le catalogue avec eux faisait un gros pic de mémoire une fois par jour.
                movies = { movieRepo.cachedTitleRows() },
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
            // Vitrine et ses doublons 4K/Dolby seulement (0.41) : bien moins de titres en mémoire ; les versions d'un titre
            // hors vitrine sont retrouvées par la recherche (titres lus à la demande).
            items = { k -> if (k == home.iptv.shared.meta.MediaKind.MOVIE) movieRepo.searchItems(movieRepo.versionIds(), inside = true) else seriesRepo.searchItems(seriesRepo.versionIds(), inside = true) },
            categoryNames = { k -> if (k == home.iptv.shared.meta.MediaKind.MOVIE) movieRepo.cachedCategoryNames() else seriesRepo.cachedCategoryNames() },
            tmdb = { app.mediaMeta.known().mapNotNull { (key, m) -> m.tmdbId?.takeIf { m.found }?.let { key to it } }.toMap() },
            splits = { store.versionSplits() },
            disk = home.iptv.tv.feature.FileVersionIndexDisk(java.io.File(filesDir, "index")),
            saveSplit = { key, on ->
                val h = store.householdId() ?: throw java.io.IOException("pas de maison")
                sync.setVersionSplit(h, key.substringBefore(':'), key.substringAfter(':'), on)
                store.saveVersionSplits(if (on) store.versionSplits() + key else store.versionSplits() - key)
            },
        ).also { v ->
            app.versionIndexes = v
            // Au plus une fois par minute : refaire l'index (des dizaines de milliers de titres) après chaque catégorie
            // remplie saturait la télé pendant le remplissage (essai 0.32.0 : rangées de Séries « Chargement… » 5 à 7 s).
            // Le dernier changement est toujours pris en compte (conflate).
            app.appScope.launch {
                runtime.filler.changed.conflate().collect { v.invalidate(); kotlinx.coroutines.delay(VERSIONS_REBUILD_MS) }
            }
            // Démarrage dans l'ordre du propriétaire (0.41) : Séries, puis Films (index des versions), puis Sport, puis Direct.
            app.appScope.launch(home.iptv.tv.diag.backgroundDispatcher) {
                runCatching { app.mediaMeta.known() }
                val order = home.iptv.tv.diag.StartupOrder.app
                runCatching { v.index(home.iptv.shared.meta.MediaKind.SERIES) }
                order.done(home.iptv.tv.diag.StartupOrder.Step.SERIES)
                runCatching { v.index(home.iptv.shared.meta.MediaKind.MOVIE) }
                order.done(home.iptv.tv.diag.StartupOrder.Step.MOVIES)
            }
        }
        val seriesCatalog = RepoSeriesCatalog(seriesRepo, runtime.filler)
        val movieCatalog = RepoMovieCatalog(movieRepo, runtime.filler)
        // Comptes IPTV du foyer partagés entre les télés (un par vidéo en même temps), un gestionnaire par processus.
        val leases = app.accountLeases
        val controller = PlayerController(leases, { store.creds() }, scope) { ExoPlayerPlayer(this) }
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
        // Changement de compte IPTV (vu quand les identifiants arrivent du serveur, avant le choix du profil) : le
        // catalogue gardé est vidé et rechargé ; quand le nouveau catalogue de films et de séries est lu en entier, la
        // progression est retrouvée sous les nouveaux numéros (rien n'est jamais effacé sur le serveur).
        scope.launch {
            vm.ui.collect { ui ->
                if (ui !is home.iptv.tv.startup.StartupUi.Ready || ui.offline) return@collect
                // Version de l'app pour la page iPhone, et rapports de lenteur gardés pendant une coupure : une fois par lancement.
                if (!app.versionReported) {
                    app.versionReported = true
                    app.appScope.launch {
                        val h = store.householdId() ?: return@launch
                        runCatching { sync.reportAppVersion(h, app.perfReporter.version) }
                        runCatching { app.perfReporter.flushPending(h) }
                        runCatching { app.perfReporter.reportPreviousExits(h) }
                    }
                }
                val fp = app.accountFingerprint() ?: return@collect
                withContext(Dispatchers.IO) { runCatching { app.remapTrigger.accountSeen(fp) { app.wipeCatalog() } } }
            }
        }
        // Un seul suivi par processus (l'activité peut être recréée ; le remplisseur, lui, est unique).
        if (app.remapWatch == null) app.remapWatch = app.appScope.launch {
            var running = false
            runtime.filler.progress.collect { p ->
                val s = p[home.iptv.shared.search.SearchSection.SERIES]; val m = p[home.iptv.shared.search.SearchSection.MOVIES]
                val ready = s != null && m != null && s.total > 0 && m.total > 0 && s.complete && m.complete
                if (!ready || running || !app.remapTrigger.pending) return@collect
                running = true
                try {
                    val result = home.iptv.tv.progress.ProgressRemapper(
                        profiles = { store.profiles().map { it.id } },
                        rows = { id -> sync.allProgress(id) },
                        movies = { movieRepo.searchItems() },
                        series = { seriesRepo.searchItems() },
                        seriesInfo = { id, cat -> seriesRepo.info(id, cat) },
                        copy = { id, rows -> sync.progressCopy(id, rows) },
                    ).run()
                    app.remapTrigger.done(result, java.text.SimpleDateFormat("d MMMM", java.util.Locale.CANADA_FRENCH).format(java.util.Date()))
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                    // Hors ligne : nouvel essai au prochain avancement du remplissage.
                } finally { running = false }
            }
        }
        // Remplissage de la recherche en pause pendant toute vidéo (une connexion ouverte = une vidéo en cours).
        scope.launch {
            controller.active.collect { runtime.setPlaying(it != null) }
        }
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
                    initialVersions = versions.current(home.iptv.shared.meta.MediaKind.MOVIE),
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
                        initialVersions = versions.current(home.iptv.shared.meta.MediaKind.SERIES),
                        languages = (profile.settings.languages ?: home.iptv.shared.versions.LanguagePrefs()).also { versions.languages = it },
                    )
                },
                episodePlayback = episodePlayback,
                requestFlush = { progressFlusher.request() },
                // Pas de recherche vocale pour l'instant (décision du propriétaire, 16 nov.).
                searchFactory = home.iptv.tv.search.searchFactory(repo, seriesRepo, movieRepo, runtime.filler, versions, person = app::personCredits),
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
                sportPrewarm = { profiles ->
                    // Après Séries et Films (0.41).
                    app.appScope.launch {
                        val order = home.iptv.tv.diag.StartupOrder.app
                        order.turn(home.iptv.tv.diag.StartupOrder.Step.SPORT)
                        try { sport.prewarm(profiles, scope)?.join() } finally { order.done(home.iptv.tv.diag.StartupOrder.Step.SPORT) }
                    }
                },
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
        home.iptv.tv.diag.PerfMonitor.data.event("App à l'écran")
        liveSessions?.resume()
        searchRuntime?.start()
        flusher?.request() // positions gardées pendant une absence ou un redémarrage
        // Mise à jour : vérifiée au retour (au plus une fois par heure) ; retour du réglage « applis inconnues ».
        (application as IptvApp).updater.let { u ->
            u.check(); u.permissionMaybeGranted()
            updateLoop?.cancel()
            // Une télé peut rester allumée longtemps sur l'app : nouvelle vérification toutes les 6 h.
            updateLoop = scope.launch { while (true) { kotlinx.coroutines.delay(home.iptv.tv.update.CHECK_EVERY_MS); u.check(force = true); (application as IptvApp).extraAccountsCheck.runPending() } }
        }
        // Comptes IPTV supplémentaires : revérifiés une fois par jour au plus (date de fin), à chaque retour à l'écran.
        (application as IptvApp).let { a -> a.appScope.launch { a.extraAccountsCheck.runPending() } }
        // Connexions en cours et message du fournisseur de chaque compte, pour la page iPhone : toutes les 10 min à l'écran.
        statusLoop?.cancel()
        statusLoop = scope.launch(Dispatchers.IO) {
            while (true) { (application as IptvApp).refreshAccountsStatus(); kotlinx.coroutines.delay(ACCOUNTS_STATUS_MS) }
        }
        // Retour du réseau : les positions gardées pendant la coupure partent.
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { flusher?.request() }
        }
        runCatching { getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
    }

    // Une seule connexion chez le fournisseur : le lecteur est libéré dès que l'app quitte l'écran.
    override fun onStop() {
        home.iptv.tv.diag.PerfMonitor.data.event("App quittée (arrière-plan)")
        updateLoop?.cancel(); updateLoop = null
        statusLoop?.cancel(); statusLoop = null
        searchRuntime?.stop()
        moviePlayback?.stop()
        networkCallback?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) } }
        networkCallback = null
        // La position de l'épisode en cours est mise dans la file avant que le lecteur soit fermé.
        playback?.stop()
        player?.closeAndRelease()
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
