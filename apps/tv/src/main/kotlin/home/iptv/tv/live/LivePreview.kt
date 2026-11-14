package home.iptv.tv.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import home.iptv.shared.xtream.EpgEntry
import home.iptv.tv.live.epg.NowNext
import home.iptv.tv.live.epg.formatLocalTime
import home.iptv.tv.player.ExoPlayerPlayer
import home.iptv.tv.player.Player
import home.iptv.tv.player.PlayerSurface
import home.iptv.tv.theme.LocalIptvTokens
import java.time.ZoneId

/**
 * Zone d'aperçu : la vidéo en lecture (ou une invitation à appuyer sur OK), le nom de la chaîne en lecture (avant
 * toute lecture : celle qui est parcourue), son guide maintenant / ensuite et l'accès aux sources.
 * Aucun lecteur n'est créé ici : [player] est l'instance unique du contrôleur.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LivePreview(
    player: Player?,
    playing: Boolean,
    title: String?,
    nowNext: NowNext,
    sourcesRequester: FocusRequester,
    onSources: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalIptvTokens.current
    val shape = remember(t.radiusCard) { RoundedCornerShape(t.radiusCard) }
    Column(modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(LiveDims.Gap)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape).background(t.surface),
            contentAlignment = Alignment.Center,
        ) {
            if (playing && player is ExoPlayerPlayer) {
                PlayerSurface(player, Modifier.fillMaxWidth().fillMaxHeight())
                if (player.failed) {
                    Overlay("Lecture impossible. Ouvre « Sources » pour essayer une autre copie.")
                } else if (!player.started) {
                    Overlay("Connexion…")
                }
            } else if (playing) {
                Overlay("Connexion…")
            } else {
                Overlay(if (title != null) "Appuie sur OK pour lancer l'aperçu." else "Choisis une chaîne.")
            }
        }
        Text(
            if (playing) "En lecture" else "Sélection",
            color = if (playing) t.accent else t.textMuted,
            fontSize = t.metaTextSize,
        )
        Text(title ?: "", color = t.text, fontSize = t.titleSize, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Guide(nowNext)
        if (playing) {
            Row(horizontalArrangement = Arrangement.spacedBy(LiveDims.Gap)) {
                LiveButton("Sources", onSources, Modifier.focusRequester(sourcesRequester))
                LiveButton("Arrêter", onStop)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Overlay(text: String) {
    val t = LocalIptvTokens.current
    Text(text, color = t.textMuted, fontSize = t.rowTextSize, modifier = Modifier.padding(LiveDims.Gap))
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Guide(nowNext: NowNext) {
    val t = LocalIptvTokens.current
    val zone = ZoneId.systemDefault()
    Column(verticalArrangement = Arrangement.spacedBy(LiveDims.RowGap)) {
        GuideLine("Maintenant", nowNext.current, zone)
        val progress = nowNext.progress
        if (nowNext.current != null && progress != null) {
            Box(Modifier.fillMaxWidth().height(LiveDims.ProgressHeight).clip(RoundedCornerShape(LiveDims.ProgressHeight)).background(t.surfaceRaised)) {
                Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(t.accent))
            }
        }
        GuideLine("Ensuite", nowNext.next, zone)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GuideLine(label: String, entry: EpgEntry?, zone: ZoneId) {
    val t = LocalIptvTokens.current
    Column {
        Text(label, color = t.textMuted, fontSize = t.metaTextSize)
        if (entry == null) {
            Text("Programme indisponible", color = t.textMuted, fontSize = t.rowTextSize)
        } else {
            Text(
                "${formatLocalTime(entry.start, zone)} – ${formatLocalTime(entry.end, zone)}  ${entry.title}",
                color = t.text, fontSize = t.rowTextSize, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
