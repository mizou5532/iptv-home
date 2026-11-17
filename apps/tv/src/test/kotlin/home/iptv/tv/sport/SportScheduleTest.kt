package home.iptv.tv.sport

import home.iptv.shared.sport.*
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

    private fun memoryStore(saved: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()) = object : SportStore {
        override fun cached(key: String) = saved[key]
        override fun saveCached(key: String, text: String, at: Long) { saved[key] = text }
        override fun cachedAt() = 0L
        override fun learned(profileId: String, league: League) = emptyList<String>()
        override fun saveLearned(profileId: String, league: League, groups: List<String>) {}
        private val stars = java.util.concurrent.ConcurrentHashMap<String, String>()
        override fun preferred(profileId: String, teamKey: String) = stars[teamKey]
        override fun savePreferred(profileId: String, teamKey: String, groupId: String?) { if (groupId == null) stars.remove(teamKey) else stars[teamKey] = groupId }
    }

    @Test fun aujourd_hui_d_abord_puis_les_jours_suivants() = runBlocking {
        val asked = java.util.Collections.synchronizedList(mutableListOf<String>())
        val schedule = SportSchedule({ url -> asked += url; """{"events":[]}""" }, memoryStore()) { now }
        val leagues = listOf(League.NHL, League.NFL, League.NBA)
        val all = scheduleSources(now, montreal, leagues)
        val today = schedule.loadToday(montreal, leagues)
        // Une demande par ligue : la semaine LNH en cours et le tableau ESPN du jour.
        assertEquals(all.filter { it.day == 0 }.map { it.url }.toSet(), asked.toSet())
        assertEquals(3, today.fetched)
        asked.clear()
        val later = schedule.loadLater(montreal, leagues)
        assertEquals(all.filter { it.day > 0 }.map { it.url }.toSet(), asked.toSet())
        assertEquals(all.size - 3, later.fetched)
        assertTrue(!later.offline && later.any)
    }

    private val nhlLive = """{"gameWeek":[{"games":[{"id":1,"startTimeUTC":"2026-11-17T00:30:00Z","gameState":"LIVE",
        "awayTeam":{"abbrev":"MTL","commonName":{"default":"Canadiens"}},"homeTeam":{"abbrev":"BOS","commonName":{"default":"Bruins"}}}]}]}"""

    @Test fun horaires_affiches_sans_attendre_les_chaines_du_fournisseur() = runBlocking {
        // Les catégories Sport du fournisseur ne répondent jamais : les horaires frais s'affichent quand même.
        val sources = object : SportSources { override suspend fun extraChannels(): List<SportChannel> = kotlinx.coroutines.awaitCancellation() }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val store = memoryStore()
            val vm = SportViewModel("p1", SportSchedule({ url -> if ("nhle" in url) nhlLive else """{"events":[]}""" }, store) { now },
                { emptyList() }, { emptyList() }, store, kotlinx.coroutines.flow.MutableStateFlow(null), scope,
                kotlinx.coroutines.Dispatchers.Default, { now }, { montreal }, sources = sources)
            vm.start()
            val ui = kotlinx.coroutines.withTimeout(5_000) { vm.ui.first { !it.loading && !it.updating } }
            assertEquals("nhl:1", ui.strip.first().id)
            assertEquals(null, ui.notice)
        } finally { scope.cancel() }
    }

    @Test fun chaines_d_un_match_gardees_tant_que_rien_ne_change() = runBlocking {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val store = memoryStore()
            val channels = listOf(SportChannel("g1", "RDS HD", "CA| SPORTS"), SportChannel("g2", "TSN 1", "CA| SPORTS"))
            val vm = SportViewModel("p1", SportSchedule({ url -> if ("nhle" in url) nhlLive else """{"events":[]}""" }, store) { now },
                { channels }, { emptyList() }, store, kotlinx.coroutines.flow.MutableStateFlow(null), scope,
                kotlinx.coroutines.Dispatchers.Default, { now }, { montreal })
            vm.start()
            val ui = kotlinx.coroutines.withTimeout(5_000) { vm.ui.first { !it.loading && !it.updating } }
            val first = ui.candidates["nhl:1"]!!
            // Même match, mêmes chaînes : l'étoile d'un AUTRE réglage relance l'écran, le calcul n'est pas refait.
            vm.refresh()
            kotlinx.coroutines.withTimeout(5_000) { while (vm.ui.value.updating || vm.lastComputed != 0) kotlinx.coroutines.delay(20) }
            assertTrue(vm.ui.value.candidates["nhl:1"] === first, "résultat gardé, pas recalculé")
            // L'étoile change la réponse : ce match est recalculé.
            vm.togglePreferred(ui.strip.first(), "g2")
            val starred = kotlinx.coroutines.withTimeout(5_000) { vm.ui.first { it.candidates["nhl:1"]?.proposed?.firstOrNull()?.groupId == "g2" } }
            assertEquals(ChannelReason.PREFERRED, starred.candidates["nhl:1"]!!.reasons["g2"])
        } finally { scope.cancel() }
    }

    @Test fun durees_notees_par_plusieurs_taches_a_la_fois() = runBlocking {
        // Horaires, chaînes du fournisseur et calcul notent leurs durées en même temps : jamais d'exception.
        val failures = java.util.concurrent.atomic.AtomicInteger(0)
        val handler = kotlinx.coroutines.CoroutineExceptionHandler { _, _ -> failures.incrementAndGet() }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default + handler)
        try {
            val store = memoryStore()
            val channels = (0 until 300).map { SportChannel("g$it", "TSN $it", "CA| SPORTS") }
            val vm = SportViewModel("p1", SportSchedule({ url -> if ("nhle" in url) nhlLive else """{"events":[]}""" }, store) { now },
                { channels }, { emptyList() }, store, kotlinx.coroutines.flow.MutableStateFlow(null), scope,
                kotlinx.coroutines.Dispatchers.Default, { now }, { montreal })
            vm.start()
            val ui = kotlinx.coroutines.withTimeout(5_000) { vm.ui.first { !it.loading && !it.updating } }
            repeat(200) { i -> vm.togglePreferred(ui.strip.first(), "g${i % 3}"); vm.refresh() }
            kotlinx.coroutines.delay(1_500)
            assertEquals(0, failures.get())
        } finally { scope.cancel() }
    }
}
