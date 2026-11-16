package home.iptv.tv.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coil3.compose.LocalPlatformContext
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.size.Precision

/**
 * Image nette (logos, photos de profil) décodée EXACTEMENT à sa taille d'affichage ([width] × [height]), avec sa
 * propre entrée de cache par taille.
 *
 * Sans cela, un même logo affiché en grand (affiche) puis en petit (bande du bas) réutilisait la grande image du
 * cache, réduite au dessin : Android ne lisse alors qu'entre pixels voisins (pas de « mipmaps »), et une réduction
 * de 5× donne des contours crénelés (essai TCL 0.14.0). Le décodeur, lui, réduit proprement.
 */
@Composable
fun rememberCrispRequest(url: String, width: Dp, height: Dp = width): ImageRequest {
    val context = LocalPlatformContext.current
    val density = LocalDensity.current
    // Taille sans limite (conteneur non borné) : 256 dp, assez pour toute vignette de l'app.
    fun px(d: Dp) = with(density) { (if (d.value.isFinite()) d else Dp(256f)).roundToPx() }.coerceIn(1, 2048)
    val w = px(width)
    val h = px(height)
    return remember(url, w, h) {
        ImageRequest.Builder(context).data(url).size(w, h).precision(Precision.EXACT)
            .memoryCacheKey(MemoryCache.Key("$url#${w}x$h")).build()
    }
}
