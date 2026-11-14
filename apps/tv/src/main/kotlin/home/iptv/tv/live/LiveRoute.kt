package home.iptv.tv.live

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import home.iptv.shared.groups.LiveGroup
import home.iptv.shared.sync.Profile
import home.iptv.shared.xtream.LiveStream
import home.iptv.tv.live.epg.EpgRepository
import home.iptv.tv.live.epg.NowNext
import home.iptv.tv.player.ExoPlayerPlayer
import home.iptv.tv.player.PlayerController
import home.iptv.tv.player.PlayerScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Le direct d'un profil. Focus seul : rien ne joue. OK : aperçu ; OK de nouveau : plein écran ; Retour revient à
 * l'aperçu. Les effets de lecture passent tous par [LivePlaybackHost] et le [PlayerController] (un seul flux, une
 * seule instance Media3). Fermer l'écran ou changer de profil libère le flux.
 */
@Composable
fun LiveRoute(
    vm: LiveBrowseViewModel,
    profile: Profile,
    player: PlayerController,
    urlFor: (LiveStream) -> String?,
    epg: EpgRepository?,
    onChangeProfile: () -> Unit,
) {
    val sectionsState = rememberLazyListState()
    val streamsState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val active by player.active.collectAsState()
    val host = remember(vm, player) {
        LivePlaybackHost(
            player = player,
            scope = scope,
            resolve = { row, sourceId ->
                val stream = vm.playbackFor(row, sourceId)
                val url = stream?.let(urlFor)
                when {
                    stream == null -> Resolved.Refused(Refusal.Unavailable)
                    url == null -> Resolved.Refused(Refusal.NoCredentials)
                    else -> Resolved.Ready(stream.id, url)
                }
            },
            rowsOf = vm::sectionRows,
            fallbackKey = { fallbackSectionKey(vm.sections.value) },
            groupOf = vm::groupOf,
        )
    }
    val state by host.state.collectAsState()
    val sections by vm.sections.collectAsState()
    val loaded by vm.loaded.collectAsState()
    val selectedKey by vm.selectedKey.collectAsState()
    val categories by vm.categories.collectAsState()
    val revision by vm.groupsRevision.collectAsState()
    var restoreId by remember { mutableStateOf<String?>(null) }
    val sourcesRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { vm.start() }
    // Quitter le direct ou changer de profil : le flux et son guide sont libérés.
    DisposableEffect(host) { onDispose { host.shutdown() } }
    // Masqué gagne : si la copie en lecture devient invisible, on arrête (sans changer de source).
    LaunchedEffect(revision, state.playingSourceId) { host.syncPlaying() }
    // Le lecteur a pu être libéré par l'activité (onStop) : la navigation repart sans lecture.
    LaunchedEffect(active, state.playingSourceId) {
        if (state.playingSourceId != null && active == null) host.playerReleased()
    }
    // Section d'origine disparue après une modification : favoris sinon première catégorie.
    LaunchedEffect(sections) {
        val origin = state.originSectionKey
        if (origin != null && sections.none { it.key == origin }) host.originGone()
    }
    // Dernière bonne copie : enregistrée seulement une fois l'image prête, jamais au focus ni sur un échec.
    val exo = active as? ExoPlayerPlayer
    LaunchedEffect(exo, state.playingSourceId, exo?.started) {
        val group = state.playingGroupId
        val source = state.playingSourceId
        if (exo != null && exo.started && group != null && source != null) vm.saveLastGoodSource(group, source)
    }
    // Retour du plein écran : la liste se replace sur la chaîne en lecture.
    var wasFullscreen by remember { mutableStateOf(false) }
    val fullscreen = state.mode == LiveMode.Fullscreen
    LaunchedEffect(fullscreen) {
        if (wasFullscreen && !fullscreen) {
            val origin = state.originSectionKey
            if (origin != null && vm.selectedKey.value != origin && sections.any { it.key == origin }) vm.selectSection(origin)
            val key = origin?.takeIf { k -> sections.any { it.key == k } } ?: vm.selectedKey.value
            val shown = withTimeoutOrNull(2_000) { vm.loaded.first { it.key == key } }
            val groupId = state.playingGroupId ?: state.selectedRowId
            restoreId = shown?.rows?.firstOrNull { it.group.id == groupId }?.anchorSourceId
        }
        wasFullscreen = fullscreen
    }

    // Guide : la source en lecture ; avant toute lecture, la chaîne sélectionnée.
    val guideSource = state.playingSourceId
        ?: if (state.playingGroupId == null) loaded.rows.firstOrNull { it.group.id == state.selectedRowId }?.anchorSourceId else null
    LaunchedEffect(epg, guideSource) { epg?.observe(guideSource) }
    val nowNext by remember(epg) { epg?.observe(null) ?: MutableStateFlow(NowNext.EMPTY) }.collectAsState()

    val panelOpen = state.panel != LivePanel.None
    val browsedTitle = loaded.rows.firstOrNull { it.group.id == state.selectedRowId }?.group?.title
    val title = state.playingTitle ?: browsedTitle
    // Rend le focus au bouton « Sources » quand le panneau se ferme dans la liste.
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(panelOpen) {
        if (wasOpen && !panelOpen && !fullscreen && state.playingGroupId != null) runCatching { sourcesRequester.requestFocus() }
        wasOpen = panelOpen
    }
    val closeThenChange = { host.shutdown(); onChangeProfile() }

    Box(Modifier.fillMaxSize()) {
        // Panneau ouvert : le fond ne prend plus le focus (le panneau le garde jusqu'à sa fermeture).
        Box(Modifier.fillMaxSize().focusProperties { canFocus = !panelOpen }) {
            if (fullscreen) {
                PlayerScreen(
                    player = active,
                    title = title ?: "",
                    controlsVisible = state.controlsVisible,
                    onBack = { host.back() },
                    onStep = host::step,
                    onOk = host::fullscreenOk,
                    onHideControls = host::hideControls,
                    onSources = host::openSources,
                    onStop = host::stop,
                )
            } else {
                LiveBrowseScreen(
                    vm, profile, sectionsState, streamsState, restoreId, state.notice,
                    onRestored = { restoreId = null },
                    onChangeProfile = closeThenChange,
                    playingGroupId = state.playingGroupId,
                    onFocusRow = { host.focus(it.group.id) },
                    onOk = host::ok,
                ) { modifier ->
                    LivePreview(
                        player = active,
                        playing = state.playingGroupId != null,
                        title = title,
                        nowNext = nowNext,
                        sourcesRequester = sourcesRequester,
                        onSources = host::openSources,
                        onStop = host::stop,
                        modifier = modifier,
                    )
                }
            }
        }
        if (state.panel == LivePanel.Sources) {
            val group by produceState<LiveGroup?>(null, state.playingSourceId, revision) {
                value = state.playingSourceId?.let { vm.groupOf(it) }
            }
            val names = remember(categories) { categories.associate { it.id to it.name } }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
                SourcesPanel(
                    title = title ?: "",
                    items = sourceItems(group?.sources.orEmpty(), names, state.playingSourceId),
                    onChoose = host::chooseSource,
                    onStop = host::stop,
                    onClose = host::closeSources,
                )
            }
        }
    }
}
