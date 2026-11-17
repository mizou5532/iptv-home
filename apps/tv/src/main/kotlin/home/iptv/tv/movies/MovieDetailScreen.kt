package home.iptv.tv.movies

import home.iptv.shared.meta.MediaKind
import home.iptv.shared.meta.MediaRef
import home.iptv.shared.meta.imdbLabel
import home.iptv.shared.meta.tmdbImage
import home.iptv.tv.meta.rememberMeta
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import home.iptv.tv.live.*
import home.iptv.tv.series.Poster
import home.iptv.tv.theme.LocalIptvTokens

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MovieDetailScreen(vm: MovieDetailViewModel, onPlay: (PlayMovieRequest) -> Unit, onBack: () -> Unit, playing: Boolean = false) {
    val t = LocalIptvTokens.current
    val s by vm.state.collectAsState()
    val first = remember { FocusRequester() }
    var wasPlaying by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.start() }
    LaunchedEffect(playing) { if (wasPlaying && !playing) vm.playbackClosed(); wasPlaying = playing }
    LaunchedEffect(s.loading, s.masked, playing, s.movie != null) { if (!s.loading && !playing) { withFrameNanos { }; runCatching { first.requestFocus() } } }
    BackHandler(enabled = !playing, onBack = onBack)
    // Fiche TMDB : image de fond, affiche, titre et résumé français, note IMDb (sinon ceux du fournisseur).
    val dm = rememberMeta(s.movie?.let { MediaRef(MediaKind.MOVIE, it.name, it.year) })
    Box(Modifier.fillMaxSize().background(t.background)) { home.iptv.tv.series.DetailBackdrop(s.movie?.backdrop, s.movie?.cover, tmdbImage(dm?.backdrop, "w1280")) }
    // Maquette « Fiche B » : affiche et boutons à gauche, titre, détails et résumé à droite.
    Row(Modifier.fillMaxSize().backKey(onBack).padding(horizontal = 44.dp, vertical = 26.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        val m = s.movie
        Column(Modifier.width(176.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(LiveDims.RowGap)) {
            if (m != null) MoviePoster(m.name, m.cover, tmdbImage(dm?.poster, "w500"))
            if (s.loading) LiveButton("Chargement…", onBack, Modifier.focusRequester(first).fillMaxWidth())
            if (m != null && !s.masked) {
                LiveButton(if (s.positionS > 0 && !s.watched) "Reprendre" else "Lire", { vm.play(false)?.let(onPlay) }, Modifier.focusRequester(first).fillMaxWidth(), accent = true, small = true)
                Column(verticalArrangement = Arrangement.spacedBy(LiveDims.RowGap)) {
                    LiveButton("Recommencer", { vm.play(true)?.let(onPlay) }, Modifier.fillMaxWidth(), small = true)
                    LiveButton(if (s.inMyList) "✓ Dans ma liste" else "+ Ma liste", vm::toggleMyList, Modifier.fillMaxWidth(), small = true)
                    home.iptv.tv.feature.VersionButton(Modifier.fillMaxWidth())
                }
            }
            if (!s.loading && m == null && !s.masked) LiveButton("Réessayer", vm::reload, Modifier.focusRequester(first).fillMaxWidth())
            if (m == null || s.masked) LiveButton("Retour", onBack, (if (s.masked) Modifier.focusRequester(first) else Modifier).fillMaxWidth())
        }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(LiveDims.Gap)) {
            if (m != null && !s.masked) {
                Text(dm?.title ?: m.name, color = t.text, fontSize = t.titleSize * 1.05f, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 2)
                val note = imdbLabel(dm?.imdbRating) ?: m.rating?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0 }?.let { "Note " + "%.1f".format(java.util.Locale.FRANCE, it) }
                val meta = listOfNotNull(m.year?.takeIf { it.isNotBlank() }, m.durationS?.takeIf { it > 0 }?.let { home.iptv.tv.feature.hours(it) }, m.genre?.takeIf { it.isNotBlank() }, note)
                if (meta.isNotEmpty()) Text(meta.joinToString(" · "), color = t.textMuted, fontSize = t.metaTextSize)
                val duration = m.durationS ?: 0
                if (s.positionS > 0 && !s.watched && duration > 0) {
                    Row(horizontalArrangement = Arrangement.spacedBy(LiveDims.Gap), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Box(Modifier.width(260.dp).height(5.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp)).background(t.surfaceRaised)) {
                            Box(Modifier.fillMaxWidth((s.positionS.toFloat() / duration).coerceIn(0f, 1f)).height(5.dp).background(t.accent))
                        }
                        Text("${((duration - s.positionS).coerceAtLeast(0) + 59) / 60} min restantes", color = t.textMuted, fontSize = t.metaTextSize)
                    }
                }
                if (s.watched) Text("Vu ✓", color = t.textMuted, fontSize = t.metaTextSize)
                (m.plot?.takeIf { it.isNotBlank() } ?: dm?.overview)?.let { Text(it, color = t.text, fontSize = t.metaTextSize, lineHeight = t.metaTextSize * 1.45f) }
                Spacer(Modifier.height(LiveDims.RowGap))
                Row(horizontalArrangement = Arrangement.spacedBy(LiveDims.Gap)) {
                    LiveButton(if (s.watched) "Marquer non vu" else "Marquer vu", { vm.markWatched(!s.watched) }, small = true)
                    LiveButton("Retour", onBack, small = true)
                }
            }
            s.message?.let { Text(it, color = t.textMuted, fontSize = t.rowTextSize) }
        }
    }
}

/** Grande affiche de la fiche (titre en attendant l'image). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MoviePoster(title: String, cover: String?, tmdb: String? = null) {
    val t = LocalIptvTokens.current
    val shape = remember(t.radiusCard) { androidx.compose.foundation.shape.RoundedCornerShape(t.radiusCard * 1.2f) }
    Box(Modifier.width(176.dp).height(250.dp).clip(shape).background(t.surfaceRaised)) {
        Text(title, color = t.text, fontSize = t.rowTextSize, maxLines = 5, modifier = Modifier.align(androidx.compose.ui.Alignment.BottomStart).padding(LiveDims.Gap))
        home.iptv.tv.feature.FeatureImage(null, cover, 352, 500, Modifier.fillMaxSize(), tmdb)
    }
}
