package home.iptv.tv.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
data class IptvTokens(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    /** Fond derrière la vidéo (bandes de lecture) : noir pur, quel que soit le thème. */
    val videoBackground: Color,
    val text: Color,
    val textMuted: Color,
    val accent: Color,
    val focusRing: Color,
    val radiusCard: Dp,
    val radiusChip: Dp,
    val titleSize: TextUnit,
    val rowTextSize: TextUnit,
    val metaTextSize: TextUnit,
)

val DarkTokens = IptvTokens(
    background = Color(0xFF0E1116),
    surface = Color(0xFF171C24),
    surfaceRaised = Color(0xFF1F2632),
    videoBackground = Color.Black,
    text = Color(0xFFF2F4F8),
    textMuted = Color(0xFFA3ADBD),
    accent = Color(0xFF2C64F0),
    focusRing = Color.White,
    radiusCard = 14.dp,
    radiusChip = 20.dp,
    titleSize = 34.sp,
    rowTextSize = 24.sp,
    metaTextSize = 18.sp,
)

val LocalIptvTokens = staticCompositionLocalOf { DarkTokens }

@Composable
fun IptvTheme(tokens: IptvTokens = DarkTokens, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalIptvTokens provides tokens, content = content)
}
