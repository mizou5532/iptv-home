package home.iptv.tv.theme

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.allowConversionToBitmap
import coil3.request.transformations
import coil3.size.Size
import coil3.transform.Transformation
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Image nette (logos, photos de profil) à [width] × [height] : décodée en entier puis réduite PAR MOITIÉS avec
 * lissage ([SmoothScale]).
 *
 * Pourquoi : pour une petite taille, le décodeur d'Android réduit un PNG en ne gardant qu'un pixel sur 2, 4 ou 8
 * (sans moyenne) ; le dessin, lui, ne lisse qu'entre pixels voisins. Les deux donnent des contours en escalier
 * (logos de la bande du bas, essais TCL 0.14.0 et 0.14.1). Une réduction de moitié lissée fait la moyenne de 4
 * pixels : répétée, elle donne un résultat propre. [cover] : l'image doit couvrir le cadre (photo recadrée).
 * Une photo animée (GIF) n'est pas transformée (elle resterait figée) : elle s'affiche telle quelle.
 */
@Composable
fun rememberCrispRequest(url: String, width: Dp, height: Dp = width, cover: Boolean = false): ImageRequest {
    val context = LocalPlatformContext.current
    val density = LocalDensity.current
    // Taille sans limite (conteneur non borné) : 256 dp, assez pour toute vignette de l'app.
    fun px(d: Dp) = with(density) { (if (d.value.isFinite()) d else Dp(256f)).roundToPx() }.coerceIn(1, 2048)
    val w = px(width)
    val h = px(height)
    return remember(url, w, h, cover) {
        ImageRequest.Builder(context).data(url).size(Size.ORIGINAL)
            .transformations(SmoothScale(w, h, cover))
            .allowConversionToBitmap(false)
            .build()
    }
}

/** Réduction lissée par moitiés jusqu'à la taille voulue (jamais d'agrandissement). */
class SmoothScale(private val width: Int, private val height: Int, private val cover: Boolean) : Transformation() {
    override val cacheKey: String = "smooth-$width-$height-$cover"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap = smoothScale(input, width, height, cover)
}

/** Taille finale d'une image [w]×[h] dans un cadre [boxW]×[boxH] (tenir dedans, ou le couvrir), jamais plus grande. */
fun scaledSize(w: Int, h: Int, boxW: Int, boxH: Int, cover: Boolean): Pair<Int, Int> {
    val fx = boxW.toFloat() / w
    val fy = boxH.toFloat() / h
    val f = min(1f, if (cover) max(fx, fy) else min(fx, fy))
    return max(1, (w * f).roundToInt()) to max(1, (h * f).roundToInt())
}

fun smoothScale(input: Bitmap, boxW: Int, boxH: Int, cover: Boolean): Bitmap {
    val (tw, th) = scaledSize(input.width, input.height, boxW, boxH, cover)
    if (tw >= input.width && th >= input.height) return input
    var current = input
    while (current.width / 2 >= tw && current.height / 2 >= th) {
        current = Bitmap.createScaledBitmap(current, current.width / 2, current.height / 2, true)
    }
    if (current.width != tw || current.height != th) current = Bitmap.createScaledBitmap(current, tw, th, true)
    return current
}
