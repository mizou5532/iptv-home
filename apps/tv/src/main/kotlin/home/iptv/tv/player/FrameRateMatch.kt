package home.iptv.tv.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlin.math.abs
import kotlin.math.roundToInt

/** Un mode de l'écran : définition et fréquence (Hz). */
data class DisplayModeInfo(val id: Int, val width: Int, val height: Int, val refreshHz: Float)

/**
 * Mode d'écran adapté à une vidéo de [fps] images/s (films : 23,976 ou 24 ; séries européennes : 25…), à la même
 * définition que le mode [current] : d'abord une fréquence égale (à 0,2 % près ; entre 23,976 et 24 Hz, la plus proche
 * gagne quand l'écran a les deux), sinon
 * un multiple entier (48, 72, 120 Hz pour 24). `null` : rien de mieux que le mode actuel (déjà adapté, cadence
 * inconnue, ou aucun mode compatible) — l'écran ne change pas.
 */
fun bestModeFor(fps: Float, current: DisplayModeInfo, modes: List<DisplayModeInfo>): DisplayModeInfo? {
    if (fps !in 10f..121f) return null
    val same = modes.filter { it.width == current.width && it.height == current.height }
    fun multiple(m: DisplayModeInfo): Int? {
        val ratio = m.refreshHz / fps
        val n = ratio.roundToInt()
        return n.takeIf { it >= 1 && abs(ratio - n) / n < 0.002f }
    }
    // Déjà adapté (24 Hz, ou 120 Hz qui contient 24 images entières) : on ne change rien.
    if (multiple(current) != null) return null
    return same.mapNotNull { m -> multiple(m)?.let { m to it } }
        .minByOrNull { (m, n) -> n * 1000 + abs(m.refreshHz - fps * n) }?.first
}

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/**
 * Films et épisodes : l'écran passe à la cadence de la vidéo ([fps], connue quand le fichier est analysé) pour des
 * mouvements réguliers (sans le petit tremblement d'un film 24 images/s sur un écran 60 Hz), puis revient au mode
 * d'origine en quittant la lecture. L'écran peut devenir noir une à deux secondes au changement (comportement normal
 * de la télé). Fonctionne sur Android 6 et plus (Fire Stick compris).
 */
@Composable
fun MatchContentFrameRate(fps: Float?) {
    val activity = LocalContext.current.activity() ?: return
    val window = activity.window ?: return
    val original = remember { window.attributes.preferredDisplayModeId }
    DisposableEffect(Unit) {
        onDispose {
            val a = window.attributes
            if (a.preferredDisplayModeId != original) { a.preferredDisplayModeId = original; window.attributes = a }
        }
    }
    LaunchedEffect(fps) {
        if (fps == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return@LaunchedEffect
        @Suppress("DEPRECATION")
        val display = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display else window.windowManager.defaultDisplay) ?: return@LaunchedEffect
        fun info(m: android.view.Display.Mode) = DisplayModeInfo(m.modeId, m.physicalWidth, m.physicalHeight, m.refreshRate)
        val target = bestModeFor(fps, info(display.mode), display.supportedModes.map(::info)) ?: return@LaunchedEffect
        val a = window.attributes
        a.preferredDisplayModeId = target.id
        window.attributes = a
    }
}
