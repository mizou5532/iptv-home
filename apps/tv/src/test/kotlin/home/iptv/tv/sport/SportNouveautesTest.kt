package home.iptv.tv.sport

import home.iptv.shared.sport.*
import home.iptv.shared.sync.SportTeam
import home.iptv.shared.sync.SportView
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** 0.39.0 : serveur de la maison, scores cachés, rangées de la grille, match suivant proposé. */
class SportNouveautesTest {
    private val now = Instant.parse("2026-11-17T00:45:00Z") // lundi 16 nov., 19 h 45 à Montréal
    private val montreal = -5 * 3600
    private val mtl = SportTeam("nhl", "10", "Canadiens", "MTL")

    private fun store(saved: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()) = object : SportStore {
        override fun cached(key: String) = saved[key]
        override fun saveCached(key: String, text: String, at: Long) { saved[key] = text }
        override fun cachedAt() = 0L
        override fun learned(profileId: String, league: League) = emptyList<String>()
        override fun saveLearned(profileId: String, league: League, groups: List<String>) {}
    }

    private val nhlLive = """{"gameWeek":[{"games":[{"id":1,"startTimeUTC":"2026-11-17T00:30:00Z","gameState":"LIVE","periodDescriptor":{"number":3},
        "awayTeam":{"abbrev":"MTL","commonName":{"default":"Canadiens"},"score":2},"homeTeam":{"abbrev":"BOS","commonName":{"default":"Bruins"},"score":1}}]}]}"""

    @Test fun horaires_par_le_serveur_de_la_maison_et_repli_sur_la_tele() = runBlocking {
        val direct = java.util.Collections.synchronizedList(mutableListOf<String>())
        val relayed = mutableListOf<List<String>>()
        // Le serveur répond pour la LNH seulement : ESPN est demandé par la télé elle-même.
        val schedule = SportSchedule({ url -> direct += url; """{"events":[]}""" }, store(),
            batch = { urls -> relayed += urls; urls.filter { "nhle" in it }.associateWith { nhlLive } }) { now }
        val r = schedule.loadToday(montreal, listOf(League.NHL, League.NBA))
        assertEquals(1, relayed.size, "une seule demande au serveur")
        assertTrue(direct.none { "nhle" in it }, "la LNH vient du serveur")
        assertTrue(direct.any { "nba" in it }, "repli : la télé demande ce que le serveur n'a pas rendu")
        assertEquals("nhl:1", r.matches.single().id)
        assertEquals(3, r.matches.single().period)
        // Serveur muet (null) : tout comme avant.
        val direct2 = java.util.Collections.synchronizedList(mutableListOf<String>())
        SportSchedule({ url -> direct2 += url; if ("nhle" in url) nhlLive else """{"events":[]}""" }, store(), batch = { null }) { now }
            .loadToday(montreal, listOf(League.NHL))
        assertTrue(direct2.any { "nhle" in it })
    }

    @Test fun reponse_du_serveur() {
        assertEquals(mapOf("https://a" to "{}"), parseRelayAnswer("""{"bodies":{"https://a":"{}","b":3},"fetched":1}"""))
        assertTrue(parseRelayAnswer("pas du json").isEmpty())
    }

    @Test fun sans_resultat_scores_et_classements_caches_puis_devoiles() = runBlocking {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val st = store()
            val vm = SportViewModel("p1", SportSchedule({ url -> if ("nhle" in url) nhlLive else """{"events":[]}""" }, st) { now },
                { emptyList() }, { emptyList() }, st, kotlinx.coroutines.flow.MutableStateFlow(null), scope,
                kotlinx.coroutines.Dispatchers.Default, { now }, { montreal }, teams = { listOf(mtl) }, view = { SportView(hideScores = true) })
            vm.start()
            val ui = kotlinx.coroutines.withTimeout(5_000) { vm.ui.first { !it.loading && !it.updating && it.strip.isNotEmpty() } }
            val m = ui.strip.first()
            assertNull(m.homeScore); assertNull(m.awayScore)
            assertTrue(ui.hideScores && ui.standings.isEmpty())
            vm.reveal(m.id)
            val shown = kotlinx.coroutines.withTimeout(5_000) { vm.ui.first { m.id in it.revealed } }
            assertEquals(1, shown.strip.first().homeScore)
        } finally { scope.cancel() }
    }

    private fun g(id: String, start: Instant, live: Boolean = false, abbr: String = "", league: League = League.NBA) =
        Match(id, league, start, if (live) MatchState.LIVE else MatchState.UPCOMING, "H$id", "A$id", false, homeAbbr = abbr, awayAbbr = "X$id", homeId = "h$id", awayId = "a$id")

    @Test fun rangees_de_la_grille() {
        val matches = listOf(
            g("live", now - 1.hours, live = true),
            g("soir", now + 1.hours),
            g("demain", now + 1.days),
            g("mtl", now + 3.days, abbr = "MTL", league = League.NHL),
        )
        val board = buildBoard(matches, now, montreal, listOf(mtl))
        val strip = stripFor(board, now, montreal, SportView())
        val rows = sportRows(strip, board, matches, now, montreal, SportView(), listOf(mtl))
        assertEquals(listOf("En direct maintenant" to listOf("live"), "Ce soir" to listOf("soir"), "Demain" to listOf("demain"),
            "Cette semaine pour mes équipes" to listOf("mtl")), rows.map { it.title to it.matches.map { m -> m.id } })
        assertTrue(strip.none { it.id == "demain" }, "la bande de l'affiche reste limitée à aujourd'hui")
    }

    @Test fun match_suivant_propose_a_la_fin() {
        val a = g("a", now - 1.hours, live = true)
        val mine = g("mtl", now - 30.minutes, live = true, abbr = "MTL", league = League.NHL)
        val cands = SportChannel("c", "RDS", "CA").let { SportCandidates(listOf(it), listOf(it)) }
        val board = buildBoard(listOf(a, mine), now, montreal, listOf(mtl))
        val ui = SportUi(board = board, strip = listOf(a, mine), candidates = mapOf("a" to cands, "mtl" to cands))
        assertEquals("mtl", nextLiveMatch(ui, "fini")?.id, "mes équipes d'abord")
        assertEquals("a", nextLiveMatch(ui.copy(candidates = mapOf("a" to cands)), "fini")?.id, "seulement un match qu'on peut regarder")
        assertEquals("Match terminé. Aa – Ha est en direct : le regarder ?", nextMatchText(a.copy(awayAbbr = "Xa", homeAbbr = "Ha")))
    }

}
