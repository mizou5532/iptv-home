package home.iptv.tv.live

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import kotlinx.coroutines.delay
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import home.iptv.shared.sync.Profile
import home.iptv.shared.text.CategoryName
import home.iptv.shared.text.categoryName
import home.iptv.shared.text.channelName
import home.iptv.shared.text.separatorTitle
import home.iptv.tv.live.epg.NowNext
import home.iptv.tv.live.epg.RowGuide
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.produceState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import home.iptv.shared.xtream.LiveStream
import home.iptv.tv.theme.LocalIptvTokens

/**
 * Écran du direct : barre du haut, puis trois zones : sections (favoris, playlists, catégories), chaînes et aperçu.
 * Le focus ne lance jamais rien ; OK ([onOk]) lance l'aperçu. La chaîne en lecture ([playingGroupId]) est marquée à
 * part du focus, et reste marquée si on change de catégorie.
 * Les noms sont nettoyés pour l'affichage (pays en pastille, qualité en étiquette, séparateurs du fournisseur en titres
 * de groupe non focalisables) ; [rowGuide] donne le programme en cours sous chaque chaîne visible.
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
    playingGroupId: String?,
    onFocusRow: (GroupRow) -> Unit,
    onOk: (GroupRow, sectionKey: String) -> Unit,
    topBarTitle: (@Composable () -> Unit)? = null,
    /** Vrai pendant qu'un écran (la recherche) est ouvert par-dessus : la liste ne réclame jamais le focus. */
    focusSuspended: Boolean = false,
    rowGuide: RowGuide? = null,
    preview: @Composable (Modifier) -> Unit,
) {
    val t = LocalIptvTokens.current
    val sections by vm.sections.collectAsState()
    val selectedKey by vm.selectedKey.collectAsState()
    val loaded by vm.loaded.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val listShown by vm.listShown.collectAsState()
    val refreshNotice by vm.refreshNotice.collectAsState()
    val firstSection = remember { FocusRequester() }
    val sectionsRequester = remember { FocusRequester() }
    val restoreRequester = remember { FocusRequester() }
    var initialFocusDone by remember { mutableStateOf(false) }
    var sectionsFocused by remember { mutableStateOf(false) }
    val selected = sections.firstOrNull { it.key == selectedKey }
    // Les chaînes d'une autre section ne s'affichent jamais sous le titre de la nouvelle : état de chargement neutre.
    val ready = selected != null && loaded.key == selectedKey
    val streams = if (ready) loaded.streams else emptyList()
    val firstSectionKey = sections.firstOrNull()?.key
    val categoryNames = remember(sections) {
        sections.filterIsInstance<LiveSection.Category>().associate { it.id to categoryName(it.title) }
    }
    // Les séparateurs du fournisseur restent à leur place dans la liste (mêmes positions) mais ne comptent pas.
    val channelCount = remember(streams) { streams.count { !isSeparator(it.name) } }
    val firstChannel = remember(streams) { streams.indexOfFirst { !isSeparator(it.name) } }
    val chipRequester = remember { FocusRequester() }

    // Focus de départ : première section (sauf si on revient d'une lecture).
    LaunchedEffect(sections.isNotEmpty(), focusSuspended) {
        // L'utilisateur est déjà ailleurs (recherche) : pas de focus de départ, ni maintenant ni à la fermeture.
        if (focusSuspended) { initialFocusDone = true; return@LaunchedEffect }
        if (sections.isNotEmpty() && restoreFocusId == null && !initialFocusDone) {
            withFrameNanos { }
            runCatching { firstSection.requestFocus() }
            initialFocusDone = true
        }
    }
    // Pas de section (premier lancement hors ligne) : le focus va à la pastille du profil, seule issue pour l'utilisateur.
    LaunchedEffect(sections.isEmpty()) {
        if (sections.isEmpty() && restoreFocusId == null && !focusSuspended) {
            delay(500)
            runCatching { chipRequester.requestFocus() }
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
        if (selectedKey != shownKey && restoreFocusId == null) {
            shownKey = selectedKey
            streamsState.scrollToItem(0)
        }
    }
    // Retour : d'abord les sections (s'il y en a) ; sinon, ou si le focus n'y va pas, on laisse le système quitter.
    var focusFailed by remember { mutableStateOf(false) }
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    BackHandler(enabled = !focusFailed && backAction(sections.size, sectionsFocused) == BackAction.FocusSections) {
        val moved = runCatching { sectionsRequester.requestFocus() }.getOrDefault(false)
        if (!moved) focusFailed = true
    }
    LaunchedEffect(focusFailed) {
        if (focusFailed) {
            dispatcher?.onBackPressed()
            focusFailed = false
        }
    }

    Column(Modifier.fillMaxSize().background(t.background)) {
        if (topBarTitle != null) LiveTopBar(profile, refreshing, chipRequester, sectionsRequester, onChangeProfile, notice = refreshNotice, title = topBarTitle)
        else LiveTopBar(profile, refreshing, chipRequester, sectionsRequester, onChangeProfile, notice = refreshNotice)
        Row(
            Modifier.fillMaxSize().padding(horizontal = LiveDims.BodyGutter),
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
                itemsIndexed(sections, key = { _, it -> it.key }) { index, section ->
                    SectionRow(
                        title = sectionLabel(section),
                        badge = sectionBadge(section),
                        count = if (ready && section.key == selectedKey) channelCount else null,
                        selected = section.key == selectedKey,
                        modifier = if (index == 0) Modifier.focusRequester(firstSection).focusProperties { up = chipRequester } else Modifier,
                        onFocus = { vm.focusSection(section.key) },
                        onClick = { vm.selectSection(section.key) },
                    )
                }
            }
            Column(Modifier.weight(LiveDims.ChannelsWeight).fillMaxHeight()) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(LiveDims.Gap)) {
                    Text(selected?.let(::sectionLabel) ?: "", color = t.text, fontSize = t.titleSize, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (ready && selected != null && channelCount > 0) {
                        Text(sectionSubtitle(selected, channelCount), color = t.textMuted, fontSize = t.metaTextSize, maxLines = 1, modifier = Modifier.padding(bottom = 4.dp))
                    }
                }
                val message = when {
                    notice != null -> notice
                    sections.isEmpty() && !listShown -> "Chargement\u2026"
                    sections.isEmpty() && !refreshing ->
                        "Aucune chaîne pour l'instant — vérifie la connexion internet de la télé."
                    ready && streams.isEmpty() -> emptySectionMessage(selected)
                    sections.isNotEmpty() && !ready -> "Chargement\u2026"
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
                    itemsIndexed(streams, key = { _, s -> s.id }) { index, s ->
                        val separator = remember(s.name) { separatorTitle(s.name) }
                        if (separator != null) {
                            GroupHeader(separator)
                            return@itemsIndexed
                        }
                        var rowModifier: Modifier = Modifier
                        if (index == firstChannel) rowModifier = rowModifier.focusProperties { up = chipRequester }
                        if (s.id == restoreFocusId) rowModifier = rowModifier.focusRequester(restoreRequester)
                        val groupRow = loaded.rows.getOrNull(index)
                        ChannelRow(
                            stream = s,
                            modifier = rowModifier,
                            playing = groupRow != null && groupRow.group.id == playingGroupId,
                            title = groupRow?.group?.let { group ->
                                if (group.sources.size > 1 || group.id.startsWith("manual:")) group.title else null
                            },
                            category = categoryNames[s.categoryId],
                            guide = rowGuideOf(rowGuide, s.id),
                            onFocus = { groupRow?.let(onFocusRow) },
                            onClick = { groupRow?.let { onOk(it, selectedKey ?: return@let) } },
                        )
                    }
                }
            }
            preview(Modifier.weight(LiveDims.PreviewWeight).fillMaxHeight().padding(vertical = LiveDims.FocusBleed))
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SectionRow(
    title: String,
    badge: String?,
    count: Int?,
    selected: Boolean,
    onClick: () -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalIptvTokens.current
    var focused by remember { mutableStateOf(false) }
    val shape = remember(t.radiusCard) { RoundedCornerShape(t.radiusCard) }
    val bg = when {
        focused -> t.surfaceRaised
        selected -> t.surface
        else -> t.background
    }
    Row(
        modifier
            .fillMaxWidth()
            // Le nom complet de la catégorie : jusqu'à deux lignes plutôt qu'un nom coupé.
            .heightIn(min = LiveDims.SectionHeight)
            .focusLook(focused, shape, "catégorie")
            .clip(shape)
            .background(bg)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = LiveDims.RowPadding, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Pastille : le pays de la catégorie, l'étoile des favoris ; en couleur d'accent pour la section choisie.
        Box(
            Modifier
                .width(LiveDims.BadgeWidth)
                .height(LiveDims.BadgeHeight)
                .clip(RoundedCornerShape(6.dp))
                .background(if (badge == null) Color.Transparent else if (selected) t.accent else t.surfaceRaised),
            contentAlignment = Alignment.Center,
        ) {
            if (badge != null) Text(badge, color = if (selected) t.onAccent else t.textMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Text(
            title, color = if (focused || selected) t.text else t.textMuted, fontSize = t.rowTextSize * 0.9f, maxLines = 2,
            overflow = TextOverflow.Ellipsis, lineHeight = t.rowTextSize * 1.1f, modifier = Modifier.weight(1f),
        )
        if (count != null) Text("$count", color = t.textMuted, fontSize = t.metaTextSize, maxLines = 1)
    }
}

/** Titre de groupe, à la place d'une ligne de séparation du fournisseur (« ##### AT&T ##### ») : jamais focalisable. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GroupHeader(title: String) {
    val t = LocalIptvTokens.current
    if (title.isEmpty()) Spacer(Modifier.height(LiveDims.RowGap))
    else Text(
        title.uppercase(), color = t.textMuted, fontSize = t.metaTextSize * 0.9f, letterSpacing = 1.sp, maxLines = 1,
        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 2.dp),
    )
}

/** Programme en cours d'une ligne : ce qui est déjà connu tout de suite, sinon demandé une fois la ligne posée. */
@Composable
private fun rowGuideOf(source: RowGuide?, id: String): NowNext? {
    if (source == null) return null
    return produceState(source.cached(id), source, id) {
        // Défilement rapide : la ligne repart avant ce délai, aucune demande n'est faite pour elle.
        if (value == null) delay(ROW_GUIDE_SETTLE_MS)
        while (true) {
            value = source.load(id)
            delay(ROW_GUIDE_REFRESH_MS)
        }
    }.value
}

private const val ROW_GUIDE_SETTLE_MS = 600L
private const val ROW_GUIDE_REFRESH_MS = 60_000L

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun ChannelRow(
    stream: LiveStream,
    playing: Boolean,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    /** Catégorie de la chaîne : son nom répété devant celui de la chaîne est retiré. */
    category: CategoryName? = null,
    /** Programme en cours, s'il est connu : deuxième ligne et barre d'avancement. */
    guide: NowNext? = null,
) {
    val t = LocalIptvTokens.current
    var focused by remember { mutableStateOf(false) }
    val shape = remember(t.radiusCard) { RoundedCornerShape(t.radiusCard) }
    val label = remember(stream, title, category) { channelName(title ?: stream.name, category).title }
    val quality = remember(stream.name) { channelQuality(stream) }
    val program = guide?.current
    Row(
        modifier
            .fillMaxWidth()
            .height(LiveDims.ChannelHeight)
            .focusLook(focused, shape, "chaîne")
            .clip(shape)
            .background(if (focused) t.surfaceRaised else t.surface)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .clickable(onClick = onClick)
            .padding(end = LiveDims.RowPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LiveDims.Gap),
    ) {
        // Chaîne en lecture : barre d'accent et mention, distinctes de l'anneau et du fond du focus.
        Box(Modifier.padding(start = LiveDims.RowGap).width(LiveDims.PlayingBar).height(LiveDims.ChannelHeight / 2).clip(RoundedCornerShape(LiveDims.PlayingBar)).background(if (playing) t.accent else Color.Transparent))
        ChannelLogo(stream, label)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, color = t.text, fontSize = t.rowTextSize, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (playing) Text("En lecture", color = t.accent, fontSize = t.metaTextSize, maxLines = 1)
            }
            if (program != null) {
                Text(program.title, color = t.textMuted, fontSize = t.metaTextSize, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val progress = guide.progress
                if (progress != null) {
                    Box(Modifier.fillMaxWidth().padding(top = 2.dp).height(LiveDims.RowProgressHeight).clip(RoundedCornerShape(LiveDims.RowProgressHeight)).background(t.surfaceRaised)) {
                        Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(t.accent))
                    }
                }
            }
        }
        if (quality != null) {
            Text(
                quality, color = t.textMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                modifier = Modifier.border(1.dp, t.textMuted.copy(alpha = 0.5f), RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

/** Logo (Coil, petite taille) ; la lettre initiale reste affichée tant qu'il n'est pas chargé ou s'il échoue. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ChannelLogo(stream: LiveStream, label: String) {
    val t = LocalIptvTokens.current
    val context = LocalContext.current
    val icon = stream.icon?.takeIf { it.isNotBlank() }
    val initial = remember(label) { label.trim().take(1).uppercase() }
    var loaded by remember(icon) { mutableStateOf(false) }
    val request = remember(icon) {
        icon?.let { ImageRequest.Builder(context).data(it).size(LiveDims.LogoRequestPx).build() }
    }
    Box(
        Modifier.size(LiveDims.Logo).clip(RoundedCornerShape(t.radiusChip / 2)).background(t.surfaceRaised),
        contentAlignment = Alignment.Center,
    ) {
        if (!loaded) {
            Text(initial, color = t.textMuted, fontSize = t.rowTextSize)
        }
        if (request != null) {
            AsyncImage(
                filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
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
