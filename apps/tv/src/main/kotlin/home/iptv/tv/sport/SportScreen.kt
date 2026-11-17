package home.iptv.tv.sport

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import home.iptv.shared.sport.*
import home.iptv.shared.sync.Profile
import home.iptv.tv.live.*
import home.iptv.tv.player.ExoPlayerPlayer
import home.iptv.tv.player.PlayerController
import home.iptv.tv.player.PlayerSurface
import home.iptv.tv.theme.LocalIptvTokens
import home.iptv.tv.theme.rememberCrispRequest
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

/** Onglet Sport : l'affiche des matchs, ou la lecture plein écran d'un match. Retour revient au même match. */
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
    home.iptv.tv.theme.VideoTheme {
        if (current == null) {
            SportAfficheScreen(vm, profile, topBarTitle, barRequester, onChangeProfile, lastMatch) { m, channels, index ->
                lastMatch = m.id
                play = SportPlay(m, channels, index)
            }
        } else {
            SportPlayerScreen(current, player, resolve, onMove = { play = current.copy(index = it) },
                onWatched = { vm.learned(current.match, it) },
                onWrong = { vm.wrongChannel(current.match, it) },
                onClose = { player.close(); play = null })
        }
    }
}

private fun teamColor(hex: String?, fallback: Color): Color =
    hex?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000 or it) } ?: fallback

/** Nom court sur l'affiche et la bande du bas : nom court de la source, sinon le sigle si le nom est long. */
private fun shortName(name: String, abbr: String, short: String = "") =
    short.ifBlank { if (name.length > 14 && abbr.isNotBlank()) abbr else name }

/**
 * Maquette 2 « Affiche » (choix du propriétaire, 16 nov.) : un match à la fois, en grand, sur un fond aux couleurs
 * des deux équipes ; « Regarder » (OK) ou le choix de la chaîne (appui long, ou « autres chaînes ») ; en bas, la
 * bande de ce qui se joue maintenant ou aujourd'hui (le focus y change l'affiche).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SportAfficheScreen(
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
    val matches = ui.strip
    var featuredId by remember { mutableStateOf(restoreKey) }
    val featured = matches.firstOrNull { it.id == featuredId } ?: matches.firstOrNull()
    var sheet by remember { mutableStateOf<Match?>(null) }
    // Retour d'une lecture : même match et focus sur « Regarder » ; sinon, premier affichage : la barre (Sport).
    LaunchedEffect(restoreKey, ui.loading) {
        if (ui.loading || restoreKey == null) return@LaunchedEffect
        withFrameNanos { }
        runCatching { watch.requestFocus() }
    }
    fun play(m: Match, at: SportChannel? = null) {
        val c = ui.candidates[m.id] ?: return
        onPlay(m, c.all, at?.let { c.all.indexOf(it) }?.takeIf { it >= 0 } ?: 0)
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF0B0F17))) {
        featured?.let { AfficheBackground(it, ui.view.theme, profile.color) }
        Column(Modifier.fillMaxSize().focusBlockedWhen(sheet != null)) {
            LiveTopBar(profile, ui.loading || ui.updating, chip, watch, onChangeProfile, title = topBarTitle, notice = ui.notice)
            ui.notice?.let {
                Row(Modifier.padding(horizontal = LiveDims.PageGutter), verticalAlignment = Alignment.CenterVertically) {
                    LiveButton("Réessayer", vm::refresh, Modifier.focusRequester(retry), small = true)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when {
                    featured != null -> AfficheCenter(featured, ui.candidates[featured.id], watch, hints = ui.view.hints,
                        down = if (matches.size > 1) tile(featured.id) else FocusRequester.Default,
                        onWatch = { play(featured) }, onChoose = { sheet = featured })
                    ui.loading -> Text("Chargement des matchs…", color = Color.White.copy(alpha = 0.8f), fontSize = t.rowTextSize)
                    else -> Text("Aucun match cette semaine pour tes équipes, ni ce soir.", color = Color.White.copy(alpha = 0.8f),
                        fontSize = t.rowTextSize, modifier = Modifier.focusRequester(watch).focusable())
                }
            }
            if (matches.size > 1) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(horizontal = LiveDims.PageGutter, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                ) {
                    matches.forEach { m ->
                        StripTile(m, selected = m.id == featured?.id, Modifier.focusRequester(tile(m.id)).focusProperties {
                            up = watch
                            // Au bout de la bande, le focus reste dessus (il ne saute pas vers la barre du haut).
                            if (m.id == matches.first().id) left = FocusRequester.Cancel
                            if (m.id == matches.last().id) right = FocusRequester.Cancel
                        },
                            onFocus = { featuredId = m.id }, onClick = { play(m) }, onLong = { sheet = m })
                    }
                }
            }
        }
        sheet?.let { m ->
            ChannelSheet(m, ui.candidates[m.id],
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

/** Fond de l'affiche : couleurs des deux équipes (visiteurs à gauche), leurs logos géants très pâles, bords assombris. */
@Composable
private fun AfficheBackground(m: Match, theme: String?, profileColor: String) {
    // Thème (page iPhone) : couleurs des deux équipes (par défaut), fond sombre uni, ou couleur du profil.
    val mine = runCatching { home.iptv.tv.theme.parseProfileColor(profileColor) }.getOrDefault(Color(0xFF3A4A7A))
    val away = when (theme) { "sombre" -> Color(0xFF26324A); "profil" -> mine; else -> teamColor(m.awayColor, Color(0xFF1C2A55)) }
    val home = when (theme) { "sombre" -> Color(0xFF1A2130); "profil" -> lerp(mine, Color.Black, 0.35f); else -> teamColor(m.homeColor, Color(0xFF5A1430)) }
    val a = lerp(away, Color.Black, 0.55f)
    val h = lerp(home, Color.Black, 0.55f)
    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to a, 0.42f to lerp(a, Color.Black, 0.15f), 0.58f to lerp(h, Color.Black, 0.15f), 1f to h))) {
        m.awayLogo?.let { GhostLogo(it, Modifier.align(Alignment.CenterStart).offset(x = (-90).dp, y = 40.dp)) }
        m.homeLogo?.let { GhostLogo(it, Modifier.align(Alignment.CenterEnd).offset(x = 90.dp, y = 40.dp)) }
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(0.35f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f))))
    }
}

@Composable
private fun GhostLogo(url: String, modifier: Modifier) {
    AsyncImage(rememberCrispRequest(url, 420.dp), contentDescription = null, filterQuality = FilterQuality.High,
        contentScale = ContentScale.Fit, modifier = modifier.size(420.dp).alpha(0.10f))
}

/** Centre de l'affiche : état (direct, heure), les deux équipes et le score, puis les boutons. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AfficheCenter(m: Match, cands: SportCandidates?, watch: FocusRequester, hints: Boolean, down: FocusRequester, onWatch: () -> Unit, onChoose: () -> Unit) {
    val t = LocalIptvTokens.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (m.state == MatchState.LIVE) LiveBadge(m.detail ?: "EN DIRECT")
            Text(if (m.state == MatchState.LIVE) m.league.label else matchWhen(m), color = Color.White.copy(alpha = 0.85f),
                fontSize = t.metaTextSize, fontWeight = FontWeight.SemiBold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(44.dp), verticalAlignment = Alignment.CenterVertically) {
            TeamSide(m.awayLogo, shortName(m.away, m.awayAbbr, m.awayShort), m.awayAbbr)
            val scored = m.awayScore != null && m.homeScore != null
            Text(if (scored) "${m.awayScore} : ${m.homeScore}" else "VS", color = if (scored) Color.White else Color.White.copy(alpha = 0.7f),
                fontSize = if (scored) 76.sp else 40.sp, fontWeight = FontWeight.Black, maxLines = 1)
            TeamSide(m.homeLogo, shortName(m.home, m.homeAbbr, m.homeShort), m.homeAbbr)
        }
        val first = cands?.all?.firstOrNull()
        val others = (cands?.likely?.size ?: 0).let { if (first != null && it > 1) it - 1 else 0 }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AfficheButton(if (first != null) "▶  Regarder sur ${first.title}" else "▶  Regarder", primary = true,
                modifier = Modifier.focusRequester(watch).focusProperties { this.down = down }.testTag("Regarder"),
                onClick = onWatch, onLong = onChoose)
            AfficheButton(if (others > 0) "$others autre${if (others > 1) "s" else ""} chaîne${if (others > 1) "s" else ""}" else "Choisir la chaîne",
                primary = false, modifier = Modifier.focusProperties { this.down = down }, onClick = onChoose, onLong = onChoose)
        }
        if (hints) Text("OK : regarder  ·  maintenir OK : choisir la chaîne", color = Color.White.copy(alpha = 0.6f), fontSize = t.metaTextSize * 0.85f)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TeamSide(logo: String?, name: String, abbr: String) {
    Column(Modifier.width(170.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Crest(logo, abbr.ifBlank { name }, 118.dp)
        Text(name, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Bouton en pilule de l'affiche : blanc pour « Regarder », verre sinon ; le focus le grossit et l'entoure. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AfficheButton(label: String, primary: Boolean, modifier: Modifier, onClick: () -> Unit, onLong: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Text(label, color = if (primary || focused) Color(0xFF0B0F17) else Color.White, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1,
        modifier = modifier.focusLook(focused, shape, "affiche $label").clip(shape)
            .background(if (focused) Color.White else if (primary) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.16f))
            .onFocusChanged { focused = it.isFocused }.okPress(onClick, onLong).clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 10.dp))
}

/** Petit match de la bande du bas : deux logos, les équipes, l'heure ou le score. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StripTile(m: Match, selected: Boolean, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit, onLong: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.focusLook(focused, shape, "match ${m.title}").clip(shape)
            .background(if (focused) Color.White.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.38f))
            .then(if (selected && !focused) Modifier.border(1.5.dp, Color.White.copy(alpha = 0.45f), shape) else Modifier)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
            .okPress(onClick, onLong).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Crest(m.awayLogo, m.awayAbbr.ifBlank { m.away }, 30.dp)
        Crest(m.homeLogo, m.homeAbbr.ifBlank { m.home }, 30.dp)
        Column {
            Text("${shortName(m.away, m.awayAbbr, m.awayShort)} – ${shortName(m.home, m.homeAbbr, m.homeShort)}", color = Color.White, fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold, maxLines = 1)
            val sub = when (m.state) {
                MatchState.LIVE -> "● " + listOfNotNull(scoreLine(m)?.substringBefore(" ·"), m.detail).joinToString(" · ").ifBlank { "En direct" }
                MatchState.FINAL -> "Terminé"
                MatchState.UPCOMING -> {
                    val day = java.time.Instant.ofEpochMilli(m.start.toEpochMilliseconds()).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                    if (day == java.time.LocalDate.now()) "Aujourd'hui · " + matchWhen(m, today = true).substringBeforeLast(" · ")
                    else matchWhen(m).substringBeforeLast(" · ")
                }
            }
            Text(sub, color = if (m.state == MatchState.LIVE) LiveRed else Color.White.copy(alpha = 0.7f), fontSize = 11.sp, maxLines = 1)
        }
    }
}

private fun reasonText(r: ChannelReason?): String = when (r) {
    ChannelReason.PREFERRED -> "Ta chaîne préférée"
    ChannelReason.CONFIRMED -> "✓ Confirmé par le guide"
    ChannelReason.EVENT -> "Chaîne de ce match"
    ChannelReason.TEAM -> "Chaîne de l'équipe"
    ChannelReason.VOTED -> "Déjà regardée par la maison"
    ChannelReason.OFFICIAL -> "Annoncée par le diffuseur"
    ChannelReason.LEARNED -> "Déjà regardée pour ce sport"
    ChannelReason.NAME -> "Trouvée par le nom du match"
    ChannelReason.FAVORITE -> "Dans tes favoris"
    ChannelReason.SPORT, null -> "Chaîne sport"
}

private val QUALITY_WORDS = listOf("4K", "UHD", "FHD", "HD", "SD")

/** « Français · HD · Annoncée par le diffuseur ». */
fun channelLine(ch: SportChannel, reason: ChannelReason?): String {
    val words = ch.title.uppercase().split(Regex("[^A-Z0-9+]+"))
    val quality = QUALITY_WORDS.firstOrNull { it in words }
    val lang = when (regionOf(ch)) { Region.QC -> "Français (Québec)"; Region.FR -> "Français (France)"; Region.US -> "Anglais (É.-U.)"; Region.CA -> "Anglais (Canada)"; Region.OTHER -> if (ch.french) "Français" else null }
    return listOfNotNull(lang, quality, reasonText(reason)).joinToString(" · ")
}

/** Sigle de la chaîne dans sa vignette : « TSN 2 », « RDS », « SN 1 ». */
private fun channelBadge(title: String): String {
    val words = title.replace(Regex("^[A-Z]{2}\\s*[:|]\\s*"), "").split(Regex("\\s+")).filter { it.isNotBlank() && it.uppercase() !in QUALITY_WORDS }
    val first = words.firstOrNull().orEmpty().take(4)
    val num = words.drop(1).firstOrNull { it.all(Char::isDigit) && it.length <= 2 }
    return listOfNotNull(first, num).joinToString(" ").uppercase().ifBlank { "TV" }
}

/**
 * Choix de la chaîne (appui long sur un match) : les chaînes qui le diffusent probablement, puis les autres chaînes
 * sport. OK = regarder ; appui long = étoile (chaîne préférée des équipes du match) ; Retour = fermer.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ChannelSheet(m: Match, cands: SportCandidates?, onPlay: (SportChannel) -> Unit, onStar: (SportChannel) -> Unit, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    BackHandler(onBack = onClose)
    LaunchedEffect(m.id) { withFrameNanos { }; runCatching { first.requestFocus() } }
    val likely = cands?.likely.orEmpty()
    val others = cands?.all.orEmpty().filter { c -> likely.none { it.groupId == c.groupId } }
    Box(Modifier.fillMaxSize().background(Color(0xB805030E))) {
        Column(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(400.dp).background(Color(0xFF171230)).backKey(onClose)
                .padding(horizontal = 22.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Crest(m.awayLogo, m.awayAbbr.ifBlank { m.away }, 28.dp)
                Text(scoreLine(m)?.substringBefore(" ·") ?: "VS", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                Crest(m.homeLogo, m.homeAbbr.ifBlank { m.home }, 28.dp)
                if (m.state == MatchState.LIVE) LiveBadge(m.detail ?: "EN DIRECT")
            }
            Text("Choisir la chaîne", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 6.dp))
            Text(
                when (likely.size) { 0 -> "Aucune chaîne confirmée pour ce match : voici tes chaînes sport."; 1 -> "1 chaîne de ta liste diffuse ce match."; else -> "${likely.size} chaînes de ta liste diffusent ce match." },
                color = Color(0xFFA29BC6), fontSize = 13.sp,
            )
            LazyColumn(Modifier.weight(1f).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                items(likely, key = { "l:" + it.groupId }) { ch ->
                    val i = likely.indexOf(ch)
                    ChannelRow(ch, cands?.reasons?.get(ch.groupId), if (i == 0) Modifier.focusRequester(first) else Modifier, { onPlay(ch) }, { onStar(ch) })
                }
                if (others.isNotEmpty()) item("autres") {
                    Text("Autres chaînes sport", color = Color(0xFFA29BC6), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
                }
                items(others, key = { "o:" + it.groupId }) { ch ->
                    ChannelRow(ch, cands?.reasons?.get(ch.groupId), if (likely.isEmpty() && others.indexOf(ch) == 0) Modifier.focusRequester(first) else Modifier,
                        { onPlay(ch) }, { onStar(ch) })
                }
            }
            Text("OK : regarder  ·  maintenir OK : ★ chaîne préférée  ·  Retour : fermer", color = Color(0xFFA29BC6), fontSize = 11.sp, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ChannelRow(ch: SportChannel, reason: ChannelReason?, modifier: Modifier, onClick: () -> Unit, onLong: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    val ink = if (focused) Color(0xFF0B0817) else Color.White
    Row(
        modifier.fillMaxWidth().clip(shape).background(if (focused) Color.White else Color.White.copy(alpha = 0.06f))
            .onFocusChanged { focused = it.isFocused }.okPress(onClick, onLong).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 52.dp, height = 32.dp).clip(RoundedCornerShape(8.dp)).background(if (focused) Color(0xFF0B0817) else Color.White.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center) {
            Text(channelBadge(ch.title), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        }
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(ch.title, color = ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (reason == ChannelReason.PREFERRED) Text("★ préférée", color = Color(0xFFE0A21B), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Text(channelLine(ch, reason), color = if (focused) Color(0xFF47425F) else Color(0xFFA29BC6), fontSize = 12.sp, maxLines = 1)
        }
        if (focused) Text("▶ OK", color = ink, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
    }
}

/** Écusson d'une équipe (logo ESPN, décodé net) ; sans image, son sigle dans un rond. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Crest(logo: String?, abbr: String, size: Dp) {
    val t = LocalIptvTokens.current
    var loaded by remember(logo) { mutableStateOf(false) }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (!loaded) Box(Modifier.size(size).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Text(abbr.take(3).uppercase(), color = Color.White, fontSize = (size.value * 0.28f).coerceIn(8f, 30f).sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        if (logo != null) AsyncImage(rememberCrispRequest(logo, size), contentDescription = abbr, modifier = Modifier.fillMaxSize(), filterQuality = FilterQuality.High,
            contentScale = ContentScale.Fit, onSuccess = { loaded = true }, onError = { loaded = false })
    }
}

private val LiveRed = Color(0xFFFF4D5E)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LiveBadge(text: String) {
    Text("● $text", color = Color.White, fontSize = LocalIptvTokens.current.metaTextSize * 0.8f, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(LiveRed).padding(horizontal = 8.dp, vertical = 3.dp))
}

private fun scoreLine(m: Match): String? =
    if (m.homeScore == null || m.awayScore == null) null else "${m.awayScore} – ${m.homeScore}" + (m.detail?.let { " · $it" } ?: "")

/**
 * Plein écran d'un match : une chaîne à la fois (une seule connexion chez le fournisseur). Droite/Bas = chaîne
 * suivante, Gauche/Haut = précédente, OK = bandeau, Retour = liste. Une chaîne regardée [LEARN_AFTER_MS] est apprise.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SportPlayerScreen(play: SportPlay, player: PlayerController, resolve: suspend (SportChannel) -> String?, onMove: (Int) -> Unit, onWatched: (SportChannel) -> Unit, onWrong: (SportChannel) -> Unit = {}, onClose: () -> Unit) {
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
    var wrongNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(banner) { showBanner = true; delay(5000); showBanner = false }
    LaunchedEffect(wrongNote) { if (wrongNote != null) { delay(4000); wrongNote = null } }
    fun step(delta: Int) {
        if (play.channels.isEmpty()) return
        onMove((play.index + delta + play.channels.size) % play.channels.size)
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black).backKey(onClose).focusRequester(keys)
            // OK : bandeau ; maintenir OK : « Pas le bon match » (la maison écarte cette chaîne pour ces équipes), chaîne suivante.
            .okPress(onClick = { banner++ }, onLong = {
                channel?.let { onWrong(it); wrongNote = "« ${it.title} » écartée pour ce match. Chaîne suivante…"; step(1) }
            })
            .onKeyEvent { e ->
            when {
                e.type != KeyEventType.KeyDown -> false
                e.key == Key.DirectionRight || e.key == Key.DirectionDown -> { step(1); true }
                e.key == Key.DirectionLeft || e.key == Key.DirectionUp -> { step(-1); true }
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
                Text("Maintenir OK : pas le bon match", color = t.textMuted, fontSize = 13.sp, maxLines = 1)
            }
        }
        wrongNote?.let {
            Text(it, color = t.text, fontSize = t.rowTextSize, maxLines = 1,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp).background(t.background.copy(alpha = 0.85f)).padding(horizontal = 18.dp, vertical = 10.dp))
        }
    }
}
