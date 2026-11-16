package home.iptv.tv.player

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
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

/** Pas du défilement sur la barre : 10 s, puis 30 s et 1 min quand on appuie plusieurs fois de suite. */
fun scrubStep(repeats: Int): Long = when {
    repeats < 4 -> 10_000L
    repeats < 10 -> 30_000L
    else -> 60_000L
}

/** Pictogrammes dessinés (aucune bibliothèque) : lecture, pause, ±10 s. */
enum class PlayerIcon { PLAY, PAUSE, BACK10, FORWARD10 }

@Composable
private fun IconGlyph(icon: PlayerIcon, color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        when (icon) {
            PlayerIcon.PLAY -> drawPath(Path().apply { moveTo(w * 0.28f, h * 0.16f); lineTo(w * 0.86f, h * 0.5f); lineTo(w * 0.28f, h * 0.84f); close() }, color)
            PlayerIcon.PAUSE -> {
                val bw = w * 0.2f
                drawRoundRect(color, Offset(w * 0.22f, h * 0.16f), Size(bw, h * 0.68f), CornerRadius(bw / 3))
                drawRoundRect(color, Offset(w * 0.58f, h * 0.16f), Size(bw, h * 0.68f), CornerRadius(bw / 3))
            }
            PlayerIcon.BACK10, PlayerIcon.FORWARD10 -> {
                val back = icon == PlayerIcon.BACK10
                // Flèche circulaire ouverte en haut, pointe vers la gauche (reculer) ou la droite (avancer).
                drawArc(color, if (back) -70f else -110f, if (back) -290f else 290f, false, Offset(w * 0.08f, h * 0.08f), Size(w * 0.84f, h * 0.84f),
                    style = Stroke(width = w * 0.08f, cap = StrokeCap.Round))
                val x = if (back) w * 0.36f else w * 0.64f
                val dir = if (back) -1f else 1f
                drawPath(Path().apply { moveTo(x, -h * 0.04f); lineTo(x + dir * w * 0.2f, h * 0.1f); lineTo(x, h * 0.24f); close() }, color)
            }
        }
    }
}

/** Bouton rond à pictogramme ; [label] (« Pause », « Reprendre »…) sert aux tests et à l'accessibilité. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun IconPlayerButton(icon: PlayerIcon, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, big: Boolean = false, badge: String? = null) {
    val t = LocalIptvTokens.current
    var focused by remember { mutableStateOf(false) }
    val size = if (big) 56.dp else 44.dp
    Box(
        modifier.size(size).testTag(label).scale(if (focused) 1.1f else 1f).clip(CircleShape)
            .background(if (focused) Color.White else Color.White.copy(alpha = if (big) 0.22f else 0.12f))
            .onFocusChanged { focused = it.isFocused }.clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val ink = if (focused) Color(0xFF0B0F17) else Color.White
        IconGlyph(icon, ink, if (big) 26.dp else 30.dp)
        badge?.let { Text(it, color = ink, fontSize = t.metaTextSize * 0.58f, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp)) }
    }
}

/**
 * Commandes des lecteurs films et séries : titre, barre de progression **qu'on parcourt** (Haut depuis les boutons,
 * puis Gauche/Droite déplacent le repère, OK y va), et boutons à pictogrammes −10 s, Lecture/Pause, +10 s, Audio et
 * sous-titres, Quitter. [onActivity] est appelé à chaque touche (le délai de masquage repart).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BoxScope.VodControls(
    player: Player?,
    title: String,
    meta: String?,
    paused: Boolean,
    onTogglePause: () -> Unit,
    onTracks: () -> Unit,
    onQuit: () -> Unit,
    playRequester: FocusRequester,
    onActivity: () -> Unit,
) {
    val t = LocalIptvTokens.current
    var position by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var target by remember { mutableStateOf<Long?>(null) }
    var repeats by remember { mutableStateOf(0) }
    var lastPress by remember { mutableStateOf(0L) }
    var barFocused by remember { mutableStateOf(false) }
    LaunchedEffect(player) {
        while (true) {
            player?.snapshot()?.let { s -> position = s.positionMs.coerceAtLeast(0); duration = s.durationMs.coerceAtLeast(0) }
            delay(400)
        }
    }
    Column(
        Modifier.align(Alignment.BottomStart).fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.85f))))
            .onPreviewKeyEvent { onActivity(); false }
            .padding(start = 48.dp, end = 48.dp, top = 60.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, color = Color.White, fontSize = t.rowTextSize, fontWeight = FontWeight.SemiBold, maxLines = 1)
            meta?.let { Text(it, color = Color.White.copy(alpha = 0.75f), fontSize = t.metaTextSize, maxLines = 1) }
        }
        if (duration > 0) {
            val shown = target ?: position
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(clock(shown / 1000), color = Color.White, fontSize = t.metaTextSize, fontWeight = if (target != null) FontWeight.Bold else FontWeight.Normal)
                BoxWithConstraints(
                    Modifier.weight(1f).height(28.dp)
                        .testTag("Barre de progression")
                        // Bas depuis la barre : toujours Lecture/Pause (pas le bouton le plus proche du repère).
                        .focusProperties { down = playRequester }
                        .onFocusChanged { barFocused = it.isFocused; if (!it.isFocused) target = null }
                        .onKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown && !(e.type == KeyEventType.KeyUp && (e.key == Key.DirectionCenter || e.key == Key.Enter))) return@onKeyEvent false
                            when (e.key) {
                                Key.DirectionLeft, Key.DirectionRight -> {
                                    val now = System.currentTimeMillis()
                                    repeats = if (now - lastPress < 700) repeats + 1 else 0
                                    lastPress = now
                                    val step = scrubStep(repeats) * if (e.key == Key.DirectionLeft) -1 else 1
                                    target = ((target ?: position) + step).coerceIn(0, duration)
                                    true
                                }
                                Key.DirectionCenter, Key.Enter -> {
                                    if (e.type == KeyEventType.KeyUp) target?.let { player?.seekTo(it); position = it; target = null }
                                    true
                                }
                                else -> false
                            }
                        }
                        .focusable(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    val frac = (position.toFloat() / duration).coerceIn(0f, 1f)
                    val tFrac = ((target ?: position).toFloat() / duration).coerceIn(0f, 1f)
                    val h = if (barFocused) 8.dp else 4.dp
                    Box(Modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.25f))) {
                        if (target != null) Box(Modifier.fillMaxHeight().fillMaxWidth(maxOf(frac, tFrac)).background(Color.White.copy(alpha = 0.45f)))
                        Box(Modifier.fillMaxHeight().fillMaxWidth(minOf(frac, tFrac).takeIf { target != null } ?: frac).background(t.accent))
                    }
                    if (barFocused) {
                        val knob = 18.dp
                        Box(Modifier.offset(x = (maxWidth - knob) * tFrac).size(knob).clip(CircleShape).background(Color.White))
                    }
                }
                Text("−" + clock((duration - shown) / 1000), color = Color.White.copy(alpha = 0.8f), fontSize = t.metaTextSize)
            }
            Text(
                if (barFocused) (if (target != null) "OK : aller à ${clock(target!! / 1000)} · ◀ ▶ pour ajuster" else "◀ ▶ parcourir · OK pour y aller") else " ",
                color = Color.White.copy(alpha = 0.7f), fontSize = t.metaTextSize * 0.85f, maxLines = 1,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconPlayerButton(PlayerIcon.BACK10, "Reculer de 10 secondes", { player?.seekBy(-10_000) }, badge = "10")
            IconPlayerButton(if (paused) PlayerIcon.PLAY else PlayerIcon.PAUSE, if (paused) "Reprendre" else "Pause", onTogglePause,
                Modifier.focusRequester(playRequester), big = true)
            IconPlayerButton(PlayerIcon.FORWARD10, "Avancer de 10 secondes", { player?.seekBy(10_000) }, badge = "10")
            Spacer(Modifier.weight(1f))
            home.iptv.tv.live.LiveButton("Audio et sous-titres", onTracks, small = true)
            home.iptv.tv.live.LiveButton("Quitter", onQuit, small = true)
        }
    }
}
