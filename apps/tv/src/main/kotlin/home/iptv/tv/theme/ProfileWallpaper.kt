package home.iptv.tv.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import coil3.compose.AsyncImage
import home.iptv.shared.sync.ProfileSettings
import home.iptv.shared.sync.SupabaseConfig

/** Adresse publique du fond d'écran d'un profil (compartiment `web`), changée à chaque enregistrement. */
fun wallpaperUrl(profileId: String, version: Long? = null) =
    "${SupabaseConfig.URL}/storage/v1/object/public/web/wallpapers/$profileId.webp" + (version?.let { "?v=$it" } ?: "")

/** Opacité du voile (couleur du fond du thème) posé sur le fond d'écran ; null = pas de fond d'écran. */
fun wallpaperVeil(s: ProfileSettings): Float? =
    if (s.hasWallpaper != true) null else (s.wallpaperDim ?: 40).coerceIn(0, 80) / 100f

/**
 * Fond d'écran du profil, derrière l'accueil (Séries, Films), avec un voile de la couleur du thème pour garder les
 * textes lisibles. Rien n'est téléchargé sans fond d'écran ; si l'image ne se charge pas, le fond du thème reste.
 */
@Composable
fun ProfileWallpaper(profile: home.iptv.shared.sync.Profile) {
    val veil = wallpaperVeil(profile.settings) ?: return
    val t = LocalIptvTokens.current
    BoxWithConstraints(Modifier.fillMaxSize().testTag("wallpaper")) {
        AsyncImage(
            model = rememberCrispRequest(wallpaperUrl(profile.id, profile.settings.wallpaperVersion), maxWidth, maxHeight, cover = true),
            contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(t.background.copy(alpha = veil)))
    }
}
