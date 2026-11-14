package home.iptv.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import home.iptv.shared.xtream.LiveStream
import home.iptv.tv.live.LiveSession
import home.iptv.tv.live.LiveSessions
import home.iptv.tv.live.LiveRoute
import home.iptv.tv.player.PlayerController
import home.iptv.tv.startup.PairingScreen
import home.iptv.tv.startup.StartupUi
import home.iptv.tv.startup.StartupViewModel
import home.iptv.tv.startup.WhoIsWatchingScreen
import home.iptv.tv.theme.IptvColors
import home.iptv.tv.theme.IptvTheme

/** Adresse de la page de configuration (à confirmer au déploiement, tâche 8). */
object ConfigPage {
    const val URL = "https://iptv-home-config.vercel.app"
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun App(
    vm: StartupViewModel,
    sessions: LiveSessions,
    player: PlayerController,
    urlFor: (LiveStream) -> String?,
) {
    val ui by vm.ui.collectAsState()
    var picked by remember { mutableStateOf<Profile?>(null) }
    LaunchedEffect(Unit) { vm.start() }
    IptvTheme {
    Box(Modifier.fillMaxSize().background(IptvColors.Background)) {
        when (val s = ui) {
            StartupUi.Loading -> Centered { Text("Chargement…", color = IptvColors.TextSecondary, fontSize = 28.sp) }
            is StartupUi.Pairing -> PairingScreen(s, ConfigPage.URL)
            is StartupUi.Ready ->
                picked.let { profile ->
                    if (profile == null) WhoIsWatchingScreen(s.profiles, s.offline) { picked = it }
                    else LiveForProfile(profile, sessions, player, urlFor) { picked = null }
                }
            is StartupUi.Failed -> Centered {
                Text(s.message, color = IptvColors.Text, fontSize = 28.sp)
                RetryButton { vm.start() }
            }
        }
    }
    }
}

/** L'écran du direct d'un profil : sa session s'ouvre à l'arrivée et se ferme au départ (jamais pendant la composition). */
@Composable
private fun LiveForProfile(
    profile: Profile,
    sessions: LiveSessions,
    player: PlayerController,
    urlFor: (LiveStream) -> String?,
    onChangeProfile: () -> Unit,
) {
    var session by remember(profile.id) { mutableStateOf<LiveSession?>(null) }
    DisposableEffect(profile.id) {
        val s = sessions.open(profile)
        session = s
        onDispose { sessions.close(s) }
    }
    session?.let { LiveRoute(it.vm, profile, player, urlFor, onChangeProfile) }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterVertically),
    ) { content() }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RetryButton(onClick: () -> Unit) {
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .focusRequester(requester)
            .clip(shape)
            .background(IptvColors.Accent)
            .border(if (focused) 4.dp else 0.dp, if (focused) IptvColors.Focus else IptvColors.Accent, shape)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent {
                if (it.type == KeyEventType.KeyUp && (it.key == Key.DirectionCenter || it.key == Key.Enter)) {
                    onClick(); true
                } else false
            }
            .focusable()
            .padding(horizontal = 40.dp, vertical = 16.dp),
    ) { Text("Réessayer", color = IptvColors.Text, fontSize = 24.sp) }
}
