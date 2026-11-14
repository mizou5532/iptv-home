package home.iptv.tv.live

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import home.iptv.tv.theme.LocalIptvTokens

/** Mise en avant d'un élément focalisé : grossissement (graphicsLayer) et anneau, sans changer la mise en page. */
@Composable
internal fun Modifier.focusLook(focused: Boolean, shape: Shape, label: String): Modifier {
    val t = LocalIptvTokens.current
    val scale by animateFloatAsState(if (focused) LiveDims.FocusScale else 1f, tween(LiveDims.FocusMs), label = label)
    return graphicsLayer { scaleX = scale; scaleY = scale }
        .then(if (focused) Modifier.border(LiveDims.Ring, t.focusRing, shape) else Modifier)
}
