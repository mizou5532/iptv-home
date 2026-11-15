package home.iptv.tv.series

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import home.iptv.tv.live.LiveButton
import home.iptv.tv.live.LiveDims
import home.iptv.tv.live.focusLook
import home.iptv.tv.theme.LocalIptvTokens

private object DetailDims {
    val Gutter = 56.dp
    val ColumnGap = 40.dp
    val PosterWidth = 220.dp
    val PosterHeight = 310.dp
    const val PosterRequestW = 440
    const val PosterRequestH = 620
    val BarWidth = 280.dp
    val Bar = 5.dp
}

/**
 * Fiche d'une série (maquette « Fiche d'une série »). Les épisodes sont dans une liste paresseuse (des centaines
 * possibles). OK sur un épisode le lit (ou le reprend), un appui long le marque vu / non vu. Retour revient à l'accueil.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SeriesDetailScreen(
    vm: SeriesDetailViewModel,
    onPlay: (PlayEpisodeRequest) -> Unit,
    onBack: () -> Unit,
    /** Vrai tant que la lecture est affichée par-dessus : à sa fermeture, le focus revient sur l'épisode lu. */
    playing: Boolean = false,
) {
    val t = LocalIptvTokens.current
    val ui by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.start() }
    BackHandler(onBack = onBack)

    Box(Modifier.fillMaxSize().background(t.background).padding(horizontal = DetailDims.Gutter, vertical = 32.dp)) {
        when {
            ui.masked || (!ui.loading && ui.title.isEmpty()) -> Unavailable(
                ui.message ?: "Fiche indisponible", retry = if (ui.masked) null else vm::reload, onBack = onBack,
            )
            ui.loading && ui.title.isEmpty() -> Text("Chargement…", color = t.textMuted, fontSize = t.rowTextSize)
            else -> Content(ui, vm, onPlay, playing)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Unavailable(message: String, retry: (() -> Unit)?, onBack: () -> Unit) {
    val t = LocalIptvTokens.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterVertically),
    ) {
        Text(message, color = t.text, fontSize = t.titleSize)
        Row(horizontalArrangement = Arrangement.spacedBy(LiveDims.Gap)) {
            if (retry != null) LiveButton("Réessayer", retry, Modifier.focusRequester(first), accent = true)
            LiveButton("Retour", onBack, if (retry == null) Modifier.focusRequester(first) else Modifier, accent = retry == null)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Content(ui: SeriesDetailState, vm: SeriesDetailViewModel, onPlay: (PlayEpisodeRequest) -> Unit, playing: Boolean) {
    val t = LocalIptvTokens.current
    val season = ui.seasons.firstOrNull { it.number == ui.selectedSeason }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(DetailDims.ColumnGap)) {
        Column(Modifier.width(DetailDims.PosterWidth).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(LiveDims.Gap)) {
            Cover(ui.title, ui.cover)
            LiveButton(
                if (ui.inMyList) "★ Dans ma liste" else "☆ Ajouter à ma liste", vm::toggleMyList,
                Modifier.fillMaxWidth(), accent = ui.inMyList,
            )
            if (season != null && season.total > 0) {
                val all = season.allWatched
                LiveButton(
                    if (all) "Marquer la saison non vue" else "Marquer la saison vue",
                    { vm.markSeasonWatched(!all) }, Modifier.fillMaxWidth(),
                )
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Text(ui.title, color = t.text, fontSize = t.titleSize * 1.2f, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val seasonsLabel = ui.seasons.size.takeIf { it > 0 }?.let { if (it == 1) "1 saison" else "$it saisons" }
            Text(
                listOfNotNull(ui.genre, seasonsLabel, ui.year).joinToString(" · "),
                color = t.textMuted, fontSize = t.metaTextSize,
            )
            ui.plot?.let {
                Text(it, color = t.textMuted, fontSize = t.metaTextSize, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = LiveDims.RowGap))
            }
            SeasonTabs(ui, vm::selectSeason)
            Episodes(ui, vm, onPlay, playing, Modifier.weight(1f))
            val note = ui.message
            Text(
                note ?: "OK : lire · appui long : marquer vu / non vu",
                color = t.textMuted, fontSize = t.metaTextSize, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = LiveDims.RowGap),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Cover(title: String, cover: String?) {
    val t = LocalIptvTokens.current
    val context = LocalContext.current
    val shape = remember(t.radiusCard) { RoundedCornerShape(t.radiusCard) }
    val server = LocalCoverServer.current
    val image = remember(cover) { resolveCover(cover, server) }
    var loaded by remember(image) { mutableStateOf(false) }
    Box(Modifier.width(DetailDims.PosterWidth).height(DetailDims.PosterHeight).clip(shape).background(t.surfaceRaised)) {
        if (!loaded) {
            Text(title, color = t.text, fontSize = t.rowTextSize, maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart).padding(LiveDims.Gap))
        }
        if (image != null) {
            AsyncImage(
                model = remember(image) { ImageRequest.Builder(context).data(image).size(DetailDims.PosterRequestW, DetailDims.PosterRequestH).build() },
                contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                onSuccess = { loaded = true }, onError = { loaded = false },
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SeasonTabs(ui: SeriesDetailState, onSelect: (Int) -> Unit) {
    if (ui.seasons.isEmpty()) return
    LazyRow(
        contentPadding = PaddingValues(horizontal = LiveDims.FocusBleed, vertical = LiveDims.FocusBleed),
        horizontalArrangement = Arrangement.spacedBy(LiveDims.RowGap),
    ) {
        items(ui.seasons, key = { it.number }) { s ->
            val selected = s.number == ui.selectedSeason
            SeasonTab(
                buildString {
                    if (s.allWatched) append("✓ ")
                    append(s.name)
                    if (selected && s.total > 0) append(" · ${s.watchedCount} / ${s.total} vus")
                },
                selected,
            ) { onSelect(s.number) }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SeasonTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val t = LocalIptvTokens.current
    var focused by remember { mutableStateOf(false) }
    val shape = remember(t.radiusChip) { RoundedCornerShape(t.radiusChip) }
    Text(
        label, color = t.text, fontSize = t.metaTextSize, maxLines = 1,
        modifier = Modifier
            .focusLook(focused, shape, "saison")
            .clip(shape)
            .background(if (selected) t.surfaceRaised else t.surface)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = LiveDims.ChipH, vertical = LiveDims.RowGap),
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Episodes(ui: SeriesDetailState, vm: SeriesDetailViewModel, onPlay: (PlayEpisodeRequest) -> Unit, playing: Boolean, modifier: Modifier) {
    val state = rememberLazyListState()
    val target = remember { FocusRequester() }
    var focusedOnce by remember { mutableStateOf(false) }
    // Premier focus : l'épisode en cours, sinon le premier pas encore vu (une seule fois, sans voler le focus ensuite).
    val targetIndex = ui.episodes.indexOfFirst { it.status is EpisodeStatus.InProgress }.takeIf { it >= 0 }
        ?: ui.episodes.indexOfFirst { it.status !is EpisodeStatus.Watched }.takeIf { it >= 0 } ?: 0
    // Épisode lu : à la fermeture de la lecture, le focus y retourne (la liste défile jusqu'à lui au besoin).
    var played by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(playing) {
        val id = played
        if (!playing && id != null) {
            vm.playbackClosed(id)
            val index = ui.episodes.indexOfFirst { it.id == id }
            if (index >= 0) {
                state.scrollToItem(index)
                withFrameNanos { }
                runCatching { target.requestFocus() }
            }
        }
    }
    // Nouvelle saison : la liste repart du haut.
    LaunchedEffect(ui.selectedSeason) { if (focusedOnce) state.scrollToItem(0) }
    LaunchedEffect(ui.episodes.isNotEmpty()) {
        if (ui.episodes.isNotEmpty() && !focusedOnce) {
            focusedOnce = true
            state.scrollToItem(targetIndex)
            withFrameNanos { }
            runCatching { target.requestFocus() }
        }
    }
    LazyColumn(
        modifier.fillMaxWidth(), state = state,
        contentPadding = PaddingValues(vertical = LiveDims.FocusBleed),
        verticalArrangement = Arrangement.spacedBy(LiveDims.RowGap),
    ) {
        itemsIndexed(ui.episodes, key = { _, e -> e.id }) { i, e ->
            EpisodeRow(
                e, if (e.id == played || (played == null && i == targetIndex)) Modifier.focusRequester(target) else Modifier,
                onClick = { vm.play(e.id, false)?.let { played = e.id; onPlay(it) } },
                onToggle = { vm.markEpisodeWatched(e.id, e.status !is EpisodeStatus.Watched) },
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun EpisodeRow(e: EpisodeUi, modifier: Modifier, onClick: () -> Unit, onToggle: () -> Unit) {
    val t = LocalIptvTokens.current
    var focused by remember { mutableStateOf(false) }
    val shape = remember(t.radiusCard) { RoundedCornerShape(t.radiusCard) }
    val ink = if (focused) t.background else t.text
    val soft = if (focused) t.surface else t.textMuted
    Row(
        modifier.fillMaxWidth()
            .focusLook(focused, shape, "épisode")
            .clip(shape)
            .background(if (focused) t.text else t.surface)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(onClick = onClick, onLongClick = onToggle)
            .padding(horizontal = LiveDims.Gap, vertical = LiveDims.RowGap),
        horizontalArrangement = Arrangement.spacedBy(LiveDims.Gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${e.number}", color = soft, fontSize = t.metaTextSize, modifier = Modifier.width(36.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LiveDims.RowGap)) {
            Text(
                e.title.ifBlank { "Épisode ${e.number}" },
                color = if (e.status is EpisodeStatus.Watched) soft else ink,
                fontSize = t.rowTextSize, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            (e.status as? EpisodeStatus.InProgress)?.let { s ->
                if (s.durationS > 0) {
                    Box(Modifier.width(DetailDims.BarWidth).height(DetailDims.Bar).clip(RoundedCornerShape(DetailDims.Bar)).background(t.surfaceRaised)) {
                        Box(Modifier.fillMaxWidth((s.positionS.toFloat() / s.durationS).coerceIn(0f, 1f)).height(DetailDims.Bar).background(t.accent))
                    }
                }
            }
        }
        e.durationS?.takeIf { it > 0 }?.let { Text("${(it + 59) / 60} min", color = soft, fontSize = t.metaTextSize) }
        Text(
            when (val s = e.status) {
                EpisodeStatus.Watched -> "✓ Vu"
                is EpisodeStatus.InProgress -> if (s.durationS > 0) "${((s.durationS - s.positionS).coerceAtLeast(0) + 59) / 60} min restantes" else "En cours"
                EpisodeStatus.Upcoming -> ""
            },
            color = ink, fontSize = t.metaTextSize, maxLines = 1, modifier = Modifier.width(150.dp),
        )
    }
}
