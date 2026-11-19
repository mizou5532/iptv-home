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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
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
                featured != null -> GridHero(featured, watch, belowHero, ui.view.theme, profile.color,
                    now = now, today = isToday(featured),
                    awayStanding = ui.standings[standingKey(featured, home = false)],
                    homeStanding = ui.standings[standingKey(featured, home = true)],
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
                                GridTile(m, gridStatus(m, isToday(m)), selected = m.id == featured.id, ui.view.theme, profile.color,
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

/**
 * Couleurs du face-à-face (visiteurs, locaux) : celles des équipes, sinon le thème choisi (« sombre », « profil »).
 * Une couleur trop claire (blanc, argent, or) est assombrie pour que le texte blanc et le trait du milieu restent lisibles.
 */
internal fun faceOffColors(m: Match, theme: String?, profileColor: String): Pair<Color, Color> {
    val mine = runCatching { home.iptv.tv.theme.parseProfileColor(profileColor) }.getOrDefault(Color(0xFF3A4A7A))
    val away = when (theme) { "sombre" -> Color(0xFF2A3550); "profil" -> mine; else -> teamColor(m.awayColor, Color(0xFF1C2A55)) }
    val home = when (theme) { "sombre" -> Color(0xFF1A2130); "profil" -> lerp(mine, Color.Black, 0.35f); else -> teamColor(m.homeColor, Color(0xFF5A1430)) }
    return readable(away) to readable(home)
}

private fun readable(c: Color): Color = if (c.luminance() > 0.45f) lerp(c, Color.Black, 0.55f) else c

/**
 * Fond « face-à-face » (maquette 1 choisie par le propriétaire, 19 nov.) : coupé en diagonale, la couleur des
 * visiteurs à gauche, celle des locaux à droite, assombri vers le bas ; [slash] = épaisseur du trait blanc du milieu
 * (0 : pas de trait) ; [shade] = voile sombre posé sur tout (pastilles).
 */
private fun Modifier.faceOff(away: Color, home: Color, tilt: Float, slash: Float, shade: Float): Modifier = drawBehind {
    val w = size.width; val h = size.height
    val topX = w * (0.5f + tilt); val botX = w * (0.5f - tilt)
    val left = Path().apply { moveTo(0f, 0f); lineTo(topX, 0f); lineTo(botX, h); lineTo(0f, h); close() }
    val right = Path().apply { moveTo(topX, 0f); lineTo(w, 0f); lineTo(w, h); lineTo(botX, h); close() }
    drawPath(left, Brush.linearGradient(listOf(away, lerp(away, Color.Black, 0.45f)), start = Offset.Zero, end = Offset(topX, h)))
    drawPath(right, Brush.linearGradient(listOf(home, lerp(home, Color.Black, 0.5f)), start = Offset(w, 0f), end = Offset(botX, h)))
    drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.04f + shade), Color.Black.copy(alpha = 0.5f + shade * 0.5f))))
    if (slash > 0f) drawLine(Color.White, Offset(topX + (topX - botX) * 0.1f, -h * 0.1f), Offset(botX - (topX - botX) * 0.1f, h * 1.1f), strokeWidth = slash)
}

/** Logo d'équipe posé sur un léger halo sombre : reste lisible même sur un fond de sa propre couleur. */
@Composable
private fun HaloCrest(logo: String?, abbr: String, size: Dp) {
    Box(Modifier.size(size * 1.3f).background(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.32f), Color.Transparent))), contentAlignment = Alignment.Center) {
        Crest(logo, abbr, size)
    }
}

private val HeroInk = Color(0xFF0A0F1D)
private val NameShadow = Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 3f), 12f)

/**
 * Match du haut en « face-à-face » : grand logo et nom de chaque équipe de part et d'autre d'un trait blanc en
 * diagonale ; au centre, VS (ou le score), l'heure et le compte à rebours, puis « Regarder » et « Autres sources ».
 * Plus de liste de chaînes ici : « Autres sources » (ou maintenir OK) ouvre le choix.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GridHero(
    m: Match, watch: FocusRequester, down: FocusRequester, theme: String?, profileColor: String,
    now: kotlin.time.Instant, today: Boolean, awayStanding: String?, homeStanding: String?, hiddenNote: Boolean,
    onWatch: () -> Unit, onChoose: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    val (away, home) = faceOffColors(m, theme, profileColor)
    val slash = with(LocalDensity.current) { 5.dp.toPx() }
    Row(
        Modifier.padding(horizontal = LiveDims.PageGutter).fillMaxWidth().height(HERO_HEIGHT).clip(shape)
            .faceOff(away, home, tilt = 0.035f, slash = slash, shade = 0f).testTag("face-a-face")
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeroTeam(m.awayLogo, m.awayAbbr.ifBlank { m.away }, shortName(m.away, m.awayAbbr, m.awayShort), awayStanding, Modifier.weight(1f))
        // Plaque sombre au centre : le trait blanc passe derrière, le texte reste lisible.
        Column(Modifier.width(320.dp).clip(RoundedCornerShape(18.dp)).background(HeroInk.copy(alpha = 0.82f)).padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            val scored = m.awayScore != null && m.homeScore != null
            if (m.state == MatchState.LIVE) { LiveBadge("EN DIRECT"); Spacer(Modifier.height(6.dp)) }
            Text(if (scored) "${m.awayScore} – ${m.homeScore}" else "VS", color = Color.White, fontSize = if (scored) 40.sp else 44.sp,
                fontWeight = FontWeight.Black, fontStyle = if (scored) FontStyle.Normal else FontStyle.Italic, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(HeroInk).border(2.5.dp, Color.White, RoundedCornerShape(12.dp))
                    .padding(horizontal = 18.dp, vertical = 0.dp))
            Spacer(Modifier.height(6.dp))
            Text(heroTime(m, today), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp))
            Spacer(Modifier.height(4.dp))
            Text(heroLine(m, now, hiddenNote), color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                AfficheButton("▶  Regarder", primary = true,
                    modifier = Modifier.focusRequester(watch).focusProperties { this.down = down; left = FocusRequester.Cancel }.testTag("Regarder"),
                    onClick = onWatch, onLong = onChoose)
                AfficheButton("Autres sources", primary = false,
                    modifier = Modifier.focusProperties { this.down = down; right = FocusRequester.Cancel }, onClick = onChoose, onLong = onChoose)
            }
        }
        HeroTeam(m.homeLogo, m.homeAbbr.ifBlank { m.home }, shortName(m.home, m.homeAbbr, m.homeShort), homeStanding, Modifier.weight(1f))
    }
}

/** Un côté du face-à-face : grand logo, nom en capitales, classement en petit. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HeroTeam(logo: String?, abbr: String, name: String, standing: String?, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        HaloCrest(logo, abbr, 96.dp)
        Text(name.uppercase(), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, style = TextStyle(shadow = NameShadow))
        if (standing != null) Text(standing, color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Grande heure du centre : « 19 h 00 », « sam. 22 nov. · 19 h 00 », l'avancement du direct ou « Terminé ». */
internal fun heroTime(m: Match, today: Boolean): String = when (m.state) {
    MatchState.LIVE -> m.detail ?: "En direct"
    MatchState.FINAL -> "Terminé"
    MatchState.UPCOMING -> matchWhen(m, today = today).substringBeforeLast(" · ")
}

/** Petite ligne sous l'heure : compte à rebours et compétition ; « sans résultat » le rappelle. */
internal fun heroLine(m: Match, now: kotlin.time.Instant, hiddenNote: Boolean): String = when {
    hiddenNote -> "Score caché : touche Info"
    m.state == MatchState.UPCOMING -> {
        val offset = java.util.TimeZone.getDefault().getOffset(m.start.toEpochMilliseconds()) / 1000
        "${countdownLabel(m.start, now, offset)} · ${m.league.label}"
    }
    else -> m.league.label
}

private val HERO_HEIGHT = 216.dp

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

/**
 * Pastille de la grille en « face-à-face » miniature : coupée en diagonale aux couleurs des deux équipes, un grand
 * logo de chaque côté, au centre l'heure (ou le score et l'avancement du direct). Le nom des équipes reste lu par
 * l'accessibilité et sert aux tests.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GridTile(m: Match, status: String, selected: Boolean, theme: String?, profileColor: String, modifier: Modifier,
                     onFocus: () -> Unit, onClick: () -> Unit, onLong: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val (away, home) = faceOffColors(m, theme, profileColor)
    val label = "${shortName(m.away, m.awayAbbr, m.awayShort)} – ${shortName(m.home, m.homeAbbr, m.homeShort)}"
    Box(
        modifier.height(TILE_HEIGHT).focusLook(focused, shape, "match ${m.title}").clip(shape)
            .faceOff(away, home, tilt = 0.06f, slash = 0f, shade = if (focused) 0.08f else 0.3f)
            .border(if (focused) 0.dp else 1.dp, if (selected && !focused) Color.White.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.06f), shape)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
            .okPress(onClick, onLong).clickable(onClick = onClick)
            .semantics { text = AnnotatedString(label) },
    ) {
        Box(Modifier.align(Alignment.CenterStart).padding(start = 4.dp)) { HaloCrest(m.awayLogo, m.awayAbbr.ifBlank { m.away }, TILE_CREST) }
        Box(Modifier.align(Alignment.CenterEnd).padding(end = 4.dp)) { HaloCrest(m.homeLogo, m.homeAbbr.ifBlank { m.home }, TILE_CREST) }
        val (big, small) = tileLines(m, status)
        val live = m.state == MatchState.LIVE
        Column(Modifier.align(Alignment.Center).widthIn(max = 96.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (small != null && m.state == MatchState.UPCOMING) TileSmall(small, live = false)
            Text(big, color = if (live && big.startsWith("●")) LiveRed else Color.White, fontSize = if (big.length <= 7) 16.sp else 13.sp,
                fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(HeroInk.copy(alpha = 0.85f)).padding(horizontal = 7.dp, vertical = 1.dp))
            if (small != null && m.state != MatchState.UPCOMING) TileSmall(small, live)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TileSmall(text: String, live: Boolean) {
    Text(text, color = if (live) LiveRed else Color.White.copy(alpha = 0.8f), fontSize = 10.sp, fontWeight = FontWeight.Bold,
        maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(shadow = NameShadow))
}

/**
 * Textes du centre d'une pastille : (grand, petit). À venir : l'heure, et le jour au-dessus s'il n'est pas
 * aujourd'hui ; en direct : le score (ou « ● DIRECT » sans résultat) et l'avancement ; terminé : le score et « Terminé ».
 */
internal fun tileLines(m: Match, status: String): Pair<String, String?> {
    val score = if (m.awayScore != null && m.homeScore != null) "${m.awayScore} – ${m.homeScore}" else null
    return when (m.state) {
        MatchState.UPCOMING -> if (" · " in status) status.substringAfterLast(" · ") to status.substringBeforeLast(" · ") else status to null
        MatchState.LIVE -> (score ?: "● DIRECT") to ("● $status").takeIf { status != "En direct" || score != null }
        MatchState.FINAL -> (score ?: "Terminé") to "Terminé".takeIf { score != null }
    }
}

private val TILE_CREST = 40.dp
private val TILE_HEIGHT = 84.dp
