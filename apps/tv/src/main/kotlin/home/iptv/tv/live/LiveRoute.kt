package home.iptv.tv.live

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import home.iptv.shared.groups.LiveGroup
import home.iptv.shared.sync.Profile
import home.iptv.shared.xtream.LiveStream
import home.iptv.tv.live.epg.EpgRepository
import home.iptv.tv.live.epg.NowNext
import home.iptv.tv.player.ExoPlayerPlayer
import home.iptv.tv.player.PlayerController
import home.iptv.tv.player.PlayerScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

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
    topBarTitle: (@Composable () -> Unit)? = null,
    searchOpen: Boolean = false,
    /** Vrai quand la liste est à l'écran (pas le plein écran) : les réglages de l'iPhone y sont suivis de près. */
    onListVisible: (Boolean) -> Unit = {},
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
    val sourcesRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { vm.start() }
    // onStop : arrêt sans condition (une résolution en cours ne doit pas rouvrir le flux en arrière-plan).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(host, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) host.suspend() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // Erreur de lecture : bascule automatique vers une autre copie ; toutes en panne → OK ou Sources pour réessayer.
    LaunchedEffect(active, (active as? ExoPlayerPlayer)?.failed) {
        if ((active as? ExoPlayerPlayer)?.failed == true) host.playFailed()
    }
    // Aucune image après START_TIMEOUT_MS : la copie est considérée en panne (même bascule).
    LaunchedEffect(active, state.playingSourceId) {
        val p = active as? ExoPlayerPlayer ?: return@LaunchedEffect
        val source = state.playingSourceId ?: return@LaunchedEffect
        kotlinx.coroutines.delay(START_TIMEOUT_MS)
        if (!p.started && !p.failed) host.copyFailed(source)
    }
    // Quitter le direct ou changer de profil : le flux et son guide sont libérés.
    DisposableEffect(host) { onDispose { host.shutdown() } }
    // Masqué gagne : si la copie en lecture devient invisible, on arrête (sans changer de source).
    LaunchedEffect(revision, state.playingSourceId) { host.syncPlaying() }
    // Image figée après le démarrage (le lecteur attend des données) : reconnexion de la même copie, puis bascule.
    val watched = active as? ExoPlayerPlayer
    LaunchedEffect(watched, watched?.started, watched?.buffering) {
        val p = watched ?: return@LaunchedEffect
        if (!p.started || !p.buffering || p.failed) return@LaunchedEffect
        val source = host.state.value.playingSourceId ?: return@LaunchedEffect
        kotlinx.coroutines.delay(home.iptv.tv.player.LIVE_STALL_MS)
        if (p.buffering && !p.failed && host.state.value.playingSourceId == source) host.stalled(source)
    }
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
        if (exo != null && exo.started && group != null && source != null) { vm.saveLastGoodSource(group, source); host.playing() }
    }
    // Retour du plein écran : la liste se replace sur la chaîne qui était en lecture (calculé à la composition,
    // pour que l'écran de la liste ne prenne pas d'abord le focus sur la première section).
    val fullscreen = state.mode == LiveMode.Fullscreen
    val listVisible by rememberUpdatedState(onListVisible)
    LaunchedEffect(fullscreen) { listVisible(!fullscreen) }
    DisposableEffect(Unit) { onDispose { listVisible(false) } }
    val returnRow = state.returnFocusRowId
    val reveal by vm.revealTarget.collectAsState()
    val revealNotice by vm.revealNotice.collectAsState()
    val revealRow = reveal?.takeIf { loaded.key == "cat:${it.categoryId}" && selectedKey == loaded.key }?.let { target ->
        loaded.rows.firstOrNull { row -> row.group.sources.any { it.id == target.sourceId } }
    }
    LaunchedEffect(reveal, loaded, sections) {
        val target = reveal ?: return@LaunchedEffect
        if (sections.none { it.key == "cat:${target.categoryId}" } || (loaded.key == "cat:${target.categoryId}" && revealRow == null)) vm.refuseReveal()
    }
    val restoreId = revealRow?.anchorSourceId ?: returnRow?.let { id -> loaded.rows.firstOrNull { it.group.id == id }?.anchorSourceId }
    LaunchedEffect(returnRow, fullscreen) {
        val origin = state.originSectionKey
        if (returnRow != null && !fullscreen && origin != null && vm.selectedKey.value != origin && sections.any { it.key == origin }) {
            vm.selectSection(origin)
        }
        // Chaîne introuvable dans la liste affichée : rien à restaurer, le focus de départ habituel s'applique.
        if (returnRow != null && !fullscreen && restoreId == null) host.focusRestored()
    }

    // Guide : la source en lecture ; avant toute lecture, la chaîne sélectionnée.
    val guideSource = state.playingSourceId
        ?: if (state.playingGroupId == null) loaded.rows.firstOrNull { it.group.id == state.selectedRowId }?.anchorSourceId else null
    LaunchedEffect(epg, guideSource) { epg?.observe(guideSource) }
    val nowNext by remember(epg) { epg?.observe(null) ?: MutableStateFlow(NowNext.EMPTY) }.collectAsState()
    val rowGuide = remember(epg) { epg?.let { home.iptv.tv.live.epg.RowGuide(it::glanceCached, it::glance) } }

    val panelOpen = state.panel != LivePanel.None
    val browsedTitle = loaded.rows.firstOrNull { it.group.id == state.selectedRowId }?.group?.title
    // Nom nettoyé partout (lecteur plein écran, aperçu, panneau Sources) : « US| RDS ᴴᴰ » → « RDS ».
    val title = (state.playingTitle ?: browsedTitle)?.let { home.iptv.shared.text.channelName(it).title }
    // Groupe de la chaîne en lecture : ses copies (nombre sur « Sources », panneau).
    val playingGroup by produceState<LiveGroup?>(null, state.playingSourceId, revision) {
        value = state.playingSourceId?.let { vm.groupOf(it) }
    }
    val sourcesCount = playingGroup?.sources?.size ?: 0
    // Rend le focus au bouton « Sources » quand le panneau se ferme dans la liste.
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(panelOpen) {
        if (wasOpen && !panelOpen && !fullscreen && state.playingGroupId != null) runCatching { sourcesRequester.requestFocus() }
        wasOpen = panelOpen
    }
    val closeThenChange = { host.shutdown(); onChangeProfile() }
    SideEffect {
        home.iptv.tv.diag.Diagnostics.screen = when {
            fullscreen -> "Direct · plein écran"
            state.playingGroupId != null -> "Direct · liste + aperçu"
            else -> "Direct · liste"
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Panneau ouvert : le fond ne prend plus le focus (le panneau le garde jusqu'à sa fermeture).
        Box(Modifier.fillMaxSize().focusBlockedWhen(panelOpen)) {
            if (fullscreen) {
                home.iptv.tv.theme.VideoTheme {
                PlayerScreen(
                    player = active,
                    title = title ?: "",
                    controlsVisible = state.controlsVisible,
                    panelOpen = panelOpen,
                    onBack = { host.back() },
                    onStep = host::step,
                    onOk = host::fullscreenOk,
                    onHideControls = host::hideControls,
                    onSources = host::openSources,
                    onStop = host::stop,
                    nowNext = nowNext,
                    sourcesCount = sourcesCount,
                    connecting = state.trying,
                    allCopiesFailed = state.failed && state.tried.size > 1,
                )
                }
            } else {
                LiveBrowseScreen(
                    vm, profile, sectionsState, streamsState, restoreId, revealNotice ?: state.notice,
                    onRestored = {
                        val target = reveal
                        if (target != null) scope.launch {
                            vm.consumeReveal(target)?.let { row -> host.ok(row, "cat:${target.categoryId}") }
                        } else host.focusRestored()
                    },
                    onChangeProfile = closeThenChange,
                    playingGroupId = state.playingGroupId,
                    onFocusRow = { host.focus(it.group.id) },
                    onOk = host::ok,
                    topBarTitle = topBarTitle,
                    focusSuspended = searchOpen,
                    rowGuide = rowGuide,
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
                        sourcesCount = sourcesCount,
                        connecting = state.trying,
                        allCopiesFailed = state.failed && state.tried.size > 1,
                    )
                }
            }
        }
        if (state.panel == LivePanel.Sources) {
            val group = playingGroup
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
