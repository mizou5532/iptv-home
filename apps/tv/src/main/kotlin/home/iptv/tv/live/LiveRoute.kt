package home.iptv.tv.live

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import home.iptv.shared.xtream.LiveStream
import home.iptv.tv.player.PlayerController
import home.iptv.tv.player.PlayerScreen

/** Liste du direct ; OK sur une chaîne ouvre la lecture plein écran, Retour revient à la liste. */
@Composable
fun LiveRoute(
    vm: LiveBrowseViewModel,
    player: PlayerController,
    urlFor: (LiveStream) -> String?,
) {
    val categoriesState = rememberLazyListState()
    val streamsState = rememberLazyListState()
    val active by player.active.collectAsState()
    var playing by remember { mutableStateOf<LiveStream?>(null) }
    var restoreId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.start() }

    val current = playing
    val p = active
    if (current != null && p != null) {
        PlayerScreen(p, current.name) {
            player.close()
            restoreId = current.id
            playing = null
        }
    } else {
        // Le lecteur a pu être libéré par l'activité (onStop) : on revient à la liste.
        if (current != null) {
            playing = null
            restoreId = current.id
        }
        LiveBrowseScreen(vm, categoriesState, streamsState, restoreId) { stream ->
            val url = urlFor(stream) ?: return@LiveBrowseScreen
            restoreId = null
            playing = stream
            player.open(url)
        }
    }
}
