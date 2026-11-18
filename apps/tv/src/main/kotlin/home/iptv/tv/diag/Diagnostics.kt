package home.iptv.tv.diag

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import home.iptv.tv.player.ExoPlayerPlayer
import home.iptv.tv.player.PlayerController

/**
 * Calque de diagnostic caché, pour photographier ce qui se passe sur la télé. Il ne montre JAMAIS d'adresse, de nom
 * d'utilisateur ni de mot de passe : seulement des états (écran, lecteur, vue vidéo, première image, taille, code
 * d'erreur Media3, élément focalisé).
 *
 * Ouverture / fermeture : appuyer 3 fois de suite (en moins de 2 s) sur la touche Info, Menu ou 0 de la
 * télécommande ; ou, si elle n'en a pas, la suite de flèches Haut, Haut, Bas, Bas, Gauche, Droite, Gauche, Droite
 * (en moins de 6 s).
 */
object Diagnostics {
    var visible by mutableStateOf(false)
    /** Écran courant (« Direct · liste », « Séries · lecture »…). */
    var screen by mutableStateOf("")
    /** Dernier élément focalisé visible (étiquette de [home.iptv.tv.live.focusLook]). */
    var focus by mutableStateOf("")
    /** Dernière recherche du guide : méthode qui a répondu, ou pourquoi rien (« Programme indisponible »). */
    var guide by mutableStateOf("")
    /** Mise à jour du catalogue du direct : avancement, durée, issue. */
    var refresh by mutableStateOf("")
    /** Onglet Sport : durée des étapes (mémoire, réseau, calcul des chaînes). */
    var sport by mutableStateOf("")
}

/** Détecte la combinaison d'ouverture du calque. Pur : testable sans télé. */
class SecretCombo {
    private var repeatKey = 0
    private val repeatTimes = ArrayDeque<Long>()
    private val arrows = ArrayDeque<Pair<Int, Long>>()

    /** Appelé à chaque appui (touche enfoncée, sans répétition). Vrai quand la combinaison est complète. */
    fun onKeyDown(keyCode: Int, atMs: Long): Boolean {
        if (keyCode in REPEAT_KEYS) {
            if (keyCode != repeatKey) { repeatKey = keyCode; repeatTimes.clear() }
            repeatTimes.addLast(atMs)
            while (repeatTimes.size > 3) repeatTimes.removeFirst()
            if (repeatTimes.size == 3 && atMs - repeatTimes.first() <= REPEAT_WINDOW_MS) { repeatTimes.clear(); return true }
            return false
        }
        repeatKey = 0; repeatTimes.clear()
        arrows.addLast(keyCode to atMs)
        while (arrows.size > ARROWS.size) arrows.removeFirst()
        if (arrows.map { it.first } == ARROWS && atMs - arrows.first().second <= ARROWS_WINDOW_MS) { arrows.clear(); return true }
        return false
    }

    private companion object {
        val REPEAT_KEYS = setOf(KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_0)
        val ARROWS = listOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        )
        const val REPEAT_WINDOW_MS = 2_000L
        const val ARROWS_WINDOW_MS = 6_000L
    }
}

/** Le calque, en haut à droite, par-dessus tout ; ne prend jamais le focus. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DiagnosticOverlay(player: PlayerController) {
    if (!Diagnostics.visible) return
    val active by player.active.collectAsState()
    val exo = active as? ExoPlayerPlayer
    val context = LocalContext.current
    val accountPosition by ((context.applicationContext as? home.iptv.tv.IptvApp)?.accountLeases?.position
        ?: kotlinx.coroutines.flow.MutableStateFlow(null)).collectAsState()
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    fun yes(b: Boolean) = if (b) "oui" else "non"
    val lines = buildList {
        add("Diagnostic · version $version")
        add("Écran : ${Diagnostics.screen.ifBlank { "?" }}")
        add("Focus : ${Diagnostics.focus.ifBlank { "?" }}")
        add("Guide : ${Diagnostics.guide.ifBlank { "pas encore demandé" }}")
        add("Mise à jour : ${Diagnostics.refresh.ifBlank { "pas encore lancée" }}")
        add("Sport : ${Diagnostics.sport.ifBlank { "pas encore ouvert" }}")
        add("Compte IPTV : ${accountPosition?.let { if (it <= 1) "principal" else "n° $it" } ?: "aucun réservé"}")
        if (active == null) add("Lecteur : aucun")
        else if (exo == null) add("Lecteur : autre")
        else {
            add("Lecteur : ${exo.diagState}${if (exo.failed) " · ÉCHEC" else ""}")
            add("Vue vidéo rattachée : ${yes(exo.viewAttached)} (texture)")
            add("1re image sur cette vue : ${yes(exo.firstFrameOnView)}")
            add("Taille vidéo : ${exo.videoSize ?: "inconnue"}")
            add("Dernière erreur : ${exo.lastErrorCode ?: "aucune"}")
        }
        add("Fermer : Info ×3, Menu ×3, 0 ×3 ou ↑↑↓↓←→←→")
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        Column(
            Modifier.padding(16.dp).widthIn(max = 420.dp).background(Color(0xCC000000)).padding(12.dp),
        ) {
            lines.forEach { Text(it, color = Color(0xFFFFEB3B), fontSize = 13.sp) }
        }
    }
}
