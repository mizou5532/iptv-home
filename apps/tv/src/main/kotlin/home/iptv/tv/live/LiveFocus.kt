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
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
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
fun LiveButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, accent: Boolean = false) {
    val t = LocalIptvTokens.current
    var focused by remember { mutableStateOf(false) }
    val shape = remember(t.radiusChip) { RoundedCornerShape(t.radiusChip) }
    Text(
        label,
        color = t.text,
        fontSize = t.rowTextSize,
        maxLines = 1,
        modifier = modifier
            .focusLook(focused, shape, "bouton $label")
            .clip(shape)
            .background(if (focused) t.surfaceRaised else if (accent) t.accent else t.surface)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = LiveDims.ChipH, vertical = LiveDims.ButtonV),
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
