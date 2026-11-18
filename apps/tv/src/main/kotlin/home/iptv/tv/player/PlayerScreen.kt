package home.iptv.tv.player

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import home.iptv.tv.live.LiveButton
import home.iptv.tv.live.backKey
import home.iptv.tv.live.LiveDims
import home.iptv.tv.theme.LocalIptvTokens
import kotlinx.coroutines.delay

/**
 * Vue Media3 de l'app (aperçu et plein écran), gonflée depuis `res/layout/player_view.xml` : seule façon de choisir
 * une TextureView (voir ce fichier). `keepScreenOn` est réaffirmé : sans lui l'économiseur d'écran coupe le flux.
 */
internal fun newPlayerView(ctx: android.content.Context): PlayerView =
    (android.view.LayoutInflater.from(ctx).inflate(home.iptv.tv.R.layout.player_view, null, false) as PlayerView).apply {
        useController = false
        keepScreenOn = true
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

/**
 * Surface vidéo : une vue Media3 (TextureView, voir [newPlayerView]) qui reçoit l'instance unique du lecteur. L'aperçu
 * et le plein écran en composent chacun une, jamais les deux en même temps ; la nouvelle vue est rattachée avant que
 * l'ancienne soit détachée (comme `PlayerView.switchTargetView`) et retirer une vue ne stoppe pas le lecteur.
 */
@Composable
fun PlayerSurface(player: ExoPlayerPlayer, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx -> newPlayerView(ctx) },
        update = { player.attach(it) },
        onRelease = { player.detach(it) },
    )
}

/**
 * Surface vidéo plein écran des films, épisodes et du Sport : **SurfaceView** (composant Compose officiel de Media3).
 * Par rapport à la TextureView ([PlayerSurface]) : image envoyée directement à l'écran (moins de travail pour
 * l'appareil, mouvements plus réguliers), HDR possible, adaptation de la cadence de l'écran. Pas utilisée pour le
 * Direct, qui passe de l'aperçu au plein écran (l'écran noir de l'essai TCL 0.4.0 venait de là) : étape suivante.
 * Image entière, bandes noires si besoin ; écran maintenu allumé (sinon l'économiseur d'écran couperait la vidéo).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoSurface(player: ExoPlayerPlayer, modifier: Modifier = Modifier) {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(player) {
        val before = view.keepScreenOn
        view.keepScreenOn = true
        player.surfaceShown(true)
        onDispose { view.keepScreenOn = before; player.surfaceShown(false) }
    }
    androidx.media3.ui.compose.ContentFrame(
        player = player.media3,
        modifier = modifier,
        surfaceType = androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW,
        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
    )
}

/**
 * Lecture plein écran. Haut/bas : chaîne précédente/suivante de la section d'origine ; OK : commandes (Sources,
 * Arrêter), qui se referment seules. Retour est géré par le parent (panneau, commandes, puis retour à l'aperçu).
 * [player] vaut `null` le temps d'un changement de chaîne : l'écran reste en place sur fond noir.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerScreen(
    player: Player?,
    title: String,
    controlsVisible: Boolean,
    panelOpen: Boolean,
    onBack: () -> Unit,
    onStep: (Int) -> Unit,
    onOk: () -> Unit,
    onHideControls: () -> Unit,
    onSources: () -> Unit,
    /** Copies de la chaîne (affiché sur le bouton « Sources »). */
    sourcesCount: Int = 0,
    /** Bascule en cours : « Essai d'une autre copie (2 sur 4)… ». */
    connecting: String? = null,
    /** Toutes les copies ont été essayées sans succès. */
    allCopiesFailed: Boolean = false,
    onStop: () -> Unit,
    /** Émission en cours / suivante et progression, quand le guide les connaît. */
    nowNext: home.iptv.tv.live.epg.NowNext = home.iptv.tv.live.epg.NowNext.EMPTY,
) {
    val t = LocalIptvTokens.current
    val keys = remember { FocusRequester() }
    val firstButton = remember { FocusRequester() }
    BackHandler(onBack = onBack)
    // Panneau Sources ouvert : pas de minuterie ; à sa fermeture, la télécommande reprend le focus (touches ou commandes).
    LaunchedEffect(controlsVisible, panelOpen) {
        if (panelOpen) return@LaunchedEffect
        withFrameNanos { } // le fond redevient focalisable dans cette image
        if (controlsVisible) {
            runCatching { firstButton.requestFocus() }
            delay(CONTROLS_MS)
            onHideControls()
        } else {
            runCatching { keys.requestFocus() }
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            // Noir pur derrière la vidéo (bandes de lecture) : volontairement hors jetons, valable pour tout thème.
            .background(Color.Black)
            .backKey(onBack)
            .focusRequester(keys)
            .onKeyEvent { e ->
                if (controlsVisible || panelOpen) return@onKeyEvent false // les boutons ou le panneau gardent la navigation
                when {
                    e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp -> { onStep(-1); true }
                    e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown -> { onStep(1); true }
                    e.type == KeyEventType.KeyUp && (e.key == Key.DirectionCenter || e.key == Key.Enter) -> { onOk(); true }
                    else -> false
                }
            }
            .focusable(),
    ) {
        if (player is ExoPlayerPlayer) {
            PlayerSurface(player, Modifier.fillMaxSize())
            if (player.failed) {
                Text(
                    if (allCopiesFailed) "Aucune copie de « $title » ne répond. Appuie sur OK pour réessayer, ou choisis une copie dans Sources."
                    else "Impossible de lire « $title ». Appuie sur OK pour réessayer.",
                    color = t.text,
                    fontSize = t.titleSize,
                    modifier = Modifier.align(Alignment.Center).padding(LiveDims.PageGutter),
                )
            } else if (!player.started) {
                Text(connecting ?: "Connexion…", color = t.textMuted, fontSize = t.rowTextSize, modifier = Modifier.align(Alignment.Center))
            }
        } else {
            Text(connecting ?: "Connexion…", color = t.textMuted, fontSize = t.rowTextSize, modifier = Modifier.align(Alignment.Center))
        }
        if (controlsVisible) {
            // Carte compacte (choix A) : l'image reste visible ; la carte se referme seule après quelques secondes.
            CompactLiveCard(title, nowNext.current?.title, nowNext.next?.title, nowNext.progress) {
                LiveButton(home.iptv.tv.live.sourcesLabel(sourcesCount), onSources, Modifier.focusRequester(firstButton), small = true)
                LiveButton("Arrêter", onStop, small = true)
            }
        }
    }
}

private const val CONTROLS_MS = 6_000L
