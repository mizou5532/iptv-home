package home.iptv.tv.startup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import home.iptv.shared.sync.Profile
import home.iptv.tv.theme.IptvColors
import home.iptv.tv.theme.parseProfileColor

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
    Box(Modifier.fillMaxSize().background(IptvColors.Background)) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Qui regarde ?", color = IptvColors.Text, fontSize = 40.sp)
            Row(Modifier.padding(top = 56.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                profiles.forEachIndexed { i, p ->
                    ProfileTile(p, if (i == 0) Modifier.focusRequester(first) else Modifier) { onPick(p) }
                }
            }
            if (onRestart != null) {
                // Sous les profils (Bas y mène) : ferme et relance l'app, la mise à jour en attente apparaît alors.
                home.iptv.tv.live.LiveButton("⟳ Redémarrer l'application", onRestart, Modifier.padding(top = 48.dp), small = true)
            }
        }
        if (offline) {
            Text(
                "Hors ligne — profils enregistrés",
                color = IptvColors.TextSecondary, fontSize = 16.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ProfileTile(profile: Profile, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(24.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(
            Modifier
                .size(184.dp)
                .scale(if (focused) 1.08f else 1f)
                .clip(shape)
                .background(parseProfileColor(profile.color))
                .border(if (focused) 5.dp else 0.dp, if (focused) IptvColors.Focus else parseProfileColor(profile.color), shape)
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
                    Text(profile.name.take(1).uppercase(), color = IptvColors.Text, fontSize = 72.sp)
                }
            }
        }
        Text(
            profile.name,
            color = if (focused) IptvColors.Text else IptvColors.TextSecondary,
            fontSize = 22.sp, modifier = Modifier.padding(top = 20.dp),
        )
    }
}
