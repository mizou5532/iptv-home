package home.iptv.tv.theme

import androidx.compose.ui.graphics.Color

object IptvColors {
    val Background = Color(0xFF0B0F17)
    val Surface = Color(0xFF1A2130)
    val Text = Color(0xFFF2F4F8)
    val TextSecondary = Color(0xFFA3ADBD)
    val Accent = Color(0xFF8FB4FF)
    val Focus = Color.White
}

/** Couleur d'un profil ("#RRGGBB"), accent par défaut si invalide. */
fun parseProfileColor(hex: String): Color {
    // Lecture maison (« #RRGGBB » ou « #AARRGGBB ») : même résultat partout, tests compris.
    val h = hex.trim().removePrefix("#")
    val v = h.takeIf { it.length == 6 || it.length == 8 }?.toLongOrNull(16) ?: return IptvColors.Accent
    return Color(if (h.length == 6) 0xFF000000 or v else v)
}
