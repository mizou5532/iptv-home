package home.iptv.tv.live

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import home.iptv.shared.sync.Profile
import home.iptv.shared.xtream.LiveStream
import home.iptv.tv.theme.LocalIptvTokens

/**
 * Écran du direct : barre du haut, sections à gauche (favoris, playlists, catégories), chaînes à droite.
 * [restoreFocusId] : chaîne à refocaliser (retour du plein écran). Les états de défilement viennent de l'appelant
 * pour survivre à la lecture. Retour : le premier appui ramène le focus sur les sections, le suivant quitte comme
 * d'habitude.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LiveBrowseScreen(
    vm: LiveBrowseViewModel,
    profile: Profile,
    sectionsState: LazyListState,
    streamsState: LazyListState,
    restoreFocusId: String?,
    notice: String?,
    onRestored: () -> Unit,
    onChangeProfile: () -> Unit,
    onPlay: (LiveStream) -> Unit,
) {
    val t = LocalIptvTokens.current
    val sections by vm.sections.collectAsState()
    val selectedKey by vm.selectedKey.collectAsState()
    val streams by vm.streams.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val firstSection = remember { FocusRequester() }
    val sectionsRequester = remember { FocusRequester() }
    val restoreRequester = remember { FocusRequester() }
    var initialFocusDone by remember { mutableStateOf(false) }
    var sectionsFocused by remember { mutableStateOf(false) }
    val selected = sections.firstOrNull { it.key == selectedKey }

    // Focus de départ : première section (sauf si on revient d'une lecture).
    LaunchedEffect(sections.isNotEmpty()) {
        if (sections.isNotEmpty() && restoreFocusId == null && !initialFocusDone) {
            withFrameNanos { }
            runCatching { firstSection.requestFocus() }
            initialFocusDone = true
        }
    }
    // Restauration du focus : une seule fois par retour de lecture (clé = restoreFocusId seul), puis consommée.
    val latestStreams by rememberUpdatedState(streams)
    LaunchedEffect(restoreFocusId) {
        when (val action = restoreAction(restoreFocusId, latestStreams)) {
            RestoreAction.None -> return@LaunchedEffect
            is RestoreAction.Channel -> {
                streamsState.scrollToItem(action.index)
                withFrameNanos { }
                runCatching { restoreRequester.requestFocus() }
            }
            RestoreAction.FirstCategory -> {
                withFrameNanos { }
                runCatching { firstSection.requestFocus() }
            }
        }
        initialFocusDone = true
        onRestored()
    }
    // Une autre section : la liste de chaînes repart du haut (pas au premier affichage ni au retour de lecture).
    var shownKey by remember { mutableStateOf(selectedKey) }
    LaunchedEffect(selectedKey) {
        if (selectedKey != shownKey) {
            shownKey = selectedKey
            streamsState.scrollToItem(0)
        }
    }
    // Retour : d'abord les sections ; une fois dessus, on laisse le système quitter.
    BackHandler(enabled = !sectionsFocused) {
        runCatching { sectionsRequester.requestFocus() }
    }

    Column(Modifier.fillMaxSize().background(t.background)) {
        LiveTopBar(profile, refreshing, onChangeProfile)
        Row(
            Modifier.fillMaxSize().padding(horizontal = LiveDims.PageGutter),
            horizontalArrangement = Arrangement.spacedBy(LiveDims.ColumnGap),
        ) {
            LazyColumn(
                state = sectionsState,
                modifier = Modifier
                    .weight(LiveDims.SectionsWeight)
                    .fillMaxHeight()
                    .onFocusChanged { sectionsFocused = it.hasFocus }
                    .focusRequester(sectionsRequester)
                    .focusRestorer(firstSection),
                contentPadding = PaddingValues(horizontal = LiveDims.FocusBleed, vertical = LiveDims.FocusBleed),
                verticalArrangement = Arrangement.spacedBy(LiveDims.RowGap),
            ) {
                items(sections, key = { it.key }) { section ->
                    SectionRow(
                        title = sectionLabel(section),
                        subtitle = if (section.key == selectedKey) sectionSubtitle(section, streams.size) else null,
                        selected = section.key == selectedKey,
                        modifier = if (section.key == sections.first().key) Modifier.focusRequester(firstSection) else Modifier,
                        onFocus = { vm.selectSection(section.key) },
                        onClick = { vm.selectSection(section.key) },
                    )
                }
            }
            Column(Modifier.weight(1f - LiveDims.SectionsWeight).fillMaxHeight()) {
                Text(selected?.let(::sectionLabel) ?: "", color = t.text, fontSize = t.titleSize)
                val message = when {
                    notice != null -> notice
                    sections.isEmpty() && !refreshing ->
                        "Aucune chaîne pour l'instant — vérifie la connexion internet de la télé."
                    sections.isNotEmpty() && streams.isEmpty() -> emptySectionMessage(selected)
                    else -> null
                }
                if (message != null) {
                    Text(message, color = t.textMuted, fontSize = t.rowTextSize, modifier = Modifier.padding(top = LiveDims.Gap))
                }
                // LazyColumn : seules les lignes visibles sont composées (20 000 chaînes possibles).
                LazyColumn(
                    state = streamsState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = LiveDims.FocusBleed, vertical = LiveDims.FocusBleed),
                    verticalArrangement = Arrangement.spacedBy(LiveDims.RowGap),
                ) {
                    items(streams, key = { it.id }) { s ->
                        ChannelRow(
                            stream = s,
                            modifier = if (s.id == restoreFocusId) Modifier.focusRequester(restoreRequester) else Modifier,
                            onClick = { onPlay(s) },
                        )
                    }
                }
            }
        }
    }
}

/** Mise en avant d'un élément focalisé : grossissement et anneau, sans changer la mise en page. */
@Composable
private fun Modifier.focusLook(focused: Boolean, shape: RoundedCornerShape, label: String): Modifier {
    val t = LocalIptvTokens.current
    val scale by animateFloatAsState(if (focused) LiveDims.FocusScale else 1f, tween(LiveDims.FocusMs), label = label)
    return graphicsLayer { scaleX = scale; scaleY = scale }
        .then(if (focused) Modifier.border(LiveDims.Ring, t.focusRing, shape) else Modifier)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SectionRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalIptvTokens.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(t.radiusCard)
    val bg = when {
        focused -> t.surfaceRaised
        selected -> t.surface
        else -> t.background
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(LiveDims.SectionHeight)
            .focusLook(focused, shape, "section")
            .clip(shape)
            .background(bg)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .clickable(onClick = onClick)
            .padding(end = LiveDims.RowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .padding(horizontal = LiveDims.RowPadding)
                .width(LiveDims.SelectionBar)
                .height(LiveDims.SelectionBarHeight)
                .clip(RoundedCornerShape(LiveDims.SelectionBar))
                .background(if (selected) t.accent else bg),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(title, color = if (focused || selected) t.text else t.textMuted, fontSize = t.rowTextSize, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = t.textMuted, fontSize = t.metaTextSize, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ChannelRow(stream: LiveStream, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalIptvTokens.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(t.radiusCard)
    Row(
        modifier
            .fillMaxWidth()
            .height(LiveDims.ChannelHeight)
            .focusLook(focused, shape, "channel")
            .clip(shape)
            .background(if (focused) t.surfaceRaised else t.surface)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = LiveDims.RowPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LiveDims.Gap),
    ) {
        ChannelLogo(stream)
        Text(channelLabel(stream), color = t.text, fontSize = t.rowTextSize, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Logo (Coil, petite taille) ; la lettre initiale reste affichée tant qu'il n'est pas chargé ou s'il échoue. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ChannelLogo(stream: LiveStream) {
    val t = LocalIptvTokens.current
    val context = LocalContext.current
    val icon = stream.icon?.takeIf { it.isNotBlank() }
    var loaded by remember(icon) { mutableStateOf(false) }
    val request = remember(icon) {
        icon?.let { ImageRequest.Builder(context).data(it).size(LiveDims.LogoRequestPx).build() }
    }
    Box(
        Modifier.size(LiveDims.Logo).clip(RoundedCornerShape(t.radiusChip / 2)).background(t.surfaceRaised),
        contentAlignment = Alignment.Center,
    ) {
        if (!loaded) {
            Text(stream.name.trim().take(1).uppercase(), color = t.textMuted, fontSize = t.rowTextSize)
        }
        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().padding(LiveDims.LogoInset),
                contentScale = ContentScale.Fit,
                onSuccess = { loaded = true },
                onError = { loaded = false },
            )
        }
    }
}
