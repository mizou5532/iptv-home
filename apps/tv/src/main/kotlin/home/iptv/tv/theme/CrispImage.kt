package home.iptv.tv.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.size.Precision

/**
 * Image nette (logos, photos de profil) : décodée exactement à la taille affichée (pas une version « au moins aussi
 * grande » réduite ensuite à l'affichage, qui crénelait les contours) ; à dessiner avec `FilterQuality.High`.
 */
@Composable
fun rememberCrispRequest(url: String): ImageRequest {
    val context = LocalPlatformContext.current
    return remember(url) { ImageRequest.Builder(context).data(url).precision(Precision.EXACT).build() }
}
