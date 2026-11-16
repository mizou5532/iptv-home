package home.iptv.tv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import home.iptv.tv.player.LocalPlayerStyle
import home.iptv.tv.player.PlayerStyle
import home.iptv.tv.player.VodControls
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Lecteur 3 « carte flottante » (au choix sur l'iPhone) : mêmes commandes, disposées en rangée. */
@RunWith(RobolectricTestRunner::class)
class PlayerStyleDpadTest {
    @get:Rule val rule = tvRule()
    private fun focused(t: String) = rule.focusedTexts()?.any { it.contains(t) } == true

    @Test fun carte_lecture_avance_sous_titres_et_barre() {
        val log = mutableListOf<String>()
        val player = FakePlayer(log).apply { durationMs = 600_000 }
        val play = FocusRequester()
        var toggles = 0; var tracks = 0
        rule.setTvContent {
            CompositionLocalProvider(LocalPlayerStyle provides PlayerStyle.CARD) {
                Box(Modifier.fillMaxSize()) {
                    VodControls(player, "Film", null, paused = true, onTogglePause = { toggles++ }, onTracks = { tracks++ }, onQuit = {}, playRequester = play, onActivity = {})
                }
            }
        }
        rule.runOnIdle { play.requestFocus() }
        rule.until { focused("#Reprendre") }
        rule.ok(); rule.until { toggles == 1 }
        rule.until(what = "barre") { rule.onAllNodesWithTextCount("1:00") > 0 }
        rule.up(); assertTrue(focused("#Barre de progression"), "${rule.focusedTexts()}")
        rule.right(); rule.ok(); rule.until { log.contains("seek@70000") }
        rule.down(); assertTrue(focused("#Reprendre"), "Bas depuis la barre : ${rule.focusedTexts()}")
        repeat(4) { if (!focused("Audio et sous-titres")) rule.right() }
        rule.ok(); rule.until { tracks == 1 }
        assertEquals(PlayerStyle.CARD, PlayerStyle.of("carte")); assertEquals(PlayerStyle.MINIMAL, PlayerStyle.of(null))
    }
}
