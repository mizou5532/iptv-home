package home.iptv.tv.live

import kotlinx.coroutines.launch
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
/** OK tenu sans répétition : appui long après ce délai (comme `combinedClickable`, qui marche sur la TCL). */
const val LONG_PRESS_MS = 500L
/**
 * Silence après un relâchement : un nouvel appui plus rapproché est la suite du même appui tenu (certaines
 * télécommandes envoient « appuyé / relâché » en boucle quand on tient OK) ; le clic court part après ce délai.
 */
const val OK_REPEAT_GAP_MS = 250L

private class OkPressState {
    var down = false
    /** Appui long déjà parti : les événements OK sont avalés jusqu'au silence. */
    var holding = false
    var longTimer: kotlinx.coroutines.Job? = null
    var pendingClick: kotlinx.coroutines.Job? = null
    var quiet: kotlinx.coroutines.Job? = null
}

/**
 * OK court ([onClick], au relâchement) ou tenu ([onLong], une seule fois). Trois façons de tenir OK sont reconnues :
 * répétitions d'Android, appui tenu sans répétition ([LONG_PRESS_MS]) et « appuyé / relâché » en boucle (un nouvel
 * appui moins de [OK_REPEAT_GAP_MS] après le relâchement ; essai TCL 0.22.0 : la carte lançait la vidéo puis
 * pause / lecture en boucle). Le clic court part donc [OK_REPEAT_GAP_MS] après le relâchement. Tout ce qui suit un
 * appui long est avalé jusqu'au silence ; un relâchement sans appui vu ici est ignoré.
 */
fun Modifier.okPress(onClick: () -> Unit, onLong: () -> Unit): Modifier = composed {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val click by androidx.compose.runtime.rememberUpdatedState(onClick)
    val long by androidx.compose.runtime.rememberUpdatedState(onLong)
    val st = remember { OkPressState() }
    fun fireLong() {
        st.longTimer?.cancel(); st.pendingClick?.cancel(); st.pendingClick = null
        if (st.holding) return
        st.holding = true
        long()
    }
    // Un appui long ouvre souvent un panneau : le focus part avant le relâchement, qui n'arrive jamais ici. Sans
    // remise à zéro, l'élément restait « tenu » et ignorait l'appui long suivant (test SportDpadTest).
    onFocusChanged { f ->
        if (!f.hasFocus) {
            st.longTimer?.cancel(); st.quiet?.cancel(); st.pendingClick?.cancel(); st.pendingClick = null
            st.down = false; st.holding = false
        }
    }.onPreviewKeyEvent { e ->
        if (e.key != Key.DirectionCenter && e.key != Key.Enter && e.key != Key.NumPadEnter) return@onPreviewKeyEvent false
        when (e.type) {
            KeyEventType.KeyDown -> {
                st.quiet?.cancel()
                when {
                    st.holding -> st.down = true
                    // Nouvel appui juste après un relâchement : c'est OK tenu (appuyé / relâché en boucle).
                    st.pendingClick?.isActive == true -> { st.down = true; fireLong() }
                    !st.down -> {
                        st.down = true
                        st.longTimer = scope.launch { kotlinx.coroutines.delay(LONG_PRESS_MS); if (st.down) fireLong() }
                    }
                    e.nativeKeyEvent.repeatCount >= LONG_PRESS_REPEATS -> fireLong()
                }
            }
            KeyEventType.KeyUp -> {
                val wasDown = st.down
                st.down = false
                st.longTimer?.cancel()
                when {
                    st.holding -> { st.quiet?.cancel(); st.quiet = scope.launch { kotlinx.coroutines.delay(OK_REPEAT_GAP_MS); st.holding = false } }
                    wasDown -> st.pendingClick = scope.launch { kotlinx.coroutines.delay(OK_REPEAT_GAP_MS); st.pendingClick = null; click() }
                }
            }
        }
        true
    }
}

/**
 * Panneau ouvert par un appui long : la touche OK encore tenue (répétitions, ou « appuyé / relâché » en boucle) ne
 * doit rien déclencher dedans. Les événements OK sont avalés jusqu'à [OK_REPEAT_GAP_MS] de silence, ou jusqu'à
 * l'appui sur une autre touche.
 */
fun Modifier.ignoreHeldOk(): Modifier = composed {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val ready = remember { booleanArrayOf(false) }
    val quiet = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
    fun restart() { quiet[0]?.cancel(); quiet[0] = scope.launch { kotlinx.coroutines.delay(OK_REPEAT_GAP_MS); ready[0] = true } }
    androidx.compose.runtime.LaunchedEffect(Unit) { restart() }
    onPreviewKeyEvent { e ->
        if (ready[0]) return@onPreviewKeyEvent false
        if (e.key != Key.DirectionCenter && e.key != Key.Enter && e.key != Key.NumPadEnter) {
            // Une autre touche (flèche, Retour) : OK n'est plus tenu.
            if (e.type == KeyEventType.KeyDown) { quiet[0]?.cancel(); ready[0] = true }
            return@onPreviewKeyEvent false
        }
        restart()
        true
    }
}
