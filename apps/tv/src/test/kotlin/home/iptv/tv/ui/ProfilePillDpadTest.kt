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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Barre du haut : plus de bouton « Changer de profil » ; la pastille du profil (photo + nom) en tient lieu. */
@RunWith(RobolectricTestRunner::class)
class ProfilePillDpadTest {
    @get:Rule val rule = tvRule()

    @Test fun la_pastille_du_profil_ramene_au_choix_du_profil() {
        var changes = 0
        rule.setTvContent {
            val chip = remember { FocusRequester() }
            val down = remember { FocusRequester() }
            Column { LiveTopBar(PROFILE, false, chip, down, onChangeProfile = { changes++ }) }
            LaunchedEffect(Unit) { chip.requestFocus() }
        }
        rule.until(what = "pastille focalisée") { rule.focusedTexts()?.any { it.contains(PROFILE.name) } == true }
        assertEquals(0, rule.onAllNodesWithTextCount("Changer de profil"))
        rule.ok()
        rule.until(what = "changement de profil demandé") { changes == 1 }
        assertTrue(changes == 1)
    }
}
