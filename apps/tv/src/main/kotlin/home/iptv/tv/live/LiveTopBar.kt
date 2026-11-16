package home.iptv.tv.live

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import home.iptv.shared.sync.Profile
import home.iptv.tv.theme.LocalIptvTokens
import home.iptv.tv.theme.parseProfileColor
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val ClockFormat = DateTimeFormatter.ofPattern("HH:mm")

/** Barre du haut : titre, mise à jour en cours, pastille du profil (la sélectionner pour changer de profil), heure. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LiveTopBar(
    profile: Profile,
    refreshing: Boolean,
    chipRequester: FocusRequester,
    downRequester: FocusRequester,
    onChangeProfile: () -> Unit,
    modifier: Modifier = Modifier,
    /** Message bref après une mise à jour ratée ou incomplète (voir [LiveBrowseViewModel.refreshNotice]). */
    notice: String? = null,
    /** Remplace le titre « Direct » (la barre des sections). */
    title: @Composable () -> Unit = { Text("Direct", color = LocalIptvTokens.current.text, fontSize = LocalIptvTokens.current.titleSize) },
) {
    val t = LocalIptvTokens.current
    Row(
        modifier.fillMaxWidth().padding(horizontal = LiveDims.PageGutter, vertical = LiveDims.TopBarVertical),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LiveDims.Gap),
    ) {
        title()
        Text(if (refreshing) "Mise à jour…" else notice.orEmpty(), color = t.textMuted, fontSize = t.metaTextSize, modifier = Modifier.weight(1f))
        // Pastille du profil (photo + nom) : la sélectionner ramène à « Qui regarde ? ».
        ProfilePill(profile, chipRequester, downRequester, onChangeProfile)
        Clock()
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ProfilePill(profile: Profile, requester: FocusRequester, down: FocusRequester, onClick: () -> Unit) {
    val t = LocalIptvTokens.current
    var focused by remember { mutableStateOf(false) }
    val shape = CircleShape
    Row(
        Modifier
            .focusRequester(requester)
            .focusProperties { this.down = down }
            .focusLook(focused, RoundedCornerShape(50), "Changer de profil")
            .clip(RoundedCornerShape(50))
            .background(if (focused) t.surfaceRaised else t.surface)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClickLabel = "Changer de profil", onClick = onClick)
            .padding(start = 4.dp, end = LiveDims.ChipH, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(LiveDims.Avatar).clip(shape).background(t.accent), contentAlignment = Alignment.Center) {
            home.iptv.tv.theme.ProfileAvatar(profile.id, profile.name, Modifier.fillMaxSize(), profile.settings.avatarVersion, profile.settings.hasAvatar != false) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(profile.name.take(1).uppercase(), color = t.onAccent, fontSize = t.rowTextSize)
                }
            }
        }
        Text(profile.name, color = t.text, fontSize = t.rowTextSize)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Clock() {
    val t = LocalIptvTokens.current
    var now by remember { mutableStateOf(LocalTime.now().format(ClockFormat)) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalTime.now().format(ClockFormat)
            delay(20_000)
        }
    }
    Text(now, color = t.textMuted, fontSize = t.rowTextSize)
}
