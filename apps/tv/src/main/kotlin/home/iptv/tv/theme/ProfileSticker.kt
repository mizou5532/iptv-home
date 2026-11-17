package home.iptv.tv.theme

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import home.iptv.shared.sync.ProfileSettings
import home.iptv.shared.sync.SupabaseConfig

/** Adresse publique de l'autocollant d'un profil (compartiment `web`), changée à chaque enregistrement. */
fun stickerUrl(profileId: String, version: Long? = null) =
    "${SupabaseConfig.URL}/storage/v1/object/public/web/stickers/$profileId.webp" + (version?.let { "?v=$it" } ?: "")

/** Où et à quelle taille montrer l'autocollant ; null = pas d'autocollant (rien n'est téléchargé). */
data class StickerLook(val left: Boolean, val size: Dp)

fun stickerLook(s: ProfileSettings): StickerLook? {
    if (s.hasSticker != true) return null
    val size = when (s.stickerSize) { "petit" -> 96.dp; "grand" -> 200.dp; else -> 140.dp }
    return StickerLook(left = s.stickerCorner == "bas-gauche", size = size)
}

/**
 * Autocollant du profil dans un coin bas de l'écran d'accueil (Séries, Films). Jamais sur une vidéo : les lecteurs
 * sont d'autres écrans. Ni focus ni clic : il ne gêne jamais la télécommande.
 */
@Composable
fun BoxScope.ProfileSticker(profile: home.iptv.shared.sync.Profile) {
    val look = stickerLook(profile.settings) ?: return
    AsyncImage(
        model = rememberCrispRequest(stickerUrl(profile.id, profile.settings.stickerVersion), look.size, look.size),
        contentDescription = null, contentScale = ContentScale.Fit,
        filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
        modifier = Modifier.align(if (look.left) Alignment.BottomStart else Alignment.BottomEnd)
            .padding(horizontal = 20.dp, vertical = 14.dp).size(look.size).testTag("sticker"),
    )
}
