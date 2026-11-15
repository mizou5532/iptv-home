package home.iptv.tv.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import home.iptv.tv.data.HomeTab
import home.iptv.tv.live.LiveDims
import home.iptv.tv.live.focusLook
import home.iptv.tv.theme.LocalIptvTokens

/** Libellé affiché de chaque section. */
fun HomeTab.label(): String = when (this) {
    HomeTab.DIRECT -> "Direct"
    HomeTab.SERIES -> "Séries"
}

/**
 * Barre des sections (Direct / Séries), à placer dans la barre du haut. Gauche et droite changent de pastille, OK
 * ouvre la section : le focus seul ne change jamais d'écran (il ne doit pas couper un direct par mégarde).
 * [selectedRequester] reçoit le focus sur la pastille de la section ouverte (retour depuis le contenu).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SectionBar(
    selected: HomeTab,
    onSelect: (HomeTab) -> Unit,
    selectedRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(LiveDims.RowGap)) {
        HomeTab.entries.forEach { tab ->
            SectionTab(tab.label(), tab == selected, if (tab == selected) Modifier.focusRequester(selectedRequester) else Modifier) {
                onSelect(tab)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SectionTab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val t = LocalIptvTokens.current
    var focused by remember { mutableStateOf(false) }
    val shape = remember(t.radiusChip) { RoundedCornerShape(t.radiusChip) }
    Box(
        modifier
            .focusLook(focused, shape, "section")
            .clip(shape)
            .background(if (focused) t.surfaceRaised else if (selected) t.surface else t.background)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = LiveDims.ChipH, vertical = LiveDims.ChipV),
    ) {
        Text(label, color = if (selected || focused) t.text else t.textMuted, fontSize = t.titleSize * 0.8f)
    }
}
