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
fun tokensFor(color: String?, theme: String?, look: home.iptv.shared.sync.ThemeLook? = null): IptvTokens {
    val accent = color?.let { runCatching { parseProfileColor(it) }.getOrNull() } ?: DEFAULT_ACCENT
    // Valeurs du compte en ligne : « sombre », « clair », « fun » (« light » accepté aussi).
    val light = theme?.trim()?.lowercase() in setOf("clair", "light")
    val base = themeBase(look?.preset, look?.background)
    val tokens = when {
        base == null -> if (light) lightTokens(accent) else darkTokens(accent)
        base.color == null -> lightTokens(accent) // « Doux » : fond teinté par la couleur du profil
        base.light -> lightOn(base.color, accent)
        else -> darkOn(base.color, accent)
    }
    return withCorners(tokens, look?.corners)
}

/** Fond d'un thème : couleur (null = teinte de l'accent, « Doux ») et clair ou sombre. */
data class ThemeBase(val color: Color?, val light: Boolean)

/** Thèmes proposés sur l'iPhone (même liste que `web/config/src/look.ts`). */
val THEME_PRESETS: Map<String, ThemeBase> = mapOf(
    "nuit" to ThemeBase(Color(0xFF0B0F17), false),
    "doux" to ThemeBase(null, true),
    "minuit" to ThemeBase(Color(0xFF0B1A33), false),
    "foret" to ThemeBase(Color(0xFF0E1F18), false),
    "prune" to ThemeBase(Color(0xFF1F1226), false),
    "charbon" to ThemeBase(Color(0xFF141414), false),
    "sable" to ThemeBase(Color(0xFFF3ECDF), true),
    "ciel" to ThemeBase(Color(0xFFEAF2FF), true),
)

/** null = aucun thème choisi (fond de la colonne `theme`). « perso » : clair si le fond choisi est clair. */
fun themeBase(preset: String?, background: String?): ThemeBase? {
    if (preset == "perso") {
        val hex = background?.trim()?.takeIf { Regex("^#[0-9A-Fa-f]{6}$").matches(it) } ?: return null
        val c = parseProfileColor(hex)
        return ThemeBase(c, c.luminance() > 0.4f)
    }
    return preset?.let { THEME_PRESETS[it] }
}

/** Fond sombre quelconque : panneaux translucides blancs, comme « Nuit ». */
fun darkOn(bg: Color, accent: Color) = darkTokens(accent).copy(
    background = bg,
    surface = Color.White.copy(alpha = 0.07f).compositeOver(bg),
    surfaceRaised = Color.White.copy(alpha = 0.16f).compositeOver(bg),
)

/** Fond clair quelconque : cartes blanches (légèrement grises si le fond est déjà presque blanc), texte prune. */
fun lightOn(bg: Color, accent: Color) = lightTokens(accent).copy(
    background = bg,
    surface = if (bg.luminance() > 0.92f) Color.Black.copy(alpha = 0.05f).compositeOver(bg) else Color.White,
)

/** Coins : carrés, arrondis (valeurs du thème) ou ronds. */
fun withCorners(t: IptvTokens, corners: String?): IptvTokens = when (corners) {
    "carres" -> t.copy(radiusCard = 8.dp, radiusChip = 10.dp)
    "ronds" -> t.copy(radiusCard = 26.dp, radiusChip = 30.dp)
    else -> t
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
