package home.iptv.tv.health

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import home.iptv.tv.live.LiveButton
import home.iptv.tv.theme.IptvColors
import kotlinx.coroutines.launch

private fun color(l: Level) = when (l) {
    Level.OK -> Color(0xFF4ADE80); Level.WARN -> Color(0xFFFFC46B); Level.BAD -> Color(0xFFFF6B6B); Level.UNKNOWN -> Color(0xFF64748B)
}

/** Écran « État de l'app » : chaque point en une ligne (pastille de couleur, nom, phrase simple), « Refaire » et « Retour ». */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HealthScreen(check: HealthCheck, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var lines by remember { mutableStateOf<List<HealthLine>?>(null) }
    var run by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val again = remember { FocusRequester() }
    LaunchedEffect(run) { lines = null; lines = check.run() }
    LaunchedEffect(Unit) { runCatching { again.requestFocus() } }
    Column(Modifier.fillMaxSize().background(IptvColors.Background).padding(horizontal = 64.dp, vertical = 36.dp)) {
        Text("État de l'app", color = IptvColors.Text, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
        Text("Un point rouge ou orange dit où chercher quand quelque chose ne marche pas.", color = IptvColors.TextSecondary, fontSize = 14.sp,
            modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val l = lines
            if (l == null) Text("Vérification en cours… (quelques secondes)", color = IptvColors.TextSecondary, fontSize = 16.sp)
            else l.forEach { line ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(color(line.level)))
                    Text(line.label, color = IptvColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(230.dp))
                    Text(line.value, color = IptvColors.TextSecondary, fontSize = 16.sp, maxLines = 2)
                }
            }
        }
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LiveButton("⟳ Refaire le bilan", { scope.launch { run++ } }, Modifier.focusRequester(again), small = true)
            LiveButton("Retour", onBack, small = true)
        }
    }
}
