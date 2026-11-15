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
import home.iptv.shared.sync.ProfilePrefs
import home.iptv.tv.data.HomeTab
import home.iptv.tv.data.LocalStore
import home.iptv.tv.nav.SectionBar
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
                    if (profile == null) WhoIsWatchingScreen(s.profiles, s.offline) { picked = it }
                    else HomeForProfile(profile, sessions, player, urlFor, store, seriesHome, seriesDetail, episodePlayback, requestFlush) { picked = null }
                }
            is StartupUi.Failed -> Centered {
                Text(s.message, color = IptvColors.Text, fontSize = 28.sp)
                RetryButton { vm.start() }
            }
        }
        DiagnosticOverlay(player)
    }
    }
}

/** Ce que l'utilisateur a demandé depuis l'accueil Séries : ouvrir une fiche ou lire un épisode. */
private sealed interface SeriesAction {
    data class Open(val seriesId: String, val categoryId: String?) : SeriesAction
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
    onChangeProfile: () -> Unit,
) {
    var session by remember(profile.id) { mutableStateOf<LiveSession?>(null) }
    DisposableEffect(profile.id) {
        val s = sessions.open(profile)
        session = s
        onDispose { sessions.close(s) }
    }
    var tab by remember(profile.id) { mutableStateOf(store.lastTab(profile.id) ?: HomeTab.DIRECT) }
    // Changement de section depuis la barre : la pastille qui avait le focus disparaît avec l'ancien écran ; le focus
    // va à la pastille de la nouvelle section (sinon plus rien n'a le focus et Bas ne fait rien : test SectionBarDpadTest).
    var focusBarAfterSwitch by remember(profile.id) { mutableStateOf(false) }
    val select = { target: HomeTab ->
        if (target != tab) {
            if (target == HomeTab.SERIES) player.close()
            tab = target
            focusBarAfterSwitch = true
            store.saveLastTab(profile.id, target)
        }
    }
    // Quelle que soit la manière d'arriver sur Séries (dernier onglet retenu compris), aucun flux ne reste ouvert.
    LaunchedEffect(tab) { if (tab == HomeTab.SERIES) player.close() }
    val barRequester = remember { FocusRequester() }
    val bar: @Composable () -> Unit = { SectionBar(tab, select, barRequester) }
    LaunchedEffect(tab, focusBarAfterSwitch) {
        if (!focusBarAfterSwitch) return@LaunchedEffect
        withFrameNanos { }
        runCatching { barRequester.requestFocus() }
        focusBarAfterSwitch = false
    }
    val s = session ?: return
    when (tab) {
        HomeTab.DIRECT -> LiveRoute(s.vm, profile, player, urlFor, s.epg, onChangeProfile, topBarTitle = bar)
        HomeTab.SERIES -> SeriesTab(profile, s, seriesHome, seriesDetail, episodePlayback, requestFlush, player, barRequester, bar, onChangeProfile)
    }
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
) {
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
    BackHandler(enabled = pending == null && !barFocused && !focusFailed) {
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
        Box(Modifier.fillMaxSize().focusBlockedWhen(pending != null)) {
            SeriesHomeScreen(
                vm, profile, onChangeProfile, topBarTitle = bar, onBarFocus = { barFocused = it },
                onOpenSeries = { id, category -> pending = SeriesAction.Open(id, category) },
                onPlayEpisode = { pending = SeriesAction.Play(it) },
                detailOpen = pending != null,
            )
        }
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
                        SeriesDetailScreen(detail, onPlay = { detailPlay = it }, onBack = { pending = null }, playing = detailPlay != null)
                    }
                    SideEffect { Diagnostics.screen = if (detailPlay == null) "Séries · fiche" else "Séries · lecture" }
                    detailPlay?.let { request -> EpisodePlayerScreen(request, playback, player) { detailPlay = null } }
                }
                is SeriesAction.Play -> {
                    SideEffect { Diagnostics.screen = "Séries · lecture" }
                    EpisodePlayerScreen(action.request, playback, player) { pending = null }
                }
            }
        }
    }
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
