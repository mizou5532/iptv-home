package home.iptv.tv.sport

import home.iptv.shared.sport.*
import home.iptv.shared.sync.Profile
import home.iptv.shared.sync.ProfileSettings
import home.iptv.shared.sync.SportTeam
import home.iptv.shared.sync.SportView
import home.iptv.shared.sync.SPORT_LAYOUT_GRID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** 0.39.0 : cas limites côté télé (serveur muet ou lent, classements, rangées, scores cachés, préparation). */
@OptIn(ExperimentalCoroutinesApi::class)
class SportNouveautesMoreTest {
    private val now = Instant.parse("2026-11-17T00:45:00Z") // lundi 16 nov., 19 h 45 à Montréal
    private val montreal = -5 * 3600
    private val mtl = SportTeam("nhl", "10", "Canadiens", "MTL")

    private class MemStore(var nowMs: () -> Long = { 0L }) : SportStore {
        val saved = java.util.concurrent.ConcurrentHashMap<String, String>()
        val at = java.util.concurrent.ConcurrentHashMap<String, Long>()
        override fun cached(key: String) = saved[key]
        override fun saveCached(key: String, text: String, at: Long) { saved[key] = text; this.at[key] = at }
        override fun cachedAt() = 0L
        override fun savedAt(key: String) = at[key]
        override fun learned(profileId: String, league: League) = emptyList<String>()
        override fun saveLearned(profileId: String, league: League, groups: List<String>) {}
    }

    private val nhlStandings = """{"standings":[{"teamAbbrev":{"default":"MTL"},"divisionName":"Atlantic","divisionSequence":3,"points":28}]}"""
    private val ligaStandings = """{"children":[{"standings":{"entries":[{"team":{"id":"83"},"stats":[{"name":"rank","displayValue":"1"},{"name":"points","displayValue":"33"}]}]}}]}"""

    // ---- Serveur de la maison muet, lent ou bizarre ----

    @Test fun serveur_lent_la_tele_demande_elle_meme_apres_le_delai() = runTest {
        val direct = mutableListOf<String>()
        val schedule = SportSchedule({ url -> direct += url; """{"gameWeek":[]}""" }, MemStore(), batch = { awaitCancellation() }) { now }
        val r = schedule.loadToday(montreal, listOf(League.NHL))
        assertTrue(direct.isNotEmpty(), "repli après $RELAY_TIMEOUT_MS ms")
        assertTrue(r.any)
        assertTrue(testScheduler.currentTime >= RELAY_TIMEOUT_MS)
    }

    @Test fun serveur_en_erreur_ou_reponse_illisible_la_tele_demande_elle_meme() = runTest {
        for (batch in listOf<suspend (List<String>) -> Map<String, String>?>(
            { throw java.io.IOException("panne") },
            { urls -> urls.associateWith { "<html>erreur</html>" } },
            { emptyMap() },
        )) {
            val direct = mutableListOf<String>()
            val r = SportSchedule({ url -> direct += url; """{"gameWeek":[]}""" }, MemStore(), batch = batch) { now }.loadToday(montreal, listOf(League.NHL))
            assertTrue(direct.any { "nhle" in it }, "repli")
            assertFalse(r.offline)
        }
    }

    @Test fun rien_a_demander_le_serveur_n_est_pas_appele() = runTest {
        var calls = 0
        val schedule = SportSchedule({ """{"gameWeek":[]}""" }, MemStore(), batch = { calls++; emptyMap() }) { now }
        schedule.cached(montreal, listOf(League.NHL))
        assertEquals(0, calls, "mémoire seulement : aucun réseau")
    }

    // ---- Classements ----

    @Test fun classements_par_le_serveur_gardes_6_h_sans_les_coupes() = runTest {
        var t = now
        val store = MemStore()
        val direct = mutableListOf<String>()
        val relayed = mutableListOf<List<String>>()
        val schedule = SportSchedule({ url -> direct += url; ligaStandings }, store,
            batch = { urls -> relayed += urls; urls.filter { "nhle" in it }.associateWith { nhlStandings } }) { t }
        val got = schedule.standings(listOf(League.NHL, League.LIGA, League.UCL, League.COPA))
        assertEquals("3e Atlantique · 28 pts", got["nhl:MTL"])
        assertEquals("1er de la Liga · 33 pts", got["soccer:83"])
        assertEquals(listOf(listOf(NHL_STANDINGS_URL, standingsUrl(League.LIGA)!!)), relayed, "jamais les coupes")
        assertEquals(listOf(standingsUrl(League.LIGA)!!), direct, "repli pour ce que le serveur n'a pas")
        // Moins de 6 h après : rien n'est redemandé, la mémoire suffit.
        t = now + 5.hours
        assertEquals(got, schedule.standings(listOf(League.NHL, League.LIGA)))
        assertEquals(1, relayed.size)
        // Plus de 6 h : redemandé.
        t = now + 7.hours
        schedule.standings(listOf(League.NHL))
        assertEquals(2, relayed.size)
        // Sans réseau : la mémoire seulement.
        assertEquals("3e Atlantique · 28 pts", schedule.standings(listOf(League.NHL), network = false)["nhl:MTL"])
        assertTrue(schedule.standings(emptyList()).isEmpty())
    }

    @Test fun classement_illisible_jamais_garde() = runTest {
        val store = MemStore()
        val schedule = SportSchedule({ "<html>panne</html>" }, store) { now }
        assertTrue(schedule.standings(listOf(League.NHL)).isEmpty())
        assertTrue(store.saved.isEmpty())
    }

    @Test fun les_classements_ne_sont_jamais_effaces_avec_les_vieux_horaires() = runTest {
        var kept = emptySet<String>()
        val store = object : SportStore by MemStore() { override fun prune(keep: Set<String>) { kept = keep } }
        SportSchedule({ """{"gameWeek":[]}""" }, store) { now }.load(montreal, listOf(League.NHL))
        assertTrue(NHL_STANDINGS_URL.substringAfter("://") in kept)
        assertTrue(STANDINGS_KEYS.isNotEmpty())
    }

    // ---- Modèle de l'écran ----

    private val nhlWeek = """{"gameWeek":[{"games":[
        {"id":1,"startTimeUTC":"2026-11-17T00:30:00Z","gameState":"LIVE","periodDescriptor":{"number":3},"awayTeam":{"abbrev":"MTL","score":2},"homeTeam":{"abbrev":"BOS","score":1}},
        {"id":5,"startTimeUTC":"2026-11-16T23:00:00Z","gameState":"OFF","awayTeam":{"abbrev":"NYR","score":2},"homeTeam":{"abbrev":"NJD","score":1}},
        {"id":2,"startTimeUTC":"2026-11-17T03:00:00Z","gameState":"FUT","awayTeam":{"abbrev":"EDM"},"homeTeam":{"abbrev":"SEA"}},
        {"id":6,"startTimeUTC":"2026-11-18T00:00:00Z","gameState":"FUT","awayTeam":{"abbrev":"CHI"},"homeTeam":{"abbrev":"DAL"}},
        {"id":3,"startTimeUTC":"2026-11-19T00:00:00Z","gameState":"FUT","awayTeam":{"abbrev":"TOR"},"homeTeam":{"abbrev":"MTL"}}]}]}"""

    private fun withVm(view: SportView, block: suspend (SportViewModel) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = MemStore()
            val vm = SportViewModel("p1", SportSchedule({ url -> when { "standings" in url -> nhlStandings; "nhle" in url -> nhlWeek; else -> """{"events":[]}""" } }, store) { now },
                { listOf(SportChannel("g1", "RDS HD", "CA| SPORTS")) }, { emptyList() }, store, kotlinx.coroutines.flow.MutableStateFlow(null), scope,
                Dispatchers.Default, { now }, { montreal }, teams = { listOf(mtl) }, view = { view })
            vm.start()
            block(vm)
        } finally { scope.cancel() }
    }

    @Test fun grille_rangees_demain_et_semaine_avec_leurs_chaines_terminees_retenues() = withVm(SportView(layout = SPORT_LAYOUT_GRID)) { vm ->
        val ui = withTimeout(5_000) { vm.ui.first { !it.loading && !it.updating && it.rows.size >= 3 } }
        assertEquals(listOf("En direct maintenant", "Ce soir", "Demain", "Cette semaine pour mes équipes"), ui.rows.map { it.title })
        assertEquals(listOf("nhl:6"), ui.rows[2].matches.map { it.id })
        assertEquals(listOf("nhl:3"), ui.rows[3].matches.map { it.id })
        for (m in ui.rows.flatMap { it.matches }) assertNotNull(ui.candidates[m.id], "chaînes calculées pour ${m.id}")
        assertTrue("nhl:5" in ui.finished, "match terminé retenu")
        assertTrue(ui.strip.none { it.id == "nhl:6" }, "bande : aujourd'hui seulement")
        val st = withTimeout(5_000) { vm.ui.first { it.standings.isNotEmpty() } }
        assertEquals("3e Atlantique · 28 pts", st.standings["nhl:MTL"])
    }

    @Test fun affiche_sans_rangees() = withVm(SportView()) { vm ->
        val ui = withTimeout(5_000) { vm.ui.first { !it.loading && !it.updating && it.strip.isNotEmpty() } }
        assertTrue(ui.rows.isEmpty(), "rangées seulement pour la grille")
    }

    @Test fun sans_resultat_tout_est_cache_partout_et_deux_info_recachent() = withVm(SportView(layout = SPORT_LAYOUT_GRID, hideScores = true)) { vm ->
        val ui = withTimeout(5_000) { vm.ui.first { !it.loading && !it.updating && it.rows.isNotEmpty() } }
        assertTrue((ui.strip + ui.rows.flatMap { it.matches }).all { it.homeScore == null && it.awayScore == null })
        delay(300)
        assertTrue(vm.ui.value.standings.isEmpty(), "classements cachés")
        vm.reveal("nhl:1")
        val shown = withTimeout(5_000) { vm.ui.first { "nhl:1" in it.revealed } }
        assertEquals(2, shown.strip.first { it.id == "nhl:1" }.awayScore)
        assertEquals(2, shown.rows.flatMap { it.matches }.first { it.id == "nhl:1" }.awayScore, "dévoilé aussi dans la grille")
        assertTrue(shown.strip.filter { it.id != "nhl:1" }.all { it.homeScore == null }, "les autres restent cachés")
        vm.reveal("nhl:1")
        val hidden = withTimeout(5_000) { vm.ui.first { "nhl:1" !in it.revealed } }
        assertNull(hidden.strip.first { it.id == "nhl:1" }.awayScore)
    }

    // ---- Fin de match ----

    private fun g(id: String, live: Boolean, start: Instant, home: Int? = null, away: Int? = null, period: Int? = null, abbr: String = "", league: League = League.NBA) =
        Match(id, league, start, if (live) MatchState.LIVE else MatchState.UPCOMING, "H$id", "A$id", false, homeAbbr = abbr, awayAbbr = "X$id",
            homeId = "h$id", awayId = "a$id", homeScore = home, awayScore = away, period = period)
    private val ch = SportChannel("c", "RDS", "CA").let { SportCandidates(listOf(it), listOf(it)) }

    @Test fun match_suivant_ordre_et_cas_vides() {
        val early = g("early", true, now - 2.hours)
        val close = g("close", true, now - 1.hours, 100, 99, 4)
        val ui = SportUi(board = SportBoard(emptyList(), emptyList()), strip = listOf(early, close), candidates = mapOf("early" to ch, "close" to ch))
        assertEquals("close", nextLiveMatch(ui, "fini")?.id, "match serré avant le plus avancé")
        assertEquals("early", nextLiveMatch(ui.copy(strip = listOf(early, close.withoutScore())), "fini")?.id, "scores cachés : le plus avancé")
        assertEquals("early", nextLiveMatch(ui, "close")?.id, "jamais le match qui vient de finir")
        assertNull(nextLiveMatch(ui.copy(strip = listOf(g("later", false, now + 1.hours))), "fini"), "rien en direct")
        assertNull(nextLiveMatch(ui.copy(candidates = emptyMap()), "fini"), "aucune chaîne")
        assertNull(nextLiveMatch(SportUi(), "fini"))
    }

    @Test fun texte_de_la_proposition() {
        val m = g("x", true, now, 1, 2, 3).copy(detail = "3e pér.", awayShort = "Oilers", homeShort = "Kraken")
        assertEquals("Match terminé. Oilers – Kraken est en direct (2-1, 3e pér.) : le regarder ?", nextMatchText(m))
        assertEquals("Match terminé. Oilers – Kraken est en direct (3e pér.) : le regarder ?", nextMatchText(m.withoutScore()))
        assertEquals("Match terminé. Oilers – Kraken est en direct : le regarder ?", nextMatchText(m.withoutScore().copy(detail = null)))
    }

    // ---- Ligne d'état de l'affiche ----

    @Test fun ligne_d_etat_de_l_affiche() {
        // Heures écrites dans le fuseau de la machine (UTC pour les tests) ; le compte à rebours n'en dépend pas.
        val off = { t: Instant -> java.util.TimeZone.getDefault().getOffset(t.toEpochMilliseconds()) / 1000 }
        val tonight = Instant.parse("2026-11-17T03:00:00Z")
        assertEquals("LNH", heroWhen(g("a", true, now, league = League.NHL), now))
        assertEquals("Terminé · NBA", heroWhen(g("b", false, now).copy(state = MatchState.FINAL), now))
        assertEquals("Dans 2 h 15 · ${matchWhen(g("c", false, tonight), today = true)}", heroWhen(g("c", false, tonight), now))
        assertTrue(heroWhen(g("c", false, tonight), now).endsWith(" · NBA"))
        assertEquals("Ça commence ! · NBA", heroWhen(g("d", false, now - 5.minutes), now))
        val wed = Instant.parse("2026-11-19T00:00:00Z")
        assertEquals("${countdownLabel(wed, now, off(wed))} · NBA", heroWhen(g("e", false, wed), now))
    }

    // ---- Préparation dès l'allumage ----

    private fun profile(id: String, home: String, sections: List<String>? = null) =
        Profile(id, id, "#8FB4FF", home, "sombre", 0, settings = ProfileSettings(sections = sections))

    @Test fun preparation_pour_tous_les_profils_avec_la_section_sport_plus_tard_si_aucun_ne_s_ouvre_dessus() = runTest {
        val asked = mutableListOf<String>()
        val prep = SportPrep(MemStore(), { url -> asked += url; if ("nhle" in url) """{"gameWeek":[]}""" else """{"events":[]}""" },
            NoSportSources, { now }, { montreal })
        val job = prep.prewarm(listOf(profile("a", "direct"), profile("b", "series", listOf("series"))), this, StandardTestDispatcher(testScheduler))
        assertNotNull(job, "le profil « a » a toutes les sections, donc le Sport")
        advanceTimeBy(PREWARM_LATER_MS - 100); runCurrent()
        assertTrue(asked.isEmpty(), "attend un peu : le reste de l'écran d'abord")
        advanceTimeBy(200); runCurrent(); advanceUntilIdle()
        assertTrue(asked.isNotEmpty())
        assertTrue(asked.any { "standings" in it }, "classements préparés aussi")
        assertNull(prep.prewarm(listOf(profile("a", "direct")), this), "au plus une fois toutes les 10 min")
    }

    @Test fun preparation_tout_de_suite_si_un_profil_s_ouvre_sur_sport() = runTest {
        val asked = mutableListOf<String>()
        val prep = SportPrep(MemStore(), { url -> asked += url; if ("nhle" in url) """{"gameWeek":[]}""" else """{"events":[]}""" },
            NoSportSources, { now }, { montreal })
        prep.prewarm(listOf(profile("a", "sport")), this, StandardTestDispatcher(testScheduler))
        runCurrent()
        assertTrue(asked.isNotEmpty(), "sans attendre")
        advanceUntilIdle()
    }
}
