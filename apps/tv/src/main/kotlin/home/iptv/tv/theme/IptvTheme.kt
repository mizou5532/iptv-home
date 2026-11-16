package home.iptv.tv.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Jetons de l'apparence (style « Verre », choisi par le propriétaire le 16 nov. 2026) : toutes les couleurs et
 * tailles de l'app en viennent. Un profil choisit sa couleur d'accent et un fond sombre ou clair ([tokensFor]).
 */
@Immutable
data class IptvTokens(
    val background: Color,
    /** Panneau translucide (cartes, lignes, pastilles au repos). */
    val surface: Color,
    /** Élément focalisé ou sélectionné sans accent. */
    val surfaceRaised: Color,
    /** Fond derrière la vidéo (bandes de lecture) : noir pur, quel que soit le thème. */
    val videoBackground: Color,
    val text: Color,
    val textMuted: Color,
    val accent: Color,
    /** Texte posé sur l'accent (noir ou blanc selon sa clarté). */
    val onAccent: Color,
    val focusRing: Color,
    /** Voile des bandeaux posés sur la vidéo (lecteurs) : toujours sombre et lisible. */
    val scrim: Color,
    val radiusCard: Dp,
    val radiusChip: Dp,
    val titleSize: TextUnit,
    val rowTextSize: TextUnit,
    val metaTextSize: TextUnit,
    val light: Boolean = false,
)

private val DEFAULT_ACCENT = Color(0xFF8FB4FF)

private fun onColor(c: Color) = if (c.luminance() > 0.45f) Color(0xFF0B0F17) else Color.White

/** Sombre (« Verre ») : fond bleu nuit, panneaux translucides, tailles réduites pour plus d'air. */
fun darkTokens(accent: Color = DEFAULT_ACCENT) = IptvTokens(
    background = Color(0xFF0B0F17),
    surface = Color.White.copy(alpha = 0.07f).compositeOver(Color(0xFF0B0F17)),
    surfaceRaised = Color.White.copy(alpha = 0.16f).compositeOver(Color(0xFF0B0F17)),
    videoBackground = Color.Black,
    text = Color(0xFFF2F4F8),
    textMuted = Color(0xFFA3ADBD),
    accent = accent,
    onAccent = onColor(accent),
    focusRing = Color.White,
    scrim = Color(0xFF0B0F17).copy(alpha = 0.66f),
    radiusCard = 16.dp,
    radiusChip = 22.dp,
    titleSize = 28.sp,
    rowTextSize = 19.sp,
    metaTextSize = 14.sp,
)

/** Clair (« Doux », profil de la sœur) : fond très pâle teinté par l'accent, cartes blanches, texte prune. */
fun lightTokens(accent: Color) = IptvTokens(
    background = accent.copy(alpha = 0.12f).compositeOver(Color.White),
    surface = Color.White,
    surfaceRaised = accent.copy(alpha = 0.45f).compositeOver(Color.White),
    videoBackground = Color.Black,
    text = Color(0xFF2B1D26),
    textMuted = Color(0xFF6E5A66),
    accent = accent,
    onAccent = onColor(accent),
    focusRing = Color(0xFF2B1D26),
    scrim = Color(0xFF0B0F17).copy(alpha = 0.66f),
    radiusCard = 18.dp,
    radiusChip = 22.dp,
    titleSize = 28.sp,
    rowTextSize = 19.sp,
    metaTextSize = 14.sp,
    light = true,
)

/** Thème d'un profil : sa couleur (« #RRGGBB ») et son fond (« clair » = clair, tout le reste = sombre). */
fun tokensFor(color: String?, theme: String?): IptvTokens {
    val accent = color?.let { runCatching { parseProfileColor(it) }.getOrNull() } ?: DEFAULT_ACCENT
    // Valeurs du compte en ligne : « sombre », « clair », « fun » (« light » accepté aussi).
    val light = theme?.trim()?.lowercase() in setOf("clair", "light")
    return if (light) lightTokens(accent) else darkTokens(accent)
}

val DarkTokens = darkTokens()

val LocalIptvTokens = staticCompositionLocalOf { DarkTokens }

@Composable
fun IptvTheme(tokens: IptvTokens = DarkTokens, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalIptvTokens provides tokens, content = content)
}

/** Par-dessus la vidéo (lecteurs) : toujours les jetons sombres, avec l'accent du profil, pour rester lisible. */
@Composable
fun VideoTheme(content: @Composable () -> Unit) = IptvTheme(darkTokens(LocalIptvTokens.current.accent), content)
