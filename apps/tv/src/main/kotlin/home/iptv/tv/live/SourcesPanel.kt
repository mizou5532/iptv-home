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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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

/**
 * Copies d'un groupe, avec des noms lisibles : nom nettoyé suivi de la qualité (« RDS · FHD »), catégorie avec son
 * pays (« CA · Sports »). Le nom d'origine reste celui de la chaîne chez le fournisseur ; seul l'affichage change.
 */
fun sourceItems(sources: List<GroupSource>, categoryNames: Map<String, String>, activeSourceId: String?): List<SourceItem> =
    sources.map {
        val n = home.iptv.shared.text.channelName(it.name)
        val c = categoryNames[it.categoryId]?.let { home.iptv.shared.text.categoryName(it) }
        SourceItem(
            it.id, listOfNotNull(n.title, n.quality).joinToString(" · "),
            c?.let { k -> listOfNotNull(k.country, k.title).joinToString(" · ") } ?: "Catégorie", it.id == activeSourceId,
        )
    }

/** « Sources » ou « Sources · 3 » quand la chaîne a plusieurs copies. */
fun sourcesLabel(count: Int): String = if (count > 1) "Sources · $count" else "Sources"

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
    val close = remember { FocusRequester() }
    val listState = rememberLazyListState()
    BackHandler(onBack = onClose)
    // Les copies arrivent après la première image (lecture hors du fil principal) : le focus va d'abord sur
    // « Fermer », présent dès l'ouverture, puis sur la copie en lecture dès qu'elle est affichée. Sans cela le
    // focus resterait derrière le panneau (relecture 4a.1, I1).
    LaunchedEffect(items.isNotEmpty()) {
        if (items.isNotEmpty()) runCatching { listState.scrollToItem(startIndex) }
        withFrameNanos { }
        val onRow = items.isNotEmpty() && runCatching { first.requestFocus() }.getOrDefault(false)
        if (!onRow) runCatching { close.requestFocus() }
    }
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
        LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(LiveDims.RowGap)) {
            itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
                SourceRow(item, if (index == startIndex) Modifier.focusRequester(first) else Modifier) { onChoose(item.id) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(LiveDims.Gap)) {
            LiveButton("Arrêter", onStop)
            LiveButton("Fermer", onClose, Modifier.focusRequester(close))
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
