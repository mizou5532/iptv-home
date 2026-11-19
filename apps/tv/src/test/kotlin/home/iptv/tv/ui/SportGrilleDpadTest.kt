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
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Disposition « grille » de l'onglet Sport à la télécommande (maquette « 1 · Grille complète » du propriétaire). */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@org.robolectric.annotation.Config(qualifiers = "w960dp-h540dp-xhdpi")
class SportGrilleDpadTest {
    @get:Rule val rule = tvRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val now = Instant.parse("2026-11-17T00:45:00Z")
    private val log = mutableListOf<String>()
    private val opened = mutableListOf<String>()
    private var nhl = """{"gameWeek":[{"games":[{"id":1,"startTimeUTC":"2026-11-17T00:30:00Z","gameState":"LIVE",
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
        SportTab(vm, GRID_PROFILE, player, { ch -> opened += ch.groupId; "fake://${ch.groupId}" },
            { Text("Sport", Modifier.focusRequester(bar).focusable()) }, bar, {})
    }

    private fun shot(name: String) {
        val dir = System.getProperty("sport.shots")?.let { java.io.File(it).apply { mkdirs() } } ?: return
        var bmp: android.graphics.Bitmap? = null
        rule.runOnUiThread {
            val v = rule.activity.window.decorView
            bmp = android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888).also { v.draw(android.graphics.Canvas(it)) }
        }
        java.io.File(dir, "$name.png").outputStream().use { bmp!!.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun toWatch() {
        rule.until(what = "match du haut") { rule.onAllNodesWithTag("face-a-face").fetchSemanticsNodes().isNotEmpty() && rule.onAllNodesWithTextCount("CANADIENS") > 0 }
        repeat(4) { if (!focusedHas("Regarder")) rule.down() }
        assertTrue(focusedHas("▶  Regarder"), "${rule.focusedTexts()}")
        // Face-à-face : plus de liste « Où regarder » dans la bannière.
        assertEquals(0, rule.onAllNodesWithTextCount("OÙ REGARDER"))
    }

    @Test fun grille_visible_filtres_puis_tuiles_le_match_du_haut_suit_le_focus_et_ok_regarde() {
        show()
        toWatch()
        rule.until(what = "chaînes trouvées") { vm.ui.value.candidates.isNotEmpty() }
        shot("grille-regarder")
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        // Bas : les filtres (« Tout », « En direct ») puis la grille.
        rule.down()
        assertTrue(focusedHas("Tout") || focusedHas("En direct") || focusedHas("match"), "${rule.focusedTexts()}")
        repeat(3) { if (!focusedHas("Oilers")) rule.down() }
        if (!focusedHas("Oilers")) repeat(3) { if (!focusedHas("Oilers")) rule.right() }
        assertTrue(focusedHas("Oilers"), "tuile du match des Oilers (${rule.focusedTexts()})")
        val tile = rule.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().first().boundsInRoot
        assertTrue(tile.bottom <= root.bottom && tile.top >= 0f, "tuile dans l'écran : $tile dans $root")
        rule.until(what = "le match du haut suit le focus") { rule.onAllNodesWithTextCount("VS") > 0 }
        shot("grille-tuile")
        rule.ok()
        rule.until(what = "lecture du match choisi") { opened.isNotEmpty() && log.contains("play") }
    }

    @Test fun maintenir_ok_sur_une_tuile_ouvre_le_choix_de_la_chaine() {
        show()
        toWatch()
        rule.down()
        repeat(3) { if (!focusedHas("Canadiens")) rule.down() }
        rule.longOk()
        rule.until(what = "choix de la chaîne") { rule.onAllNodesWithTextCount("Choisir la chaîne") > 0 }
        assertTrue(opened.isEmpty(), "l'appui long ne lance rien")
        rule.back()
        rule.until(what = "retour sur Regarder") { focusedHas("Regarder") }
    }

    @Test fun autres_sources_ouvre_le_choix_de_la_chaine_et_le_retour_revient_a_regarder() {
        show()
        toWatch()
        rule.right()
        assertTrue(focusedHas("Autres sources"), "${rule.focusedTexts()}")
        rule.right()
        assertTrue(focusedHas("Autres sources"), "Droite ne quitte pas la bannière (${rule.focusedTexts()})")
        rule.ok()
        rule.until(what = "choix de la chaîne") { rule.onAllNodesWithTextCount("Choisir la chaîne") > 0 }
        shot("grille-sources")
        assertTrue(opened.isEmpty(), "« Autres sources » ne lance rien")
        rule.back()
        rule.until(what = "retour sur Regarder") { focusedHas("Regarder") }
    }

    @Test fun defilement_jusqu_a_la_derniere_tuile_puis_retour_en_haut() {
        // Soirée chargée : 14 matchs de plus ce soir, soit plusieurs rangées sous la bannière.
        val teams = listOf("TOR", "OTT", "PHI", "PIT", "BUF", "WSH", "NYI", "WPG", "TBL", "CHI", "CBJ", "STL", "LAK", "CAR", "VGK", "DAL",
            "COL", "MIN", "NSH", "ANA", "SJS", "UTA", "NJD", "FLA", "DET", "CGY", "VAN", "NYR")
        val extra = teams.chunked(2).mapIndexed { i, (a, h) ->
            """{"id":${100 + i},"startTimeUTC":"2026-11-17T0${1 + i % 3}:00:00Z","gameState":"FUT",
            "awayTeam":{"abbrev":"$a","commonName":{"default":"Equipe$a"},"placeName":{"default":"V"}},
            "homeTeam":{"abbrev":"$h","commonName":{"default":"Equipe$h"},"placeName":{"default":"V"}}}"""
        }
        nhl = nhl.replace("]}]}", "," + extra.joinToString(",") + "]}]}")
        show()
        toWatch()
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        rule.until(what = "grille chargée") { rule.onAllNodesWithTextCount("EquipeNYR") > 0 }
        rule.down()
        // Descend tant que le focus bouge : chaque tuile focalisée reste entièrement à l'écran.
        val seen = mutableListOf<String>()
        repeat(12) {
            rule.down()
            val node = rule.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().first()
            assertTrue(node.boundsInRoot.bottom <= root.bottom + 1f && node.boundsInRoot.top >= 0f, "tuile visible : ${node.boundsInRoot} dans $root")
            seen += rule.focusedTexts()?.joinToString().orEmpty()
        }
        shot("grille-bas")
        // Le focus a traversé au moins 4 rangées puis s'est arrêté en bas (sans se perdre).
        assertTrue(seen.distinct().size >= 4, "rangées traversées : $seen")
        assertEquals(seen[seen.size - 1], seen[seen.size - 2], "arrêt sur la dernière rangée : $seen")
        assertTrue(seen.last().contains("Equipe") || seen.last().contains("Canadiens"), "${seen.last()}")
        repeat(14) { if (!focusedHas("Regarder")) rule.up() }
        assertTrue(focusedHas("Regarder"), "Haut ramène à « Regarder » (${rule.focusedTexts()})")
    }

    @Test fun filtre_en_direct_qui_disparait_le_focus_n_est_jamais_perdu() {
        show()
        toWatch()
        rule.down()
        repeat(3) { if (!focusedHas("En direct")) rule.right() }
        assertTrue(focusedHas("En direct"), "${rule.focusedTexts()}")
        rule.ok()
        // Le match en direct se termine : plus de filtre « En direct ».
        nhl = nhl.replace("\"gameState\":\"LIVE\"", "\"gameState\":\"OFF\"")
        vm.refresh()
        rule.until(what = "filtre « En direct » disparu") { rule.onAllNodes(androidx.compose.ui.test.hasText("En direct", substring = true)).fetchSemanticsNodes().isEmpty() }
        // Un seul sport et plus de direct : plus aucun filtre ; le focus revient sur « Regarder » (jamais perdu).
        rule.until(what = "focus revenu sur Regarder") { focusedHas("Regarder") }
        rule.down()
        assertTrue(focusedHas("Canadiens") || focusedHas("Oilers"), "Bas mène aux tuiles (${rule.focusedTexts()})")
    }
}

private val GRID_PROFILE = PROFILE.copy(settings = home.iptv.shared.sync.ProfileSettings(sportView = home.iptv.shared.sync.SportView(layout = home.iptv.shared.sync.SPORT_LAYOUT_GRID)))
