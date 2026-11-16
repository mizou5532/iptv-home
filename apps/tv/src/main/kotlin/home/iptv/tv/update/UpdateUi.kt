package home.iptv.tv.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import home.iptv.tv.live.focusBlockedWhen
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import home.iptv.tv.live.LiveButton
import home.iptv.tv.theme.LocalIptvTokens

/** Fourni par l'activité ; absent (null) dans les écrans de test qui n'en ont pas besoin : pas de bouton. */
val LocalUpdater = staticCompositionLocalOf<Updater?> { null }

/** Panneau ouvert ou non, et où rendre le focus à sa fermeture (le bouton de la barre). Fourni par [UpdateHost]. */
class UpdatePanelState {
    var open by mutableStateOf(false)
    val button = FocusRequester()
}
val LocalUpdatePanel = staticCompositionLocalOf<UpdatePanelState?> { null }

/**
 * Dessine le panneau de mise à jour par-dessus [content] (dans le même écran : le focus de la télécommande y
 * entre sans fenêtre séparée) ; le contenu derrière ne peut pas prendre le focus pendant ce temps.
 */
@Composable
fun UpdateHost(content: @Composable () -> Unit) {
    val panel = remember { UpdatePanelState() }
    val updater = LocalUpdater.current
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(panel.open) {
        if (wasOpen && !panel.open) { withFrameNanos { }; runCatching { panel.button.requestFocus() } }
        wasOpen = panel.open
    }
    CompositionLocalProvider(LocalUpdatePanel provides panel) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().focusBlockedWhen(panel.open)) { content() }
            if (panel.open && updater != null) UpdatePanel(updater) { panel.open = false }
        }
    }
}

/** Pastille de la barre du haut : visible seulement quand une version plus récente existe. */
@Composable
fun UpdateDot(modifier: Modifier = Modifier) {
    val t = LocalIptvTokens.current
    Box(modifier.size(10.dp).clip(CircleShape).background(t.accent))
}

/** Textes exacts du panneau, par état (testés). */
fun updateTexts(state: UpdateState): Pair<String, String?> = when (state) {
    UpdateState.None -> "L'application est à jour." to null
    is UpdateState.Available -> "Nouvelle version ${state.remote.versionName} disponible." to "Le téléchargement prend environ une minute, puis l'application se met à jour et se relance."
    is UpdateState.Downloading -> "Téléchargement de la version ${state.remote.versionName}… ${state.percent} %" to "Tu peux fermer ce panneau : le téléchargement continue."
    is UpdateState.NeedsPermission -> "Android doit d'abord autoriser IPTV Home à installer ses mises à jour." to
        "« Ouvrir le réglage », puis active iptv-home (Autoriser cette source) et reviens avec Retour. À faire une seule fois."
    is UpdateState.Installing -> "Installation de la version ${state.remote.versionName}…" to
        "Si Android le demande, choisis « Mettre à jour ». L'application se ferme puis se relance ; sinon, rouvre-la."
    is UpdateState.Failed -> state.message to null
}

/** Panneau de mise à jour ; [onClose] le ferme sans rien annuler. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun UpdatePanel(updater: Updater, onClose: () -> Unit) {
    val t = LocalIptvTokens.current
    val state by updater.state.collectAsState()
    val first = remember { FocusRequester() }
    val (title, detail) = updateTexts(state)
    LaunchedEffect(state::class) { withFrameNanos { }; runCatching { first.requestFocus() } }
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 640.dp).clip(RoundedCornerShape(16.dp)).background(t.surface).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Mise à jour", color = t.accent, fontSize = t.metaTextSize)
            Text(title, color = t.text, fontSize = t.titleSize)
            detail?.let { Text(it, color = t.textMuted, fontSize = t.rowTextSize) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                when (state) {
                    is UpdateState.Available -> {
                        LiveButton("Mettre à jour", updater::start, Modifier.focusRequester(first), accent = true)
                        LiveButton("Plus tard", onClose)
                    }
                    is UpdateState.NeedsPermission -> {
                        LiveButton("Ouvrir le réglage", updater::openPermission, Modifier.focusRequester(first), accent = true)
                        LiveButton("Plus tard", onClose)
                    }
                    is UpdateState.Failed -> {
                        LiveButton("Réessayer", updater::start, Modifier.focusRequester(first), accent = true)
                        LiveButton("Plus tard", { updater.dismissFailure(); onClose() })
                    }
                    else -> LiveButton("Fermer", onClose, Modifier.focusRequester(first))
                }
            }
        }
    }
}
