package home.iptv.tv.ui

import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import home.iptv.tv.feature.DismissSheet
import home.iptv.tv.feature.LandscapeCard
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Maintenir OK sur une carte de « Reprendre » (essai TCL 0.22.0 : la vidéo se lançait, puis pause / lecture en
 * boucle). Trois façons de tenir OK selon la télécommande : répétitions d'Android, appui tenu sans répétition,
 * « appuyé / relâché » en boucle. Aucune ne doit lancer la lecture ni toucher un bouton de la confirmation.
 */
@RunWith(RobolectricTestRunner::class)
class ResumeLongPressDpadTest {
    @get:Rule val rule = tvRule()
    private var clicks = 0
    private var longs = 0
    private var confirms = 0
    private var cancels = 0

    private fun show() {
        rule.setTvContent {
            val card = remember { FocusRequester() }
            var sheet by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { withFrameNanos { }; card.requestFocus() }
            Box {
                LandscapeCard("Serie a", "S1 · É2", null, null, 0.3f, card, onLong = { longs++; sheet = true }) { clicks++ }
                if (sheet) DismissSheet("Serie a", onConfirm = { confirms++; sheet = false }, onCancel = { cancels++; sheet = false })
            }
        }
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(100)
    }

    private fun send(action: Int, repeat: Int = 0) = rule.runOnUiThread {
        val t = android.os.SystemClock.uptimeMillis()
        rule.activity.window.decorView.dispatchKeyEvent(KeyEvent(t, t, action, KeyEvent.KEYCODE_DPAD_CENTER, repeat))
    }
    private fun pause(ms: Long) { rule.mainClock.advanceTimeBy(ms) }
    private fun sheetShown() = rule.onAllNodes(androidx.compose.ui.test.hasText("Retirer de Reprendre ?")).fetchSemanticsNodes().isNotEmpty()

    private fun assertLongOnly() {
        pause(1_000)
        assertEquals(0, clicks, "aucune lecture")
        assertEquals(1, longs, "une seule demande de retrait")
        assertEquals(0, confirms + cancels, "aucun bouton de la confirmation touché par l'appui tenu")
        assertTrue(sheetShown(), "la confirmation reste affichée")
    }

    @Test fun tenu_avec_repetitions_android() {
        show()
        send(KeyEvent.ACTION_DOWN); pause(500)
        repeat(8) { i -> send(KeyEvent.ACTION_DOWN, i + 1); pause(50) }
        send(KeyEvent.ACTION_UP)
        assertLongOnly()
    }

    @Test fun tenu_sans_repetition() {
        show()
        send(KeyEvent.ACTION_DOWN); pause(800)
        send(KeyEvent.ACTION_UP)
        assertLongOnly()
    }

    @Test fun tenu_en_appuye_relache_en_boucle() {
        show()
        repeat(12) { send(KeyEvent.ACTION_DOWN); pause(40); send(KeyEvent.ACTION_UP); pause(60) }
        assertLongOnly()
    }

    @Test fun appui_court_lance_la_lecture_et_la_confirmation_repond_ensuite() {
        show()
        send(KeyEvent.ACTION_DOWN); pause(80); send(KeyEvent.ACTION_UP); pause(400)
        assertEquals(1, clicks); assertEquals(0, longs)
        // Appui long, relâché, puis un vrai appui sur le bouton qui a le focus (Annuler).
        send(KeyEvent.ACTION_DOWN); pause(700); send(KeyEvent.ACTION_UP); pause(600)
        assertEquals(1, longs)
        send(KeyEvent.ACTION_DOWN); pause(80); send(KeyEvent.ACTION_UP); pause(400)
        assertEquals(1, cancels, "Annuler répond à un nouvel appui")
        assertEquals(1, clicks)
    }
}
