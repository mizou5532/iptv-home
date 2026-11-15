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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import home.iptv.shared.sync.Profile
import home.iptv.tv.theme.LocalIptvTokens
import home.iptv.tv.theme.parseProfileColor
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val ClockFormat = DateTimeFormatter.ofPattern("HH:mm")

/** Barre du haut : titre, mise à jour en cours, profil, « Changer de profil », heure. */
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
        Box(
            Modifier.size(LiveDims.Avatar).clip(CircleShape).background(parseProfileColor(profile.color)),
            contentAlignment = Alignment.Center,
        ) { Text(profile.name.take(1).uppercase(), color = t.text, fontSize = t.rowTextSize) }
        Text(profile.name, color = t.text, fontSize = t.rowTextSize)
        ProfileChip("Changer de profil", chipRequester, downRequester, onChangeProfile)
        Clock()
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ProfileChip(label: String, requester: FocusRequester, down: FocusRequester, onClick: () -> Unit) {
    val t = LocalIptvTokens.current
    var focused by remember { mutableStateOf(false) }
    val shape = remember(t.radiusChip) { RoundedCornerShape(t.radiusChip) }
    Box(
        Modifier
            .focusRequester(requester)
            .focusProperties { this.down = down }
            .focusLook(focused, shape, "Changer de profil")
            .clip(shape)
            .background(if (focused) t.surfaceRaised else t.surface)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = LiveDims.ChipH, vertical = LiveDims.ChipV),
    ) { Text(label, color = t.text, fontSize = t.metaTextSize) }
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
