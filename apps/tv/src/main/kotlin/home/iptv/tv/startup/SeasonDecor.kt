package home.iptv.tv.startup

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import home.iptv.shared.account.Season
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Couleurs du mot du jour, par fête (fond, texte). */
private fun greetingColors(s: Season): Pair<Color, Color> = when (s) {
    Season.NOEL -> Color(0xFFC62828) to Color.White
    Season.NOUVEL_AN -> Color(0xFFE8B64C) to Color(0xFF2A1E05)
    Season.HALLOWEEN -> Color(0xFFFF8A2B) to Color(0xFF2B1300)
    Season.VALENTIN -> Color(0xFFE84A7F) to Color.White
    Season.RAMADAN, Season.AID_FITR, Season.AID_ADHA -> Color(0xFF15404F) to Color(0xFFF5C96B)
    Season.FETE_NATIONALE -> Color(0xFF1E50B4) to Color.White
}

/** Mot du jour au-dessus de « Qui regarde ? » (« Joyeux Noël ! »), en pastille aux couleurs de la fête. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SeasonGreeting(season: Season, text: String, modifier: Modifier = Modifier) {
    val (bg, ink) = greetingColors(season)
    Text(
        text, color = ink, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold,
        modifier = modifier.clip(RoundedCornerShape(999.dp)).background(bg).padding(horizontal = 22.dp, vertical = 8.dp).testTag("season-greeting"),
    )
}

/** Petit générateur stable : mêmes positions à chaque affichage (pas de « saut » quand l'écran revient). */
private class Seeds(n: Int, seed: Int) {
    val v: FloatArray = FloatArray(n * 6).also { a ->
        var s = seed.toLong() * 2654435761L + 1
        for (i in a.indices) { s = (s * 6364136223846793005L + 1442695040888963407L); a[i] = ((s ushr 33) % 10_000).toFloat() / 10_000f }
    }
    operator fun get(i: Int, k: Int) = v[i * 6 + k]
}

/**
 * Décor animé d'une fête, derrière les profils de « Qui regarde ? ». Dessiné en une seule toile (léger pour la télé) ;
 * [dark] : fond sombre (Aurore) ou clair (Doux), pour que la neige et les étoiles restent visibles sur les deux.
 */
@Composable
fun SeasonLayer(season: Season, dark: Boolean, modifier: Modifier = Modifier) {
    val loop = rememberInfiniteTransition(label = "saison")
    // Une seule horloge (0 → 1 en 30 s) ; chaque particule a sa vitesse entière : boucle sans à-coup.
    val t by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(30_000, easing = LinearEasing), RepeatMode.Restart), label = "t")
    val seeds = remember(season) { Seeds(80, season.ordinal + 7) }
    val heart = remember { heartPath() }
    val emojiPaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textAlign = android.graphics.Paint.Align.CENTER } }
    Canvas(modifier.fillMaxSize().testTag("season-layer")) {
        when (season) {
            Season.NOEL -> { snow(t, seeds, dark); garland(t) }
            Season.NOUVEL_AN -> { confetti(t, seeds, listOf(Color(0xFFE8B64C), Color(0xFFF2F4F8), Color(0xFF8FB4FF), Color(0xFFF4B6C8), Color(0xFF7EE2A0))); sparkles(t, seeds, Color(0xFFFFD873)) }
            Season.HALLOWEEN -> { glow(Color(0xFFFF8A2B), 0.10f); emojis(t, seeds, emojiPaint, listOf("🦇"), count = 7, drift = true); corners(emojiPaint, "🎃") }
            Season.VALENTIN -> hearts(t, seeds, heart)
            Season.RAMADAN -> { glow(Color(0xFFF5C96B), 0.08f); stars(t, seeds, dark); crescent(dark); lanterns(t) }
            Season.AID_FITR, Season.AID_ADHA -> { glow(Color(0xFFF5C96B), 0.10f); stars(t, seeds, dark); crescent(dark); lanterns(t); sparkles(t, seeds, Color(0xFFF5C96B)) }
            Season.FETE_NATIONALE -> { confetti(t, seeds, listOf(Color(0xFF1E50B4), Color.White, Color(0xFF6D9BFF))); corners(emojiPaint, "⚜️") }
        }
    }
}

private fun wave(t: Float, speed: Int, phase: Float) = sin((t * speed + phase) * 2 * PI).toFloat()

/** Atténuation derrière le titre et les profils (zone centrale) : le décor ne gêne jamais la lecture. */
private fun DrawScope.calm(x: Float, y: Float): Float =
    if (x in size.width * 0.18f..size.width * 0.82f && y in size.height * 0.26f..size.height * 0.86f) 0.3f else 1f

private fun DrawScope.glow(c: Color, a: Float) {
    drawRect(Brush.verticalGradient(listOf(c.copy(alpha = a), Color.Transparent), endY = size.height * 0.6f))
}

/** Flocons qui tombent en se balançant ; plus gros devant, plus pâles derrière. */
private fun DrawScope.snow(t: Float, s: Seeds, dark: Boolean) {
    val color = if (dark) Color.White else Color(0xFF9DB7DD)
    for (i in 0 until 70) {
        val depth = s[i, 2]
        val r = (2f + depth * 5f).dp.toPx()
        val speed = 1 + (depth * 3).toInt()
        val y = ((s[i, 1] + t * speed) % 1f) * (size.height + 40f) - 20f
        val x = s[i, 0] * size.width + wave(t, speed * 2, s[i, 3]) * 18.dp.toPx()
        drawCircle(color.copy(alpha = (0.35f + depth * 0.55f) * calm(x, y)), r, Offset(x, y))
    }
}

/** Guirlande en haut : fil qui pend en arcs, ampoules qui scintillent. */
private fun DrawScope.garland(t: Float) {
    val colors = listOf(Color(0xFFE53935), Color(0xFF43A047), Color(0xFFFFC107), Color(0xFF1E88E5))
    val arcs = 3
    val left = size.width * 0.18f
    val w = size.width * 0.64f / arcs
    val sag = 34.dp.toPx()
    val wire = Path()
    for (a in 0 until arcs) {
        val x0 = left + a * w
        wire.moveTo(x0, 0f); wire.quadraticTo(x0 + w / 2, sag * 2, x0 + w, 0f)
    }
    drawPath(wire, Color(0xFF2E3B2E), style = Stroke(2.dp.toPx()))
    val perArc = 7
    for (a in 0 until arcs) for (k in 1 until perArc) {
        val u = k / perArc.toFloat()
        val x = left + a * w + u * w
        val y = 2 * u * (1 - u) * sag * 2 // point de la courbe (Bézier quadratique)
        val c = colors[(a * perArc + k) % colors.size]
        val on = 0.55f + 0.45f * abs(wave(t, 9, (a * perArc + k) * 0.37f))
        drawCircle(c.copy(alpha = 0.35f * on), 13.dp.toPx(), Offset(x, y + 9.dp.toPx()))
        drawRoundRect(c.copy(alpha = on), Offset(x - 5.dp.toPx(), y + 2.dp.toPx()), Size(10.dp.toPx(), 15.dp.toPx()), CornerRadius(5.dp.toPx()))
    }
}

private fun DrawScope.confetti(t: Float, s: Seeds, colors: List<Color>) {
    for (i in 0 until 60) {
        val speed = 1 + (s[i, 2] * 3).toInt()
        val y = ((s[i, 1] + t * speed) % 1f) * (size.height + 40f) - 20f
        val x = s[i, 0] * size.width + wave(t, speed, s[i, 3]) * 24.dp.toPx()
        val angle = (t * speed * 360f * 3 + s[i, 4] * 360f) % 360f
        rotate(angle, Offset(x, y)) {
            drawRect(colors[i % colors.size].copy(alpha = 0.85f * calm(x, y)), Offset(x - 4.dp.toPx(), y - 7.dp.toPx()), Size(8.dp.toPx(), 14.dp.toPx()))
        }
    }
}

/** Étincelles en croix qui s'allument et s'éteignent sur place. */
private fun DrawScope.sparkles(t: Float, s: Seeds, c: Color) {
    for (i in 0 until 18) {
        val p = Offset(s[i, 3] * size.width, s[i, 4] * size.height * 0.85f)
        val a = abs(wave(t, 4 + (i % 5), s[i, 5])) * calm(p.x, p.y)
        if (a < 0.15f) continue
        val l = (6 + 10 * a).dp.toPx()
        drawLine(c.copy(alpha = a), p - Offset(l, 0f), p + Offset(l, 0f), 2.dp.toPx())
        drawLine(c.copy(alpha = a), p - Offset(0f, l), p + Offset(0f, l), 2.dp.toPx())
        drawCircle(c.copy(alpha = a), 2.5f.dp.toPx(), p)
    }
}

private fun DrawScope.stars(t: Float, s: Seeds, dark: Boolean) {
    val c = if (dark) Color.White else Color(0xFFC9A44C)
    for (i in 0 until 40) {
        val p = Offset(s[i, 0] * size.width, s[i, 1] * size.height * 0.7f)
        val a = (0.25f + 0.75f * abs(wave(t, 3 + (i % 4), s[i, 5]))) * calm(p.x, p.y)
        drawCircle(c.copy(alpha = a), (1.2f + s[i, 2] * 2.2f).dp.toPx(), p)
    }
}

/** Croissant de lune doré en haut à droite (disque moins un disque décalé). */
private fun DrawScope.crescent(dark: Boolean) {
    val r = 58.dp.toPx()
    val c = Offset(size.width - 170.dp.toPx(), 120.dp.toPx())
    val moon = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r)) }
    val bite = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c + Offset(r * 0.45f, -r * 0.2f), r * 0.9f)) }
    val shape = Path().apply { op(moon, bite, PathOperation.Difference) }
    drawCircle(Color(0xFFF5C96B).copy(alpha = if (dark) 0.18f else 0.25f), r * 1.7f, c)
    drawPath(shape, Color(0xFFF5C96B))
}

/** Lanternes suspendues qui se balancent doucement (fil, chapeau, corps doré éclairé, pompon). */
private fun DrawScope.lanterns(t: Float) {
    val spots = listOf(0.19f to 140f, 0.29f to 80f, 0.62f to 90f, 0.71f to 150f)
    spots.forEachIndexed { i, (fx, len) ->
        val top = Offset(size.width * fx, 0f)
        val swing = 4f * wave(t, 2 + i % 2, i * 0.25f)
        rotate(swing, top) {
            val l = len.dp.toPx()
            drawLine(Color(0xFFB08A3E), top, top + Offset(0f, l), 1.5f.dp.toPx())
            val w = 34.dp.toPx(); val h = 50.dp.toPx()
            val body = Offset(top.x - w / 2, l + 8.dp.toPx())
            drawCircle(Color(0xFFFFC870).copy(alpha = 0.28f), w * 1.3f, body + Offset(w / 2, h / 2))
            drawRoundRect(Color(0xFF8A5A14), Offset(top.x - w * 0.35f, l), Size(w * 0.7f, 10.dp.toPx()), CornerRadius(3.dp.toPx()))
            drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFFFE3A0), Color(0xFFF0A93B)), startY = body.y, endY = body.y + h),
                body, Size(w, h), CornerRadius(w / 2.4f))
            for (k in 1..2) drawLine(Color(0xFF8A5A14).copy(alpha = 0.6f), body + Offset(w * k / 3f, 4.dp.toPx()), body + Offset(w * k / 3f, h - 4.dp.toPx()), 1.dp.toPx())
            drawRoundRect(Color(0xFF8A5A14), Offset(top.x - w * 0.3f, body.y + h - 2.dp.toPx()), Size(w * 0.6f, 8.dp.toPx()), CornerRadius(3.dp.toPx()))
            drawLine(Color(0xFFC62828), Offset(top.x, body.y + h + 6.dp.toPx()), Offset(top.x, body.y + h + 20.dp.toPx()), 3.dp.toPx())
        }
    }
}

/** Cœur unitaire (largeur 1), dessiné à l'échelle voulue. */
private fun heartPath() = Path().apply {
    moveTo(0.5f, 0.95f)
    cubicTo(0.1f, 0.65f, -0.05f, 0.35f, 0.18f, 0.15f)
    cubicTo(0.35f, 0f, 0.5f, 0.12f, 0.5f, 0.25f)
    cubicTo(0.5f, 0.12f, 0.65f, 0f, 0.82f, 0.15f)
    cubicTo(1.05f, 0.35f, 0.9f, 0.65f, 0.5f, 0.95f)
    close()
}

/** Cœurs qui montent en ondulant. */
private fun DrawScope.hearts(t: Float, s: Seeds, heart: Path) {
    val colors = listOf(Color(0xFFE84A7F), Color(0xFFFF8FB1), Color(0xFFC2185B))
    for (i in 0 until 26) {
        val speed = 1 + (s[i, 2] * 2).toInt()
        val y = size.height + 30f - ((s[i, 1] + t * speed) % 1f) * (size.height + 80f)
        val x = s[i, 0] * size.width + wave(t, speed * 2, s[i, 3]) * 26.dp.toPx()
        val w = (18 + s[i, 4] * 26).dp.toPx()
        translate(x - w / 2, y - w / 2) {
            scale(w, w, pivot = Offset.Zero) {
                drawPath(heart, colors[i % colors.size].copy(alpha = (0.55f + s[i, 5] * 0.35f) * calm(x, y)))
            }
        }
    }
}

/** Emojis qui flottent (chauves-souris). */
private fun DrawScope.emojis(t: Float, s: Seeds, paint: android.graphics.Paint, list: List<String>, count: Int, drift: Boolean) {
    for (i in 0 until count) {
        paint.textSize = (34 + s[i, 2] * 26).dp.toPx()
        val x = ((s[i, 0] + if (drift) t * (1 + i % 2) else 0f) % 1f) * size.width
        val y = (0.10f + s[i, 1] * 0.10f) * size.height + wave(t, 3 + i % 3, s[i, 3]) * 14.dp.toPx()
        drawContext.canvas.nativeCanvas.drawText(list[i % list.size], x, y, paint)
    }
}

/** Grands emojis posés dans les coins bas (citrouilles, fleurs de lys). */
private fun DrawScope.corners(paint: android.graphics.Paint, emoji: String) {
    paint.textSize = 72.dp.toPx()
    drawContext.canvas.nativeCanvas.drawText(emoji, 90.dp.toPx(), size.height - 40.dp.toPx(), paint)
    paint.textSize = 56.dp.toPx()
    drawContext.canvas.nativeCanvas.drawText(emoji, size.width - 90.dp.toPx(), size.height - 50.dp.toPx(), paint)
}
