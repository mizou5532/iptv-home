package home.iptv.tv.theme

import androidx.compose.ui.graphics.Color

object IptvColors {
    val Background = Color(0xFF0E1116)
    val Surface = Color(0xFF171C24)
    val Text = Color(0xFFF2F4F8)
    val TextSecondary = Color(0xFFA3ADBD)
    val Accent = Color(0xFF2C64F0)
    val Focus = Color.White
}

/** Couleur d'un profil ("#RRGGBB"), accent par défaut si invalide. */
fun parseProfileColor(hex: String): Color = runCatching {
    Color(android.graphics.Color.parseColor(hex))
}.getOrDefault(IptvColors.Accent)
