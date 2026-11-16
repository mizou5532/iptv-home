package home.iptv.tv.movies

import home.iptv.tv.feature.FeatureHero
import home.iptv.tv.feature.LandscapeCard
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import home.iptv.shared.sync.Profile
import home.iptv.tv.live.*
import home.iptv.tv.series.Poster
import home.iptv.tv.series.RowSection
import home.iptv.tv.theme.LocalIptvTokens

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MovieHomeScreen(vm: MovieHomeViewModel, profile: Profile, onChangeProfile: () -> Unit, topBarTitle: @Composable () -> Unit, onBarFocus: (Boolean) -> Unit, onOpenMovie: (String, String?) -> Unit, onPlayMovie: (PlayMovieRequest) -> Unit, detailOpen: Boolean = false) {
    val t = LocalIptvTokens.current
    val ui by vm.ui.collectAsState()
    val chip = remember { FocusRequester() }; val content = remember { FocusRequester() }
    val requesters = remember { HashMap<String, FocusRequester>() }
    var opened by remember { mutableStateOf<String?>(null) }
    fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }
    LaunchedEffect(Unit) { vm.start() }
    LaunchedEffect(detailOpen) {
        if (!detailOpen && opened != null) {
            withFrameNanos { }
            runCatching { requesters[opened]?.requestFocus() }
            opened = null
        }
    }
    @Composable fun card(c: MovieCard, section: String) {
        val key = "$section:${c.movieId}"
        Poster(c.title, c.cover, if (c.durationS > 0) c.positionS.toFloat()/c.durationS else null, requester = requester(key)) { opened = key; onOpenMovie(c.movieId, c.categoryId) }
    }
    Column(Modifier.fillMaxSize().background(t.background)) {
        LiveTopBar(profile, ui.loading, chip, content, onChangeProfile, Modifier.onFocusChanged { onBarFocus(it.hasFocus) }, title = topBarTitle)
        LazyColumn(Modifier.fillMaxSize().focusRequester(content), contentPadding = PaddingValues(horizontal = 48.dp, vertical = LiveDims.FocusBleed), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            if (ui.features.isNotEmpty()) item("resume") {
                FeatureHero(
                    ui.features, requester("hero"),
                    onPlay = { f -> opened = "hero"; onPlayMovie(PlayMovieRequest(f.itemId, f.categoryId.orEmpty(), startS = f.startS)) },
                    onOpen = { f -> opened = "hero"; onOpenMovie(f.itemId, f.categoryId) },
                )
            } else ui.home.resume?.let { r -> item("resume") {
                home.iptv.tv.series.HeroBlock(
                    if (r.positionS > 0) "REPRENDRE" else "À DÉCOUVRIR", r.title,
                    if (r.positionS > 0 && r.durationS > 0) "${(r.durationS - r.positionS).coerceAtLeast(0) / 60} min restantes" else null,
                    r.cover, if (r.positionS > 0 && r.durationS > 0) r.positionS.toFloat() / r.durationS else null,
                ) {
                    LiveButton(if (r.positionS > 0) "Reprendre" else "Lire", { opened = "hero"; onPlayMovie(PlayMovieRequest(r.movieId, r.categoryId, startS = r.positionS)) }, Modifier.focusRequester(requester("hero")), accent = true)
                    LiveButton("Voir la fiche", { opened = "hero"; onOpenMovie(r.movieId, r.categoryId) })
                }
            } }
            if (ui.home.continueRow.isNotEmpty()) item("continue") { RowSection("Reprendre") { items(ui.home.continueRow, key = { it.movieId }) { c ->
                val key = "continue:${c.movieId}"
                val left = if (c.durationS > 0) "${((c.durationS - c.positionS).coerceAtLeast(0) + 59) / 60} min restantes" else null
                LandscapeCard(c.title, left, ui.backdrops[c.movieId], c.cover, if (c.durationS > 0) c.positionS.toFloat() / c.durationS else null, requester(key)) {
                    opened = key; onPlayMovie(PlayMovieRequest(c.movieId, c.categoryId, startS = c.positionS))
                }
            } } }
            if (ui.home.myList.isNotEmpty()) item("list") { RowSection("Ma liste") { items(ui.home.myList, key = { it.movieId }) { card(it, "list") } } }
            if (ui.home.recent.isNotEmpty()) item("recent") { RowSection("Ajoutés récemment", "Parmi les catégories déjà parcourues") { items(ui.home.recent, key = { it.movieId }) { card(it, "recent") } } }
            items(ui.home.categoryRows, key = { "category:${it.id}" }) { row ->
                DisposableEffect(row.id) { vm.loadCategory(row.id); onDispose { vm.cancelCategory(row.id) } }
                val movies = ui.rowMovies[row.id]
                var placeholderFocused by remember(row.id) { mutableStateOf(false) }
                val first = movies?.firstOrNull()
                LaunchedEffect(first?.movieId) {
                    if (first != null && placeholderFocused) { withFrameNanos { }; runCatching { requester("cat:${row.id}:${first.movieId}").requestFocus() } }
                }
                RowSection(row.name) {
                    if (movies.isNullOrEmpty()) item("placeholder") {
                        LiveButton(when { row.id in ui.failedRows -> "Indisponible · Réessayer"; movies != null -> "Aucun film dans cette catégorie"; else -> "Chargement…" }, { vm.loadCategory(row.id) }, Modifier.width(160.dp).height(232.dp).onFocusChanged { if (it.isFocused) placeholderFocused = true })
                    } else items(movies, key = { it.movieId }) { card(it, "cat:${row.id}") }
                }
            }
            if (ui.loading) item("loading") { LiveButton("Chargement…", {}) }
            ui.message?.let { message -> item("message") { Column { Text(message, color = t.textMuted, fontSize = t.rowTextSize); LiveButton("Réessayer", vm::retry) } } }
        }
    }
}

@Composable
internal fun MovieHomeBackHandler(pending: Boolean, barFocused: Boolean, requestBarFocus: () -> Boolean, exit: () -> Unit) {
    var focusFailed by remember { mutableStateOf(false) }
    val exitNow by rememberUpdatedState(exit)
    androidx.activity.compose.BackHandler(enabled = !pending && !barFocused && !focusFailed) {
        if (!runCatching { requestBarFocus() }.getOrDefault(false)) focusFailed = true
    }
    // Désactiver d'abord notre gestionnaire, puis déléguer au parent : pas de récursion ni Retour avalé.
    LaunchedEffect(focusFailed) {
        if (focusFailed) {
            exitNow()
            focusFailed = false
        }
    }
}
