package home.iptv.tv.feature

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
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
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import home.iptv.shared.versions.Version
import home.iptv.tv.live.LiveButton
import home.iptv.tv.theme.LocalIptvTokens

/** Versions du titre affiché (langues, 4K) et changement de version ; [versions] vide = pas de choix. */
data class VersionChoice(
    val versions: List<Version>, val currentId: String,
    /** « Ce n'est pas le même titre » : sépare la version affichée ; rend faux en cas d'échec. Null = pas proposé. */
    val onSplit: (suspend (Version) -> Boolean)? = null,
    val onPick: (Version) -> Unit,
)

/** Fournie par l'onglet (versions de la recherche ou de l'index) ; absente sinon. */
val LocalVersionChoice = staticCompositionLocalOf<VersionChoice?> { null }

/**
 * Bouton « Version : Français (Québec) » de la fiche, s'il y a plusieurs versions ; OK ouvre la liste, une version
 * choisie remplace la fiche (même titre, autre langue ou qualité).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun VersionButton(modifier: Modifier = Modifier) {
    val choice = LocalVersionChoice.current ?: return
    if (choice.versions.size < 2) return
    val current = choice.versions.firstOrNull { it.id == choice.currentId } ?: return
    var open by remember { mutableStateOf(false) }
    var confirmSplit by remember { mutableStateOf(false) }
    var splitMessage by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LiveButton("Version : ${current.tag.label}", { open = true }, modifier, small = true)
    if (!open) { confirmSplit = false; splitMessage = null }
    if (open) Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val t = LocalIptvTokens.current
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
        BackHandler { open = false }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
            Column(
                Modifier.width(520.dp).clip(RoundedCornerShape(t.radiusCard)).background(t.surfaceRaised).padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Choisir la version", color = t.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Même titre, autre langue ou qualité. Les langues préférées se règlent sur l'iPhone (profil → Langues).",
                    color = t.textMuted, fontSize = 13.sp)
                choice.versions.forEachIndexed { i, v ->
                    val mark = if (v.id == current.id) "✓ " else ""
                    LiveButton(mark + v.tag.label, { open = false; if (v.id != current.id) choice.onPick(v) },
                        (if (v.id == current.id || (i == 0 && choice.versions.none { it.id == current.id })) Modifier.focusRequester(first) else Modifier).fillMaxWidth(),
                        accent = v.id == current.id, small = true)
                }
                choice.onSplit?.let { split ->
                    Text(splitMessage ?: "Un autre film ou une autre série s'est glissé ici ? Sépare la version affichée (pour toute la maison ; on peut tout réunir depuis l'iPhone, Réglages).",
                        color = t.textMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    LiveButton(if (confirmSplit) "Confirmer : séparer « ${current.tag.label} »" else "Ce n'est pas le même titre", {
                        if (!confirmSplit) confirmSplit = true
                        else scope.launch {
                            if (split(current)) open = false
                            else { confirmSplit = false; splitMessage = "Séparation impossible : vérifie la connexion puis réessaie." }
                        }
                    }, Modifier.fillMaxWidth(), small = true)
                }
            }
        }
    }
}
