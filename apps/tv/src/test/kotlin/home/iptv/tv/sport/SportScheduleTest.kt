package home.iptv.tv.sport

import home.iptv.shared.sport.League
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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
        val urls = scheduleSources(now, montreal, listOf(League.NHL, League.NBA, League.NFL)).map { it.url }
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

    @Test fun semaine_complete_seulement_pour_les_ligues_des_favorites() {
        val steelers = home.iptv.shared.sync.SportTeam("nfl", "23", "Steelers", "PIT")
        val barca = home.iptv.shared.sync.SportTeam("esp.1", "83", "FC Barcelone", "BAR")
        val src = scheduleSources(now, montreal, listOf(League.NHL, League.NBA, League.NFL, League.LIGA, League.UCL), fullWeekFor(listOf(steelers, barca)))
        fun count(l: League) = src.count { it.league == l }
        assertEquals(1, count(League.NBA), "NBA sans favorite : aujourd'hui seulement")
        assertEquals(8, count(League.NFL)); assertEquals(8, count(League.LIGA)); assertEquals(8, count(League.UCL), "Barça : tout le soccer")
        assertEquals(2, count(League.NHL))
    }

    @Test fun jours_suivants_recents_pas_relus_aujourd_hui_toujours() = runBlocking {
        val asked = java.util.Collections.synchronizedList(mutableListOf<String>())
        val saved = mutableMapOf<String, String>()
        val nowMs = now.toEpochMilliseconds()
        val store = object : SportStore {
            override fun cached(key: String) = saved[key] ?: """{"events":[]}"""
            override fun saveCached(key: String, text: String, at: Long) { synchronized(saved) { saved[key] = text } }
            override fun cachedAt() = 0L
            override fun learned(profileId: String, league: League) = emptyList<String>()
            override fun saveLearned(profileId: String, league: League, groups: List<String>) {}
            override fun savedAt(key: String) = nowMs - 60_000 // tout a été lu il y a 1 min
        }
        val schedule = SportSchedule({ url -> asked += url; if ("nhle" in url) """{"gameWeek":[]}""" else """{"events":[]}""" }, store) { now }
        schedule.load(montreal, listOf(League.NHL, League.NFL))
        assertEquals(2, asked.size, "LNH de la semaine + NFL d'aujourd'hui seulement : $asked")
        asked.clear()
        val r = schedule.cached(montreal, listOf(League.NHL, League.NFL))
        assertTrue(asked.isEmpty(), "la mémoire n'utilise jamais le réseau"); assertTrue(r.any)
    }

    @Test fun ouverture_affiche_la_memoire_pendant_que_le_reseau_tarde() = runBlocking {
        val nhl = """{"gameWeek":[{"games":[{"id":1,"startTimeUTC":"2026-11-17T00:30:00Z","gameState":"LIVE",
            "awayTeam":{"abbrev":"MTL","commonName":{"default":"Canadiens"}},"homeTeam":{"abbrev":"BOS","commonName":{"default":"Bruins"}}}]}]}"""
        val store = object : SportStore {
            override fun cached(key: String) = if ("nhle" in key) nhl else """{"events":[]}"""
            override fun saveCached(key: String, text: String, at: Long) {}
            override fun cachedAt() = 0L // vieux : le réseau est demandé
            override fun learned(profileId: String, league: League) = emptyList<String>()
            override fun saveLearned(profileId: String, league: League, groups: List<String>) {}
        }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val vm = SportViewModel("p1", SportSchedule({ kotlinx.coroutines.awaitCancellation() }, store) { now },
                { emptyList() }, { emptyList() }, store, kotlinx.coroutines.flow.MutableStateFlow(null), scope,
                kotlinx.coroutines.Dispatchers.Default, { now }, { montreal })
            vm.start()
            val ui = kotlinx.coroutines.withTimeout(5_000) { vm.ui.first { !it.loading } }
            assertTrue(ui.updating, "mise à jour en cours, réseau pas encore répondu")
            assertEquals("Canadiens – Bruins".let { "Canadiens" }, ui.strip.first().away.substringAfterLast(' '))
        } finally { scope.cancel() }
    }
}
