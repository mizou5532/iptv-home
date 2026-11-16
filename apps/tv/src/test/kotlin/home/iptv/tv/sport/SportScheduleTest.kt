package home.iptv.tv.sport

import home.iptv.shared.sport.League
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Horaires : ESPN demandé jour par jour (les plages répondent 400 depuis nov. 2026), cache élagué. */
class SportScheduleTest {
    private val now = Instant.parse("2026-11-17T00:45:00Z") // lundi 16 nov., 19 h 45 à Montréal
    private val montreal = -5 * 3600

    @Test fun espn_un_tableau_par_jour_sans_plage() {
        val urls = scheduleSources(now, montreal, listOf(League.NHL, League.NBA, League.NFL)).map { it.second }
        val espn = urls.filter { "espn" in it }
        assertEquals(16, espn.size, "8 jours × 2 ligues")
        assertTrue(espn.none { Regex("dates=\\d{8}-").containsMatchIn(it) }, "jamais de plage de dates")
        assertTrue(espn.any { it.endsWith("basketball/nba/scoreboard?dates=20261116") }, "aujourd'hui (heure locale)")
        assertTrue(espn.any { it.endsWith("football/nfl/scoreboard?dates=20261123") }, "jusqu'au 8e jour")
    }

    @Test fun matchs_nba_et_nfl_lus_et_cache_elague() = runBlocking {
        val saved = mutableMapOf<String, String>()
        var kept = emptySet<String>()
        val store = object : SportStore {
            override fun cached(key: String) = saved[key]
            override fun saveCached(key: String, text: String, at: Long) { synchronized(saved) { saved[key] = text } }
            override fun cachedAt() = 0L
            override fun learned(profileId: String, league: League) = emptyList<String>()
            override fun saveLearned(profileId: String, league: League, groups: List<String>) {}
            override fun prune(keep: Set<String>) { kept = keep }
        }
        val nba = """{"events":[{"id":"401","date":"2026-11-17T00:30Z","status":{"type":{"state":"pre"}},
            "competitions":[{"competitors":[{"homeAway":"home","team":{"id":"14","displayName":"Miami Heat"}},{"homeAway":"away","team":{"id":"10","displayName":"Houston Rockets"}}]}]}]}"""
        val nfl = """{"events":[{"id":"501","date":"2026-11-17T01:15Z","status":{"type":{"state":"pre"}},
            "competitions":[{"competitors":[{"homeAway":"home","team":{"id":"33","displayName":"Baltimore Ravens"}},{"homeAway":"away","team":{"id":"24","displayName":"Los Angeles Chargers"}}]}]}]}"""
        val result = SportSchedule({ url ->
            when {
                Regex("dates=\\d{8}-").containsMatchIn(url) -> error("400")
                url.endsWith("nba/scoreboard?dates=20261116") -> nba
                url.endsWith("nfl/scoreboard?dates=20261116") -> nfl
                "espn" in url -> """{"events":[]}"""
                else -> """{"gameWeek":[]}"""
            }
        }, store) { now }.load(montreal, listOf(League.NHL, League.NBA, League.NFL))
        assertEquals(setOf("Miami Heat", "Baltimore Ravens"), result.matches.map { it.home }.toSet())
        assertFalse(result.offline)
        assertEquals(saved.keys, kept, "seules les réponses encore demandées sont gardées")
    }
}
