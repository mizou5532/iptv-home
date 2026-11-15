package home.iptv.tv.series

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import home.iptv.tv.live.LiveButton
import home.iptv.tv.theme.LocalIptvTokens

/** Écran provisoire des tâches 5 (fiche de série) et 6 (lecture) : un message et « Retour ». Retire-le avec elles. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SeriesPlaceholder(message: String, onBack: () -> Unit) {
    val t = LocalIptvTokens.current
    val requester = remember { FocusRequester() }
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    Column(
        Modifier.fillMaxSize().background(t.background).padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterVertically),
    ) {
        Text(message, color = t.text, fontSize = t.titleSize)
        LiveButton("Retour", onBack, Modifier.focusRequester(requester), accent = true)
    }
}
