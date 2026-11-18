package home.iptv.tv.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import home.iptv.tv.live.LiveButton
import home.iptv.tv.theme.LocalIptvTokens

/**
 * Quelqu'un regarde déjà (tous les comptes IPTV sont pris) : qui regarde quoi et où, avec « Prendre la main » (sa
 * vidéo s'arrête, la nôtre démarre) ou « Laisser regarder » (rien n'est lu). Le focus va sur « Laisser regarder » :
 * jamais de coupure chez l'autre par un OK trop rapide. Retour = laisser regarder.
 */
@Composable
fun TakeOverDialog(player: PlayerController) {
    val conflict by player.conflict.collectAsState()
    val c = conflict ?: return
    Dialog(onDismissRequest = { player.leave() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val t = LocalIptvTokens.current
        val leave = remember { FocusRequester() }
        LaunchedEffect(c) { withFrameNanos { }; runCatching { leave.requestFocus() } }
        BackHandler { player.leave() }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
            Column(
                Modifier.width(620.dp).clip(RoundedCornerShape(t.radiusCard)).background(t.surfaceRaised).padding(28.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Quelqu’un regarde déjà", color = t.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                c.occupants.forEach { Text(occupantLine(it), color = t.text, fontSize = 18.sp) }
                Text(
                    if (c.occupants.size <= 1) "L’abonnement ne permet qu’une vidéo à la fois. Prendre la main arrête sa vidéo."
                    else "Tous les comptes IPTV sont utilisés. Prendre la main arrête la vidéo de la télé choisie.",
                    color = t.textMuted, fontSize = 14.sp,
                )
                LiveButton("Laisser regarder", { player.leave() }, Modifier.focusRequester(leave).fillMaxWidth(), accent = true)
                c.occupants.forEach { o ->
                    val id = o.streamId ?: return@forEach
                    val label = if (c.occupants.size <= 1) "Prendre la main" else "Prendre la main sur ${o.deviceName.ifBlank { "cette télé" }}"
                    LiveButton(label, { player.takeOver(id) }, Modifier.fillMaxWidth())
                }
            }
        }
    }
}
