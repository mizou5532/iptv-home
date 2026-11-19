package home.iptv.tv.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import home.iptv.tv.live.LiveTopBar
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertTrue

/**
 * « Signaler une lenteur » à la télécommande : à droite de la pastille du profil, OK fabrique un vrai rapport sur la
 * télé simulée (mémoire, appareil, catalogue…) ; sans maison connue, il est gardé pour plus tard. Rien ne plante.
 */
@RunWith(RobolectricTestRunner::class)
class ReportButtonDpadTest {
    @get:Rule val rule = tvRule()

    @Test fun a_droite_de_la_pastille_ok_fabrique_et_garde_le_rapport() {
        rule.setTvContent {
            val chip = remember { FocusRequester() }
            val down = remember { FocusRequester() }
            Column { LiveTopBar(PROFILE, false, chip, down, onChangeProfile = {}) }
            LaunchedEffect(Unit) { chip.requestFocus() }
        }
        rule.until(what = "pastille") { rule.focusedTexts()?.any { it.contains(PROFILE.name) } == true }
        rule.right()
        // Petite icône sans texte (0.40.1) : reconnue par sa description.
        rule.until(what = "icône") {
            rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Signaler une lenteur") and androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        rule.ok()
        rule.until(timeoutMs = 10_000, what = "rapport gardé (pas de maison dans le test)") {
            rule.onAllNodesWithTextCount("Rapport gardé, envoyé plus tard") > 0 && rule.onAllNodesWithTextCount("✓") > 0
        }
        val app = rule.activity.applicationContext as home.iptv.tv.IptvApp
        val pending = java.io.File(app.filesDir, "rapports-en-attente").listFiles().orEmpty()
        assertTrue(pending.isNotEmpty(), "rapport écrit sur la télé")
        val text = pending.maxBy { it.name }.readText()
        for (part in listOf("\"appareil\"", "\"memoire\"", "\"catalogue\"", "\"touches\"", "\"reseau\"", "\"stockage\"")) assertTrue(part in text, "contient $part")
    }
}
