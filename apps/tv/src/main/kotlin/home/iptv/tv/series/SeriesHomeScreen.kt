package home.iptv.tv.series

import home.iptv.tv.feature.FeatureHero
import home.iptv.tv.feature.LandscapeCard
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import home.iptv.shared.sync.Profile
import home.iptv.tv.live.LiveButton
import home.iptv.tv.live.LiveDims
import home.iptv.tv.live.LiveTopBar
import home.iptv.tv.live.focusLook
import home.iptv.tv.theme.LocalIptvTokens
import kotlinx.coroutines.launch

/** Demande de lecture d'un épisode (la lecture elle-même est faite par la tâche suivante). */
data class PlayEpisodeRequest(
    val seriesId: String,
    val categoryId: String?,
    val episodeId: String,
    val season: Int,
    val number: Int,
    val startS: Int,
    val restart: Boolean,
)

private object SeriesDims {
    // Style « Verre » : affiches un peu plus petites, plus d'air et plus de titres à l'écran.
    val PosterWidth = 140.dp
    val PosterHeight = 204.dp
    const val PosterRequestW = 240
    const val PosterRequestH = 348
    val ProgressBar = 5.dp
    val BlockPoster = 150.dp
    val BlockPosterHeight = 218.dp
    val BlockPadding = 24.dp
    val BlockGap = 32.dp
    val SectionGap = 28.dp
    val Gutter = 56.dp
}

/**
 * Accueil Séries : bloc « Reprendre », puis rangées horizontales. La liste verticale et chaque rangée sont paresseuses ;
 * la rangée d'une catégorie ne charge ses séries que lorsqu'elle approche de l'écran ([loadCategory]).
 * [topBarTitle] est la barre des sections ; [barFocused] indique si le focus est dedans (Retour y ramène d'abord).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SeriesHomeScreen(
    vm: SeriesHomeViewModel,
    profile: Profile,
    onChangeProfile: () -> Unit,
    topBarTitle: @Composable () -> Unit,
    onBarFocus: (Boolean) -> Unit,
    onOpenSeries: (seriesId: String, categoryId: String?) -> Unit,
    onPlayEpisode: (PlayEpisodeRequest) -> Unit,
    /** Vrai tant que la fiche d'une série est ouverte par-dessus : à sa fermeture, le focus revient sur la carte d'origine. */
    detailOpen: Boolean = false,
) {
    val t = LocalIptvTokens.current
    val ui by vm.ui.collectAsState()
    val cards = remember { CardFocus() }
    val home = ui.home
    val resumeRequester = remember { FocusRequester() }
    val chipRequester = remember { FocusRequester() }
    val contentRequester = remember { FocusRequester() }
    var focusedOnce by remember { mutableStateOf(false) }
    var contentFocused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.start() }
    val hasHero = ui.features.isNotEmpty() || home.resume != null
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // La bannière arrive quelques instants après l'ouverture : si on n'est pas encore descendu, l'accueil reste en haut.
    LaunchedEffect(hasHero) { if (hasHero && !contentFocused) listState.scrollToItem(0) }
    // Premier focus : le premier bouton de la bannière dès qu'elle existe (une seule fois).
    LaunchedEffect(hasHero) {
        // Jamais si l'utilisateur est déjà descendu dans le contenu.
        if (hasHero && !focusedOnce && !contentFocused) {
            focusedOnce = true
            runCatching { resumeRequester.requestFocus() }
        }
    }

    // Retour de la fiche : le focus retourne sur la carte qui l'a ouverte (clé = rangée + identifiant de série).
    LaunchedEffect(detailOpen) {
        if (!detailOpen) {
            val key = cards.lastOpened ?: return@LaunchedEffect
            cards.lastOpened = null
            val target = if (key == RESUME_KEY) resumeRequester else cards.requesters[key] ?: return@LaunchedEffect
            withFrameNanos { }
            runCatching { target.requestFocus() }
        }
    }

    Column(Modifier.fillMaxSize().background(t.background)) {
        LiveTopBar(
            profile, refreshing = ui.loading, chipRequester = chipRequester, downRequester = contentRequester,
            onChangeProfile = onChangeProfile,
            modifier = Modifier.onFocusChanged { onBarFocus(it.hasFocus) },
            title = topBarTitle,
        )
        LazyColumn(
            Modifier.fillMaxSize().focusRequester(contentRequester).onFocusChanged { contentFocused = it.hasFocus },
            state = listState,
            contentPadding = PaddingValues(horizontal = SeriesDims.Gutter, vertical = LiveDims.FocusBleed),
            verticalArrangement = Arrangement.spacedBy(SeriesDims.SectionGap),
        ) {
            if (ui.features.isNotEmpty()) {
                item(key = "resume") {
                    FeatureHero(
                        ui.features, resumeRequester,
                        onPlay = { f ->
                            val r = f.resume ?: return@FeatureHero
                            val e = r.episode ?: return@FeatureHero
                            cards.lastOpened = RESUME_KEY
                            onPlayEpisode(PlayEpisodeRequest(r.seriesId, r.categoryId, e.id, e.season, e.number, r.positionS, false))
                        },
                        onOpen = { f -> cards.lastOpened = RESUME_KEY; onOpenSeries(f.itemId, f.categoryId) },
                        onFocused = { scope.launch { listState.animateScrollToItem(0) } },
                    )
                }
            } else {
                // Place toujours réservée en haut : quand la bannière arrive, elle s'insère au-dessus de ce qu'on voit
                // au lieu de se retrouver cachée au-dessus de l'écran (essai TCL 0.9.0).
                item(key = "resume") {
                    home.resume?.let { resume -> ResumeBlock(resume, resumeRequester, { id, c -> cards.lastOpened = RESUME_KEY; onOpenSeries(id, c) }, { cards.lastOpened = RESUME_KEY; onPlayEpisode(it) }) }
                        ?: Spacer(Modifier.height(0.dp))
                }
            }
            if (home.continueRow.isNotEmpty()) item(key = "continue") {
                RowSection("Reprendre") {
                    items(home.continueRow, key = { it.seriesId }) { r ->
                        val key = "continue:${r.seriesId}"
                        LandscapeCard(
                            r.title,
                            listOfNotNull(r.episode?.shortLabel(), r.remainingLabel()).joinToString(" · "),
                            ui.backdrops[r.seriesId], r.cover, r.progressFraction().takeIf { it > 0f },
                            requester = cards.requester(key),
                        ) {
                            cards.lastOpened = key
                            // « Reprendre » relance l'épisode ; une série à jour ouvre sa fiche.
                            val e = r.episode
                            if (e != null && r.state != ResumeState.UpToDate) onPlayEpisode(PlayEpisodeRequest(r.seriesId, r.categoryId, e.id, e.season, e.number, r.positionS, false))
                            else onOpenSeries(r.seriesId, r.categoryId)
                        }
                    }
                }
            }
            if (home.myList.isNotEmpty()) item(key = "list") {
                RowSection("Ma liste") { items(home.myList, key = { it.seriesId }) { c -> CardPoster(c, "list", cards, onOpenSeries) } }
            }
            if (home.newEpisodes.isNotEmpty()) item(key = "new") {
                RowSection("Nouveaux épisodes dans tes séries") {
                    items(home.newEpisodes, key = { it.seriesId }) { c -> CardPoster(c, "new", cards, onOpenSeries) }
                }
            }
            items(home.categoryRows, key = { "cat:" + it.id }) { row ->
                // La rangée n'est composée que près de l'écran : c'est ce qui déclenche le chargement de ses séries.
                // La demande part après une courte attente et s'annule si la rangée quitte l'écran.
                DisposableEffect(row.id) {
                    vm.loadCategory(row.id)
                    onDispose { vm.cancelCategory(row.id) }
                }
                val cardsOfRow = ui.rowSeries[row.id]
                // Rangée pas encore chargée : une carte « Chargement… » FOCALISABLE. Sans elle, Bas ne trouvait rien sous
                // la dernière rangée chargée ; la rangée suivante, composée puis aussitôt retirée par la recherche de
                // focus, annulait son chargement : seules les premières rangées apparaissaient (essai TCL 0.4.0,
                // test SeriesDpadTest.bas_parcourt_toutes_les_rangees_de_categories).
                var placeholderFocused by remember(row.id) { mutableStateOf(false) }
                val firstKey = cardsOfRow?.firstOrNull()?.let { "cat:${row.id}:${it.seriesId}" }
                val loadedNow by rememberUpdatedState(firstKey != null)
                // La carte d'attente avait le focus quand les affiches sont arrivées : il passe à la première affiche.
                LaunchedEffect(firstKey) {
                    if (placeholderFocused && firstKey != null) {
                        withFrameNanos { }
                        runCatching { cards.requester(firstKey).requestFocus() }
                        placeholderFocused = false
                    }
                }
                RowSection(row.name) {
                    when {
                        cardsOfRow == null || cardsOfRow.isEmpty() -> item(key = "placeholder") {
                            PlaceholderCard(
                                when {
                                    cardsOfRow != null -> "Aucune série dans cette catégorie."
                                    row.id in ui.failedRows -> "Indisponible pour l'instant."
                                    else -> "Chargement…"
                                },
                            ) { f ->
                                // Perte du focus parce que la carte est remplacée par les affiches : on garde la marque.
                                if (f) placeholderFocused = true else if (!loadedNow) placeholderFocused = false
                            }
                        }
                        else -> items(cardsOfRow, key = { it.seriesId }) { c -> CardPoster(c, "cat:" + row.id, cards, onOpenSeries) }
                    }
                }
            }
            if (ui.loading && !hasHero) item(key = "loading") { Hint("Chargement…") }
            ui.message?.let { item(key = "message") { Hint(it) } }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Hint(text: String) {
    val t = LocalIptvTokens.current
    Text(text, color = t.textMuted, fontSize = t.rowTextSize, modifier = Modifier.padding(vertical = LiveDims.Gap))
}

/** Carte d'attente : même taille qu'une affiche, focalisable pour que Bas puisse toujours descendre d'une rangée. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlaceholderCard(text: String, onFocus: (Boolean) -> Unit) {
    val t = LocalIptvTokens.current
    var focused by remember { mutableStateOf(false) }
    val shape = remember(t.radiusCard) { RoundedCornerShape(t.radiusCard) }
    Box(
        Modifier
            .width(SeriesDims.PosterWidth).height(SeriesDims.PosterHeight)
            .focusLook(focused, shape, "carte Chargement")
            .clip(shape)
            .background(t.surface)
            .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
            .semantics(mergeDescendants = true) {}
            .focusable(),
    ) {
        Text(text, color = t.textMuted, fontSize = t.rowTextSize, modifier = Modifier.align(Alignment.Center).padding(LiveDims.Gap))
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun RowSection(title: String, subtitle: String? = null, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val t = LocalIptvTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(LiveDims.Gap)) {
        Row(horizontalArrangement = Arrangement.spacedBy(LiveDims.Gap), verticalAlignment = Alignment.Bottom) {
            Text(title, color = t.text, fontSize = t.titleSize * 0.8f)
            if (subtitle != null) Text(subtitle, color = t.textMuted, fontSize = t.metaTextSize)
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = LiveDims.FocusBleed, vertical = LiveDims.FocusBleed),
            horizontalArrangement = Arrangement.spacedBy(LiveDims.Gap),
            content = content,
        )
    }
}

@Composable
private fun CardPoster(card: SeriesCard, section: String, cards: CardFocus, onOpen: (String, String?) -> Unit) {
    val key = "$section:${card.seriesId}"
    Poster(card.title, card.cover, card.progress, card.caption, cards.requester(key)) { cards.lastOpened = key; onOpen(card.seriesId, card.categoryId) }
}

private const val RESUME_KEY = "resume"

/** Demandeurs de focus des cartes (clé = rangée + série) et dernière carte ouverte, pour restaurer le focus au retour de la fiche. */
private class CardFocus {
    val requesters = HashMap<String, FocusRequester>()
    var lastOpened: String? = null
    fun requester(key: String): FocusRequester = requesters.getOrPut(key) { FocusRequester() }
}

/** Affiche (Coil, ~240 px) ; le titre reste affiché sur fond tant que l'image manque, échoue ou n'existe pas. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun Poster(title: String, cover: String?, progress: Float?, caption: String? = null, requester: FocusRequester? = null, onClick: () -> Unit) {
    val t = LocalIptvTokens.current
    val context = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    val shape = remember(t.radiusCard) { RoundedCornerShape(t.radiusCard) }
    val server = LocalCoverServer.current
    val image = remember(cover) { resolveCover(cover, server) }
    val request = remember(image) {
        image?.let { ImageRequest.Builder(context).data(it).size(SeriesDims.PosterRequestW, SeriesDims.PosterRequestH).build() }
    }
    var loaded by remember(image) { mutableStateOf(false) }
    Column(Modifier.width(SeriesDims.PosterWidth), verticalArrangement = Arrangement.spacedBy(LiveDims.RowGap)) {
        Box(
            Modifier
                .height(SeriesDims.PosterHeight).fillMaxWidth()
                .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
                .focusLook(focused, shape, "affiche")
                .clip(shape)
                .background(t.surfaceRaised)
                .onFocusChanged { focused = it.isFocused }
                .clickable(onClick = onClick),
        ) {
            if (!loaded) {
                Text(
                    title, color = t.text, fontSize = t.rowTextSize, maxLines = 4, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart).padding(LiveDims.Gap),
                )
            }
            if (request != null) {
                AsyncImage(
                    model = request, contentDescription = title, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    onSuccess = { loaded = true }, onError = { loaded = false },
                )
            }
            if (progress != null) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(SeriesDims.ProgressBar).background(t.background)) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(SeriesDims.ProgressBar).background(t.accent))
                }
            }
        }
        Text(
            if (caption.isNullOrBlank()) title else caption,
            color = t.textMuted, fontSize = t.metaTextSize, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Bloc « Reprendre » : la série la plus récente, ses boutons et « Ensuite : … ». */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ResumeBlock(
    r: ResumeItem,
    playRequester: FocusRequester,
    onOpenSeries: (String, String?) -> Unit,
    onPlay: (PlayEpisodeRequest) -> Unit,
) {
    val t = LocalIptvTokens.current
    val context = LocalContext.current
    val shape = remember(t.radiusCard) { RoundedCornerShape(t.radiusCard * 1.5f) }
    val posterShape = remember(t.radiusCard) { RoundedCornerShape(t.radiusCard) }
    val episode = r.episode
    val server = LocalCoverServer.current
    val image = remember(r.cover) { resolveCover(r.cover, server) }
    var loaded by remember(image) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(shape).background(t.surface).padding(SeriesDims.BlockPadding),
        horizontalArrangement = Arrangement.spacedBy(SeriesDims.BlockGap),
    ) {
        Box(Modifier.width(SeriesDims.BlockPoster).height(SeriesDims.BlockPosterHeight).clip(posterShape).background(t.surfaceRaised)) {
            if (!loaded) Text(r.title, color = t.text, fontSize = t.rowTextSize, maxLines = 4, modifier = Modifier.align(Alignment.BottomStart).padding(LiveDims.Gap))
            if (image != null) {
                AsyncImage(
                    model = remember(image) { ImageRequest.Builder(context).data(image).size(SeriesDims.PosterRequestW, SeriesDims.PosterRequestH).build() },
                    contentDescription = r.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                    onSuccess = { loaded = true }, onError = { loaded = false },
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LiveDims.RowGap)) {
            Text(
                when (r.state) { ResumeState.InProgress -> "REPRENDRE"; ResumeState.NextEpisode -> "ÉPISODE SUIVANT"; ResumeState.UpToDate -> "À JOUR" },
                color = t.accent, fontSize = t.metaTextSize,
            )
            Text(r.title, color = t.text, fontSize = t.titleSize * 1.35f, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    episode == null -> "Tu as tout vu pour l'instant."
                    else -> "Saison ${episode.season} · Épisode ${episode.number}" + episode.title.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()
                },
                color = t.text, fontSize = t.rowTextSize, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (r.state == ResumeState.InProgress) {
                Row(horizontalArrangement = Arrangement.spacedBy(LiveDims.Gap), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(360.dp).height(LiveDims.ProgressHeight).clip(RoundedCornerShape(LiveDims.ProgressHeight)).background(t.surfaceRaised)) {
                        Box(Modifier.fillMaxWidth(r.progressFraction()).height(LiveDims.ProgressHeight).background(t.accent))
                    }
                    r.remainingLabel()?.let { Text(it, color = t.textMuted, fontSize = t.metaTextSize) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(LiveDims.Gap), verticalAlignment = Alignment.CenterVertically) {
                if (episode != null) {
                    LiveButton(
                        if (r.state == ResumeState.InProgress) "Reprendre" else "Lire",
                        { onPlay(PlayEpisodeRequest(r.seriesId, r.categoryId, episode.id, episode.season, episode.number, r.positionS, false)) },
                        Modifier.focusRequester(playRequester), accent = true,
                    )
                    if (r.state == ResumeState.InProgress) {
                        LiveButton("Recommencer l'épisode", {
                            onPlay(PlayEpisodeRequest(r.seriesId, r.categoryId, episode.id, episode.season, episode.number, 0, true))
                        })
                    }
                }
                LiveButton(
                    "Tous les épisodes", { onOpenSeries(r.seriesId, r.categoryId) },
                    if (episode == null) Modifier.focusRequester(playRequester) else Modifier,
                )
                r.then?.let {
                    Text(
                        "Ensuite : É${it.number}" + it.title.takeIf { n -> n.isNotBlank() }?.let { n -> " · $n" }.orEmpty() + " (lecture automatique)",
                        color = t.textMuted, fontSize = t.metaTextSize, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** Grand bloc d'accueil (films) au style « Verre » : affiche à gauche, étiquette, grand titre, détails, boutons. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun HeroBlock(label: String, title: String, detail: String?, cover: String?, progress: Float?, buttons: @Composable RowScope.() -> Unit) {
    val t = LocalIptvTokens.current
    val context = LocalContext.current
    val shape = remember(t.radiusCard) { RoundedCornerShape(t.radiusCard * 1.5f) }
    val posterShape = remember(t.radiusCard) { RoundedCornerShape(t.radiusCard) }
    val server = LocalCoverServer.current
    val image = remember(cover) { resolveCover(cover, server) }
    var loaded by remember(image) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(shape).background(t.surface).padding(SeriesDims.BlockPadding),
        horizontalArrangement = Arrangement.spacedBy(SeriesDims.BlockGap),
    ) {
        Box(Modifier.width(SeriesDims.BlockPoster).height(SeriesDims.BlockPosterHeight).clip(posterShape).background(t.surfaceRaised)) {
            if (!loaded) Text(title, color = t.text, fontSize = t.rowTextSize, maxLines = 4, modifier = Modifier.align(Alignment.BottomStart).padding(LiveDims.Gap))
            if (image != null) {
                AsyncImage(
                    model = remember(image) { ImageRequest.Builder(context).data(image).size(SeriesDims.PosterRequestW, SeriesDims.PosterRequestH).build() },
                    contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                    onSuccess = { loaded = true }, onError = { loaded = false },
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LiveDims.RowGap)) {
            Text(label, color = t.accent, fontSize = t.metaTextSize, fontWeight = FontWeight.SemiBold)
            Text(title, color = t.text, fontSize = t.titleSize * 1.35f, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            detail?.let { Text(it, color = t.textMuted, fontSize = t.rowTextSize, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (progress != null) {
                Box(Modifier.width(360.dp).height(LiveDims.ProgressHeight).clip(RoundedCornerShape(LiveDims.ProgressHeight)).background(t.surfaceRaised)) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(LiveDims.ProgressHeight).background(t.accent))
                }
            }
            Spacer(Modifier.height(LiveDims.RowGap))
            Row(horizontalArrangement = Arrangement.spacedBy(LiveDims.Gap), verticalAlignment = Alignment.CenterVertically, content = buttons)
        }
    }
}
