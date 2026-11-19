package home.iptv.tv.ui

import home.iptv.shared.sport.*
import home.iptv.shared.sync.ProfilePrefs
import home.iptv.tv.data.HomeTab
import home.iptv.tv.sport.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Rapport du propriétaire (0.40.1) : « quand je change d'onglet et que je reviens sur Sport, on dirait qu'elle se
 * charge de nouveau ». L'écran Sport est créé une seule fois par profil ; au retour, l'affiche est là tout de suite et
 * les horaires ne sont pas relus.
 */
@RunWith(RobolectricTestRunner::class)
class SportKeptDpadTest {
    @get:Rule val rule = tvRule()
    private val rig = HomeRig(tab = HomeTab.SPORT)
    private val now = Instant.parse("2026-11-17T00:45:00Z")
    private val nhl = """{"gameWeek":[{"games":[{"id":1,"startTimeUTC":"2026-11-17T00:30:00Z","gameState":"LIVE",
        "awayTeam":{"abbrev":"MTL","commonName":{"default":"Canadiens"},"placeName":{"default":"Montréal"}},
        "homeTeam":{"abbrev":"BOS","commonName":{"default":"Bruins"},"placeName":{"default":"Boston"}}}]}]}"""
    private val store = object : SportStore {
        val cache = mutableMapOf<String, String>()
        override fun cached(key: String) = cache[key]
        override fun saveCached(key: String, text: String, at: Long) { cache[key] = text }
        override fun cachedAt() = now.toEpochMilliseconds()
        override fun learned(profileId: String, league: League) = emptyList<String>()
        override fun saveLearned(profileId: String, league: League, groups: List<String>) {}
        override fun preferred(profileId: String, teamKey: String): String? = null
        override fun savePreferred(profileId: String, teamKey: String, groupId: String?) {}
    }
    private var created = 0
    private var fetches = 0

    @After fun tearDown() { rig.scope.cancel() }

    private fun focusedHas(text: String) = rule.focusedTexts()?.any { it.contains(text) } == true

    @Test fun retour_sur_sport_sans_recharger() {
        rig.show(rule) { profile, _, scope ->
            created++
            SportViewModel(profile.id, SportSchedule({ url -> fetches++; if ("nhle" in url) nhl else """{"events":[]}""" }, store) { now },
                { emptyList() }, { emptyList() }, store, MutableStateFlow<ProfilePrefs?>(NO_PREFS), scope, Dispatchers.IO, { now }, { -5 * 3600 })
        }
        rule.until(timeoutMs = 10_000, what = "affiche du match") { rule.onAllNodesWithTextCount("Canadiens") > 0 }
        val fetchesBefore = fetches
        assertEquals(1, created)
        // Vers Séries par la barre, puis retour sur Sport.
        rule.up(); rule.up()
        repeat(6) { rule.left() }
        repeat(6) { if (!focusedHas("Séries")) rule.right() }
        assertTrue(focusedHas("Séries"), "pastille Séries : ${rule.focusedTexts()}")
        rule.ok()
        rule.until(what = "accueil Séries") { rule.onAllNodesWithTextCount("Rangee 1") > 0 }
        repeat(6) { if (!focusedHas("Sport")) rule.right() }
        assertTrue(focusedHas("Sport"), "pastille Sport : ${rule.focusedTexts()}")
        rule.ok()
        rule.until(what = "affiche de retour") { rule.onAllNodesWithTextCount("Canadiens") > 0 }
        assertEquals(1, created, "Sport créé une seule fois")
        assertEquals(fetchesBefore, fetches, "horaires pas relus au retour")
    }
}
