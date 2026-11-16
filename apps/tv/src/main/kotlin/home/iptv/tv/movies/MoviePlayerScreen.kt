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
fun MoviePlayerScreen(request: PlayMovieRequest, playback: MoviePlayback, controller: PlayerController, onClose: () -> Unit) {
    val t = LocalIptvTokens.current
    val state by playback.state.collectAsState()
    val tracks by playback.tracks.collectAsState()
    val active by controller.active.collectAsState()
    val exo = active as? ExoPlayerPlayer
    val playing = state as? MoviePlaybackState.Playing
    var controls by remember { mutableStateOf(false) }; var panel by remember { mutableStateOf(false) }; var paused by remember(active) { mutableStateOf(false) }
    val keys = remember { FocusRequester() }; val button = remember { FocusRequester() }
    val close by rememberUpdatedState(onClose)
    LaunchedEffect(request) { playback.begin(request); playback.state.first { it is MoviePlaybackState.Idle }; close() }
    DisposableEffect(playback) { onDispose { playback.stop() } }
    LaunchedEffect(exo, exo?.ended) { if (exo?.ended == true) playback.onEnded() }
    LaunchedEffect(exo, exo?.failed) { if (exo?.failed == true) playback.onFailed() }
    LaunchedEffect(exo, exo?.trackList) { exo?.trackList?.let(playback::onTracks) }
    LaunchedEffect(active) { while (true) {
        delay(1000)
        active?.snapshot()?.let { s -> if (s.ready && !s.ended && !s.failed) playback.onTick((s.positionMs/1000).toInt(), (s.durationMs.coerceAtLeast(0)/1000).toInt(), s.playing) }
    } }
    val overlay = state is MoviePlaybackState.Failed || state is MoviePlaybackState.Finished
    val back = { when { panel -> panel = false; controls && playing != null -> controls = false; else -> onClose() } }
    BackHandler(onBack = back)
    LaunchedEffect(controls, panel, overlay) {
        if (!panel) { withFrameNanos { }; runCatching { (if (controls || overlay) button else keys).requestFocus() } }
    }
    Box(Modifier.fillMaxSize().background(t.videoBackground).backKey(back).focusRequester(keys).onKeyEvent { e ->
        if (controls || panel || overlay) false else when {
            e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft -> { exo?.seekBy(-10000); true }
            e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight -> { exo?.seekBy(10000); true }
            e.type == KeyEventType.KeyUp && (e.key == Key.DirectionCenter || e.key == Key.Enter) -> { controls = true; true }
            e.type == KeyEventType.KeyDown && (e.key == Key.DirectionUp || e.key == Key.DirectionDown) -> { controls = true; true }
            e.type == KeyEventType.KeyDown && e.key == Key.MediaPlayPause -> { paused = !paused; exo?.setPaused(paused); true }
            else -> false
        }
    }.focusable()) {
        if (exo != null && playing != null) PlayerSurface(exo, Modifier.fillMaxSize())
        if (state is MoviePlaybackState.Loading || playing != null && exo?.started != true) Text("Connexion…", color = t.textMuted, fontSize = t.rowTextSize, modifier = Modifier.align(Alignment.Center))
        if (controls && playing != null) Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(t.background.copy(alpha = .9f)).padding(LiveDims.ColumnGap), verticalArrangement = Arrangement.spacedBy(LiveDims.Gap)) {
            Text(playing.movie.name, color = t.text, fontSize = t.titleSize)
            Row(horizontalArrangement = Arrangement.spacedBy(LiveDims.Gap)) {
                LiveButton(if (paused) "Lecture" else "Pause", { paused = !paused; exo?.setPaused(paused) }, Modifier.focusRequester(button), accent = true)
                LiveButton("Audio et sous-titres", { panel = true })
                LiveButton("Quitter", onClose)
            }
        }
        if (overlay) Column(Modifier.align(Alignment.Center).background(t.background).padding(LiveDims.ColumnGap), verticalArrangement = Arrangement.spacedBy(LiveDims.Gap)) {
            val failure = state as? MoviePlaybackState.Failed
            Text(failure?.message ?: "Film terminé", color = t.text, fontSize = t.titleSize)
            if (state is MoviePlaybackState.Finished) LiveButton("Recommencer", playback::restart, Modifier.focusRequester(button), accent = true)
            if (failure?.canRetry == true) LiveButton("Réessayer", playback::retry, Modifier.focusRequester(button), accent = true)
            LiveButton("Retour à la fiche", onClose, if (failure?.canRetry == false) Modifier.focusRequester(button) else Modifier)
        }
        if (panel) TrackPanel(tracks, playback::chooseAudio, playback::chooseSubtitle, { panel = false }, Modifier.align(Alignment.CenterEnd))
    }
}
