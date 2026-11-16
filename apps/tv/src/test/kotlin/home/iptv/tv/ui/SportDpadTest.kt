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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
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
        "tvBroadcasts":[{"network":"TVAS","countryCode":"CA"},{"network":"NESN","countryCode":"US"},{"network":"SN","countryCode":"CA"}]},
        {"id":2,"startTimeUTC":"2026-11-17T03:00:00Z","gameState":"FUT",
        "awayTeam":{"abbrev":"EDM","commonName":{"default":"Oilers"},"placeName":{"default":"Edmonton"}},
        "homeTeam":{"abbrev":"SEA","commonName":{"default":"Kraken"},"placeName":{"default":"Seattle"}}},
        {"id":3,"startTimeUTC":"2026-11-19T00:00:00Z","gameState":"FUT",
        "awayTeam":{"abbrev":"TOR","commonName":{"default":"Maple Leafs"},"placeName":{"default":"Toronto"}},
        "homeTeam":{"abbrev":"MTL","commonName":{"default":"Canadiens"},"placeName":{"default":"Montréal"}}}]}]}"""
    private val store = object : SportStore {
        val cache = mutableMapOf<String, String>(); val learnedMap = mutableMapOf<String, List<String>>()
        override fun cached(key: String) = cache[key]
        override fun saveCached(key: String, text: String, at: Long) { cache[key] = text }
        override fun cachedAt() = 0L
        override fun learned(profileId: String, league: League) = learnedMap[league.key].orEmpty()
        override fun saveLearned(profileId: String, league: League, groups: List<String>) { learnedMap[league.key] = groups }
        val preferredMap = mutableMapOf<String, String>()
        override fun preferred(profileId: String, teamKey: String) = preferredMap[teamKey]
        override fun savePreferred(profileId: String, teamKey: String, groupId: String?) { if (groupId == null) preferredMap.remove(teamKey) else preferredMap[teamKey] = groupId }
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

    private fun toWatch() {
        rule.until(what = "affiche du match") { rule.onAllNodesWithTextCount("Canadiens") > 0 }
        repeat(4) { if (!focusedHas("Regarder")) rule.down() }
        assertTrue(focusedHas("▶  Regarder sur TVA Sports HD"), "${rule.focusedTexts()}")
    }

    @Test fun affiche_regarder_chaines_francaises_d_abord_puis_suivante_et_retour() {
        show()
        toWatch()
        assertTrue(rule.onAllNodesWithTextCount("1 autre chaîne") > 0, "Sportsnet annoncée en plus de TVA Sports")
        rule.ok()
        rule.until(what = "lecture de la 1re chaîne") { opened.firstOrNull() == "g-tvas" && log.contains("play") }
        rule.until { rule.onAllNodesWithTextCount("Chaîne 1 sur 3 : TVA Sports HD   ◀ ▶ autre chaîne") > 0 }
        rule.right()
        rule.until(what = "chaîne suivante") { opened.lastOrNull() == "g-sn" }
        rule.left(); rule.left()
        rule.until(what = "tour complet vers la dernière") { opened.lastOrNull() == "g-rds" }
        assertTrue(opened.none { it == "g-news" }, "jamais une chaîne hors sport dans le parcours")
        rule.back()
        rule.until(what = "retour sur le même match") { focusedHas("Regarder") }
        assertEquals(null, player.active.value)
    }

    @Test fun appui_long_choix_de_la_chaine_etoile_puis_lecture() {
        show()
        toWatch()
        rule.longOk()
        rule.until(what = "choix de la chaîne") { rule.onAllNodesWithTextCount("Choisir la chaîne") > 0 && focusedHas("TVA Sports HD") }
        assertTrue(opened.isEmpty(), "l'appui long ne lance rien")
        assertTrue(rule.onAllNodesWithTextCount("2 chaînes de ta liste diffusent ce match.") > 0)
        rule.down()
        assertTrue(focusedHas("Sportsnet HD"), "${rule.focusedTexts()}")
        rule.longOk()
        rule.until(what = "étoile posée pour les Canadiens") { store.preferredMap["nhl:MTL"] == "g-sn" && rule.onAllNodesWithTextCount("★ préférée") > 0 }
        assertTrue(opened.isEmpty())
        rule.back()
        rule.until(what = "panneau fermé, retour sur Regarder") { focusedHas("▶  Regarder sur Sportsnet HD") }
        rule.longOk()
        rule.until { focusedHas("Sportsnet HD") && rule.onAllNodesWithTextCount("Choisir la chaîne") > 0 }
        rule.down()
        rule.ok()
        rule.until(what = "lecture de la chaîne choisie") { opened.lastOrNull() == "g-tvas" }
        rule.until { rule.onAllNodesWithTextCount("Chaîne 2 sur 3 : TVA Sports HD   ◀ ▶ autre chaîne") > 0 }
    }

    @Test fun affiche_tient_dans_l_ecran_et_la_bande_change_le_match() {
        show()
        toWatch()
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val watch = rule.onNodeWithTag("Regarder").fetchSemanticsNode().boundsInRoot
        rule.down()
        assertTrue(focusedHas("Canadiens – Bruins"), "bande : le match affiché a le focus (${rule.focusedTexts()})")
        val tile = rule.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().first().boundsInRoot
        assertTrue(watch.bottom < tile.top, "bouton au-dessus de la bande : $watch / $tile")
        assertTrue(tile.bottom <= root.bottom && watch.top > 0f, "tout dans l'écran : $tile dans $root")
        rule.right()
        assertTrue(focusedHas("Oilers – Kraken"), "${rule.focusedTexts()}")
        rule.until(what = "l'affiche suit la bande") { rule.onAllNodesWithTextCount("Oilers") > 0 && rule.onAllNodesWithTextCount("VS") > 0 }
        rule.right()
        assertTrue(focusedHas("Oilers – Kraken"), "la bande s'arrête aux matchs d'aujourd'hui (${rule.focusedTexts()})")
        assertEquals(0, rule.onAllNodesWithTextCount("Maple Leafs – Canadiens"), "pas le match de mercredi des Canadiens")
        rule.up()
        assertTrue(focusedHas("Regarder"), "${rule.focusedTexts()}")
    }
}
