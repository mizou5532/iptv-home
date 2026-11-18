package home.iptv.shared.sport

import home.iptv.shared.sync.SportTeam
import home.iptv.shared.sync.SportView
import kotlinx.serialization.json.Json
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** 0.39.0 : cas limites de l'affiche automatique, du compte à rebours, des classements et des scores cachés. */
class SportBannerMoreTest {
    private val montreal = -5 * 3600
    private val now = Instant.parse("2026-11-17T01:00:00Z")
    private val mtl = SportTeam("nhl", "10", "Canadiens", "MTL")
    private val barca = SportTeam("esp.1", "83", "FC Barcelone", "BAR")

    private fun m(id: String, league: League, state: MatchState = MatchState.UPCOMING, home: Int? = null, away: Int? = null, period: Int? = null,
                  detail: String? = null, abbr: String = "", homeId: String = "h$id", start: Instant = now - 1.hours) =
        Match(id, league, start, state, "H$id", "A$id", false, homeAbbr = abbr, awayAbbr = "X$id", homeId = homeId, awayId = "a$id",
            homeScore = home, awayScore = away, period = period, detail = detail)
    private fun live(id: String, league: League, home: Int?, away: Int?, period: Int? = null, detail: String? = null, abbr: String = "", start: Instant = now - 1.hours) =
        m(id, league, MatchState.LIVE, home, away, period, detail, abbr, start = start)

    // ---- Match serré en fin de partie ----

    @Test fun serre_hockey_prolongation_et_tirs() {
        assertTrue(isCloseLate(live("a", League.NHL, 3, 3, period = 4)), "prolongation, égalité")
        assertTrue(isCloseLate(live("b", League.NHL, 0, 0, period = 5)), "tirs au but")
        assertTrue(isCloseLate(live("c", League.NHL, 4, 3, period = 3)))
        assertFalse(isCloseLate(live("d", League.NHL, 4, 3, period = null)), "période inconnue : jamais")
        assertFalse(isCloseLate(live("e", League.NHL, null, 3, period = 3)), "score incomplet : jamais")
    }

    @Test fun serre_football_et_basket() {
        assertTrue(isCloseLate(live("a", League.NFL, 21, 14, period = 4)), "7 points au 4e quart")
        assertTrue(isCloseLate(live("b", League.NFL, 20, 12, period = 4)), "8 points : encore serré")
        assertFalse(isCloseLate(live("c", League.NFL, 21, 12, period = 4)), "9 points : non")
        assertFalse(isCloseLate(live("d", League.NFL, 21, 21, period = 3)), "3e quart : pas encore")
        assertTrue(isCloseLate(live("e", League.NBA, 110, 110, period = 5)), "prolongation")
    }

    @Test fun serre_soccer_toutes_competitions() {
        for (l in listOf(League.LIGA, League.UCL, League.EPL, League.MLS)) {
            assertTrue(isCloseLate(live("a$l", l, 1, 1, detail = "70'")), "$l 70e")
            assertFalse(isCloseLate(live("b$l", l, 1, 1, detail = "69'")), "$l 69e")
            assertFalse(isCloseLate(live("c$l", l, 3, 1, detail = "85'")), "$l 2 buts")
        }
        assertNull(soccerMinute(null)); assertNull(soccerMinute("Mi-temps")); assertNull(soccerMinute(""))
        assertEquals(45, soccerMinute("45'+2'")); assertEquals(7, soccerMinute(" 7' "))
        assertFalse(isCloseLate(m("t", League.LIGA, MatchState.FINAL, 1, 1, detail = "90'")), "terminé : jamais")
    }

    // ---- Affiche automatique ----

    private fun headOf(matches: List<Match>, view: SportView? = null, teams: List<SportTeam> = listOf(mtl)) =
        stripFor(buildBoard(matches, now, montreal, teams), now, montreal, view).firstOrNull()?.id

    @Test fun deux_de_mes_equipes_en_direct_l_ordre_de_mes_favoris_decide() {
        val bar = m("bar", League.LIGA, MatchState.LIVE, 0, 0, detail = "20'", homeId = "83")
        val mtlLive = live("mtl", League.NHL, 1, 0, period = 1, abbr = "MTL")
        assertEquals("mtl", headOf(listOf(bar, mtlLive), teams = listOf(mtl, barca)))
        assertEquals("bar", headOf(listOf(bar, mtlLive), teams = listOf(barca, mtl)))
    }

    @Test fun plusieurs_matchs_serres_le_plus_ancien_d_abord() {
        val a = live("a", League.NBA, 100, 99, period = 4, start = now - 2.hours)
        val b = live("b", League.NHL, 2, 2, period = 3, start = now - 3.hours)
        assertEquals("b", headOf(listOf(a, b)))
    }

    @Test fun sans_resultat_un_match_serre_n_est_jamais_mis_en_avant() {
        val next = m("mtl", League.NHL, abbr = "MTL", start = now + 3.hours)
        val close = live("close", League.NBA, 100, 98, period = 4)
        assertEquals("close", headOf(listOf(next, close)))
        assertEquals("mtl", headOf(listOf(next, close), SportView(hideScores = true)))
    }

    @Test fun rien_de_mes_equipes_ni_direct_le_premier_de_la_journee() {
        val a = m("a", League.NBA, start = now + 1.hours)
        val b = m("b", League.NFL, start = now + 2.hours)
        assertNotNull(headOf(listOf(a, b)))
        assertNull(headOf(emptyList()))
    }

    @Test fun reglages_inconnus_valent_automatique() {
        val next = m("mtl", League.NHL, abbr = "MTL", start = now + 3.hours)
        val close = live("close", League.NBA, 100, 98, period = 4)
        assertEquals("close", headOf(listOf(next, close), SportView(featured = "n_importe_quoi")))
        assertEquals("mtl", headOf(listOf(next, close), SportView(featured = FEATURED_MINE)))
    }

    @Test fun l_affiche_n_est_jamais_en_double_dans_la_bande() {
        val next = m("mtl", League.NHL, abbr = "MTL", start = now + 3.hours)
        val close = live("close", League.NBA, 100, 98, period = 4)
        val strip = stripFor(buildBoard(listOf(next, close), now, montreal, listOf(mtl)), now, montreal, null)
        assertEquals(strip.map { it.id }.distinct(), strip.map { it.id })
    }

    // ---- Compte à rebours ----

    @Test fun compte_a_rebours_limites() {
        val t = Instant.parse("2026-11-21T00:00:00Z") // vendredi 20 nov., 19 h à Montréal
        assertEquals("Dans 1 min", countdownLabel(t, t - 1.seconds, montreal))
        assertEquals("Dans 1 min", countdownLabel(t, t - 59.seconds, montreal))
        assertEquals("Dans 59 min", countdownLabel(t, t - 59.minutes, montreal))
        assertEquals("Dans 1 h", countdownLabel(t, t - 1.hours, montreal))
        assertEquals("Dans 1 h 05", countdownLabel(t, t - 65.minutes, montreal))
        assertEquals("Dans 23 h 59", countdownLabel(t, t - 23.hours - 59.minutes, montreal))
        assertEquals("Vendredi 19 h", countdownLabel(t, t - 24.hours, montreal))
        assertEquals("Ça commence !", countdownLabel(t, t + 10.minutes, montreal), "heure passée, score pas encore arrivé")
    }

    @Test fun compte_a_rebours_jours_de_la_semaine_et_fuseau() {
        val base = Instant.parse("2026-11-16T17:00:00Z") // lundi 16 nov., 12 h à Montréal
        val days = (0..6).map { countdownLabel(base + it.days, base - 2.days, montreal).substringBefore(' ') }
        assertEquals(listOf("Lundi", "Mardi", "Mercredi", "Jeudi", "Vendredi", "Samedi", "Dimanche"), days)
        // Minuit passé selon le fuseau : 23 h 30 à Montréal est encore le lundi.
        val late = Instant.parse("2026-11-17T04:30:00Z")
        assertEquals("Lundi 23 h 30", countdownLabel(late, late - 3.days, montreal))
        assertEquals("Mardi 4 h 30", countdownLabel(late, late - 3.days, 0))
    }

    // ---- Scores cachés ----

    @Test fun sans_score_garde_tout_le_reste() {
        val a = live("a", League.NHL, 2, 1, period = 3, detail = "3e pér.", abbr = "MTL")
        val b = a.withoutScore()
        assertNull(b.homeScore); assertNull(b.awayScore)
        assertEquals(a.copy(homeScore = null, awayScore = null), b)
        assertFalse(isCloseLate(b), "sans score : jamais « serré »")
    }

    @Test fun reglage_sans_resultat_lu_et_ecrit_par_l_iphone() {
        val json = Json { ignoreUnknownKeys = true }
        assertTrue(json.decodeFromString(SportView.serializer(), """{"hide_scores":true}""").hideScores)
        assertFalse(json.decodeFromString(SportView.serializer(), """{}""").hideScores)
        assertFalse(json.decodeFromString(SportView.serializer(), """{"featured":"auto"}""").hideScores)
        assertTrue(json.encodeToString(SportView.serializer(), SportView(hideScores = true)).contains("\"hide_scores\":true"))
    }

    // ---- Période lue dans les horaires ----

    @Test fun periode_lue_seulement_en_direct() {
        val nhl = """{"gameWeek":[{"games":[
            {"id":1,"startTimeUTC":"2026-11-17T00:30:00Z","gameState":"LIVE","periodDescriptor":{"number":3},"awayTeam":{"abbrev":"MTL","score":2},"homeTeam":{"abbrev":"BOS","score":1}},
            {"id":2,"startTimeUTC":"2026-11-16T00:30:00Z","gameState":"OFF","periodDescriptor":{"number":3},"awayTeam":{"abbrev":"MTL","score":2},"homeTeam":{"abbrev":"BOS","score":1}}]}]}"""
        val ms = parseNhlSchedule(nhl)
        assertEquals(3, ms.first { it.id == "nhl:1" }.period)
        assertNull(ms.first { it.id == "nhl:2" }.period)
        val espn = """{"events":[{"id":"9","date":"2026-11-17T00:30Z","status":{"period":4,"type":{"state":"in"}},
            "competitions":[{"competitors":[{"homeAway":"home","score":"100","team":{"id":"1","displayName":"A"}},{"homeAway":"away","score":"98","team":{"id":"2","displayName":"B"}}]}]}]}"""
        val e = parseEspnScoreboard(League.NBA, espn).single()
        assertEquals(4, e.period)
        assertTrue(isCloseLate(e))
    }

    // ---- Classements ----

    @Test fun classements_reponses_incompletes_ou_bizarres() {
        assertTrue(parseNhlStandings("{}").isEmpty())
        assertTrue(parseNhlStandings("""{"standings":[{"teamAbbrev":{"default":"MTL"}}]}""").isEmpty(), "sans rang : ignoré")
        assertEquals("1er Atlantique", parseNhlStandings("""{"standings":[{"teamAbbrev":{"default":"mtl"},"divisionSequence":1,"divisionName":"Atlantic"}]}""")["nhl:MTL"])
        assertEquals("2e Nouvelle · 10 pts", parseNhlStandings("""{"standings":[{"teamAbbrev":{"default":"XYZ"},"divisionSequence":2,"divisionName":"Nouvelle","points":10,"streakCode":"OT","streakCount":3}]}""")["nhl:XYZ"],
            "division inconnue gardée telle quelle, série en prolongation non affichée")
        assertEquals("5e Centrale · 20 pts", parseNhlStandings("""{"standings":[{"teamAbbrev":{"default":"DAL"},"divisionSequence":5,"divisionName":"Central","points":20,"streakCode":"W","streakCount":1}]}""")["nhl:DAL"],
            "série d'une seule victoire : rien")
        assertTrue(parseEspnStandings(League.NFL, "pas du json").isEmpty())
        assertTrue(parseEspnStandings(League.LIGA, """{"children":[{"standings":{"entries":[{"team":{"id":"1"},"stats":[{"name":"points","displayValue":"3"}]}]}}]}""").isEmpty(),
            "soccer sans rang : ignoré")
        val flat = parseEspnStandings(League.MLS, """{"standings":{"entries":[{"team":{"id":"7"},"stats":[{"name":"rank","displayValue":"4"},{"name":"points","displayValue":"50"}]}]}}""")
        assertEquals("4e de la MLS · 50 pts", flat["soccer:7"], "classement sans groupes")
        val nba = parseEspnStandings(League.NBA, """{"children":[{"name":"Western Conference","abbreviation":"West","standings":{"entries":[{"team":{"id":"3"},"stats":[
            {"name":"wins","displayValue":"10"},{"name":"losses","displayValue":"2"},{"name":"playoffSeed","displayValue":"1.0"},{"name":"streak","displayValue":"W3"}]}]}}]}""")
        assertEquals("10-2 · 1er Ouest · 3 victoires de suite", nba["nba:3"])
    }

    @Test fun cles_et_adresses_de_classement() {
        assertEquals(NHL_STANDINGS_URL, standingsUrl(League.NHL))
        assertEquals("https://site.api.espn.com/apis/v2/sports/basketball/nba/standings", standingsUrl(League.NBA))
        assertEquals("https://site.api.espn.com/apis/v2/sports/soccer/eng.1/standings", standingsUrl(League.EPL))
        for (cup in listOf(League.UCL, League.UEL, League.COPA, League.SUPERCOPA)) assertNull(standingsUrl(cup), "$cup")
        val liga = m("x", League.LIGA, homeId = "83")
        assertEquals("soccer:83", standingKey(liga, home = true))
        assertEquals("soccer:ax", standingKey(liga, home = false))
        val ucl = m("y", League.UCL, homeId = "83")
        assertEquals("soccer:83", standingKey(ucl, home = true), "un match de coupe du Barça montre son rang en Liga")
        assertEquals("nba:hz", standingKey(m("z", League.NBA), home = true))
        assertEquals("nhl:XZ", standingKey(m("z", League.NHL), home = false))
        assertEquals("1er", ordinal(1)); assertEquals("2e", ordinal(2)); assertEquals("11e", ordinal(11))
        assertNull(streakText("W", 1)); assertNull(streakText(null, 4)); assertNull(streakText("W", null))
        assertEquals("2 défaites de suite", streakText("l", 2))
    }
}
