package home.iptv.tv.live

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.graphicsLayer
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import home.iptv.tv.theme.LocalIptvTokens

/** Mise en avant d'un élément focalisé : grossissement (graphicsLayer) et anneau, sans changer la mise en page. */
@Composable
internal fun Modifier.focusLook(focused: Boolean, shape: Shape, label: String): Modifier {
    val t = LocalIptvTokens.current
    val scale by animateFloatAsState(if (focused) LiveDims.FocusScale else 1f, tween(LiveDims.FocusMs), label = label)
    // Calque de diagnostic : dernier élément focalisé VISIBLE (un focus « perdu » laisse l'ancienne valeur).
    if (focused) androidx.compose.runtime.SideEffect { home.iptv.tv.diag.Diagnostics.focus = label }
    return graphicsLayer { scaleX = scale; scaleY = scale }
        .then(if (focused) Modifier.border(LiveDims.Ring, t.focusRing, shape) else Modifier)
}

/** Bouton de la télécommande (Sources, Arrêter…) : même focus que le reste du direct ; [accent] pour l'action principale. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LiveButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, accent: Boolean = false, small: Boolean = false) {
    val t = LocalIptvTokens.current
    var focused by remember { mutableStateOf(false) }
    val shape = remember(t.radiusChip) { RoundedCornerShape(t.radiusChip) }
    Text(
        label,
        color = if (accent && !focused) t.onAccent else t.text,
        fontSize = if (small) t.metaTextSize else t.rowTextSize,
        maxLines = 1,
        modifier = modifier
            .focusLook(focused, shape, "bouton $label")
            .clip(shape)
            .background(if (focused) t.surfaceRaised else if (accent) t.accent else t.surface)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = if (small) 14.dp else LiveDims.ChipH, vertical = if (small) 8.dp else LiveDims.ButtonV),
    )
}

/**
 * Fond recouvert (panneau, fiche, lecture) : la télécommande ne peut plus y entrer tant que [blocked] est vrai.
 *
 * Ne JAMAIS écrire `focusProperties { canFocus = … }` sur un conteneur sans focus propre : Compose applique alors la
 * valeur aux premiers éléments focalisables trouvés dessous, c'est-à-dire aux listes (LazyColumn, LazyRow) elles-mêmes.
 * Avec `canFocus = true`, la liste entière devient une cible : Droite y pose le focus, sans aucun dessin, et le curseur
 * « disparaît » (essai TCL 0.4.0, test `LiveDpadTest`). Ici le conteneur est un groupe ; on refuse seulement d'y entrer.
 */
fun Modifier.focusBlockedWhen(blocked: Boolean): Modifier =
    focusProperties { onEnter = { if (blocked) cancelFocusChange() } }.focusGroup()

/**
 * Retour de la télécommande, traité avant le système de focus. Sans cela, quand un élément focalisable (bouton,
 * ligne d'un panneau) a un parent lui-même focalisable (le fond du lecteur qui reçoit les touches), Compose traite
 * Retour comme « sortir du groupe » : le focus saute sur le parent, la touche est avalée et rien ne se ferme
 * (tests `SeriesDpadTest`, `LiveDpadTest`). L'action part au relâchement de la touche, une seule fois.
 */
fun Modifier.backKey(onBack: () -> Unit): Modifier = onPreviewKeyEvent { e ->
    if (e.key != Key.Back) return@onPreviewKeyEvent false
    if (e.type == KeyEventType.KeyUp) onBack()
    true
}

/** Nombre de répétitions de la touche OK tenue avant l'appui long (Android répète après ~0,5 s). */
const val LONG_PRESS_REPEATS = 1

/**
 * OK court ([onClick], au relâchement) ou tenu ([onLong], dès la première répétition, une seule fois). Le
 * relâchement d'un appui long ne déclenche rien, et un relâchement sans appui vu ici (touche tenue sur un autre
 * élément qui vient d'ouvrir ce panneau) est ignoré : l'appui long n'enchaîne jamais une lecture.
 */
fun Modifier.okPress(onClick: () -> Unit, onLong: () -> Unit): Modifier = composed {
    val state = remember { booleanArrayOf(false, false) } // [0] appui vu ici, [1] appui long déjà parti
    onPreviewKeyEvent { e ->
        if (e.key != Key.DirectionCenter && e.key != Key.Enter && e.key != Key.NumPadEnter) return@onPreviewKeyEvent false
        val repeat = e.nativeKeyEvent.repeatCount
        when (e.type) {
            KeyEventType.KeyDown -> when {
                repeat == 0 -> { state[0] = true; state[1] = false }
                state[0] && !state[1] && repeat >= LONG_PRESS_REPEATS -> { state[1] = true; onLong() }
            }
            KeyEventType.KeyUp -> { if (state[0] && !state[1]) onClick(); state[0] = false; state[1] = false }
        }
        true
    }
}
