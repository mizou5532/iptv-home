package home.iptv.tv.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import home.iptv.tv.theme.LocalIptvTokens
import kotlinx.coroutines.delay

/** Reconnexions silencieuses (films, séries) avant d'afficher « Impossible de lire ». */
const val MAX_SILENT_RECOVERIES = 2

/** Après autant de lecture sans incident depuis la dernière reconnexion, le compteur repart de zéro. */
const val RECOVERY_RESET_S = 60

/** Une « fin » à plus de cette marge de la durée connue est une coupure du fournisseur, pas la vraie fin. */
const val EARLY_END_MARGIN_S = 180

/** Direct : image figée (lecteur en attente de données) depuis ce délai après le démarrage → reconnexion. */
const val LIVE_STALL_MS = 8_000L

/** Message affiché pendant une reconnexion automatique. */
const val RECONNECTING = "Reconnexion…"

/**
 * Media3 annonce « fin » quand le serveur ferme la connexion, même au milieu du fichier (vérifié dans 1.11.1,
 * `DefaultHttpDataSource.readInternal`). Durée inconnue : on ne peut pas savoir, c'est une vraie fin.
 */
fun isEarlyEnd(positionS: Int, durationS: Int): Boolean = durationS > 0 && durationS - positionS > EARLY_END_MARGIN_S

/** Délai sans nouvel appui avant de sauter réellement (une seule demande au fournisseur pour une rafale d'appuis). */
const val SEEK_SETTLE_MS = 700L

/**
 * Sauts gauche/droite regroupés : chaque appui déplace une cible affichée ; le lecteur ne saute qu'une fois, quand
 * les appuis cessent ([SEEK_SETTLE_MS]). Sans cela, une touche maintenue ouvrait une demande chez le fournisseur par
 * appui (une seule connexion permise : écran noir ou erreur).
 */
class SeekBatch {
    /** Position visée, `null` sans saut en attente. */
    var targetMs by mutableStateOf<Long?>(null)
        private set
    /** Écart cumulé depuis la position de départ (affichage « +1:30 »). */
    var deltaMs by mutableLongStateOf(0L)
        private set
    /** Change à chaque appui : relance l'attente avant le saut. */
    var nonce by mutableIntStateOf(0)
        private set

    fun nudge(player: Player?, stepMs: Long) {
        val s = player?.snapshot() ?: return
        val base = targetMs ?: s.positionMs.coerceAtLeast(0)
        val max = if (s.durationMs > 0) s.durationMs else Long.MAX_VALUE
        val target = (base + stepMs).coerceIn(0, max)
        deltaMs += target - base
        targetMs = target
        nonce++
    }

    /** Saute à la cible (un seul appel au lecteur) et vide l'attente. */
    fun commit(player: Player?) {
        val t = targetMs ?: return
        targetMs = null
        deltaMs = 0
        player?.seekTo(t)
    }

    fun clear() { targetMs = null; deltaMs = 0 }
}

/** Applique le saut en attente quand les appuis cessent ; un changement de lecteur abandonne l'attente. */
@Composable
fun SeekBatchEffect(batch: SeekBatch, player: Player?) {
    LaunchedEffect(player) { batch.clear() }
    LaunchedEffect(batch.nonce, player) {
        if (batch.targetMs == null) return@LaunchedEffect
        delay(SEEK_SETTLE_MS)
        batch.commit(player)
    }
}

/** Cible du saut en attente, en bas au centre : « ▶▶ 12:34 (+1:30) ». */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BoxScope.SeekBatchLabel(batch: SeekBatch) {
    val target = batch.targetMs ?: return
    val t = LocalIptvTokens.current
    val d = batch.deltaMs
    val sign = if (d < 0) "−" else "+"
    Text(
        (if (d < 0) "◀◀  " else "▶▶  ") + clockText(target / 1000) + "   (" + sign + clockText(kotlin.math.abs(d) / 1000) + ")",
        color = Color.White, fontSize = t.titleSize, fontWeight = FontWeight.Bold,
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp)
            .clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 24.dp, vertical = 10.dp),
    )
}

/** 75 → « 1:15 », 3725 → « 1:02:05 ». */
fun clockText(totalS: Long): String {
    val s = totalS.coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
