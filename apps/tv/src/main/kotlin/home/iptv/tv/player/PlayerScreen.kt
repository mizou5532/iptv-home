package home.iptv.tv.player

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import home.iptv.tv.theme.IptvColors

/** Lecture plein écran. Retour : le parent arrête le lecteur et revient à la liste. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerScreen(player: Player, channelName: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (player is ExoPlayerPlayer) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        // Sans cela l'économiseur d'écran de Google TV se déclenche en pleine lecture et stoppe le flux (onStop).
                        keepScreenOn = true
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                },
                update = { player.attach(it) },
                onRelease = { it.player = null },
            )
            if (player.failed) {
                Text(
                    "Impossible de lire « $channelName ». Appuie sur Retour.",
                    color = IptvColors.Text,
                    fontSize = 32.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}
