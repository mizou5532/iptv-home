package home.iptv.tv.sport

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import home.iptv.shared.sport.*
import home.iptv.shared.sync.Profile
import home.iptv.tv.live.*
import home.iptv.tv.player.ExoPlayerPlayer
import home.iptv.tv.player.PlayerController
import home.iptv.tv.player.PlayerSurface
import home.iptv.tv.theme.LocalIptvTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dayFormat = SimpleDateFormat("EEE d MMM", Locale.CANADA_FRENCH)
private val hourFormat = SimpleDateFormat("H 'h' mm", Locale.CANADA_FRENCH)

/** « EN COURS · LNH » ou « sam. 22 nov. · 19 h 00 · Liga » (fuseau de la télé). */
fun matchWhen(m: Match, today: Boolean = false): String {
    if (m.state == MatchState.LIVE) return "EN COURS · ${m.league.label}"
    val date = Date(m.start.toEpochMilliseconds())
    return (if (today) "" else dayFormat.format(date) + " · ") + hourFormat.format(date) + " · " + m.league.label
}

/** Lecture en cours depuis un match : la liste de chaînes à parcourir et la position. */
data class SportPlay(val match: Match, val channels: List<SportChannel>, val index: Int = 0)

/** Onglet Sport : la liste des matchs, ou la lecture plein écran d'un match. Retour revient au même match. */
@Composable
fun SportTab(
    vm: SportViewModel,
    profile: Profile,
    player: PlayerController,
    resolve: suspend (SportChannel) -> String?,
    topBarTitle: @Composable () -> Unit,
    barRequester: FocusRequester,
    onChangeProfile: () -> Unit,
) {
    var play by remember { mutableStateOf<SportPlay?>(null) }
    var lastMatch by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.start() }
    // Rafraîchi toutes les 30 min tant que l'onglet est affiché.
    LaunchedEffect(Unit) { while (true) { delay(SPORT_REFRESH_MS); vm.refresh() } }
    DisposableEffect(Unit) { onDispose { player.close() } }
    val current = play
    if (current == null) {
        SportListScreen(vm, profile, topBarTitle, barRequester, onChangeProfile, lastMatch) { m, cands ->
            lastMatch = m.id
            play = SportPlay(m, cands.all)
        }
    } else {
        SportPlayerScreen(current, player, resolve, onMove = { play = current.copy(index = it) },
            onWatched = { vm.learned(current.match, it.groupId) },
            onClose = { player.close(); play = null })
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SportListScreen(
    vm: SportViewModel,
    profile: Profile,
    topBarTitle: @Composable () -> Unit,
    barRequester: FocusRequester,
    onChangeProfile: () -> Unit,
    restoreId: String?,
    onPlay: (Match, SportCandidates) -> Unit,
) {
    val t = LocalIptvTokens.current
    val ui by vm.ui.collectAsState()
    val chip = remember { FocusRequester() }
    val content = remember { FocusRequester() }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    fun req(id: String) = requesters.getOrPut(id) { FocusRequester() }
    val retry = remember { FocusRequester() }
    // Retour d'une lecture : focus sur le même match ; sinon, premier affichage : la barre (Sport).
    LaunchedEffect(restoreId, ui.loading) {
        if (ui.loading) return@LaunchedEffect
        withFrameNanos { }
        val target = restoreId?.takeIf { id -> (ui.board.mine + ui.board.tonight).any { it.id == id } }
        if (target != null) runCatching { req(target).requestFocus() }
    }
    Column(Modifier.fillMaxSize().background(t.background)) {
        LiveTopBar(profile, ui.loading, chip, content, onChangeProfile, title = topBarTitle)
        LazyColumn(Modifier.fillMaxSize().focusRequester(content), contentPadding = PaddingValues(horizontal = 48.dp, vertical = LiveDims.FocusBleed),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            ui.notice?.let { notice -> item("notice") {
                Row(horizontalArrangement = Arrangement.spacedBy(LiveDims.Gap), verticalAlignment = Alignment.CenterVertically) {
                    Text(notice, color = t.textMuted, fontSize = t.rowTextSize)
                    LiveButton("Réessayer", vm::refresh, Modifier.focusRequester(retry))
                }
            } }
            if (ui.loading) item("loading") { LiveButton("Chargement des matchs…", {}) }
            item("mine-title") { Text("MES ÉQUIPES", color = t.accent, fontSize = t.metaTextSize) }
            item("mine") {
                if (ui.board.mine.isEmpty() && !ui.loading) Text("Aucun match des Canadiens ou du Barça cette semaine.", color = t.textMuted, fontSize = t.rowTextSize)
                else LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = LiveDims.FocusBleed)) {
                    items(ui.board.mine, key = { it.id }) { m ->
                        MatchCard(m, ui.candidates[m.id], false, Modifier.width(360.dp).focusRequester(req(m.id))) { ui.candidates[m.id]?.let { onPlay(m, it) } }
                    }
                }
            }
            item("tonight-title") { Text("CE SOIR", color = t.accent, fontSize = t.metaTextSize) }
            if (ui.board.tonight.isEmpty() && !ui.loading) item("tonight-empty") { Text("Aucun autre match aujourd'hui.", color = t.textMuted, fontSize = t.rowTextSize) }
            items(ui.board.tonight, key = { it.id }) { m ->
                MatchCard(m, ui.candidates[m.id], true, Modifier.fillMaxWidth().focusRequester(req(m.id))) { ui.candidates[m.id]?.let { onPlay(m, it) } }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MatchCard(m: Match, cands: SportCandidates?, today: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val t = LocalIptvTokens.current
    var focused by remember { mutableStateOf(false) }
    val shape = remember { RoundedCornerShape(12.dp) }
    Column(
        modifier.focusLook(focused, shape, "match").clip(shape).background(if (focused) t.surfaceRaised else t.surface)
            .onFocusChanged { focused = it.isFocused }.clickable(onClick = onClick).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(matchWhen(m, today), color = if (m.state == MatchState.LIVE) t.accent else t.textMuted, fontSize = t.metaTextSize)
        Text(m.title, color = t.text, fontSize = t.rowTextSize, maxLines = 1)
        val names = cands?.proposed?.joinToString(" · ") { it.title }
        Text(if (names.isNullOrBlank()) "Aucune chaîne trouvée : OK parcourt tes chaînes sport." else names, color = t.textMuted, fontSize = t.metaTextSize, maxLines = 1)
    }
}

/**
 * Plein écran d'un match : une chaîne à la fois (une seule connexion chez le fournisseur). Droite/Bas = chaîne
 * suivante, Gauche/Haut = précédente, OK = bandeau, Retour = liste. Une chaîne regardée [LEARN_AFTER_MS] est apprise.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SportPlayerScreen(play: SportPlay, player: PlayerController, resolve: suspend (SportChannel) -> String?, onMove: (Int) -> Unit, onWatched: (SportChannel) -> Unit, onClose: () -> Unit) {
    val t = LocalIptvTokens.current
    val active by player.active.collectAsState()
    val keys = remember { FocusRequester() }
    var banner by remember { mutableStateOf(0) }
    var unavailable by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val channel = play.channels.getOrNull(play.index)
    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { keys.requestFocus() } }
    // Une chaîne à la fois : l'ancien flux est fermé avant d'ouvrir le suivant (PlayerController.open).
    LaunchedEffect(play.index, channel?.groupId) {
        banner++
        unavailable = false
        val url = channel?.let { resolve(it) }
        if (url == null) { player.close(); unavailable = true } else player.open(url)
    }
    LaunchedEffect(play.index, active) {
        val ch = channel ?: return@LaunchedEffect
        if (active == null) return@LaunchedEffect
        delay(LEARN_AFTER_MS)
        if ((active as? ExoPlayerPlayer)?.failed != true) onWatched(ch)
    }
    var showBanner by remember { mutableStateOf(true) }
    LaunchedEffect(banner) { showBanner = true; delay(5000); showBanner = false }
    fun step(delta: Int) {
        if (play.channels.isEmpty()) return
        onMove((play.index + delta + play.channels.size) % play.channels.size)
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black).backKey(onClose).focusRequester(keys).onKeyEvent { e ->
            when {
                e.type != KeyEventType.KeyDown -> false
                e.key == Key.DirectionRight || e.key == Key.DirectionDown -> { step(1); true }
                e.key == Key.DirectionLeft || e.key == Key.DirectionUp -> { step(-1); true }
                e.key == Key.DirectionCenter || e.key == Key.Enter -> { scope.launch { banner++ }; true }
                else -> false
            }
        }.focusable(),
    ) {
        (active as? ExoPlayerPlayer)?.let { PlayerSurface(it, Modifier.fillMaxSize()) }
        val exo = active as? ExoPlayerPlayer
        val status = when {
            play.channels.isEmpty() -> "Aucune chaîne sport visible. Vérifie l'organisation sur l'iPhone."
            unavailable -> "Chaîne indisponible : ▶ pour la suivante."
            exo?.failed == true -> "Impossible de lire cette chaîne : ▶ pour la suivante."
            exo != null && !exo.started -> "Connexion…"
            else -> null
        }
        status?.let { Text(it, color = t.text, fontSize = t.rowTextSize, modifier = Modifier.align(Alignment.Center)) }
        if (showBanner && channel != null) {
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(t.background.copy(alpha = 0.85f)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(play.match.title, color = t.text, fontSize = t.titleSize, maxLines = 1)
                Text("Chaîne ${play.index + 1} sur ${play.channels.size} : ${channel.title}   ◀ ▶ autre chaîne", color = t.textMuted, fontSize = t.rowTextSize, maxLines = 1)
            }
        }
    }
}
