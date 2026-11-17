package home.iptv.tv.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import home.iptv.shared.meta.MediaKind
import home.iptv.shared.meta.MediaRef
import home.iptv.shared.meta.imdbLabel
import home.iptv.shared.meta.tmdbImage
import home.iptv.shared.search.SearchSection
import home.iptv.tv.live.focusLook
import home.iptv.tv.meta.rememberMeta
import home.iptv.tv.series.ImdbBadge
import home.iptv.tv.series.LocalCoverServer
import home.iptv.tv.series.resolveCover
import home.iptv.tv.theme.LocalIptvTokens
import home.iptv.tv.theme.rememberCrispRequest
import kotlinx.coroutines.launch

/** Libellé d'une section de recherche (puce et en-tête). */
fun sectionLabel(s: SearchSection) = when (s) { SearchSection.LIVE -> "Direct"; SearchSection.SERIES -> "Séries"; SearchSection.MOVIES -> "Films" }

/**
 * Recherche (maquette 3 + clavier A, choix du propriétaire, 17 nov.) : à gauche, ce qu'on tape, un clavier QWERTY
 * maison (la télécommande, sans clavier du système), les sections et la liste des résultats ; à droite, l'aperçu du
 * titre choisi (image de fond, logo, note IMDb, résumé TMDB). Un résultat est revérifié au OK, avant toute navigation.
 * [onSection] : passer à une autre section (Films, Séries, Direct) en gardant le texte.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchScreen(
    vm: SearchViewModel, onOpen: (SearchResult) -> Unit, onBack: () -> Unit, voice: (() -> Unit)?, restoreId: String?,
    onSection: ((SearchSection) -> Unit)? = null,
    /** Langues préférées du profil : ordre des étiquettes et version ouverte par OK. */
    languages: home.iptv.shared.versions.LanguagePrefs = home.iptv.shared.versions.LanguagePrefs(),
) {
    val ui by vm.ui.collectAsState()
    val t = LocalIptvTokens.current
    val scope = rememberCoroutineScope()
    val firstKey = remember { FocusRequester() }
    val seeKey = remember { FocusRequester() }
    val empty = remember { FocusRequester() }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    fun requester(id: String) = requesters.getOrPut(id) { FocusRequester() }
    var selected by remember { mutableStateOf(restoreId) }
    var resultsFocused by remember { mutableStateOf(restoreId != null) }
    var refusal by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    val latest by rememberUpdatedState(ui)
    suspend fun focusResult(id: String?) {
        if (latest.results.isEmpty()) { withFrameNanos { }; runCatching { empty.requestFocus() }; return }
        val index = latest.results.indexOfFirst { it.id == id }.coerceAtLeast(0)
        val target = latest.results[index].id
        // Déjà à l'écran : pas de défilement (la liste ne saute pas sous les yeux).
        if (target !in list.layoutInfo.visibleItemsInfo.map { it.key }) list.scrollToItem(index)
        withFrameNanos { }
        runCatching { requester(target).requestFocus() }
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        if (restoreId != null) focusResult(restoreId) else runCatching { firstKey.requestFocus() }
    }
    // [resultsFocused] = l'utilisateur est dans les résultats ; le remplissage ne vole jamais le focus au clavier ; un
    // résultat toujours présent garde le focus, un résultat retiré le cède au premier (ou à l'état vide).
    LaunchedEffect(ui.results.map { it.id }) {
        if (resultsFocused) focusResult(selected?.takeIf { id -> latest.results.any { it.id == id } })
    }
    fun type(text: String) { refusal = null; resultsFocused = false; vm.setQuery(text) }
    fun toResults() { resultsFocused = true; scope.launch { focusResult(selected) } }
    fun toKeyboard() { resultsFocused = false; runCatching { seeKey.requestFocus() } }
    BackHandler(onBack = onBack)
    val open: (SearchResult) -> Unit = { result ->
        selected = result.id
        scope.launch {
            val current = vm.currentResult(result.id)
            if (current == null) refusal = if (ui.section == SearchSection.LIVE) "Chaîne indisponible." else "Titre indisponible."
            else { refusal = null; onOpen(current) }
        }
    }
    val kind = when (ui.section) { SearchSection.SERIES -> MediaKind.SERIES; SearchSection.MOVIES -> MediaKind.MOVIE; SearchSection.LIVE -> null }
    val chosen = ui.results.firstOrNull { it.id == selected } ?: ui.results.firstOrNull()
    val chosenMeta = rememberMeta(chosen?.let { r -> kind?.let { MediaRef(it, r.title) } })

    Box(Modifier.fillMaxSize().background(t.background)) {
        // Fond : l'image du titre choisi, fondue vers la gauche (la colonne reste lisible).
        tmdbImage(chosenMeta?.backdrop, "w1280")?.let { url ->
            AsyncImage(rememberCrispRequest(url, 960.dp, 540.dp, cover = true), contentDescription = null, contentScale = ContentScale.Crop,
                filterQuality = androidx.compose.ui.graphics.FilterQuality.High, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to t.background, 0.44f to t.background.copy(alpha = 0.97f),
                0.72f to t.background.copy(alpha = 0.55f), 1f to t.background.copy(alpha = 0.25f))))
        }
        Row(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Column(Modifier.width(410.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Rechercher dans : ${sectionLabel(ui.section)}", color = t.textMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    listOf(SearchSection.MOVIES, SearchSection.SERIES, SearchSection.LIVE).forEach { s ->
                        SectionChip(sectionLabel(s).let { if (s == SearchSection.LIVE) "Chaînes" else it }, s == ui.section) { if (s != ui.section) onSection?.invoke(s) }
                    }
                }
                QueryBar(ui.query, ui.results.size.takeIf { ui.query.isNotBlank() }, ::type)
                SearchKeyboard(ui.query, ::type, firstKey, seeKey, onSee = ::toResults, voice = voice)
                refusal?.let { Text(it, color = t.text, fontSize = 13.sp) }
                ui.progress?.let { Text("Catalogue en préparation : ${it.done} / ${it.total} catégories, d'autres résultats peuvent apparaître.", color = t.textMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                val upToKeyboard = Modifier.onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionUp) { toKeyboard(); true } else false
                }
                if (ui.results.isEmpty()) {
                    Text(ui.message ?: "Recherche…", color = t.textMuted, fontSize = 14.sp,
                        modifier = Modifier.focusRequester(empty).onFocusChanged { if (it.isFocused) resultsFocused = true }
                            .then(upToKeyboard).focusable().padding(8.dp))
                } else {
                    LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                        itemsIndexed(ui.results, key = { _, it -> it.id }) { index, result ->
                            val focusMod = Modifier.onFocusChanged { if (it.hasFocus) { selected = result.id; resultsFocused = true } }
                                .then(if (index == 0) upToKeyboard else Modifier)
                            if (ui.section == SearchSection.LIVE) {
                                home.iptv.tv.live.ChannelRow(
                                    stream = home.iptv.shared.xtream.LiveStream(result.anchorSourceId ?: result.id, result.title, result.categoryId, result.cover, null, null),
                                    playing = false, onFocus = { selected = result.id; resultsFocused = true }, onClick = { open(result) },
                                    modifier = focusMod.focusRequester(requester(result.id)),
                                )
                            } else {
                                ResultRow(result, kind!!, languages, focusMod.focusRequester(requester(result.id))) { open(result) }
                            }
                        }
                    }
                }
            }
            // Aperçu du titre choisi (rien de focalisable : la télécommande reste dans la colonne de gauche).
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                if (chosen != null) Preview(chosen, kind, chosenMeta, languages)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SectionChip(label: String, on: Boolean, onClick: () -> Unit) {
    val t = LocalIptvTokens.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Text(label, color = if (on || focused) Color(0xFF0B0F17) else t.text, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = Modifier.focusLook(focused, shape, "section $label").clip(shape)
            .background(if (focused) Color.White else if (on) t.accent else Color.White.copy(alpha = 0.08f))
            .onFocusChanged { focused = it.isFocused }.clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp))
}

/** Ce qu'on a tapé (pas de clavier du système) ; [onText] sert aussi aux tests et aux services d'accessibilité. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun QueryBar(query: String, count: Int?, onText: (String) -> Unit) {
    val t = LocalIptvTokens.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.1f))
            .testTag("search-query").semantics { setText { onText(it.text); true } }.padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("⌕", color = t.textMuted, fontSize = 17.sp)
        Text(if (query.isEmpty()) "Titre, série, chaîne…" else "$query▏", color = if (query.isEmpty()) t.textMuted else t.text,
            fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        count?.let { Text(if (it == 1) "1 résultat" else "$it résultats", color = t.textMuted, fontSize = 11.sp) }
    }
}

private enum class KeyPage { LETTERS, DIGITS, ACCENTS }

private val LETTER_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
private val DIGIT_ROWS = listOf("1234567890", "-'&:.,!?()", "+/#@%")
private val ACCENT_ROWS = listOf("éèêëàâäç", "ùûüôöîïœ", "æÿñ")

/**
 * Clavier QWERTY maison (clavier A) : lettres, chiffres (123) et accents (àéç), ⌫, espace, Effacer et « Voir ↓ »
 * (aller aux résultats). La touche choisie devient blanche et grossit, comme le reste de l'app.
 */
@Composable
private fun SearchKeyboard(query: String, onText: (String) -> Unit, firstKey: FocusRequester, seeKey: FocusRequester, onSee: () -> Unit, voice: (() -> Unit)?) {
    var page by remember { mutableStateOf(KeyPage.LETTERS) }
    val rows = when (page) { KeyPage.LETTERS -> LETTER_ROWS; KeyPage.DIGITS -> DIGIT_ROWS; KeyPage.ACCENTS -> ACCENT_ROWS }
    val latest by rememberUpdatedState(query)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xC7101520)).padding(9.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        rows.forEachIndexed { r, row ->
            Row(Modifier.fillMaxWidth().padding(horizontal = if (r == 1 && page == KeyPage.LETTERS) 16.dp else 0.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                row.forEachIndexed { i, c ->
                    KbKey(c.uppercase(), Modifier.weight(1f).then(if (r == 0 && i == 0) Modifier.focusRequester(firstKey) else Modifier)) { onText(latest + c) }
                }
                if (r == rows.lastIndex) KbKey("⌫", Modifier.weight(1.6f), tag = "kb-retour") { onText(latest.dropLast(1)) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            KbKey(if (page == KeyPage.DIGITS) "ABC" else "123", Modifier.weight(1.3f)) { page = if (page == KeyPage.DIGITS) KeyPage.LETTERS else KeyPage.DIGITS }
            KbKey(if (page == KeyPage.ACCENTS) "ABC" else "àéç", Modifier.weight(1.3f)) { page = if (page == KeyPage.ACCENTS) KeyPage.LETTERS else KeyPage.ACCENTS }
            KbKey("espace", Modifier.weight(3f)) { onText("$latest ") }
            KbKey("Effacer", Modifier.weight(1.8f)) { onText("") }
            if (voice != null) KbKey("🎤", Modifier.weight(1f), tag = "kb-parler") { voice() }
            KbKey("Voir ↓", Modifier.weight(1.8f).focusRequester(seeKey), accent = true, tag = "kb-voir") { onSee() }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun KbKey(label: String, modifier: Modifier, accent: Boolean = false, tag: String? = null, onClick: () -> Unit) {
    val t = LocalIptvTokens.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier.height(29.dp).focusLook(focused, shape, "touche $label").clip(shape)
            .background(if (focused) Color.White else if (accent) t.accent else Color.White.copy(alpha = 0.09f))
            .onFocusChanged { focused = it.isFocused }.clickable(onClick = onClick)
            .testTag(tag ?: "kb-$label"),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (focused || accent) Color(0xFF0B0F17) else t.text, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Ligne d'un film ou d'une série : affiche, titre, type et année, note IMDb. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ResultRow(r: SearchResult, kind: MediaKind, languages: home.iptv.shared.versions.LanguagePrefs, modifier: Modifier, onClick: () -> Unit) {
    val t = LocalIptvTokens.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(11.dp)
    val m = rememberMeta(MediaRef(kind, r.title))
    val server = LocalCoverServer.current
    val image = tmdbImage(m?.poster, "w185") ?: remember(r.cover) { resolveCover(r.cover, server) }
    val ink = if (focused) Color(0xFF0B0F17) else t.text
    Row(
        modifier.fillMaxWidth().clip(shape).background(if (focused) Color.White else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(width = 30.dp, height = 44.dp).clip(RoundedCornerShape(6.dp)).background(t.surfaceRaised)) {
            if (image != null) AsyncImage(rememberCrispRequest(image, 30.dp, 44.dp, cover = true), contentDescription = null,
                contentScale = ContentScale.Crop, filterQuality = androidx.compose.ui.graphics.FilterQuality.High, modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f)) {
            Text(m?.title ?: r.title, color = ink, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(listOfNotNull(if (kind == MediaKind.MOVIE) "Film" else "Série", m?.year?.toString() ?: r.versions.firstNotNullOfOrNull { it.year }).joinToString(" · "),
                    color = if (focused) Color(0xFF47425F) else t.textMuted, fontSize = 10.sp, maxLines = 1)
                // Langues des versions réunies (« QC FR EN 4K ») : plus besoin d'ouvrir chaque copie pour savoir.
                if (r.versions.isNotEmpty()) home.iptv.shared.versions.versionBadges(r.versions, languages).take(5).forEach { b ->
                    Text(b, color = if (focused) Color(0xFF0B0F17) else t.text, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(if (focused) Color(0x220B0F17) else t.surfaceRaised).padding(horizontal = 4.dp, vertical = 1.dp))
                }
            }
        }
        imdbLabel(m?.imdbRating)?.let { ImdbBadge(it.removePrefix("IMDb ")) }
    }
}

/** Aperçu à droite : logo du titre (ou son nom), année, type, note IMDb, résumé, et comment l'ouvrir. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Preview(r: SearchResult, kind: MediaKind?, m: home.iptv.shared.meta.MediaMeta?, languages: home.iptv.shared.versions.LanguagePrefs = home.iptv.shared.versions.LanguagePrefs()) {
    val t = LocalIptvTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        val logo = tmdbImage(m?.logo, "w500")
        if (logo != null) {
            AsyncImage(rememberCrispRequest(logo, 360.dp, 74.dp), contentDescription = m?.title ?: r.title, contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart, filterQuality = androidx.compose.ui.graphics.FilterQuality.High, modifier = Modifier.height(74.dp).fillMaxWidth(0.8f))
        } else {
            Text(m?.title ?: r.title, color = t.text, fontSize = 26.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        val type = when (kind) { MediaKind.MOVIE -> "Film"; MediaKind.SERIES -> "Série"; null -> "Chaîne en direct" }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(listOfNotNull(m?.year?.toString(), type).joinToString(" · "), color = t.text.copy(alpha = 0.85f), fontSize = 13.sp)
            imdbLabel(m?.imdbRating)?.let { ImdbBadge(it.removePrefix("IMDb ")) }
        }
        m?.overview?.let { Text(it, color = t.text.copy(alpha = 0.85f), fontSize = 13.sp, lineHeight = 19.sp, maxLines = 6, overflow = TextOverflow.Ellipsis) }
        val best = home.iptv.shared.versions.bestVersion(r.versions, languages)
        if (r.versions.size > 1 && best != null) {
            Text("${r.versions.size} versions : " + r.versions.map { it.tag.label }.distinct().joinToString(", "),
                color = t.text.copy(alpha = 0.85f), fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Text(when {
            kind == null -> "OK : regarder la chaîne"
            best != null && r.versions.size > 1 -> "OK : ouvrir en ${best.tag.label} (autre version dans la fiche)"
            else -> "OK : ouvrir la fiche"
        }, color = t.textMuted, fontSize = 11.sp)
    }
}
