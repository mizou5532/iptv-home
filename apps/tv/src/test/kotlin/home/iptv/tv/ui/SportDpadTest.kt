package home.iptv.tv.ui

import androidx.compose.foundation.focusable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.tv.material3.Text
import home.iptv.shared.sport.*
import home.iptv.shared.sync.ProfilePrefs
import home.iptv.tv.player.PlayerController
import home.iptv.tv.sport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Onglet Sport à la télécommande : match, chaîne suivante, retour sur le même match. */
@RunWith(RobolectricTestRunner::class)
class SportDpadTest {
    @get:Rule val rule = tvRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val now = Instant.parse("2026-11-17T00:45:00Z")
    private val log = mutableListOf<String>()
    private val opened = mutableListOf<String>()
    private val nhl = """{"gameWeek":[{"games":[{"id":1,"startTimeUTC":"2026-11-17T00:30:00Z","gameState":"LIVE",
        "awayTeam":{"abbrev":"MTL","commonName":{"default":"Canadiens"},"placeName":{"default":"Montréal"}},
        "homeTeam":{"abbrev":"BOS","commonName":{"default":"Bruins"},"placeName":{"default":"Boston"}},
        "tvBroadcasts":[{"network":"TVAS","countryCode":"CA"},{"network":"NESN","countryCode":"US"},{"network":"SN","countryCode":"CA"}]}]}]}"""
    private val store = object : SportStore {
        val cache = mutableMapOf<String, String>(); val learnedMap = mutableMapOf<String, List<String>>()
        override fun cached(key: String) = cache[key]
        override fun saveCached(key: String, text: String, at: Long) { cache[key] = text }
        override fun cachedAt() = 0L
        override fun learned(profileId: String, league: League) = learnedMap[league.key].orEmpty()
        override fun saveLearned(profileId: String, league: League, groups: List<String>) { learnedMap[league.key] = groups }
    }
    private val channels = listOf(
        SportChannel("g-tvas", "TVA Sports HD", "CA| SPORTS FR"), SportChannel("g-sn", "Sportsnet HD", "CA| SPORTS EN"),
        SportChannel("g-rds", "RDS HD", "CA| SPORTS FR"), SportChannel("g-news", "LCN", "CA| GENERAL FR"),
    )
    private val vm = SportViewModel("p1", SportSchedule({ url -> if ("nhle" in url) nhl else """{"events":[]}""" }, store) { now },
        { channels }, { emptyList() }, store, MutableStateFlow<ProfilePrefs?>(NO_PREFS), scope, Dispatchers.IO, { now }, { -5 * 3600 })
    private val player = PlayerController { FakePlayer(log) }
    @After fun tearDown() { scope.cancel() }

    private fun focusedHas(text: String) = rule.focusedTexts()?.any { it.contains(text) } == true
    private fun show() = rule.setTvContent {
        val bar = remember { FocusRequester() }
        LaunchedEffect(Unit) { withFrameNanos { }; bar.requestFocus() }
        SportTab(vm, PROFILE, player, { ch -> opened += ch.groupId; "fake://${ch.groupId}" },
            { Text("Sport", Modifier.focusRequester(bar).focusable()) }, bar, {})
    }

    @Test fun match_des_canadiens_chaines_francaises_d_abord_puis_suivante_et_retour() {
        show()
        rule.until(what = "carte du match") { rule.onAllNodesWithTextCount("Canadiens") > 0 }
        assertTrue(rule.onAllNodesWithTextCount("TVA Sports HD · Sportsnet HD") > 0, "chaînes proposées, français d'abord")
        repeat(3) { if (!focusedHas("Canadiens")) rule.down() }
        assertTrue(focusedHas("EN COURS · LNH"), "${rule.focusedTexts()}")
        rule.ok()
        rule.until(what = "lecture de la 1re chaîne") { opened.firstOrNull() == "g-tvas" && log.contains("play") }
        rule.until { rule.onAllNodesWithTextCount("Chaîne 1 sur 3 : TVA Sports HD   ◀ ▶ autre chaîne") > 0 }
        rule.right()
        rule.until(what = "chaîne suivante") { opened.lastOrNull() == "g-sn" }
        rule.left(); rule.left()
        rule.until(what = "tour complet vers la dernière") { opened.lastOrNull() == "g-rds" }
        assertTrue(opened.none { it == "g-news" }, "jamais une chaîne hors sport dans le parcours")
        rule.back()
        rule.until(what = "retour sur le même match") { focusedHas("Canadiens") }
        assertEquals(null, player.active.value)
    }
}
