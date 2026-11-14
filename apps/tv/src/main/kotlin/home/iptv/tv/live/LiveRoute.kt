package home.iptv.tv.live

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import home.iptv.shared.sync.Profile
import home.iptv.shared.xtream.LiveStream
import home.iptv.tv.player.PlayerController
import home.iptv.tv.player.PlayerScreen

/** Liste du direct ; OK sur une chaîne ouvre la lecture plein écran, Retour revient à la liste. */
@Composable
fun LiveRoute(
    vm: LiveBrowseViewModel,
    profile: Profile,
    player: PlayerController,
    urlFor: (LiveStream) -> String?,
    onChangeProfile: () -> Unit,
) {
    val sectionsState = rememberLazyListState()
    val streamsState = rememberLazyListState()
    val active by player.active.collectAsState()
    var playing by remember { mutableStateOf<LiveStream?>(null) }
    var restoreId by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.start() }
    // Le lecteur a pu être libéré par l'activité (onStop) : on revient à la liste, hors composition.
    LaunchedEffect(playing, active) {
        val s = playing
        if (s != null && active == null) {
            restoreId = s.id
            playing = null
        }
    }

    val current = playing
    val p = active
    if (current != null && p != null) {
        PlayerScreen(p, current.name) {
            player.close()
            restoreId = current.id
            playing = null
        }
    } else {
        LiveBrowseScreen(
            vm, profile, sectionsState, streamsState, restoreId, notice,
            onRestored = { restoreId = null },
            onChangeProfile = onChangeProfile,
        ) { stream ->
            val url = urlFor(stream)
            if (url == null) {
                notice = "Identifiants IPTV manquants — ouvre la page de configuration."
                return@LiveBrowseScreen
            }
            notice = null
            restoreId = null
            playing = stream
            player.open(url)
        }
    }
}
