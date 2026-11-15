package home.iptv.tv.live

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import home.iptv.shared.groups.GroupSource
import home.iptv.tv.theme.LocalIptvTokens

/** Une copie proposée dans le panneau : nom original du fournisseur et catégorie d'origine. */
data class SourceItem(val id: String, val name: String, val category: String, val active: Boolean)

fun sourceItems(sources: List<GroupSource>, categoryNames: Map<String, String>, activeSourceId: String?): List<SourceItem> =
    sources.map { SourceItem(it.id, it.name, categoryNames[it.categoryId] ?: "Catégorie", it.id == activeSourceId) }

/**
 * Panneau « Sources » : les copies du groupe en lecture avec leurs noms originaux et leur catégorie, la copie
 * active marquée, « Arrêter » toujours atteignable. Il prend le focus à l'ouverture (le fond devient non focalisable tant qu'il est ouvert) ;
 * Retour le ferme d'abord (le parent rend le focus à son point de départ).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SourcesPanel(
    title: String,
    items: List<SourceItem>,
    onChoose: (String) -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalIptvTokens.current
    val first = remember { FocusRequester() }
    val startIndex = items.indexOfFirst { it.active }.coerceAtLeast(0)
    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Column(
        modifier
            .fillMaxHeight()
            .fillMaxWidth(LiveDims.PanelWidthFraction)
            .background(t.background)
            .padding(LiveDims.PageGutter / 2),
        verticalArrangement = Arrangement.spacedBy(LiveDims.Gap),
    ) {
        Text("Sources", color = t.text, fontSize = t.titleSize)
        Text(title, color = t.textMuted, fontSize = t.rowTextSize, maxLines = 1, overflow = TextOverflow.Ellipsis)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LiveDims.RowGap)) {
            itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
                SourceRow(item, if (index == startIndex) Modifier.focusRequester(first) else Modifier) { onChoose(item.id) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(LiveDims.Gap)) {
            LiveButton("Arrêter", onStop)
            LiveButton("Fermer", onClose)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SourceRow(item: SourceItem, modifier: Modifier, onClick: () -> Unit) {
    val t = LocalIptvTokens.current
    var focused by remember { mutableStateOf(false) }
    val shape = remember(t.radiusCard) { RoundedCornerShape(t.radiusCard) }
    Row(
        modifier
            .fillMaxWidth()
            .focusLook(focused, shape, "source")
            .clip(shape)
            .background(if (focused) t.surfaceRaised else t.surface)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(LiveDims.RowPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LiveDims.Gap),
    ) {
        Box(Modifier.width(LiveDims.SelectionBar).height(LiveDims.SelectionBarHeight).clip(RoundedCornerShape(LiveDims.SelectionBar)).background(if (item.active) t.accent else t.surface))
        Column(Modifier.weight(1f)) {
            Text(item.name, color = t.text, fontSize = t.rowTextSize, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                if (item.active) "${item.category} · En lecture" else item.category,
                color = if (item.active) t.accent else t.textMuted,
                fontSize = t.metaTextSize,
                maxLines = 1,
            )
        }
    }
}
