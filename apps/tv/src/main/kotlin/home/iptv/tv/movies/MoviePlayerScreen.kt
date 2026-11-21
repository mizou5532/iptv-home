package home.iptv.tv.movies

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.*
import androidx.tv.material3.*
import home.iptv.tv.live.*
import home.iptv.tv.player.*
import home.iptv.tv.series.TrackPanel
import home.iptv.tv.theme.LocalIptvTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MoviePlayerScreen(
    request: PlayMovieRequest, playback: MoviePlayback, controller: PlayerController,
    /** Autres versions du même film (0.41) : proposées si celle-ci ne démarre pas. */
    others: List<home.iptv.shared.versions.Version> = emptyList(),
    onTryOther: (home.iptv.shared.versions.Version) -> Unit = {},
    /** L'image est arrivée (version qui marche). */
    onStarted: (PlayMovieRequest) -> Unit = {},
    onClose: () -> Unit,
) {
    val t = LocalIptvTokens.current
    val state by playback.state.collectAsState()
    val tracks by playback.tracks.collectAsState()
    val active by controller.active.collectAsState()
    val exo = active as? ExoPlayerPlayer
    val playing = state as? MoviePlaybackState.Playing
    var controls by remember { mutableStateOf(false) }; var panel by remember { mutableStateOf(false) }; var paused by remember(active) { mutableStateOf(active?.let { !it.snapshot().playing } ?: false) }
    var activity by remember { mutableStateOf(0) }
    val keys = remember { FocusRequester() }; val button = remember { FocusRequester() }
    val seek = remember { SeekBatch() }
    SeekBatchEffect(seek, active)
    val close by rememberUpdatedState(onClose)
    LaunchedEffect(request) { playback.begin(request); playback.state.first { it is MoviePlaybackState.Idle }; close() }
    DisposableEffect(playback) { onDispose { playback.stop() } }
    LaunchedEffect(exo, exo?.ended) { if (exo?.ended == true) playback.onEnded() }
    LaunchedEffect(exo, exo?.failed) { if (exo?.failed == true) playback.onFailed() }
    LaunchedEffect(exo, exo?.trackList) { exo?.trackList?.let(playback::onTracks) }
    val started by rememberUpdatedState(onStarted)
    LaunchedEffect(request, exo, exo?.started) { if (exo?.started == true && state is MoviePlaybackState.Playing) started(request) }
    LaunchedEffect(active) { while (true) {
        delay(1000)
        active?.snapshot()?.let { s -> if (s.ready && !s.ended && !s.failed) playback.onTick((s.positionMs/1000).toInt(), (s.durationMs.coerceAtLeast(0)/1000).toInt(), s.playing) }
    } }
    val overlay = state is MoviePlaybackState.Failed || state is MoviePlaybackState.Finished
    val back = { when { panel -> panel = false; controls && playing != null -> controls = false; else -> onClose() } }
    BackHandler(onBack = back)
    // En pause, les commandes restent ; en lecture, elles disparaissent après 6 s sans touche.
    LaunchedEffect(controls, paused, activity, panel) { if (controls && !paused && !panel) { delay(6000); controls = false } }
    LaunchedEffect(controls, panel, overlay, state, others.firstOrNull()?.id) {
        if (!panel) { withFrameNanos { }; runCatching { (if (controls || overlay) button else keys).requestFocus() } }
    }
    Box(Modifier.fillMaxSize().background(t.videoBackground).backKey(back).focusRequester(keys).onKeyEvent { e ->
        if (controls || panel || overlay) false else when {
            // Appuis regroupés : un seul saut chez le fournisseur quand la touche est relâchée (voir SeekBatch).
            e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft -> { seek.nudge(active, -10000); true }
            e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight -> { seek.nudge(active, 10000); true }
            // OK : pause tout de suite et affiche les commandes ; OK sur ▶ reprend.
            e.type == KeyEventType.KeyUp && (e.key == Key.DirectionCenter || e.key == Key.Enter) -> { paused = true; active?.setPaused(true); controls = true; true }
            e.type == KeyEventType.KeyDown && (e.key == Key.DirectionUp || e.key == Key.DirectionDown) -> { controls = true; true }
            // Lecture/Pause, et les touches Lecture et Pause séparées de certaines télécommandes (comme les séries).
            e.type == KeyEventType.KeyDown && (e.key == Key.MediaPlayPause || e.key == Key.MediaPlay || e.key == Key.MediaPause) -> {
                paused = if (e.key == Key.MediaPlayPause) !paused else e.key == Key.MediaPause
                active?.setPaused(paused); true
            }
            else -> false
        }
    }.focusable()) {
        if (exo != null && playing != null) {
            VideoSurface(exo, Modifier.fillMaxSize())
            // Écran à la cadence du film (24 images/s : plus de petit tremblement), mode d'origine rétabli en sortant.
            MatchContentFrameRate(exo.videoFrameRate)
        }
        if (state is MoviePlaybackState.Loading || playing != null && exo?.started != true) Text(if (playing?.reconnecting == true) RECONNECTING else "Connexion…", color = t.textMuted, fontSize = t.rowTextSize, modifier = Modifier.align(Alignment.Center))
        if (!controls) SeekBatchLabel(seek)
        // Ligne fine (choix A) : titre, barre de progression et petits boutons, l'image reste visible.
        if (controls && playing != null && !panel) VodControls(
            active, playing.movie.name, listOfNotNull(playing.movie.year?.takeIf { it.isNotBlank() }, playing.movie.genre?.takeIf { it.isNotBlank() }).joinToString(" · ").ifBlank { null },
            paused, onTogglePause = { paused = !paused; active?.setPaused(paused) }, onTracks = { panel = true }, onQuit = onClose,
            playRequester = button, onActivity = { activity++ }, cover = playing.movie.cover,
        )
        if (overlay) Column(Modifier.align(Alignment.Center).background(t.background).padding(LiveDims.ColumnGap), verticalArrangement = Arrangement.spacedBy(LiveDims.Gap)) {
            val failure = state as? MoviePlaybackState.Failed
            Text(failure?.message ?: "Film terminé", color = t.text, fontSize = t.titleSize)
            if (state is MoviePlaybackState.Finished) LiveButton("Recommencer", playback::restart, Modifier.focusRequester(button), accent = true)
            // Version en panne : la suivante d'abord (0.41) ; si elle marche, elle sera retenue pour ce film.
            val other = if (failure != null) others.firstOrNull() else null
            if (other != null) LiveButton("Essayer une autre version (${other.tag.label})", { onTryOther(other) }, Modifier.focusRequester(button), accent = true)
            if (failure?.canRetry == true) LiveButton("Réessayer", playback::retry, if (other == null) Modifier.focusRequester(button) else Modifier, accent = other == null)
            LiveButton("Retour à la fiche", onClose, if (failure?.canRetry == false && other == null) Modifier.focusRequester(button) else Modifier)
        }
        if (panel) TrackPanel(tracks, playback::chooseAudio, playback::chooseSubtitle, { panel = false }, Modifier.align(Alignment.CenterEnd))
    }
}
