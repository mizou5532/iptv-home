package home.iptv.tv.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.withFrameNanos
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import home.iptv.shared.xtream.LiveStream
import home.iptv.tv.theme.IptvColors

/**
 * Deux colonnes : catégories (à gauche), chaînes de la catégorie (à droite).
 * [restoreFocusId] : chaîne à refocaliser (retour du plein écran). Les états de défilement viennent de l'appelant
 * pour survivre à la lecture.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LiveBrowseScreen(
    vm: LiveBrowseViewModel,
    categoriesState: LazyListState,
    streamsState: LazyListState,
    restoreFocusId: String?,
    onPlay: (LiveStream) -> Unit,
) {
    val categories by vm.categories.collectAsState()
    val streams by vm.streams.collectAsState()
    val selected by vm.selectedId.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val firstCategory = remember { FocusRequester() }
    val restoreRequester = remember { FocusRequester() }
    var initialFocusDone by remember { mutableStateOf(false) }

    // Focus de départ : première catégorie (sauf si on revient d'une lecture).
    LaunchedEffect(categories.isNotEmpty()) {
        if (categories.isNotEmpty() && restoreFocusId == null && !initialFocusDone) {
            withFrameNanos { }
            runCatching { firstCategory.requestFocus() }
            initialFocusDone = true
        }
    }
    LaunchedEffect(restoreFocusId, streams) {
        if (restoreFocusId == null) return@LaunchedEffect
        val index = streams.indexOfFirst { it.id == restoreFocusId }
        if (index < 0) return@LaunchedEffect
        streamsState.scrollToItem(index)
        withFrameNanos { }
        runCatching { restoreRequester.requestFocus() }
        initialFocusDone = true
    }

    Row(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(Modifier.width(440.dp).fillMaxHeight()) {
            Text("Direct", color = IptvColors.Text, fontSize = 40.sp)
            Text(
                if (refreshing) "Mise à jour…" else " ",
                color = IptvColors.TextSecondary,
                fontSize = 22.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
            LazyColumn(state = categoriesState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(categories, key = { it.id }) { cat ->
                    val first = cat.id == categories.first().id
                    Row2(
                        text = cat.name,
                        selected = cat.id == selected,
                        modifier = if (first) Modifier.focusRequester(firstCategory) else Modifier,
                        onFocus = { vm.select(cat.id) },
                        onClick = { vm.select(cat.id) },
                    )
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Text(
                categories.firstOrNull { it.id == selected }?.name ?: "",
                color = IptvColors.Text,
                fontSize = 32.sp,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            if (categories.isEmpty() && !refreshing) {
                Text("Aucune chaîne en cache.", color = IptvColors.TextSecondary, fontSize = 28.sp)
            }
            // LazyColumn : seules les lignes visibles sont composées (20 000 chaînes possibles).
            LazyColumn(state = streamsState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(streams, key = { it.id }) { s ->
                    Row2(
                        text = s.name,
                        prefix = s.number?.toString(),
                        selected = false,
                        modifier = if (s.id == restoreFocusId) Modifier.focusRequester(restoreRequester) else Modifier,
                        onClick = { onPlay(s) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Row2(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    prefix: String? = null,
    onFocus: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val bg = when {
        focused -> IptvColors.Accent
        selected -> IptvColors.Accent.copy(alpha = 0.35f)
        else -> IptvColors.Surface
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .border(if (focused) 4.dp else 0.dp, if (focused) IptvColors.Focus else bg, shape)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus?.invoke()
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (prefix != null) {
            Box(Modifier.width(72.dp)) { Text(prefix, color = IptvColors.TextSecondary, fontSize = 26.sp) }
        }
        Text(text, color = IptvColors.Text, fontSize = 28.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
