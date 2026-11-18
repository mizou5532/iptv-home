package home.iptv.tv.sport

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import home.iptv.shared.sport.*
import home.iptv.shared.sync.Profile
import home.iptv.tv.live.*
import home.iptv.tv.theme.LocalIptvTokens

/** Un filtre au-dessus de la grille : « Tout · 12 », « En direct · 3 », « LNH · 6 »… */
internal data class GridChip(val key: String, val label: String, val count: Int)

/** Sports des filtres, dans cet ordre (clés de [League.sport]). */
private val GRID_SPORTS = listOf("hockey" to "LNH", "football" to "NFL", "basketball" to "NBA", "soccer" to "Soccer")

/**
 * Filtres de la grille : « Tout », « En direct » s'il y a des matchs en cours, puis chaque sport présent. Aucun filtre
 * quand ils montreraient tous la même chose (un seul sport, sans direct ou seulement du direct).
 */
internal fun gridChips(matches: List<Match>): List<GridChip> {
    val live = matches.count { it.state == MatchState.LIVE }
    val others = listOfNotNull(GridChip("direct", "En direct", live).takeIf { live > 0 }) +
        GRID_SPORTS.mapNotNull { (key, label) -> matches.count { it.league.sport == key }.takeIf { it > 0 }?.let { GridChip(key, label, it) } }
    if (others.none { it.count < matches.size }) return emptyList()
    return listOf(GridChip("tout", "Tout", matches.size)) + others
}

/** Matchs montrés pour le filtre [key] (« tout », « direct » ou un sport). */
internal fun gridFilter(matches: List<Match>, key: String): List<Match> = when (key) {
    "tout" -> matches
    "direct" -> matches.filter { it.state == MatchState.LIVE }
    else -> matches.filter { it.league.sport == key }
}

/** État d'une tuile : « 2e pér. 12:41 », « Terminé », « 19 h 00 » (aujourd'hui) ou « sam. 22 nov. · 19 h 00 ». */
internal fun gridStatus(m: Match, today: Boolean): String = when (m.state) {
    MatchState.LIVE -> m.detail ?: "En direct"
    MatchState.FINAL -> "Terminé"
    MatchState.UPCOMING -> matchWhen(m, today = today).substringBeforeLast(" · ")
}

private val GridBackground = Color(0xFF0B1220)
private val GridCard = Color(0xFF131C2E)
private val GridLine = Color(0xFF1E293B)
private val GridMuted = Color(0xFF94A3B8)
private const val GRID_COLUMNS = 4

/**
 * Disposition « grille » de l'onglet Sport (choix du propriétaire, 18 nov., maquette « 1 · Grille complète ») : le
 * match choisi en haut (équipes, score, boutons, chaînes probables), des filtres, puis tous les matchs de la bande en
 * grille de 4 colonnes qui défile vers le bas : plus rien de caché à droite. Le focus sur une tuile met ce match en
 * haut ; OK = regarder ; maintenir OK = choisir la chaîne. Mêmes données et mêmes gestes que l'affiche.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SportGrilleScreen(
    vm: SportViewModel,
    profile: Profile,
    topBarTitle: @Composable () -> Unit,
    barRequester: FocusRequester,
    onChangeProfile: () -> Unit,
    restoreKey: String?,
    onPlay: (Match, List<SportChannel>, Int) -> Unit,
) {
    val t = LocalIptvTokens.current
    val ui by vm.ui.collectAsState()
    val chip = remember { FocusRequester() }
    val watch = remember { FocusRequester() }
    val retry = remember { FocusRequester() }
    val tiles = remember { mutableMapOf<String, FocusRequester>() }
    fun tile(id: String) = tiles.getOrPut(id) { FocusRequester() }
    val chipRequesters = remember { mutableMapOf<String, FocusRequester>() }
    fun chipFocus(key: String) = chipRequesters.getOrPut(key) { FocusRequester() }
    // Rangées par moment (En direct, Ce soir, Demain, Cette semaine pour mes équipes) ; sans rangées (ancien
    // calcul) : la bande du jour en une seule rangée sans titre.
    val rows = ui.rows.ifEmpty { if (ui.strip.isEmpty()) emptyList() else listOf(SportRow("", ui.strip)) }
    val matches = (ui.strip + rows.flatMap { it.matches }).distinctBy { it.id }
    var featuredId by remember { mutableStateOf(restoreKey) }
    val featured = matches.firstOrNull { it.id == featuredId } ?: ui.strip.firstOrNull() ?: matches.firstOrNull()
    var sheet by remember { mutableStateOf<Match?>(null) }
    val gridMatches = rows.flatMap { it.matches }
    val chips = gridChips(gridMatches)
    var filter by remember { mutableStateOf("tout") }
    // Un filtre qui ne montre plus rien (fin des matchs en direct, mise à jour) revient à « Tout ».
    val activeFilter = filter.takeIf { key -> chips.any { it.key == key } } ?: "tout"
    val sections = rows.map { r -> r.title to gridFilter(r.matches, activeFilter) }.filter { it.second.isNotEmpty() }
        .ifEmpty { rows.map { it.title to it.matches } }
    val shown = sections.flatMap { it.second }
    // Filtre disparu : on revient vraiment à « Tout » (il ne doit pas revenir tout seul quand un match recommence).
    LaunchedEffect(activeFilter) { if (filter != activeFilter) filter = activeFilter }
    // Élément qui a le focus (filtre ou tuile) : s'il disparaît (fin du direct, mise à jour), le focus va au filtre
    // actif, sinon à « Regarder », au lieu de se perdre.
    var focusedKey by remember { mutableStateOf<String?>(null) }
    // Listes à jour, lues au moment d'une perte de focus : un élément qui disparaît ne s'efface pas de [focusedKey].
    val liveChips by rememberUpdatedState(chips.map { "chip:${it.key}" })
    val liveTiles by rememberUpdatedState(shown.map { "tile:${it.id}" })
    LaunchedEffect(focusedKey, chips.map { it.key }, shown.map { it.id }) {
        val k = focusedKey ?: return@LaunchedEffect
        val present = if (k.startsWith("chip:")) chips.any { "chip:${it.key}" == k } else shown.any { "tile:${it.id}" == k }
        if (present) return@LaunchedEffect
        focusedKey = null
        withFrameNanos { }
        runCatching { if (chips.isNotEmpty()) chipFocus(activeFilter).requestFocus() else watch.requestFocus() }
    }
    val today = java.time.LocalDate.now()
    fun isToday(m: Match) = java.time.Instant.ofEpochMilli(m.start.toEpochMilliseconds()).atZone(java.time.ZoneId.systemDefault()).toLocalDate() == today
    val belowHero = when {
        chips.isNotEmpty() -> chipFocus(activeFilter)
        shown.isNotEmpty() -> tile(shown.first().id)
        else -> FocusRequester.Default
    }
    LaunchedEffect(restoreKey, ui.loading) {
        if (ui.loading || restoreKey == null) return@LaunchedEffect
        withFrameNanos { }
        runCatching { watch.requestFocus() }
    }
    fun play(m: Match, at: SportChannel? = null) {
        val c = ui.candidates[m.id] ?: return
        onPlay(m, c.all, at?.let { c.all.indexOf(it) }?.takeIf { it >= 0 } ?: 0)
    }
    val now = rememberNow(vm::clock)
    Box(Modifier.fillMaxSize().background(GridBackground).revealKey(ui, featured, vm)) {
        Column(Modifier.fillMaxSize().focusBlockedWhen(sheet != null)) {
            LiveTopBar(profile, ui.loading || ui.updating, chip, watch, onChangeProfile, title = topBarTitle, notice = ui.notice)
            ui.notice?.let {
                Row(Modifier.padding(horizontal = LiveDims.PageGutter), verticalAlignment = Alignment.CenterVertically) {
                    LiveButton("Réessayer", vm::refresh, Modifier.focusRequester(retry), small = true)
                }
            }
            when {
                featured != null -> GridHero(featured, ui.candidates[featured.id], watch, belowHero, ui.view.theme, profile.color, ui.view.hints,
                    now = now, standings = listOfNotNull(ui.standings[standingKey(featured, home = false)]?.let { shortName(featured.away, featured.awayAbbr, featured.awayShort) + " : " + it },
                        ui.standings[standingKey(featured, home = true)]?.let { shortName(featured.home, featured.homeAbbr, featured.homeShort) + " : " + it }),
                    hiddenNote = scoreHidden(ui, featured),
                    onWatch = { play(featured) }, onChoose = { sheet = featured })
                ui.loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Chargement des matchs…", color = Color.White.copy(alpha = 0.8f), fontSize = t.rowTextSize)
                }
                else -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Aucun match cette semaine pour tes équipes, ni ce soir.", color = Color.White.copy(alpha = 0.8f),
                        fontSize = t.rowTextSize, modifier = Modifier.focusRequester(watch).focusable())
                }
            }
            if (featured != null && chips.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = LiveDims.PageGutter, end = LiveDims.PageGutter, top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    chips.forEach { c ->
                        GridChipButton(c, selected = c.key == activeFilter,
                            Modifier.focusRequester(chipFocus(c.key))
                                .onFocusChanged { f -> if (f.isFocused) focusedKey = "chip:${c.key}" else if (focusedKey == "chip:${c.key}" && "chip:${c.key}" in liveChips) focusedKey = null }
                                .focusProperties {
                                up = watch
                                if (shown.isNotEmpty()) down = tile(shown.first().id)
                                if (c.key == chips.first().key) left = FocusRequester.Cancel
                                if (c.key == chips.last().key) right = FocusRequester.Cancel
                            },
                            onClick = { filter = c.key })
                    }
                }
            }
            if (featured != null && shown.isNotEmpty()) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(horizontal = LiveDims.PageGutter - LiveDims.FocusBleed)
                        .padding(LiveDims.FocusBleed).padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    var lineIndex = 0
                    sections.forEach { (title, list) ->
                      if (title.isNotEmpty()) Text(title.uppercase(), color = GridMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                          modifier = Modifier.padding(top = 4.dp, start = 2.dp))
                      list.chunked(GRID_COLUMNS).forEach { line ->
                        val row = lineIndex++
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            line.forEachIndexed { col, m ->
                                GridTile(m, gridStatus(m, isToday(m)), selected = m.id == featured.id,
                                    Modifier.weight(1f).focusRequester(tile(m.id))
                                        .onFocusChanged { f -> if (f.isFocused) focusedKey = "tile:${m.id}" else if (focusedKey == "tile:${m.id}" && "tile:${m.id}" in liveTiles) focusedKey = null }
                                        .focusProperties {
                                        if (row == 0) up = if (chips.isNotEmpty()) chipFocus(activeFilter) else watch
                                        if (col == 0) left = FocusRequester.Cancel
                                        if (col == line.lastIndex) right = FocusRequester.Cancel
                                    },
                                    onFocus = { featuredId = m.id }, onClick = { play(m) }, onLong = { sheet = m })
                            }
                            // Dernière ligne incomplète : cases vides pour garder la largeur des tuiles.
                            repeat(GRID_COLUMNS - line.size) { Spacer(Modifier.weight(1f)) }
                        }
                      }
                    }
                }
            }
        }
        sheet?.let { held ->
            val m = matches.firstOrNull { it.id == held.id } ?: held
            ChannelSheet(m, ui.candidates[m.id], onReveal = if (scoreHidden(ui, m)) { { vm.reveal(m.id) } } else null,
                onPlay = { ch -> sheet = null; play(m, ch) },
                onStar = { ch -> vm.togglePreferred(m, ch.groupId) },
                onClose = { sheet = null; featuredId = m.id })
        }
        // Panneau fermé : le focus revient sur « Regarder » du même match.
        var wasOpen by remember { mutableStateOf(false) }
        LaunchedEffect(sheet) {
            if (sheet != null) { wasOpen = true; return@LaunchedEffect }
            if (wasOpen) { wasOpen = false; withFrameNanos { }; runCatching { watch.requestFocus() } }
        }
    }
}

/** Fond du match du haut : la couleur des visiteurs à gauche, celle de l'équipe locale à droite, sur un fond sombre. */
private fun heroBrush(m: Match, theme: String?, profileColor: String): Brush {
    val mine = runCatching { home.iptv.tv.theme.parseProfileColor(profileColor) }.getOrDefault(Color(0xFF3A4A7A))
    val away = when (theme) { "sombre" -> Color(0xFF26324A); "profil" -> mine; else -> teamColor(m.awayColor, Color(0xFF1C2A55)) }
    val home = when (theme) { "sombre" -> Color(0xFF1A2130); "profil" -> lerp(mine, Color.Black, 0.35f); else -> teamColor(m.homeColor, Color(0xFF5A1430)) }
    val base = Color(0xFF111A2C)
    return Brush.horizontalGradient(0f to lerp(base, away, 0.42f), 0.45f to lerp(base, away, 0.12f), 0.55f to lerp(base, home, 0.10f), 1f to lerp(base, home, 0.32f))
}

/** Match du haut : état, équipes et score, boutons ; à droite, les chaînes qui le diffusent probablement. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GridHero(
    m: Match, cands: SportCandidates?, watch: FocusRequester, down: FocusRequester, theme: String?, profileColor: String, hints: Boolean,
    now: kotlin.time.Instant, standings: List<String>, hiddenNote: Boolean,
    onWatch: () -> Unit, onChoose: () -> Unit,
) {
    val t = LocalIptvTokens.current
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.padding(horizontal = LiveDims.PageGutter).fillMaxWidth().height(186.dp).clip(shape)
            .background(heroBrush(m, theme, profileColor)).border(1.dp, GridLine, shape)
            .padding(horizontal = 26.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (m.state == MatchState.LIVE) LiveBadge("EN DIRECT")
                Text(heroWhen(m, now), color = GridMuted, fontSize = t.metaTextSize, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Crest(m.awayLogo, m.awayAbbr.ifBlank { m.away }, 52.dp)
                Text(shortName(m.away, m.awayAbbr, m.awayShort), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                val scored = m.awayScore != null && m.homeScore != null
                Text(if (scored) "${m.awayScore} – ${m.homeScore}" else "VS", color = if (scored) Color.White else Color.White.copy(alpha = 0.7f),
                    fontSize = if (scored) 40.sp else 24.sp, fontWeight = FontWeight.Black, maxLines = 1)
                Text(shortName(m.home, m.homeAbbr, m.homeShort), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Crest(m.homeLogo, m.homeAbbr.ifBlank { m.home }, 52.dp)
            }
            // Une seule ligne : l'avancement du direct, sinon le classement des deux équipes ; « sans résultat » le dit.
            val sub = when {
                hiddenNote -> HIDDEN_SCORE_NOTE
                m.state == MatchState.LIVE && m.detail != null -> m.detail
                standings.isNotEmpty() -> standings.joinToString("   ·   ")
                else -> null
            }
            sub?.let { Text(it, color = GridMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            val first = cands?.all?.firstOrNull()
            val others = (cands?.likely?.size ?: 0).let { if (first != null && it > 1) it - 1 else 0 }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                AfficheButton(if (first != null) "▶  Regarder sur ${first.display}" else "▶  Regarder", primary = true,
                    modifier = Modifier.focusRequester(watch).focusProperties { this.down = down; left = FocusRequester.Cancel }.testTag("Regarder"),
                    onClick = onWatch, onLong = onChoose)
                AfficheButton(if (others > 0) "$others autre${if (others > 1) "s" else ""} chaîne${if (others > 1) "s" else ""}" else "Choisir la chaîne",
                    primary = false, modifier = Modifier.focusProperties { this.down = down; right = FocusRequester.Cancel }, onClick = onChoose, onLong = onChoose)
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(GridLine))
        Column(
            Modifier.width(250.dp).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        ) {
            Text("OÙ REGARDER", color = GridMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            val likely = cands?.likely.orEmpty().ifEmpty { cands?.all.orEmpty() }.take(3)
            when {
                cands == null -> Text("Recherche des chaînes…", color = GridMuted, fontSize = 12.sp)
                likely.isEmpty() -> Text("Aucune chaîne trouvée : maintiens OK pour choisir.", color = GridMuted, fontSize = 12.sp, maxLines = 2)
                else -> likely.forEach { ch ->
                    Column {
                        Text(ch.display, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(channelLine(ch, cands?.reasons?.get(ch.groupId)), color = GridMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (hints) Text("OK : regarder  ·  maintenir OK : choisir la chaîne", color = Color.White.copy(alpha = 0.55f), fontSize = 10.sp, maxLines = 1)
        }
    }
}

/** Filtre en pilule : blanc quand il est choisi ; le focus l'entoure. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GridChipButton(c: GridChip, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    val light = selected || focused
    Text("${c.label} · ${c.count}", color = if (light) GridBackground else Color.White, fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold, maxLines = 1,
        modifier = modifier.focusLook(focused, shape, "filtre ${c.label}").clip(shape)
            .background(if (focused) Color.White else if (selected) Color.White.copy(alpha = 0.88f) else GridCard)
            .onFocusChanged { focused = it.isFocused }.clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp))
}

/** Tuile de la grille : bande aux couleurs des deux équipes, compétition et état, puis une ligne par équipe avec le score. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GridTile(m: Match, status: String, selected: Boolean, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit, onLong: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.focusLook(focused, shape, "match ${m.title}").clip(shape)
            .background(if (focused) Color(0xFF22304A) else GridCard)
            .border(1.dp, if (selected && !focused) Color.White.copy(alpha = 0.45f) else GridLine, shape)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
            .okPress(onClick, onLong).clickable(onClick = onClick),
    ) {
        Row(Modifier.fillMaxWidth().height(3.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight().background(teamColor(m.awayColor, GridLine)))
            Box(Modifier.weight(1f).fillMaxHeight().background(teamColor(m.homeColor, GridLine)))
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(m.league.label.uppercase(), color = GridMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text((if (m.state == MatchState.LIVE) "● " else "") + status, color = if (m.state == MatchState.LIVE) LiveRed else Color.White.copy(alpha = 0.8f),
                    fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            GridTeamLine(m.awayLogo, m.awayAbbr.ifBlank { m.away }, shortName(m.away, m.awayAbbr, m.awayShort), m.awayScore)
            GridTeamLine(m.homeLogo, m.homeAbbr.ifBlank { m.home }, shortName(m.home, m.homeAbbr, m.homeShort), m.homeScore)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GridTeamLine(logo: String?, abbr: String, name: String, score: Int?) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Crest(logo, abbr, 20.dp)
        Text(name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (score != null) Text("$score", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    }
}
