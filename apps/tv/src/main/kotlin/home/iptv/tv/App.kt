package home.iptv.tv

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import home.iptv.shared.sync.Profile
import home.iptv.shared.search.SearchSection
import home.iptv.tv.search.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.isActive
import home.iptv.shared.sync.ProfilePrefs
import home.iptv.tv.data.HomeTab
import kotlinx.coroutines.flow.first
import home.iptv.tv.data.LocalStore
import home.iptv.tv.nav.SectionBar
import home.iptv.tv.movies.*
import home.iptv.tv.series.PlayEpisodeRequest
import home.iptv.tv.series.SeriesDetailFactory
import home.iptv.tv.series.SeriesDetailScreen
import home.iptv.tv.series.SeriesHomeScreen
import home.iptv.tv.series.SeriesHomeViewModel
import home.iptv.tv.series.EpisodePlaybackFactory
import home.iptv.tv.series.EpisodePlayerScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import home.iptv.shared.xtream.LiveStream
import home.iptv.tv.live.LiveSession
import home.iptv.tv.live.LiveSessions
import home.iptv.tv.live.LiveRoute
import home.iptv.tv.live.focusBlockedWhen
import home.iptv.tv.diag.DiagnosticOverlay
import home.iptv.tv.diag.Diagnostics
import androidx.compose.runtime.SideEffect
import home.iptv.tv.player.PlayerController
import home.iptv.tv.startup.PairingScreen
import home.iptv.tv.startup.StartupUi
import home.iptv.tv.startup.StartupViewModel
import home.iptv.tv.startup.WhoIsWatchingScreen
import home.iptv.tv.theme.IptvColors
import home.iptv.tv.theme.IptvTheme

/** Adresse de la page de configuration (à confirmer au déploiement, tâche 8). */
object ConfigPage {
    const val URL = "https://iptv-home-config.vercel.app"
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun App(
    vm: StartupViewModel,
    sessions: LiveSessions,
    player: PlayerController,
    urlFor: (LiveStream) -> String?,
    store: LocalStore,
    seriesHome: (Profile, StateFlow<ProfilePrefs?>, CoroutineScope) -> SeriesHomeViewModel,
    seriesDetail: SeriesDetailFactory,
    episodePlayback: EpisodePlaybackFactory,
    requestFlush: () -> Unit,
    movieHome: (Profile, StateFlow<ProfilePrefs?>, CoroutineScope) -> MovieHomeViewModel,
    movieDetail: MovieDetailFactory,
    moviePlayback: MoviePlaybackFactory,
    searchFactory: SearchFactory? = null,
    voice: (((String) -> Unit) -> Unit)? = null,
    searchProfile: (StateFlow<ProfilePrefs?>?, SearchSection) -> Unit = { _, _ -> },
    sportFactory: home.iptv.tv.sport.SportFactory? = null,
    /** G : prépare l'onglet Sport des profils qui s'ouvrent dessus, pendant « Qui regarde ? » (ne bloque rien). */
    sportPrewarm: (List<Profile>) -> Unit = {},
) {
    val ui by vm.ui.collectAsState()
    var picked by remember { mutableStateOf<Profile?>(null) }
    LaunchedEffect(Unit) { vm.start() }
    IptvTheme {
    Box(Modifier.fillMaxSize().background(IptvColors.Background)) {
        when (val s = ui) {
            StartupUi.Loading -> Centered { Text("Chargement…", color = IptvColors.TextSecondary, fontSize = 28.sp) }
            is StartupUi.Pairing -> PairingScreen(s, ConfigPage.URL)
            is StartupUi.Ready ->
                picked.let { profile ->
                    if (profile == null) {
                        val ctx = androidx.compose.ui.platform.LocalContext.current
                        val app = ctx.applicationContext as? IptvApp
                        // Fin de l'abonnement : dernière date connue tout de suite, puis celle du fournisseur.
                        var expiresAt by remember { mutableStateOf(app?.store?.iptvExpiresAt()) }
                        androidx.compose.runtime.LaunchedEffect(Unit) { app?.updater?.check(force = true) }
                        androidx.compose.runtime.LaunchedEffect(s.profiles) { sportPrewarm(s.profiles) }
                        androidx.compose.runtime.LaunchedEffect(Unit) {
                            app?.let { a -> expiresAt = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { a.checkIptvAccount() } }
                        }
                        val notice = home.iptv.shared.account.expiryNotice(expiresAt, System.currentTimeMillis() / 1000,
                            java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000)
                        val today = remember { java.time.LocalDate.now().let { home.iptv.shared.account.Day(it.year, it.monthValue, it.dayOfMonth) } }
                        val season = home.iptv.shared.account.activeSeason(today, s.seasons)
                        var health by remember { mutableStateOf(false) }
                        if (health && app != null) home.iptv.tv.health.HealthScreen(app.healthCheck) { health = false }
                        else WhoIsWatchingScreen(s.profiles, s.offline, theme = s.welcomeTheme, deviceLabel = s.deviceLabel, notice = notice,
                            season = season, greeting = season?.let { home.iptv.shared.account.seasonGreeting(it, today) }, onRestart = { home.iptv.tv.startup.restartApp(ctx) },
                            onHealth = if (app != null) { { health = true } } else null) { picked = it }
                    }
                    else home.iptv.tv.theme.IptvTheme(home.iptv.tv.theme.tokensFor(profile.color, profile.theme, profile.settings.look)) { androidx.compose.runtime.CompositionLocalProvider(home.iptv.tv.player.LocalPlayerStyle provides home.iptv.tv.player.PlayerStyle.of(profile.settings.playerStyle)) { home.iptv.tv.update.UpdateHost { HomeForProfile(profile, sessions, player, urlFor, store, seriesHome, seriesDetail, episodePlayback, requestFlush, movieHome, movieDetail, moviePlayback, searchFactory, voice, searchProfile, sportFactory) { picked = null } } } }
                }
            is StartupUi.Failed -> Centered {
                Text(s.message, color = IptvColors.Text, fontSize = 28.sp)
                RetryButton { vm.start() }
            }
        }
        BusyNotice(player)
        home.iptv.tv.player.TakeOverDialog(player)
        ProviderAlert()
        DiagnosticOverlay(player)
    }
    }
}

/** Panne vérifiée (fournisseur muet ou plus d'internet) : message en haut de l'écran, sans prendre le focus. */
@Composable
private fun ProviderAlert() {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as? IptvApp ?: return
    val text by app.providerWatch.alert.collectAsState()
    TopMessage(text ?: return)
}

@Composable
private fun TopMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(top = 40.dp), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
        Text(
            text, color = IptvColors.Text, fontSize = 22.sp,
            modifier = Modifier.padding(horizontal = 60.dp).background(IptvColors.Background.copy(alpha = 0.92f), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .padding(horizontal = 28.dp, vertical = 16.dp),
        )
    }
}

/**
 * Tous les comptes IPTV du foyer sont pris (une vidéo par compte) : message en haut de l'écran pendant 8 s, sans
 * prendre le focus (« Les 2 comptes IPTV sont déjà utilisés : Maha sur Télé du salon. »).
 */
@Composable
private fun BusyNotice(player: PlayerController) {
    val message by player.busy.collectAsState()
    var shown by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(message) {
        shown = message
        if (message != null) { kotlinx.coroutines.delay(8_000); shown = null }
    }
    TopMessage(shown ?: return)
}

/** Ce que l'utilisateur a demandé depuis l'accueil Séries : ouvrir une fiche ou lire un épisode. */
private sealed interface SeriesAction {
    data class Open(val seriesId: String, val categoryId: String?, val versions: List<home.iptv.shared.versions.Version> = emptyList()) : SeriesAction
    data class Play(val request: PlayEpisodeRequest) : SeriesAction
}

/**
 * Un profil : sa session (préférences, direct) s'ouvre à l'arrivée et se ferme au départ (jamais pendant la
 * composition). La barre des sections (Direct / Séries) choisit l'écran ; le dernier choix est retenu par profil.
 * Passer à Séries ferme d'abord tout lecteur : une seule connexion chez le fournisseur.
 */
@Composable
internal fun HomeForProfile(
    profile: Profile,
    sessions: LiveSessions,
    player: PlayerController,
    urlFor: (LiveStream) -> String?,
    store: LocalStore,
    seriesHome: (Profile, StateFlow<ProfilePrefs?>, CoroutineScope) -> SeriesHomeViewModel,
    seriesDetail: SeriesDetailFactory,
    episodePlayback: EpisodePlaybackFactory,
    requestFlush: () -> Unit,
    movieHome: ((Profile, StateFlow<ProfilePrefs?>, CoroutineScope) -> MovieHomeViewModel)? = null,
    movieDetail: MovieDetailFactory? = null,
    moviePlayback: MoviePlaybackFactory? = null,
    searchFactory: SearchFactory? = null,
    voice: (((String) -> Unit) -> Unit)? = null,
    searchProfile: (StateFlow<ProfilePrefs?>?, SearchSection) -> Unit = { _, _ -> },
    sportFactory: home.iptv.tv.sport.SportFactory? = null,
    onChangeProfile: () -> Unit,
) {
    var session by remember(profile.id) { mutableStateOf<LiveSession?>(null) }
    DisposableEffect(profile.id) {
        val s = sessions.open(profile)
        session = s
        onDispose { sessions.close(s) }
    }
    // Sections choisies sur l'iPhone pour ce profil (la sœur peut cacher Direct et Sport, par exemple).
    val tabs = remember(profile.settings.sections) { home.iptv.tv.data.visibleTabs(profile.settings.sections) }
    var tab by remember(profile.id) { mutableStateOf(home.iptv.tv.data.openingTab(profile.home, profile.settings.sections)) }
    // Changement de section depuis la barre : la pastille qui avait le focus disparaît avec l'ancien écran ; le focus
    // va à la pastille de la nouvelle section (sinon plus rien n'a le focus et Bas ne fait rien : test SectionBarDpadTest).
    var focusBarAfterSwitch by remember(profile.id) { mutableStateOf(false) }
    val select = { target: HomeTab ->
        if (target != tab && target in tabs) {
            if (target != HomeTab.DIRECT) player.close()
            tab = target
            focusBarAfterSwitch = true
            store.saveLastTab(profile.id, target)
        }
    }
    // Quelle que soit la manière d'arriver sur Séries (dernier onglet retenu compris), aucun flux ne reste ouvert.
    LaunchedEffect(tab) { if (tab != HomeTab.DIRECT) player.close() }
    val barRequester = remember { FocusRequester() }
    var searchOpen by remember(profile.id, tab) { mutableStateOf(false) }
    var restoreSearchButton by remember(profile.id) { mutableStateOf(false) }
    val searchRequester = remember { FocusRequester() }
    val bar: @Composable () -> Unit = { SectionBar(tab, select, barRequester, onSearch = if (searchFactory != null && tab != HomeTab.SPORT) { { searchOpen = true } } else null, searchRequester = searchRequester, tabs = tabs) }
    val closeSearch = { searchOpen = false; restoreSearchButton = true }
    LaunchedEffect(restoreSearchButton) {
        if (restoreSearchButton) { withFrameNanos { }; runCatching { searchRequester.requestFocus() }; restoreSearchButton = false }
    }
    LaunchedEffect(tab, focusBarAfterSwitch) {
        if (!focusBarAfterSwitch) return@LaunchedEffect
        withFrameNanos { }
        runCatching { barRequester.requestFocus() }
        focusBarAfterSwitch = false
    }
    val s = session ?: return
    val section = when (tab) { HomeTab.DIRECT, HomeTab.SPORT -> SearchSection.LIVE; HomeTab.SERIES -> SearchSection.SERIES; HomeTab.MOVIES -> SearchSection.MOVIES }
    DisposableEffect(s, section) {
        searchProfile(s.watcher.prefs, section)
        onDispose { searchProfile(null, section) }
    }
    val searchScope = rememberCoroutineScope()
    val searches = remember(s) { mutableMapOf<SearchSection, SearchViewModel>() }
    val searchVm = searchFactory?.let { searches.getOrPut(section) { it(section, s, searchScope) } }
    // Puces de la recherche (Films, Séries, Chaînes) : on passe à l'onglet voulu et la recherche s'y rouvre avec le texte.
    var reopenSearch by remember(profile.id) { mutableStateOf<String?>(null) }
    val switchSearch: (SearchSection) -> Unit = { target ->
        reopenSearch = searchVm?.ui?.value?.query.orEmpty()
        val to = when (target) { SearchSection.LIVE -> HomeTab.DIRECT; SearchSection.SERIES -> HomeTab.SERIES; SearchSection.MOVIES -> HomeTab.MOVIES }
        if (to != tab && to in tabs) {
            if (to != HomeTab.DIRECT) player.close()
            tab = to
            store.saveLastTab(profile.id, to)
        }
    }
    LaunchedEffect(tab, reopenSearch, searchVm) {
        val q = reopenSearch ?: return@LaunchedEffect
        if (searchVm != null) { searchVm.setQuery(q); searchOpen = true }
        reopenSearch = null
    }
    val stillOpen by rememberUpdatedState(searchOpen)
    val currentSection by rememberUpdatedState(section)
    var voiceEpoch by remember(s) { mutableStateOf(0) }
    LaunchedEffect(searchOpen, section) { voiceEpoch++ }
    val speak: (() -> Unit)? = if (voice != null && searchVm != null) { {
        val epoch = voiceEpoch
        val prefs = s.watcher.prefs.value
        voice { text ->
            if (searchScope.isActive && stillOpen && currentSection == section && voiceEpoch == epoch && s.watcher.prefs.value == prefs) searchVm.setQuery(text)
        }
    } } else null
    val searchChips = remember(tabs) {
        listOf(SearchSection.MOVIES to HomeTab.MOVIES, SearchSection.SERIES to HomeTab.SERIES, SearchSection.LIVE to HomeTab.DIRECT).filter { it.second in tabs }.map { it.first }
    }
    Box(Modifier.fillMaxSize()) { androidx.compose.runtime.CompositionLocalProvider(home.iptv.tv.search.LocalSearchSections provides searchChips) {
        when (tab) {
            HomeTab.DIRECT -> {
                Box(Modifier.fillMaxSize().focusBlockedWhen(searchOpen)) {
                    LiveRoute(s.vm, profile, player, urlFor, s.epg, onChangeProfile, topBarTitle = bar, searchOpen = searchOpen, onListVisible = s::setFastPoll)
                }
                if (searchOpen && searchVm != null) SearchScreen(searchVm, {
                    closeSearch()
                    // Le retour du Direct doit donner le focus à la chaîne, pas au bouton de la barre.
                    restoreSearchButton = false
                    s.vm.reveal(it.categoryId, it.anchorSourceId ?: it.id)
                }, closeSearch, speak, null, switchSearch)
            }
            HomeTab.SERIES -> SeriesTab(profile, s, seriesHome, seriesDetail, episodePlayback, requestFlush, player, barRequester, bar, onChangeProfile, searchVm, searchOpen, closeSearch, speak, switchSearch)
            HomeTab.MOVIES -> if (movieHome != null && movieDetail != null && moviePlayback != null) MovieTab(profile, s, movieHome, movieDetail, moviePlayback, requestFlush, player, barRequester, bar, onChangeProfile, searchVm, searchOpen, closeSearch, speak, switchSearch)
            HomeTab.SPORT -> if (sportFactory != null) {
                val sportScope = rememberCoroutineScope()
                val sportVm = remember(s) { sportFactory(profile, s, sportScope) }
                // Les copies des chaînes (groupes) sont préparées par le Direct : sans passage par l'onglet Direct
                // (profil qui s'ouvre sur Sport), elles n'existaient pas et toute chaîne était « indisponible »
                // (essai TCL 0.14.0). On les prépare dès l'arrivée sur Sport.
                LaunchedEffect(s) { s.vm.start() }
                home.iptv.tv.sport.SportTab(sportVm, profile, player, { ch ->
                    // Chaîne d'une catégorie Sport (événement, équipe), peut-être masquée dans le Direct : lue par son identifiant.
                    if (ch.direct) return@SportTab urlFor(LiveStream(ch.anchorSourceId, ch.title, "", null, null, null))
                    s.vm.start()
                    kotlinx.coroutines.withTimeoutOrNull(20_000) { s.vm.groupsRevision.first { it > 0 } }
                    // Meilleure copie visible du groupe (dernière bonne copie d'abord), comme le Direct.
                    val group = s.vm.groupOf(ch.anchorSourceId)
                    val stream = group?.let { s.vm.playbackFor(home.iptv.tv.live.GroupRow(it, ch.anchorSourceId), stillCurrent = { true }) }
                    stream?.let(urlFor)
                }, bar, barRequester, onChangeProfile)
            }
        }
    } }
}

@Composable
private fun SeriesTab(
    profile: Profile,
    session: LiveSession,
    seriesHome: (Profile, StateFlow<ProfilePrefs?>, CoroutineScope) -> SeriesHomeViewModel,
    seriesDetail: SeriesDetailFactory,
    episodePlayback: EpisodePlaybackFactory,
    requestFlush: () -> Unit,
    player: PlayerController,
    barRequester: FocusRequester,
    bar: @Composable () -> Unit,
    onChangeProfile: () -> Unit,
    searchVm: SearchViewModel?, searchOpen: Boolean, closeSearch: () -> Unit, speak: (() -> Unit)?,
    onSearchSection: (SearchSection) -> Unit = {},
) {
    var searchRestore by remember { mutableStateOf<String?>(null) }
    // Les opérations réseau et disque de l'accueil tournent hors du fil principal (le ViewModel les envoie sur IO).
    val scope = remember(session) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    DisposableEffect(scope) { onDispose { scope.cancel() } }
    val vm = remember(session) { seriesHome(profile, session.watcher.prefs, scope) }
    // Un lecteur d'épisodes par profil : changer de profil enregistre la position pour ce profil et ferme le flux.
    val playback = remember(session) { episodePlayback(profile, session.watcher.prefs) }
    DisposableEffect(playback) { onDispose { playback.stop() } }
    // L'onglet Séries s'ouvre : on envoie ce qui attend dans la file.
    LaunchedEffect(Unit) { requestFlush() }
    var pending by remember(session) { mutableStateOf<SeriesAction?>(null) }
    val indexes = home.iptv.tv.feature.LocalVersionIndexes.current
    // À la fermeture d'une fiche ou d'une lecture, l'accueil relit la progression (Reprendre, Continuer).
    var wasPending by remember { mutableStateOf(false) }
    LaunchedEffect(pending != null) {
        if (pending == null && wasPending) vm.reloadProgress()
        wasPending = pending != null
    }
    var barFocused by remember { mutableStateOf(false) }
    var focusFailed by remember { mutableStateOf(false) }
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    // Retour : d'abord la barre des sections (jamais de piège), puis on quitte comme d'habitude.
    BackHandler(enabled = !searchOpen && pending == null && !barFocused && !focusFailed) {
        if (!runCatching { barRequester.requestFocus() }.getOrDefault(false)) focusFailed = true
    }
    LaunchedEffect(focusFailed) {
        if (focusFailed) {
            dispatcher?.onBackPressed()
            focusFailed = false
        }
    }
    Box(Modifier.fillMaxSize()) {
        SideEffect { if (pending == null) Diagnostics.screen = "Séries · accueil" }
        Box(Modifier.fillMaxSize().focusBlockedWhen(pending != null || searchOpen)) {
            SeriesHomeScreen(
                vm, profile, onChangeProfile, topBarTitle = bar, onBarFocus = { barFocused = it },
                onOpenSeries = { id, category -> pending = SeriesAction.Open(id, category) },
                onPlayEpisode = { pending = SeriesAction.Play(it) },
                detailOpen = pending != null || searchOpen,
            )
        }
        val langs = profile.settings.languages ?: home.iptv.shared.versions.LanguagePrefs()
        if (searchOpen && pending == null && searchVm != null) SearchScreen(searchVm, {
            searchRestore = it.id
            val best = home.iptv.shared.versions.bestVersion(it.versions, langs)
            pending = SeriesAction.Open(best?.id ?: it.id, best?.categoryId ?: it.categoryId, it.versions)
        }, closeSearch, speak, searchRestore, onSearchSection, langs)
        pending?.let { action ->
            when (action) {
                is SeriesAction.Open -> {
                    // Une fiche par ouverture (clé = identifiant de série) ; sa lecture passe par EpisodePlayerScreen.
                    val detailScope = remember(action) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
                    DisposableEffect(detailScope) { onDispose { detailScope.cancel() } }
                    val detail = remember(action) { seriesDetail(profile, session.watcher.prefs, action.seriesId, action.categoryId, detailScope) }
                    // La lecture se superpose à la fiche : Retour revient à la fiche, pas à l'accueil.
                    var detailPlay by remember(action) { mutableStateOf<PlayEpisodeRequest?>(null) }
                    Box(Modifier.fillMaxSize().focusBlockedWhen(detailPlay != null)) {
                        val shown = shownVersions(action.versions, indexes, home.iptv.shared.meta.MediaKind.SERIES, action.seriesId, langs)
                        androidx.compose.runtime.CompositionLocalProvider(home.iptv.tv.feature.LocalVersionChoice provides
                            home.iptv.tv.feature.VersionChoice(shown, action.seriesId, onSplit = indexes?.let { ix -> { v -> splitVersion(ix, home.iptv.shared.meta.MediaKind.SERIES, v) { pending = SeriesAction.Open(action.seriesId, action.categoryId) } } }) { v -> pending = SeriesAction.Open(v.id, v.categoryId, shown) }) {
                            SeriesDetailScreen(detail, onPlay = { detailPlay = it }, onBack = { pending = null }, playing = detailPlay != null)
                        }
                    }
                    SideEffect { Diagnostics.screen = if (detailPlay == null) "Séries · fiche" else "Séries · lecture" }
                    detailPlay?.let { request -> home.iptv.tv.theme.VideoTheme { EpisodePlayerScreen(request, playback, player) { detailPlay = null } } }
                }
                is SeriesAction.Play -> {
                    SideEffect { Diagnostics.screen = "Séries · lecture" }
                    home.iptv.tv.theme.VideoTheme { EpisodePlayerScreen(action.request, playback, player) { pending = null } }
                }
            }
        }
    }
}

@Composable
private fun MovieTab(
    profile: Profile, session: LiveSession,
    homeFactory: (Profile, StateFlow<ProfilePrefs?>, CoroutineScope) -> MovieHomeViewModel,
    detailFactory: MovieDetailFactory, playbackFactory: MoviePlaybackFactory,
    requestFlush: () -> Unit, player: PlayerController, barRequester: FocusRequester,
    bar: @Composable () -> Unit, onChangeProfile: () -> Unit,
    searchVm: SearchViewModel?, searchOpen: Boolean, closeSearch: () -> Unit, speak: (() -> Unit)?,
    onSearchSection: (SearchSection) -> Unit = {},
) {
    val scope = remember(session) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    DisposableEffect(scope) { onDispose { scope.cancel() } }
    val vm = remember(session) { homeFactory(profile, session.watcher.prefs, scope) }
    val playback = remember(session) { playbackFactory(profile, session.watcher.prefs) }
    DisposableEffect(playback) { onDispose { playback.stop() } }
    var detail by remember(session) { mutableStateOf<Pair<String, String?>?>(null) }
    val indexes = home.iptv.tv.feature.LocalVersionIndexes.current
    /** Versions du titre ouvert depuis la recherche (choix de version sur la fiche) ; vide sinon. */
    var detailVersions by remember(session) { mutableStateOf<List<home.iptv.shared.versions.Version>>(emptyList()) }
    var play by remember(session) { mutableStateOf<PlayMovieRequest?>(null) }
    var barFocused by remember { mutableStateOf(false) }
    var pendingBefore by remember { mutableStateOf(false) }
    var searchRestore by remember { mutableStateOf<String?>(null) }
    val pending = detail != null || play != null
    LaunchedEffect(pending) { if (!pending && pendingBefore) vm.reloadProgress(); pendingBefore = pending }
    LaunchedEffect(Unit) { requestFlush() }
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    MovieHomeBackHandler(pending || searchOpen, barFocused, { barRequester.requestFocus() }, { dispatcher?.onBackPressed() })
    Box(Modifier.fillMaxSize()) {
        SideEffect { Diagnostics.screen = if (play != null) "Films · lecture" else if (detail != null) "Films · fiche" else "Films · accueil" }
        Box(Modifier.fillMaxSize().focusBlockedWhen(pending || searchOpen)) {
            MovieHomeScreen(vm, profile, onChangeProfile, bar, { barFocused = it }, { id, cat -> detailVersions = emptyList(); detail = id to cat }, { play = it }, pending || searchOpen)
        }
        val langs = profile.settings.languages ?: home.iptv.shared.versions.LanguagePrefs()
        if (searchOpen && !pending && searchVm != null) SearchScreen(searchVm, {
            searchRestore = it.id
            val best = home.iptv.shared.versions.bestVersion(it.versions, langs)
            detailVersions = it.versions
            detail = (best?.id ?: it.id) to (best?.categoryId ?: it.categoryId)
        }, closeSearch, speak, searchRestore, onSearchSection, langs)
        detail?.let { target ->
            val detailScope = remember(target) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
            DisposableEffect(detailScope) { onDispose { detailScope.cancel() } }
            val detailVm = remember(target) { detailFactory(profile, session.watcher.prefs, target.first, target.second, detailScope) }
            Box(Modifier.fillMaxSize().focusBlockedWhen(play != null)) {
                val shown = shownVersions(detailVersions, indexes, home.iptv.shared.meta.MediaKind.MOVIE, target.first, langs)
                androidx.compose.runtime.CompositionLocalProvider(home.iptv.tv.feature.LocalVersionChoice provides
                    home.iptv.tv.feature.VersionChoice(shown, target.first, onSplit = indexes?.let { ix -> { v -> splitVersion(ix, home.iptv.shared.meta.MediaKind.MOVIE, v) { detailVersions = emptyList(); detail = target.first to target.second } } }) { v -> detailVersions = shown; detail = v.id to v.categoryId }) {
                    MovieDetailScreen(detailVm, { play = it }, { detail = null }, play != null)
                }
            }
        }
        play?.let { home.iptv.tv.theme.VideoTheme { MoviePlayerScreen(it, playback, player) { play = null } } }
    }
}

/**
 * Versions proposées sur une fiche : celles de la recherche, sinon celles de l'index (fiche ouverte depuis l'accueil
 * ou Ma liste) ; « Seulement mes langues » retire les autres langues, sauf la version ouverte.
 */
private fun shownVersions(
    given: List<home.iptv.shared.versions.Version>, indexes: home.iptv.tv.feature.VersionIndexes?,
    kind: home.iptv.shared.meta.MediaKind, id: String, langs: home.iptv.shared.versions.LanguagePrefs,
): List<home.iptv.shared.versions.Version> {
    val all = given.ifEmpty { indexes?.current(kind)?.versionsOf(id).orEmpty() }
    val mine = home.iptv.shared.versions.myVersions(all, langs).mapTo(HashSet()) { it.id }
    return all.filter { it.id == id || it.id in mine }
}

/** « Ce n'est pas le même titre » : sépare [v] pour la maison ; vrai si c'est fait ([done] rouvre la fiche seule). */
private suspend fun splitVersion(indexes: home.iptv.tv.feature.VersionIndexes, kind: home.iptv.shared.meta.MediaKind, v: home.iptv.shared.versions.Version, done: () -> Unit): Boolean {
    val ok = kotlinx.coroutines.withContext(Dispatchers.IO) {
        try { indexes.split(kind, v.id); true } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { false }
    }
    if (ok) done()
    return ok
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterVertically),
    ) { content() }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RetryButton(onClick: () -> Unit) {
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .focusRequester(requester)
            .clip(shape)
            .background(IptvColors.Accent)
            .border(if (focused) 4.dp else 0.dp, if (focused) IptvColors.Focus else IptvColors.Accent, shape)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent {
                if (it.type == KeyEventType.KeyUp && (it.key == Key.DirectionCenter || it.key == Key.Enter)) {
                    onClick(); true
                } else false
            }
            .focusable()
            .padding(horizontal = 40.dp, vertical = 16.dp),
    ) { Text("Réessayer", color = IptvColors.Text, fontSize = 24.sp) }
}
