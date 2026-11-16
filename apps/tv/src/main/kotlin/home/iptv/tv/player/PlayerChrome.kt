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

/** Style du lecteur films et séries (réglage `player_style` du profil) : 4 « minimal » par défaut, 3 « carte ». */
enum class PlayerStyle { MINIMAL, CARD;
    companion object { fun of(v: String?) = if (v?.trim()?.lowercase() == "carte") CARD else MINIMAL }
}
val LocalPlayerStyle = androidx.compose.runtime.staticCompositionLocalOf { PlayerStyle.MINIMAL }

/** État de la barre qu'on parcourt : repère visé (null = aucun), pour les deux styles. */
private class ScrubState { var target by mutableStateOf<Long?>(null); var repeats = 0; var lastPress = 0L; var focused by mutableStateOf(false) }

/**
 * Barre de progression **qu'on parcourt** : ◀ ▶ déplacent le repère (10 s, puis 30 s et 1 min si on insiste), OK y va.
 * [exit] : la direction (Haut ou Bas) et le bouton où l'on revient (Lecture/Pause).
 */
@Composable
private fun ScrubBar(state: ScrubState, position: Long, duration: Long, player: Player?, playRequester: FocusRequester, exitUp: Boolean, thin: Boolean, modifier: Modifier, onSeek: (Long) -> Unit) {
    val t = LocalIptvTokens.current
    BoxWithConstraints(
        modifier.height(if (thin) 22.dp else 28.dp)
            .testTag("Barre de progression")
            .focusProperties { if (exitUp) up = playRequester else down = playRequester }
            .onFocusChanged { state.focused = it.isFocused; if (!it.isFocused) state.target = null }
            .onKeyEvent { e ->
                val ok = e.key == Key.DirectionCenter || e.key == Key.Enter
                if (e.type != KeyEventType.KeyDown && !(e.type == KeyEventType.KeyUp && ok)) return@onKeyEvent false
                when {
                    e.key == Key.DirectionLeft || e.key == Key.DirectionRight -> {
                        val now = System.currentTimeMillis()
                        state.repeats = if (now - state.lastPress < 700) state.repeats + 1 else 0
                        state.lastPress = now
                        val step = scrubStep(state.repeats) * if (e.key == Key.DirectionLeft) -1 else 1
                        state.target = ((state.target ?: position) + step).coerceIn(0, duration)
                        true
                    }
                    ok -> { if (e.type == KeyEventType.KeyUp) state.target?.let { player?.seekTo(it); onSeek(it); state.target = null }; true }
                    else -> false
                }
            }
            .focusable(),
        contentAlignment = Alignment.CenterStart,
    ) {
        val frac = (position.toFloat() / duration).coerceIn(0f, 1f)
        val tFrac = ((state.target ?: position).toFloat() / duration).coerceIn(0f, 1f)
        val h = if (state.focused) 8.dp else if (thin) 3.dp else 5.dp
        val fill = if (thin) Color.White else t.accent
        Box(Modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.22f))) {
            if (state.target != null) Box(Modifier.fillMaxHeight().fillMaxWidth(maxOf(frac, tFrac)).background(Color.White.copy(alpha = 0.45f)))
            Box(Modifier.fillMaxHeight().fillMaxWidth(if (state.target != null) minOf(frac, tFrac) else frac).background(fill))
        }
        if (state.focused) {
            val knob = 18.dp
            Box(Modifier.offset(x = (maxWidth - knob) * tFrac).size(knob).clip(CircleShape).background(Color.White))
        }
    }
}

/** Bouton rond à pictogramme de sous-titres ou de fermeture (petits boutons du bas). */
@Composable
private fun SmallIconButton(kind: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier.size(40.dp).testTag(label).scale(if (focused) 1.1f else 1f).clip(CircleShape)
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.12f))
            .onFocusChanged { focused = it.isFocused }.clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val ink = if (focused) Color(0xFF0B0F17) else Color.White
        Canvas(Modifier.size(20.dp)) {
            val w = size.width; val h = size.height
            if (kind == "sub") {
                drawRoundRect(ink, Offset(w * 0.04f, h * 0.18f), Size(w * 0.92f, h * 0.64f), CornerRadius(w * 0.14f), style = Stroke(w * 0.09f))
                drawRoundRect(ink, Offset(w * 0.2f, h * 0.52f), Size(w * 0.22f, h * 0.1f), CornerRadius(w * 0.05f))
                drawRoundRect(ink, Offset(w * 0.5f, h * 0.52f), Size(w * 0.3f, h * 0.1f), CornerRadius(w * 0.05f))
            } else {
                val st = Stroke(w * 0.11f, cap = StrokeCap.Round)
                drawLine(ink, Offset(w * 0.2f, h * 0.2f), Offset(w * 0.8f, h * 0.8f), st.width, StrokeCap.Round)
                drawLine(ink, Offset(w * 0.8f, h * 0.2f), Offset(w * 0.2f, h * 0.8f), st.width, StrokeCap.Round)
            }
        }
    }
}

private val endFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.CANADA_FRENCH)

/**
 * Commandes des lecteurs films et séries, selon [LocalPlayerStyle] : 4 « cinéma minimal » (par défaut) ou 3 « carte
 * flottante ». Les deux : Lecture/Pause, −10 s, +10 s, barre qu'on parcourt, Audio et sous-titres, Quitter.
 * [onActivity] est appelé à chaque touche (le délai de masquage repart). [cover] : affiche (style carte).
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
    cover: String? = null,
) {
    val t = LocalIptvTokens.current
    var position by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    val scrub = remember { ScrubState() }
    LaunchedEffect(player) {
        while (true) {
            player?.snapshot()?.let { s -> position = s.positionMs.coerceAtLeast(0); duration = s.durationMs.coerceAtLeast(0) }
            delay(400)
        }
    }
    val shown = scrub.target ?: position
    val tracksRequester = remember { FocusRequester() }
    val hint = if (scrub.focused) (scrub.target?.let { "OK : aller à ${clock(it / 1000)} · ◀ ▶ pour ajuster" } ?: "◀ ▶ parcourir · OK pour y aller") else null
    val endsAt = if (duration > 0) "Fin à " + endFormat.format(java.util.Date(System.currentTimeMillis() + (duration - shown))) else null
    val playButton: @Composable (Modifier) -> Unit = { m ->
        IconPlayerButton(if (paused) PlayerIcon.PLAY else PlayerIcon.PAUSE, if (paused) "Reprendre" else "Pause", onTogglePause, m.focusRequester(playRequester), big = true)
    }
    when (LocalPlayerStyle.current) {
        // 4 · Cinéma minimal : presque rien, trois grands ronds au centre, une barre fine tout en bas.
        PlayerStyle.MINIMAL -> Box(Modifier.fillMaxSize().onPreviewKeyEvent { onActivity(); false }
            .background(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.25f), Color.Black.copy(alpha = 0.62f))))) {
            Column(Modifier.align(Alignment.TopStart).padding(start = 48.dp, top = 32.dp, end = 240.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = Color.White, fontSize = t.titleSize * 0.9f, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                meta?.let { Text(it, color = Color.White.copy(alpha = 0.8f), fontSize = t.metaTextSize, maxLines = 1) }
            }
            Column(Modifier.align(Alignment.TopEnd).padding(end = 48.dp, top = 36.dp), horizontalAlignment = Alignment.End) {
                Text(endFormat.format(java.util.Date()), color = Color.White.copy(alpha = 0.85f), fontSize = t.metaTextSize)
                endsAt?.let { Text(it.lowercase(), color = Color.White.copy(alpha = 0.6f), fontSize = t.metaTextSize * 0.85f) }
            }
            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
                IconPlayerButton(PlayerIcon.BACK10, "Reculer de 10 secondes", { player?.seekBy(-10_000) }, badge = "10")
                playButton(Modifier.size(84.dp).focusProperties { down = tracksRequester })
                IconPlayerButton(PlayerIcon.FORWARD10, "Avancer de 10 secondes", { player?.seekBy(10_000) }, badge = "10")
            }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(clock(shown / 1000), color = Color.White, fontSize = t.metaTextSize, fontWeight = if (scrub.target != null) FontWeight.Bold else FontWeight.Normal)
                    Spacer(Modifier.weight(1f))
                    hint?.let { Text(it, color = Color.White.copy(alpha = 0.75f), fontSize = t.metaTextSize * 0.85f) }
                        ?: Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            SmallIconButton("sub", "Audio et sous-titres", onTracks, Modifier.focusRequester(tracksRequester))
                            SmallIconButton("close", "Quitter", onQuit)
                        }
                    Spacer(Modifier.weight(1f))
                    if (duration > 0) Text("−" + clock((duration - shown) / 1000), color = Color.White.copy(alpha = 0.85f), fontSize = t.metaTextSize)
                }
                if (duration > 0) ScrubBar(scrub, position, duration, player, playRequester, exitUp = true, thin = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) { position = it }
            }
        }
        // 3 · Carte flottante : affiche, titre, fin prévue, barre et boutons ronds dans une carte en bas.
        PlayerStyle.CARD -> Box(Modifier.fillMaxSize().onPreviewKeyEvent { onActivity(); false }
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))) {
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 26.dp).fillMaxWidth(0.84f)
                    .clip(RoundedCornerShape(24.dp)).background(Color(0xFF12161F).copy(alpha = 0.86f)).padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                if (cover != null) Box(Modifier.width(78.dp).height(112.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.1f))) {
                    home.iptv.tv.feature.FeatureImage(null, cover, 160, 230, Modifier.fillMaxSize())
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(title, color = Color.White, fontSize = t.rowTextSize, fontWeight = FontWeight.ExtraBold, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                        meta?.let { Text("  ·  $it", color = Color.White.copy(alpha = 0.65f), fontSize = t.metaTextSize, maxLines = 1) }
                        Spacer(Modifier.weight(1f))
                        endsAt?.let { Text(it, color = Color.White.copy(alpha = 0.65f), fontSize = t.metaTextSize * 0.9f) }
                    }
                    if (duration > 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(clock(shown / 1000), color = Color.White, fontSize = t.metaTextSize * 0.9f)
                        ScrubBar(scrub, position, duration, player, playRequester, exitUp = false, thin = false, modifier = Modifier.weight(1f)) { position = it }
                        Text("−" + clock((duration - shown) / 1000), color = Color.White.copy(alpha = 0.8f), fontSize = t.metaTextSize * 0.9f)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconPlayerButton(PlayerIcon.BACK10, "Reculer de 10 secondes", { player?.seekBy(-10_000) }, badge = "10")
                        playButton(Modifier)
                        IconPlayerButton(PlayerIcon.FORWARD10, "Avancer de 10 secondes", { player?.seekBy(10_000) }, badge = "10")
                        Spacer(Modifier.weight(1f))
                        hint?.let { Text(it, color = Color.White.copy(alpha = 0.7f), fontSize = t.metaTextSize * 0.8f) }
                        SmallIconButton("sub", "Audio et sous-titres", onTracks, Modifier.focusRequester(tracksRequester))
                        SmallIconButton("close", "Quitter", onQuit)
                    }
                }
            }
        }
    }
}
