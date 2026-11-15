package home.iptv.tv.series

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import home.iptv.tv.live.LiveButton
import home.iptv.tv.live.LiveDims
import home.iptv.tv.player.ExoPlayerPlayer
import home.iptv.tv.player.PlayerController
import home.iptv.tv.player.PlayerSurface
import home.iptv.tv.theme.LocalIptvTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * Lecture plein écran d'un épisode. Au premier affichage elle demande [playback] de lire [request] ; en la quittant
 * (Retour, Réessayer impossible, changement de profil, fin de composition) la position est enregistrée et le lecteur fermé.
 *
 * Télécommande : OK ou haut/bas affichent les commandes (Pause, Audio et sous-titres, Quitter) ; gauche/droite
 * reculent ou avancent de 10 s ; Retour ferme le panneau, puis les commandes, puis la lecture.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun EpisodePlayerScreen(
    request: PlayEpisodeRequest,
    playback: EpisodePlayback,
    controller: PlayerController,
    onClose: () -> Unit,
) {
    val t = LocalIptvTokens.current
    val state by playback.state.collectAsState()
    val tracks by playback.tracks.collectAsState()
    val active by controller.active.collectAsState()
    val exo = active as? ExoPlayerPlayer
    var controlsVisible by remember { mutableStateOf(false) }
    var panelOpen by remember { mutableStateOf(false) }
    var paused by remember(exo) { mutableStateOf(false) }
    val keys = remember { FocusRequester() }
    val firstButton = remember { FocusRequester() }
    val overlayFocus = remember { FocusRequester() }

    val close by rememberUpdatedState(onClose)
    // onStop de l'activité a tout arrêté : à son retour on revient à l'écran d'origine, rien ne reprend seul.
    // On attend un VRAI retour à Idle, lu sur le flux lui-même après begin() : la valeur composée (collectAsState)
    // peut encore être l'Idle d'avant le démarrage, ce qui fermait la lecture aussitôt ouverte (essai TCL 0.4.0,
    // test SeriesDpadTest.ok_sur_un_episode_lance_la_lecture_et_la_garde).
    LaunchedEffect(request) {
        playback.begin(request)
        playback.state.first { it is EpisodePlaybackState.Idle }
        close()
    }
    // Quitter l'écran (Retour, fin de composition, changement de profil) : position enregistrée, lecteur fermé.
    DisposableEffect(playback) { onDispose { playback.stop() } }

    val ended = exo?.ended == true
    val failed = exo?.failed == true
    LaunchedEffect(exo, ended) { if (ended) playback.onEnded() }
    LaunchedEffect(exo, failed) { if (failed) playback.onFailed() }
    LaunchedEffect(exo, exo?.trackList) { exo?.trackList?.let { playback.onTracks(it) } }
    // Une seconde de lecture à la fois : le lecteur Media3 ne se lit que depuis le fil principal.
    LaunchedEffect(exo) {
        val player = exo ?: return@LaunchedEffect
        while (true) {
            delay(1_000)
            val s = player.snapshot()
            if (s.ready && !s.ended && !s.failed) playback.onTick((s.positionMs / 1000).toInt(), (s.durationMs.coerceAtLeast(0) / 1000).toInt(), s.playing)
        }
    }

    val playing = state as? EpisodePlaybackState.Playing
    // Cartes d'état avec boutons ; « Connexion… » n'en a pas et laisse le fond recevoir les touches.
    val overlayUp = state is EpisodePlaybackState.NextCountdown || state is EpisodePlaybackState.Finished || state is EpisodePlaybackState.Failed
    BackHandler {
        when {
            panelOpen -> panelOpen = false
            controlsVisible && playing != null -> controlsVisible = false
            else -> onClose()
        }
    }
    // Focus : le fond reçoit les touches ; les commandes, le panneau et les cartes d'état prennent le focus à leur tour.
    LaunchedEffect(controlsVisible, panelOpen, overlayUp) {
        if (panelOpen) return@LaunchedEffect
        withFrameNanos { }
        when {
            overlayUp -> runCatching { overlayFocus.requestFocus() }
            controlsVisible -> {
                runCatching { firstButton.requestFocus() }
                delay(CONTROLS_MS)
                controlsVisible = false
            }
            else -> runCatching { keys.requestFocus() }
        }
    }
    LaunchedEffect(playing?.episode?.id) { controlsVisible = false; panelOpen = false }

    Box(
        Modifier
            .fillMaxSize()
            .background(t.videoBackground)
            .focusRequester(keys)
            .onKeyEvent { e ->
                if (controlsVisible || panelOpen || overlayUp) return@onKeyEvent false
                val player = exo
                when {
                    e.type != KeyEventType.KeyDown && e.type != KeyEventType.KeyUp -> false
                    e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft -> { player?.seekBy(-SEEK_MS); true }
                    e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight -> { player?.seekBy(SEEK_MS); true }
                    e.type == KeyEventType.KeyDown && (e.key == Key.DirectionUp || e.key == Key.DirectionDown) -> { controlsVisible = true; true }
                    e.type == KeyEventType.KeyUp && (e.key == Key.DirectionCenter || e.key == Key.Enter) -> { controlsVisible = true; true }
                    e.type == KeyEventType.KeyDown && (e.key == Key.MediaPlayPause || e.key == Key.MediaPlay || e.key == Key.MediaPause) -> {
                        paused = if (e.key == Key.MediaPlayPause) !paused else e.key == Key.MediaPause
                        player?.setPaused(paused); true
                    }
                    else -> false
                }
            }
            .focusable(),
    ) {
        if (exo != null && (playing != null || state is EpisodePlaybackState.NextCountdown)) {
            PlayerSurface(exo, Modifier.fillMaxSize())
        }
        if (state is EpisodePlaybackState.Loading || (playing != null && exo?.started != true)) {
            Text("Connexion…", color = t.textMuted, fontSize = t.rowTextSize, modifier = Modifier.align(Alignment.Center))
        }
        if (controlsVisible && playing != null) {
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().background(t.background.copy(alpha = 0.85f)).padding(LiveDims.ColumnGap),
                verticalArrangement = Arrangement.spacedBy(LiveDims.Gap),
            ) {
                Text(episodeTitle(playing), color = t.text, fontSize = t.titleSize, maxLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(LiveDims.Gap)) {
                    LiveButton(if (paused) "Lecture" else "Pause", {
                        paused = !paused; exo?.setPaused(paused)
                    }, Modifier.focusRequester(firstButton), accent = true)
                    LiveButton("Audio et sous-titres", { panelOpen = true })
                    LiveButton("Quitter", onClose)
                }
            }
        }
        when (val s = state) {
            is EpisodePlaybackState.NextCountdown -> Column(
                Modifier.align(Alignment.BottomEnd).background(t.background.copy(alpha = 0.9f)).padding(LiveDims.ColumnGap),
                verticalArrangement = Arrangement.spacedBy(LiveDims.Gap),
            ) {
                Text("Épisode suivant dans ${s.secondsLeft} s", color = t.text, fontSize = t.titleSize)
                Text(nextTitle(s.next), color = t.textMuted, fontSize = t.rowTextSize, maxLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(LiveDims.Gap)) {
                    LiveButton("Lire maintenant", playback::playNextNow, Modifier.focusRequester(overlayFocus), accent = true)
                    LiveButton("Annuler", playback::cancelNext)
                }
            }
            is EpisodePlaybackState.Finished -> Message(
                if (s.seriesEnded) "Dernier épisode terminé. Tu as tout vu !" else "Lecture terminée.",
            ) { LiveButton("Retour", onClose, Modifier.focusRequester(overlayFocus), accent = true) }
            is EpisodePlaybackState.Failed -> Message(s.message) {
                if (s.canRetry) LiveButton("Réessayer", playback::retry, Modifier.focusRequester(overlayFocus), accent = true)
                LiveButton("Retour", onClose, if (s.canRetry) Modifier else Modifier.focusRequester(overlayFocus), accent = !s.canRetry)
            }
            else -> {}
        }
        if (panelOpen) {
            TrackPanel(
                tracks, onAudio = playback::chooseAudio, onSubtitle = playback::chooseSubtitle,
                onClose = { panelOpen = false }, modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Message(text: String, buttons: @Composable () -> Unit) {
    val t = LocalIptvTokens.current
    Column(
        Modifier.fillMaxSize().padding(LiveDims.PageGutter),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(LiveDims.ColumnGap, Alignment.CenterVertically),
    ) {
        Text(text, color = t.text, fontSize = t.titleSize, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(LiveDims.Gap)) { buttons() }
    }
}

private fun episodeTitle(p: EpisodePlaybackState.Playing) =
    "${p.seriesName} · S${p.episode.season} É${p.episode.number}" + p.episode.title.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()

private fun nextTitle(e: home.iptv.shared.xtream.Episode) =
    "S${e.season} É${e.number}" + e.title.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()

private const val CONTROLS_MS = 6_000L
private const val SEEK_MS = 10_000L
