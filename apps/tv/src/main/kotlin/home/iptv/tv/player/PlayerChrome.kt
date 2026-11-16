package home.iptv.tv.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import home.iptv.tv.theme.LocalIptvTokens
import kotlinx.coroutines.delay

/** « 1:16:42 » ou « 16:42 ». */
fun clock(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/**
 * Lecteur films et séries, version « ligne fine » (choix A du propriétaire) : l'image reste presque entièrement
 * visible ; en bas, le titre, une barre fine avec le temps écoulé et restant, puis de petits boutons [buttons].
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BoxScope.ThinPlayerControls(player: Player?, title: String, meta: String?, buttons: @Composable RowScope.() -> Unit) {
    val t = LocalIptvTokens.current
    var position by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    LaunchedEffect(player) {
        while (true) {
            player?.snapshot()?.let { s -> position = s.positionMs.coerceAtLeast(0); duration = s.durationMs.coerceAtLeast(0) }
            delay(500)
        }
    }
    Column(
        Modifier.align(Alignment.BottomStart).fillMaxWidth().background(t.scrim).padding(horizontal = 56.dp, vertical = 26.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, color = Color.White, fontSize = t.rowTextSize, fontWeight = FontWeight.SemiBold, maxLines = 1)
            meta?.let { Text(it, color = Color.White.copy(alpha = 0.75f), fontSize = t.metaTextSize, maxLines = 1) }
        }
        if (duration > 0) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(clock(position / 1000), color = Color.White.copy(alpha = 0.8f), fontSize = t.metaTextSize)
                Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.28f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth((position.toFloat() / duration).coerceIn(0f, 1f)).background(t.accent))
                }
                Text("−" + clock((duration - position) / 1000), color = Color.White.copy(alpha = 0.8f), fontSize = t.metaTextSize)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            buttons()
            Spacer(Modifier.weight(1f))
            Text("◀ ▶ 10 s", color = Color.White.copy(alpha = 0.6f), fontSize = t.metaTextSize)
        }
    }
}

/**
 * Lecteur du direct, version « carte compacte » (choix A) : une petite carte en bas à gauche (chaîne, émission,
 * progression, émission suivante) et de petits boutons en bas à droite.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BoxScope.CompactLiveCard(channel: String, now: String?, next: String?, progress: Float?, buttons: @Composable RowScope.() -> Unit) {
    val t = LocalIptvTokens.current
    Column(
        Modifier.align(Alignment.BottomStart).padding(start = 44.dp, bottom = 40.dp).widthIn(max = 560.dp)
            .clip(RoundedCornerShape(20.dp)).background(t.scrim).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFE5484D)))
            Text("EN DIRECT · $channel", color = Color.White.copy(alpha = 0.8f), fontSize = t.metaTextSize, maxLines = 1)
        }
        Text(now ?: channel, color = Color.White, fontSize = t.rowTextSize, fontWeight = FontWeight.SemiBold, maxLines = 1)
        if (progress != null) {
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.22f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(t.accent))
            }
        }
        next?.let { Text("Ensuite · $it", color = Color.White.copy(alpha = 0.75f), fontSize = t.metaTextSize, maxLines = 1) }
    }
    Row(Modifier.align(Alignment.BottomEnd).padding(end = 44.dp, bottom = 40.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) { buttons() }
}
