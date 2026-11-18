package home.iptv.tv.startup

import home.iptv.tv.data.openingTab
import home.iptv.tv.nav.label
import androidx.compose.ui.platform.testTag
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.luminance
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
import home.iptv.tv.theme.darkTokens
import home.iptv.tv.theme.lightTokens
import home.iptv.tv.theme.parseProfileColor

/** Style « Doux » (maquette 4, choisie le 16 nov. 2026) : clair et pastel, formes qui flottent, bulles qui tournent. */
/**
 * Couleurs du thème Doux. [DOUX] : clair (par défaut) ; [DOUX_NUIT] : le même dessin en sombre (choix du propriétaire,
 * 18 nov. : « comme Clair, mais plus sombre »). [Letter] : initiale d'un profil sans photo, sur la bulle blanche.
 */
private data class DouxPalette(
    val Background: Color, val Ink: Color, val InkSoft: Color,
    val Blue: Color, val Peach: Color, val Pink: Color, val Star: Color,
    val Letter: Color, val dark: Boolean,
    /** Anneau du profil sélectionné : bien visible sur le fond (prune foncé en clair, rose vif en sombre). */
    val Ring: Color,
)

private val DOUX = DouxPalette(
    Background = Color(0xFFFDF3F6), Ink = Color(0xFF2B1D26), InkSoft = Color(0xFF6E5A66),
    Blue = Color(0xFFDCE8FF), Peach = Color(0xFFFFE6C2), Pink = Color(0xFFF4B6C8), Star = Color(0xFFF29BB6),
    Letter = Color(0xFF2B1D26), dark = false, Ring = Color(0xFF2B1D26),
)

private val DOUX_NUIT = DouxPalette(
    Background = Color(0xFF17121D), Ink = Color(0xFFFBEFF4), InkSoft = Color(0xFFB9A7B4),
    Blue = Color(0xFF26324F), Peach = Color(0xFF3D3022), Pink = Color(0xFF5A2E44), Star = Color(0xFFF29BB6),
    Letter = Color(0xFF2B1D26), dark = true, Ring = Color(0xFFF29BB6),
)

/** Identifiant du thème sombre, choisi sur l'iPhone (`households.settings.welcome_theme`). */
const val WELCOME_DOUX_NUIT = "doux_nuit"

/** Palette en cours pour l'écran « Qui regarde ? » (Doux ou Doux nuit). */
private val LocalDoux = androidx.compose.runtime.staticCompositionLocalOf { DOUX }

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
    deviceLabel: String? = null,
    /** Thème choisi sur l'iPhone pour toute la maison : « doux » (par défaut), « doux_nuit » ou « aurore ». */
    theme: String? = null,
    /** Alerte en haut de l'écran (fin d'abonnement IPTV proche) ; null = rien. */
    notice: String? = null,
    /** Fête du moment (thèmes de saison, réglables sur l'iPhone) et son mot du jour ; null = décor habituel. */
    season: home.iptv.shared.account.Season? = null,
    greeting: String? = null,
    onPick: (Profile) -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(profiles) { if (profiles.isNotEmpty()) runCatching { first.requestFocus() } }
    if (theme == "aurore") {
        AuroreWelcome(profiles, offline, onRestart, deviceLabel, first, notice, season, greeting, onPick)
        return
    }
    val Doux = if (theme == WELCOME_DOUX_NUIT) DOUX_NUIT else DOUX
    // Boutons (Redémarrer) aux couleurs de l'écran, clair ou sombre.
    androidx.compose.runtime.CompositionLocalProvider(LocalDoux provides Doux) {
    IptvTheme(if (Doux.dark) darkTokens(Color(0xFFF4B6C8)) else lightTokens(Doux.Pink)) {
        Box(Modifier.fillMaxSize().background(Doux.Background)) {
            FloatingShapes()
            season?.let { SeasonLayer(it, dark = Doux.dark) }
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (season != null && greeting != null) SeasonGreeting(season, greeting, Modifier.padding(bottom = 18.dp))
                Text("Coucou ! Qui regarde ?", color = Doux.Ink, fontSize = 38.sp, fontWeight = FontWeight.ExtraBold)
                Row(Modifier.padding(top = 40.dp), horizontalArrangement = Arrangement.spacedBy(44.dp)) {
                    profiles.forEachIndexed { i, p ->
                        ProfileBubble(p, i, if (i == 0) Modifier.focusRequester(first) else Modifier) { onPick(p) }
                    }
                }
                if (deviceLabel != null) {
                    Text(deviceLabel, color = Doux.InkSoft, fontSize = 14.sp, modifier = Modifier.padding(top = 20.dp))
                }
                if (onRestart != null) {
                    // Sous les profils (Bas y mène) : ferme et relance l'app, la mise à jour en attente apparaît alors.
                    home.iptv.tv.live.LiveButton("⟳ Redémarrer l'application", onRestart, Modifier.padding(top = 36.dp), small = true)
                }
            }
            NoticeBanner(notice)
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
}

/** Grands ronds pastel et petite étoile qui flottent lentement (dessin simple : léger pour la télé). */
@Composable
private fun BoxScope.FloatingShapes() {
    val Doux = LocalDoux.current
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
    val Doux = LocalDoux.current
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
                    .border(4.dp, if (focused) Doux.Ring else Color.Transparent, CircleShape)
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
                home.iptv.tv.theme.ProfileAvatar(profile.id, profile.name, Modifier.fillMaxSize(), profile.settings.avatarVersion, profile.settings.hasAvatar != false) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(profile.name.take(1).uppercase(), color = Doux.Letter, fontSize = 48.sp, fontWeight = FontWeight.ExtraBold)
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

// ---------------------------------------------------------------------------------------------------------------
// Thème « Aurore » (maquette 1) : nuit, grands halos aux couleurs des profils qui dérivent lentement, cartes de verre.

private val AuroreNight = Color(0xFF070A12)


@Composable
private fun Halo(color: Color, size: Dp, modifier: Modifier) {
    Box(modifier.size(size).background(androidx.compose.ui.graphics.Brush.radialGradient(listOf(color.copy(alpha = 0.5f), color.copy(alpha = 0.18f), Color.Transparent))))
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AuroreWelcome(
    profiles: List<Profile>, offline: Boolean, onRestart: (() -> Unit)?, deviceLabel: String?, first: FocusRequester, notice: String?,
    season: home.iptv.shared.account.Season?, greeting: String?, onPick: (Profile) -> Unit,
) {
    val colors = profiles.map { parseProfileColor(it.color) }.ifEmpty { listOf(Color(0xFF8FB4FF), Color(0xFFF4B6C8), Color(0xFFFFC46B)) }
    val a by looping(0f, 1f, 14_000)
    val b by looping(0f, 1f, 18_000)
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    val hello = if (hour in 5..17) "Bonjour" else "Bonsoir"
    IptvTheme(home.iptv.tv.theme.darkTokens(colors.first())) {
        Box(Modifier.fillMaxSize().background(AuroreNight)) {
            Halo(colors[0], 560.dp, Modifier.align(Alignment.TopStart).offset((-160 + 110 * a).dp, (-220 + 60 * a).dp))
            Halo(colors[1 % colors.size], 600.dp, Modifier.align(Alignment.CenterEnd).offset((220 - 130 * b).dp, (60 - 40 * b).dp))
            Halo(colors[2 % colors.size], 560.dp, Modifier.align(Alignment.BottomCenter).offset((-80 * a).dp, 330.dp))
            season?.let { SeasonLayer(it, dark = true) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 28.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("IPTV HOME", color = Color(0xFFC9D1DE), fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                Text(java.text.SimpleDateFormat("EEEE d MMM · HH:mm", java.util.Locale.CANADA_FRENCH).format(java.util.Date()), color = Color(0xFFC9D1DE), fontSize = 14.sp)
            }
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                if (season != null && greeting != null) SeasonGreeting(season, greeting, Modifier.padding(bottom = 16.dp))
                Text("$hello. Qui regarde ?", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.ExtraBold)
                Row(Modifier.padding(top = 36.dp), horizontalArrangement = Arrangement.spacedBy(26.dp), verticalAlignment = Alignment.Bottom) {
                    profiles.forEachIndexed { i, p -> AuroreTile(p, if (i == 0) Modifier.focusRequester(first) else Modifier) { onPick(p) } }
                }
                if (deviceLabel != null) Text(deviceLabel, color = Color(0xFFA3ADBD), fontSize = 14.sp, modifier = Modifier.padding(top = 18.dp))
                if (onRestart != null) home.iptv.tv.live.LiveButton("⟳ Redémarrer l'application", onRestart, Modifier.padding(top = 30.dp), small = true)
            }
            NoticeBanner(notice, top = 64.dp)
            if (offline) Text("Hors ligne — profils enregistrés", color = Color(0xFFA3ADBD), fontSize = 15.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp))
        }
    }
}

/** Bandeau d'alerte (fin d'abonnement) en haut, ambre et lisible sur les deux thèmes ; jamais focalisable. */
@Composable
private fun BoxScope.NoticeBanner(text: String?, top: Dp = 28.dp) {
    if (text == null) return
    Row(
        Modifier.align(Alignment.TopCenter).padding(top = top).clip(RoundedCornerShape(999.dp)).background(Color(0xFFFFC46B))
            .padding(horizontal = 20.dp, vertical = 10.dp).testTag("notice"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(Color(0xFF3A2410)), contentAlignment = Alignment.Center) {
            Text("!", color = Color(0xFFFFC46B), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
        }
        Text(text, color = Color(0xFF3A2410), fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AuroreTile(profile: Profile, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val color = parseProfileColor(profile.color)
    val lift by animateFloatAsState(if (focused) 1f else 0f, tween(180), label = "carte")
    val shape = RoundedCornerShape(26.dp)
    Column(
        modifier
            .graphicsLayer { translationY = -10f * lift * density; scaleX = 1f + 0.05f * lift; scaleY = 1f + 0.05f * lift }
            .width(176.dp).clip(shape)
            .background(Color.White.copy(alpha = 0.05f + 0.09f * lift))
            .border(if (focused) 2.dp else 1.dp, Color.White.copy(alpha = if (focused) 0.9f else 0.12f), shape)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent {
                if (it.type == KeyEventType.KeyUp && (it.key == Key.DirectionCenter || it.key == Key.Enter)) { onClick(); true } else false
            }
            .focusable()
            .padding(vertical = 22.dp, horizontal = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(if (focused) 116.dp else 100.dp)
                .border(if (focused) 4.dp else 0.dp, if (focused) Color.White else Color.Transparent, CircleShape)
                .padding(if (focused) 4.dp else 0.dp).clip(CircleShape).background(color),
            contentAlignment = Alignment.Center,
        ) {
            home.iptv.tv.theme.ProfileAvatar(profile.id, profile.name, Modifier.fillMaxSize(), profile.settings.avatarVersion, profile.settings.hasAvatar != false) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(profile.name.take(1).uppercase(), color = if (color.luminance() > 0.45f) Color(0xFF0B0F17) else Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Text(profile.name, color = if (focused) Color.White else Color(0xFFC9D1DE), fontSize = 20.sp, fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium)
        Text("S'ouvre sur ${openingTab(profile.home, profile.settings.sections).label()}", color = color.copy(alpha = if (focused) 1f else 0.75f), fontSize = 12.sp)
    }
}
