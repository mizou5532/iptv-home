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
        SportListScreen(vm, profile, topBarTitle, barRequester, onChangeProfile, lastMatch) { key, m, cands ->
            lastMatch = key
            play = SportPlay(m, cands.all)
        }
    } else {
        home.iptv.tv.theme.VideoTheme {
        SportPlayerScreen(current, player, resolve, onMove = { play = current.copy(index = it) },
            onWatched = { vm.learned(current.match, it.groupId) },
            onClose = { player.close(); play = null })
        }
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
    restoreKey: String?,
    onPlay: (key: String, Match, SportCandidates) -> Unit,
) {
    val t = LocalIptvTokens.current
    val ui by vm.ui.collectAsState()
    val chip = remember { FocusRequester() }
    val content = remember { FocusRequester() }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    fun req(key: String) = requesters.getOrPut(key) { FocusRequester() }
    val retry = remember { FocusRequester() }
    // Retour d'une lecture : focus sur la même carte (équipe ou match) ; sinon, premier affichage : la barre (Sport).
    LaunchedEffect(restoreKey, ui.loading) {
        if (ui.loading || restoreKey == null) return@LaunchedEffect
        withFrameNanos { }
        if (restoreKey in requesters) runCatching { req(restoreKey).requestFocus() }
    }
    Column(Modifier.fillMaxSize().background(t.background)) {
        LiveTopBar(profile, ui.loading, chip, content, onChangeProfile, title = topBarTitle)
        LazyColumn(Modifier.fillMaxSize().focusRequester(content), contentPadding = PaddingValues(horizontal = 48.dp, vertical = LiveDims.FocusBleed),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ui.notice?.let { notice -> item("notice") {
                Row(horizontalArrangement = Arrangement.spacedBy(LiveDims.Gap), verticalAlignment = Alignment.CenterVertically) {
                    Text(notice, color = t.textMuted, fontSize = t.metaTextSize)
                    LiveButton("Réessayer", vm::refresh, Modifier.focusRequester(retry), small = true)
                }
            } }
            // Maquette 4 « Mes équipes » (choix du propriétaire, 16 nov.) : une grande carte par équipe favorite.
            item("mine-title") {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Mes équipes", color = t.text, fontSize = t.titleSize, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold)
                    Text(if (ui.loading) "Chargement des matchs…" else "Leur prochain match, en direct dès qu'il commence", color = t.textMuted, fontSize = t.metaTextSize)
                }
            }
            item("mine") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = LiveDims.FocusBleed, horizontal = LiveDims.FocusBleed)) {
                    items(ui.board.teams, key = { "team:${it.team.league}:${it.team.id}" }) { card ->
                        val key = "team:${card.team.league}:${card.team.id}"
                        val m = card.next
                        TeamCardView(card, m?.let { ui.candidates[it.id] }, Modifier.focusRequester(req(key))) {
                            if (m != null) ui.candidates[m.id]?.let { onPlay(key, m, it) }
                        }
                    }
                }
            }
            item("tonight-title") { Text("Aussi ce soir", color = t.text, fontSize = t.titleSize * 0.75f, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
            item("tonight") {
                if (ui.board.tonight.isEmpty() && !ui.loading) Text("Aucun autre match aujourd'hui.", color = t.textMuted, fontSize = t.metaTextSize)
                else LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = LiveDims.FocusBleed, horizontal = LiveDims.FocusBleed)) {
                    items(ui.board.tonight, key = { it.id }) { m ->
                        MatchTile(m, ui.candidates[m.id], Modifier.focusRequester(req(m.id))) { ui.candidates[m.id]?.let { onPlay(m.id, m, it) } }
                    }
                }
            }
        }
    }
}

/** Écusson d'une équipe (logo ESPN) ; sans image, son sigle dans un rond. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Crest(logo: String?, abbr: String, size: androidx.compose.ui.unit.Dp) {
    val t = LocalIptvTokens.current
    var loaded by remember(logo) { mutableStateOf(false) }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (!loaded) Box(Modifier.size(size).clip(androidx.compose.foundation.shape.CircleShape).background(t.surfaceRaised), contentAlignment = Alignment.Center) {
            Text(abbr.take(3).uppercase(), color = t.text, fontSize = t.metaTextSize * 0.8f, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        }
        if (logo != null) coil3.compose.AsyncImage(logo, contentDescription = abbr, modifier = Modifier.fillMaxSize(),
            contentScale = androidx.compose.ui.layout.ContentScale.Fit, onSuccess = { loaded = true }, onError = { loaded = false })
    }
}

private val LiveRed = Color(0xFFFF4D5E)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LiveBadge(text: String) {
    Text("● $text", color = Color.White, fontSize = LocalIptvTokens.current.metaTextSize * 0.8f, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(LiveRed).padding(horizontal = 8.dp, vertical = 3.dp))
}

private fun scoreLine(m: Match): String? =
    if (m.homeScore == null || m.awayScore == null) null else "${m.awayScore} – ${m.homeScore}" + (m.detail?.let { " · $it" } ?: "")

/** Carte d'une équipe favorite : écusson, nom, son match (en direct avec le score, ou le prochain) et les chaînes. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TeamCardView(card: TeamCard, cands: SportCandidates?, modifier: Modifier, onClick: () -> Unit) {
    val t = LocalIptvTokens.current
    var focused by remember { mutableStateOf(false) }
    val shape = remember { RoundedCornerShape(18.dp) }
    val team = card.team
    val color = team.color?.let { runCatching { home.iptv.tv.theme.parseProfileColor(if (it.startsWith("#")) it else "#$it") }.getOrNull() } ?: t.accent
    val m = card.next
    Column(
        modifier.width(232.dp).height(178.dp).focusLook(focused, shape, "équipe").clip(shape)
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(color.copy(alpha = 0.45f), t.surface)))
            .onFocusChanged { focused = it.isFocused }.clickable(onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Crest(team.logo, team.abbr ?: team.name, 46.dp)
            Text(team.name, color = t.text, fontSize = t.rowTextSize * 0.9f, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold, maxLines = 2)
        }
        if (m == null) {
            Text("Pas de match cette semaine", color = t.textMuted, fontSize = t.metaTextSize)
        } else {
            val mineHome = m.involves(team) && (m.homeId == team.id || m.homeAbbr.equals(espnToNhl(team.abbr.orEmpty()), true))
            val opponent = if (mineHome) m.away else m.home
            Text((if (m.state == MatchState.LIVE) "En direct contre " else "Contre ") + opponent, color = t.text, fontSize = t.metaTextSize, maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (m.state == MatchState.LIVE) LiveBadge(scoreLine(m) ?: "EN DIRECT")
                Text(matchWhen(m), color = if (m.state == MatchState.LIVE) t.text else t.textMuted, fontSize = t.metaTextSize * 0.85f, maxLines = 1)
            }
            val names = cands?.proposed?.joinToString(" · ") { it.title }
            Text(if (names.isNullOrBlank()) "OK : parcourir tes chaînes sport" else names, color = t.textMuted, fontSize = t.metaTextSize * 0.8f, maxLines = 1)
        }
    }
}

/** Match de « Aussi ce soir » : compétition et heure (ou en direct), deux écussons, score ou « VS ». */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MatchTile(m: Match, cands: SportCandidates?, modifier: Modifier, onClick: () -> Unit) {
    val t = LocalIptvTokens.current
    var focused by remember { mutableStateOf(false) }
    val shape = remember { RoundedCornerShape(16.dp) }
    Column(
        modifier.width(236.dp).focusLook(focused, shape, "match").clip(shape).background(if (focused) t.surfaceRaised else t.surface)
            .onFocusChanged { focused = it.isFocused }.clickable(onClick = onClick).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(m.league.label, color = t.textMuted, fontSize = t.metaTextSize * 0.8f, maxLines = 1)
            if (m.state == MatchState.LIVE) LiveBadge(m.detail ?: "EN DIRECT")
            else Text(hourFormat.format(Date(m.start.toEpochMilliseconds())), color = t.text, fontSize = t.metaTextSize * 0.85f, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(80.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Crest(m.awayLogo, m.awayAbbr.ifBlank { m.away }, 38.dp)
                Text(m.away, color = t.text, fontSize = t.metaTextSize * 0.75f, maxLines = 1)
            }
            Text(if (m.awayScore != null && m.homeScore != null) "${m.awayScore} – ${m.homeScore}" else "VS",
                color = if (m.homeScore != null) t.text else t.textMuted, fontSize = if (m.homeScore != null) t.titleSize * 0.8f else t.metaTextSize,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Column(Modifier.width(80.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Crest(m.homeLogo, m.homeAbbr.ifBlank { m.home }, 38.dp)
                Text(m.home, color = t.text, fontSize = t.metaTextSize * 0.75f, maxLines = 1)
            }
        }
        val names = cands?.proposed?.joinToString(" · ") { it.title }
        if (!names.isNullOrBlank()) Text(names, color = t.textMuted, fontSize = t.metaTextSize * 0.75f, maxLines = 1)
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
