package home.iptv.tv.startup

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import home.iptv.shared.sync.Profile
import home.iptv.tv.feature.LocalFeatureAutoRotate
import home.iptv.tv.theme.IptvTheme
import home.iptv.tv.theme.lightTokens
import home.iptv.tv.theme.parseProfileColor

/** Style « Doux » (maquette 4, choisie le 16 nov. 2026) : clair et pastel, formes qui flottent, bulles qui tournent. */
private object Doux {
    val Background = Color(0xFFFDF3F6)
    val Ink = Color(0xFF2B1D26)
    val InkSoft = Color(0xFF6E5A66)
    val Blue = Color(0xFFDCE8FF)
    val Peach = Color(0xFFFFE6C2)
    val Pink = Color(0xFFF4B6C8)
    val Star = Color(0xFFF29BB6)
}

/** Forme de bulle irrégulière (coins inégaux) : en tournant, elle « respire » autour de la photo. */
private val BlobShape = RoundedCornerShape(topStartPercent = 42, topEndPercent = 58, bottomEndPercent = 55, bottomStartPercent = 45)

/**
 * Valeur animée en boucle (aller-retour ou tour complet). Les tests de télécommande coupent les animations
 * ([LocalFeatureAutoRotate]) : une animation sans fin empêcherait l'attente du calme.
 */
@Composable
private fun looping(from: Float, to: Float, ms: Int, reverse: Boolean = true): State<Float> {
    if (!LocalFeatureAutoRotate.current) return remember { mutableStateOf(from) }
    val transition = rememberInfiniteTransition(label = "doux")
    return transition.animateFloat(
        from, to,
        infiniteRepeatable(tween(ms, easing = if (reverse) FastOutSlowInEasing else LinearEasing), if (reverse) RepeatMode.Reverse else RepeatMode.Restart),
        label = "doux",
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun WhoIsWatchingScreen(
    profiles: List<Profile>,
    offline: Boolean,
    /** Redémarrer l'app (pour faire apparaître une mise à jour sans redémarrer la télé) ; null = pas de bouton. */
    onRestart: (() -> Unit)? = null,
    onPick: (Profile) -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(profiles) { if (profiles.isNotEmpty()) runCatching { first.requestFocus() } }
    // Boutons (Redémarrer) aux couleurs claires de l'écran.
    IptvTheme(lightTokens(Doux.Pink)) {
        Box(Modifier.fillMaxSize().background(Doux.Background)) {
            FloatingShapes()
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Coucou ! Qui regarde ?", color = Doux.Ink, fontSize = 38.sp, fontWeight = FontWeight.ExtraBold)
                Row(Modifier.padding(top = 40.dp), horizontalArrangement = Arrangement.spacedBy(44.dp)) {
                    profiles.forEachIndexed { i, p ->
                        ProfileBubble(p, i, if (i == 0) Modifier.focusRequester(first) else Modifier) { onPick(p) }
                    }
                }
                if (onRestart != null) {
                    // Sous les profils (Bas y mène) : ferme et relance l'app, la mise à jour en attente apparaît alors.
                    home.iptv.tv.live.LiveButton("⟳ Redémarrer l'application", onRestart, Modifier.padding(top = 36.dp), small = true)
                }
            }
            if (offline) {
                Text(
                    "Hors ligne — profils enregistrés",
                    color = Doux.InkSoft, fontSize = 15.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp),
                )
            }
        }
    }
}

/** Grands ronds pastel et petite étoile qui flottent lentement (dessin simple : léger pour la télé). */
@Composable
private fun BoxScope.FloatingShapes() {
    val slow by looping(0f, 1f, 7000)
    val fast by looping(0f, 1f, 5000)
    Circle(Doux.Blue, 230.dp, Modifier.align(Alignment.TopStart).offset((-70).dp, (-50).dp + (10 * slow).dp))
    Circle(Doux.Peach, 260.dp, Modifier.align(Alignment.BottomEnd).offset(60.dp, 70.dp - (12 * fast).dp))
    Circle(Doux.Pink, 54.dp, Modifier.align(Alignment.TopEnd).offset((-150).dp, 54.dp + (8 * slow).dp))
    Text("★", color = Doux.Star, fontSize = 26.sp, modifier = Modifier.align(Alignment.BottomStart).offset(150.dp, (-80).dp - (10 * fast).dp))
}

@Composable
private fun Circle(color: Color, size: Dp, modifier: Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ProfileBubble(profile: Profile, index: Int, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val color = parseProfileColor(profile.color)
    // Chaque bulle tourne à son rythme (40 à 52 s le tour) : vivant mais calme.
    val turn by looping(0f, 360f, 40_000 + index * 6_000, reverse = false)
    val scale by animateFloatAsState(if (focused) 1.1f else 1f, tween(180), label = "profil")
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier.graphicsLayer { scaleX = scale; scaleY = scale }) {
        Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(if (focused) 150.dp else 130.dp)
                    .graphicsLayer { rotationZ = turn; alpha = if (focused) 0.95f else 0.55f }
                    .clip(BlobShape).background(color),
            )
            Box(
                Modifier
                    .size(118.dp)
                    // Sélection : anneau prune autour de l'anneau blanc (maquette 4).
                    .border(4.dp, if (focused) Doux.Ink else Color.Transparent, CircleShape)
                    .padding(4.dp)
                    .border(5.dp, Color.White, CircleShape)
                    .clip(CircleShape)
                    .background(Color.White)
                    .onFocusChanged { focused = it.isFocused }
                    .onKeyEvent {
                        if (it.type == KeyEventType.KeyUp && (it.key == Key.DirectionCenter || it.key == Key.Enter)) {
                            onClick(); true
                        } else false
                    }
                    .focusable(),
                contentAlignment = Alignment.Center,
            ) {
                home.iptv.tv.theme.ProfileAvatar(profile.id, profile.name, Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(profile.name.take(1).uppercase(), color = Doux.Ink, fontSize = 48.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }
        Text(
            profile.name,
            color = if (focused) Doux.Ink else Doux.InkSoft,
            fontSize = 22.sp, fontWeight = if (focused) FontWeight.ExtraBold else FontWeight.SemiBold,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}
