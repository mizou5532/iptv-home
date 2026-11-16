package home.iptv.tv.ui

import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.*
import home.iptv.shared.sync.Profile
import home.iptv.tv.movies.*
import home.iptv.tv.movies.moviePrefs
import home.iptv.tv.movies.FakeMovieCatalog
import home.iptv.tv.movies.FakeMovieClient
import home.iptv.tv.movies.MemoryMovieState
import home.iptv.tv.data.HomeTab
import home.iptv.tv.nav.SectionBar
import home.iptv.tv.theme.IptvTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class MovieDpadTest {
    @get:Rule val rule = tvRule()
    private val rig = HomeRig(tab = HomeTab.MOVIES)
    @After fun cleanup() { rig.scope.cancel() }
    private fun focused(text: String) = rule.focusedTexts()?.any { it.contains(text) } == true
    private fun firstPoster() {
        rig.show(rule)
        rule.until { rule.onAllNodesWithTextCount("Film c1-1") > 0 }
        repeat(5) { if (!focused("Film c1-")) rule.down() }
        assertTrue(focused("Film c1-"))
    }
    @Test fun back_exits_once_when_section_bar_cannot_take_focus() {
        var exits = 0
        rule.setTvContent {
            MovieHomeBackHandler(pending = false, barFocused = false, requestBarFocus = { false }, exit = { exits++ })
            home.iptv.tv.live.LiveButton("Contenu", {})
        }
        rule.back()
        rule.until { exits == 1 }
        assertTrue(exits == 1)
    }
    @Test fun arrows_ok_open_detail_and_back_restores_poster() {
        firstPoster(); rule.ok()
        rule.until { rule.onAllNodesWithTextCount("Recommencer") > 0 }
        rule.back()
        rule.until { focused("Film c1-") }
        assertTrue(rule.onAllNodesWithTextCount("Recommencer") == 0)
    }
    @Test fun all_category_rows_reachable_with_down() {
        firstPoster()
        val seen = mutableSetOf<String>()
        repeat(10) {
            rule.down()
            rule.until { focused("Film c") }
            rule.focusedTexts()?.firstOrNull { it.startsWith("Film c") }?.let { seen += it.removePrefix("Film ").substringBefore('-') }
        }
        assertTrue(seen.containsAll((2..8).map { "c$it" }), "rangées atteintes : $seen")
    }
    @Test fun back_returns_section_bar_arrows_switch_sections_and_persist_movies() {
        firstPoster(); rule.back()
        assertTrue(focused("Films"))
        rule.left(); assertTrue(focused("Séries")); rule.ok()
        rule.until { rule.onAllNodesWithTextCount("Rangee 1") > 0 }
        rule.right(); rule.ok()
        rule.until { rule.onAllNodesWithTextCount("Films rangée 1") > 0 }
        assertTrue(focused("Films"))
        assertTrue(rig.store.tab == HomeTab.MOVIES)
    }
    @Test fun play_controls_audio_and_back_keep_single_player() {
        firstPoster(); rule.ok()
        rule.until { focused("Lire") }
        rule.ok()
        rule.until { rig.player.active.value != null }
        rule.until { rule.focusedTexts()?.isEmpty() == true }
        rule.ok(); rule.until { focused("#Reprendre") }
        rule.right(); rule.right(); assertTrue(focused("Audio et sous-titres"), "${rule.focusedTexts()}"); rule.ok()
        rule.until { focused("Aucun") }
        rule.down(); assertTrue(focused("Fermer"))
        rule.back(); rule.until { rule.onAllNodesWithTextCount("Sous-titres") == 0 }
        assertTrue(rig.player.active.value != null)
        rule.back(); rule.back()
        rule.until { rig.player.active.value == null }
        rule.until { focused("Lire") }
    }
    @Test fun ok_met_en_pause_et_la_barre_se_parcourt_puis_ok_reprend() {
        firstPoster(); rule.ok()
        rule.until { focused("Lire") }
        rule.ok()
        rule.until { rig.player.active.value != null }
        (rig.player.active.value as FakePlayer).durationMs = 600_000
        rule.until { rule.focusedTexts()?.isEmpty() == true }
        rule.ok()
        rule.until(what = "pause et commandes") { focused("#Reprendre") && rig.log.contains("paused=true") }
        rule.until(what = "barre affichée") { rule.onAllNodesWithTextCount("1:00") > 0 }
        rule.up(); assertTrue(focused("#Barre de progression"), "${rule.focusedTexts()}")
        repeat(3) { rule.right() } // 3 × 10 s depuis 1:00
        rule.until(what = "repère à 1:30") { rule.onAllNodesWithTextCount("1:30") > 0 }
        rule.ok()
        rule.until(what = "saut à 1:30", detail = { "${rig.log}" }) { rig.log.contains("seek@90000") }
        rule.down(); rule.until { focused("#Reprendre") }
        rule.ok()
        rule.until(what = "reprise") { rig.log.lastOrNull { it.startsWith("paused") } == "paused=false" }
    }
}
