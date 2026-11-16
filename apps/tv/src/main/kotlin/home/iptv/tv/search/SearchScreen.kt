package home.iptv.tv.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import home.iptv.shared.search.SearchSection
import home.iptv.tv.live.focusLook
import home.iptv.tv.series.Poster
import home.iptv.tv.theme.LocalIptvTokens
import kotlinx.coroutines.launch

/** Recherche locale ; un résultat est revérifié au moment du OK, avant toute navigation. */
@Composable
fun SearchScreen(vm: SearchViewModel, onOpen: (SearchResult) -> Unit, onBack: () -> Unit, voice: (() -> Unit)?, restoreId: String?) {
    val ui by vm.ui.collectAsState()
    val t = LocalIptvTokens.current
    val scope = rememberCoroutineScope()
    val query = remember { FocusRequester() }
    val empty = remember { FocusRequester() }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    fun requester(id: String) = requesters.getOrPut(id) { FocusRequester() }
    var selected by remember { mutableStateOf(restoreId) }
    var resultsFocused by remember { mutableStateOf(restoreId != null) }
    var refusal by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    val grid = rememberLazyGridState()
    val latest by rememberUpdatedState(ui)
    suspend fun focusResult(id: String?) {
        val index = latest.results.indexOfFirst { it.id == id }.coerceAtLeast(0)
        if (latest.results.isEmpty()) { empty.requestFocus(); return }
        if (latest.section == SearchSection.LIVE) list.scrollToItem(index) else grid.scrollToItem(index)
        withFrameNanos { }
        runCatching { requester(latest.results[index].id).requestFocus() }
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        if (restoreId != null) focusResult(restoreId) else query.requestFocus()
    }
    // Le remplissage ne vole jamais le focus au champ. Une affiche retirée cède le focus à la première.
    LaunchedEffect(ui.results.map { it.id }) {
        if (resultsFocused) focusResult(selected)
    }
    BackHandler(onBack = onBack)
    val open: (SearchResult) -> Unit = { result ->
        selected = result.id
        scope.launch {
            val current = vm.currentResult(result.id)
            if (current == null) refusal = if (ui.section == SearchSection.LIVE) "Chaîne indisponible." else "Titre indisponible."
            else { refusal = null; onOpen(current) }
        }
    }
    Column(Modifier.fillMaxSize().background(t.background).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val section = when (ui.section) { SearchSection.LIVE -> "Direct"; SearchSection.SERIES -> "Séries"; SearchSection.MOVIES -> "Films" }
        Text("Rechercher dans : $section", color = t.text, fontSize = t.titleSize)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BasicTextField(ui.query, { refusal = null; vm.setQuery(it) },
                textStyle = TextStyle(color = t.text, fontSize = t.rowTextSize), singleLine = true,
                modifier = Modifier.weight(1f).testTag("search-query").focusRequester(query)
                    .onFocusChanged { if (it.isFocused) resultsFocused = false }
                    .onPreviewKeyEvent {
                        if (it.key == Key.DirectionDown && it.type == KeyEventType.KeyDown) {
                            resultsFocused = true; scope.launch { focusResult(selected) }; true
                        } else false
                    }.background(t.surfaceRaised).padding(16.dp))
            if (voice != null) Text("Parler", color = t.text, modifier = Modifier.clickable(onClick = voice).padding(16.dp))
        }
        refusal?.let { Text(it, color = t.text) }
        ui.progress?.let { Text("Catalogue en préparation : ${it.done} catégories sur ${it.total}. D'autres résultats peuvent apparaître.", color = t.textMuted) }
        Text("${ui.results.size} résultats", color = t.textMuted)
        val resultModifier: (SearchResult, Int) -> Modifier = { result, index ->
            Modifier.onFocusChanged { if (it.hasFocus) { selected = result.id; resultsFocused = true } }
                .onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionUp && index < (if (ui.section == SearchSection.LIVE) 1 else 5)) {
                        query.requestFocus(); true
                    } else false
                }
        }
        if (ui.results.isEmpty()) {
            Text(ui.message ?: "Recherche…", color = t.textMuted, fontSize = t.rowTextSize,
                modifier = Modifier.focusRequester(empty).onFocusChanged { if (it.isFocused) resultsFocused = true }
                    .onPreviewKeyEvent { if (it.key == Key.DirectionUp && it.type == KeyEventType.KeyDown) { query.requestFocus(); true } else false }
                    .focusable().padding(16.dp))
        } else if (ui.section == SearchSection.LIVE) {
            LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(ui.results, key = { _, it -> it.id }) { index, result ->
                    var focused by remember { mutableStateOf(false) }
                    Row(resultModifier(result, index).fillMaxWidth().focusRequester(requester(result.id))
                        .focusLook(focused, androidx.compose.foundation.shape.RoundedCornerShape(t.radiusCard), "résultat")
                        .onFocusChanged { focused = it.isFocused }.background(t.surface).clickable { open(result) }.padding(16.dp)) {
                        Text(result.title, color = t.text, fontSize = t.rowTextSize)
                    }
                }
            }
        } else {
            LazyVerticalGrid(columns = GridCells.Fixed(5), state = grid, verticalArrangement = Arrangement.spacedBy(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(ui.results, key = { _, it -> it.id }) { index, result ->
                    Box(resultModifier(result, index)) {
                        Poster(result.title, result.cover, null, requester = requester(result.id)) { open(result) }
                    }
                }
            }
        }
    }
}
