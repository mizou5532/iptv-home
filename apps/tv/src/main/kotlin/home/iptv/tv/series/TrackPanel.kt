package home.iptv.tv.series

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import home.iptv.tv.live.LiveButton
import home.iptv.tv.live.LiveDims
import home.iptv.tv.live.focusLook
import home.iptv.tv.player.PlayerTracks
import home.iptv.tv.player.TrackOption
import home.iptv.tv.theme.LocalIptvTokens

/**
 * Panneau « Audio et sous-titres » : les pistes du fichier avec leur langue et leur libellé, « Aucun » pour les
 * sous-titres. Il prend le focus à son ouverture sur la piste audio en cours ; Retour est géré par l'écran parent.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TrackPanel(
    tracks: PlayerTracks,
    onAudio: (TrackOption) -> Unit,
    onSubtitle: (TrackOption?) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalIptvTokens.current
    val first = remember { FocusRequester() }
    val close = remember { FocusRequester() }
    // Les lignes d'une liste paresseuse sont composées à la mesure : on attend une image avant de demander le focus,
    // et « Fermer » sert de repli pour que le focus ne reste jamais sous le panneau (relecture 4a.1, I2).
    LaunchedEffect(Unit) {
        withFrameNanos { }
        if (!runCatching { first.requestFocus() }.getOrDefault(false)) runCatching { close.requestFocus() }
    }
    val firstAudio = tracks.audio.indexOfFirst { it.selected }.coerceAtLeast(0)
    Column(
        modifier
            .fillMaxHeight()
            .fillMaxWidth(LiveDims.PanelWidthFraction)
            .background(t.background)
            .padding(LiveDims.PageGutter / 2),
        verticalArrangement = Arrangement.spacedBy(LiveDims.Gap),
    ) {
        Text("Audio et sous-titres", color = t.text, fontSize = t.titleSize)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LiveDims.RowGap)) {
            item(key = "audio-title") { Text("Audio", color = t.textMuted, fontSize = t.rowTextSize) }
            if (tracks.audio.isEmpty()) {
                item(key = "audio-none") { Text("Une seule piste dans ce fichier.", color = t.textMuted, fontSize = t.metaTextSize) }
            }
            tracks.audio.forEachIndexed { i, o ->
                item(key = "a-${o.id}") {
                    TrackRow(trackTitle(o, i), o.selected, if (i == firstAudio) Modifier.focusRequester(first) else Modifier) { onAudio(o) }
                }
            }
            item(key = "sub-title") { Text("Sous-titres", color = t.textMuted, fontSize = t.rowTextSize, modifier = Modifier.padding(top = LiveDims.Gap)) }
            item(key = "sub-none") {
                TrackRow("Aucun", tracks.subtitles.none { it.selected }, if (tracks.audio.isEmpty()) Modifier.focusRequester(first) else Modifier) { onSubtitle(null) }
            }
            tracks.subtitles.forEachIndexed { i, o ->
                item(key = "t-${o.id}") { TrackRow(trackTitle(o, i), o.selected, Modifier) { onSubtitle(o) } }
            }
        }
        LiveButton("Fermer", onClose, Modifier.focusRequester(close))
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TrackRow(title: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val t = LocalIptvTokens.current
    var focused by remember { mutableStateOf(false) }
    val shape = remember(t.radiusCard) { RoundedCornerShape(t.radiusCard) }
    Row(
        modifier
            .fillMaxWidth()
            .focusLook(focused, shape, "track")
            .clip(shape)
            .background(if (focused) t.surfaceRaised else t.surface)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(LiveDims.RowPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LiveDims.Gap),
    ) {
        Box(Modifier.width(LiveDims.SelectionBar).height(LiveDims.SelectionBarHeight).clip(RoundedCornerShape(LiveDims.SelectionBar)).background(if (selected) t.accent else t.surface))
        Text(title, color = t.text, fontSize = t.rowTextSize, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (selected) Text("Choisi", color = t.accent, fontSize = t.metaTextSize)
    }
}
