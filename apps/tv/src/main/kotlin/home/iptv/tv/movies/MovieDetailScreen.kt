package home.iptv.tv.movies

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
    LaunchedEffect(s.loading, s.masked, playing) { if (!s.loading && !playing) { withFrameNanos { }; runCatching { first.requestFocus() } } }
    BackHandler(enabled = !playing, onBack = onBack)
    Row(Modifier.fillMaxSize().background(t.background).backKey(onBack).padding(48.dp).verticalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        s.movie?.let { Poster(it.name, it.cover, null) {} }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LiveDims.Gap)) {
            val m = s.movie
            if (s.loading) LiveButton("Chargement…", onBack, Modifier.focusRequester(first))
            if (m != null && !s.masked) {
                Text(m.name, color = t.text, fontSize = t.titleSize)
                val meta = listOfNotNull(m.year?.takeIf { it.isNotBlank() }, m.durationS?.takeIf { it > 0 }?.let { "${it/60} min" }, m.genre?.takeIf { it.isNotBlank() }, m.rating?.takeIf { it.isNotBlank() }?.let { "Note : $it" })
                if (meta.isNotEmpty()) Text(meta.joinToString(" · "), color = t.textMuted, fontSize = t.metaTextSize)
                m.plot?.takeIf { it.isNotBlank() }?.let { Text(it, color = t.text, fontSize = t.rowTextSize) }
                if (s.watched) Text("Vu", color = t.accent, fontSize = t.metaTextSize)
                LiveButton(if (s.positionS > 0 && !s.watched) "Reprendre" else "Lire", { vm.play(false)?.let(onPlay) }, Modifier.focusRequester(first), accent = true)
                LiveButton("Recommencer", { vm.play(true)?.let(onPlay) })
                LiveButton(if (s.inMyList) "Retirer de Ma liste" else "Ajouter à Ma liste", vm::toggleMyList)
                LiveButton(if (s.watched) "Marquer non vu" else "Marquer vu", { vm.markWatched(!s.watched) })
            }
            s.message?.let { Text(it, color = t.textMuted, fontSize = t.rowTextSize) }
            if (!s.loading && m == null && !s.masked) LiveButton("Réessayer", vm::reload, Modifier.focusRequester(first))
            LiveButton("Retour", onBack, if (s.masked) Modifier.focusRequester(first) else Modifier)
        }
    }
}
