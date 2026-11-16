package home.iptv.tv.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import home.iptv.shared.sync.SupabaseConfig

/**
 * Image de profil publique (compartiment `web`, `avatars/<id du profil>.webp`), posée par-dessus [fallback] (la
 * lettre sur la couleur du profil) seulement quand elle existe et se charge. Aucun profil sans image n'est touché.
 */
fun avatarUrl(profileId: String, version: Long? = null) =
    "${SupabaseConfig.URL}/storage/v1/object/public/web/avatars/$profileId.webp" + (version?.let { "?v=$it" } ?: "")

/** [version] (réglage `avatar_v`, posé par la page iPhone à chaque photo enregistrée) : une nouvelle photo s'affiche aussitôt. */
@Composable
fun ProfileAvatar(profileId: String, name: String, modifier: Modifier, version: Long? = null, show: Boolean = true, fallback: @Composable () -> Unit) {
    var loaded by remember(profileId, version) { mutableStateOf(false) }
    Box(modifier) {
        if (!loaded) fallback()
        if (show) AsyncImage(
            model = rememberCrispRequest(avatarUrl(profileId, version)), filterQuality = androidx.compose.ui.graphics.FilterQuality.High, contentDescription = "Image du profil $name", contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(), onSuccess = { loaded = true }, onError = { loaded = false },
        )
    }
}
