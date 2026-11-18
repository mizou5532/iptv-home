package home.iptv.shared.sport

import home.iptv.shared.sync.SportTeam
import home.iptv.shared.sync.SportView
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class SportBannerTest {
    private fun res(name: String) = SportBannerTest::class.java.getResource("/sport/$name")!!.readText()
    private val montreal = -5 * 3600
    private val now = Instant.parse("2026-11-17T01:00:00Z")
    private val mtl = SportTeam("nhl", "10", "Canadiens", "MTL")

    private fun m(id: String, league: League, live: Boolean = false, home: Int? = null, away: Int? = null, period: Int? = null,
                  detail: String? = null, abbr: String = "", start: Instant = now - 1.hours) =
        Match(id, league, start, if (live) MatchState.LIVE else MatchState.UPCOMING, "H$id", "A$id", false,
            homeAbbr = abbr, awayAbbr = "X$id", homeId = "h$id", awayId = "a$id", homeScore = home, awayScore = away, period = period, detail = detail)

    @Test fun serre_en_fin_de_partie() {
        assertTrue(isCloseLate(m("a", League.NHL, true, 2, 1, period = 3)))
        assertFalse(isCloseLate(m("b", League.NHL, true, 3, 1, period = 3)), "2 buts d'écart")
        assertFalse(isCloseLate(m("c", League.NHL, true, 1, 1, period = 2)), "2e période")
        assertTrue(isCloseLate(m("d", League.LIGA, true, 0, 0, detail = "74'")))
        assertTrue(isCloseLate(m("d2", League.LIGA, true, 1, 2, detail = "90'+3'")))
        assertFalse(isCloseLate(m("e", League.LIGA, true, 0, 0, detail = "Mi-temps")))
        assertTrue(isCloseLate(m("f", League.NBA, true, 101, 95, period = 4)))
        assertFalse(isCloseLate(m("g", League.NBA, true, 101, 90, period = 4)))
        assertFalse(isCloseLate(m("h", League.NFL, false, 7, 7, period = 4)), "pas en direct")
        assertEquals(90, soccerMinute("90'+3'"))
    }

    @Test fun affiche_automatique_dans_l_ordre_promis() {
        fun head(vararg ms: Match) = stripFor(buildBoard(ms.toList(), now, montreal, listOf(mtl)), now, montreal, null).first().id
        val mineNext = m("mtl", League.NHL, abbr = "MTL", start = now + 2.hours)
        val mineLive = m("mtl", League.NHL, true, 1, 3, period = 1, abbr = "MTL")
        val close = m("close", League.NBA, true, 100, 98, period = 4)
        val liveEarly = m("early", League.LIGA, true, 0, 0, detail = "20'", start = now - 2.hours)
        val liveLate = m("late", League.NFL, true, 0, 21, period = 2, start = now - 30.minutes)
        assertEquals("mtl", head(mineLive, close, liveEarly), "mon équipe en direct")
        assertEquals("close", head(mineNext, close, liveEarly), "match serré en fin de partie")
        assertEquals("mtl", head(mineNext, liveEarly, liveLate), "puis mon prochain match")
        assertEquals("early", head(liveLate, liveEarly), "puis le direct le plus avancé")
        // Réglages choisis sur l'iPhone : inchangés.
        val board = buildBoard(listOf(mineNext, close), now, montreal, listOf(mtl))
        assertEquals("mtl", stripFor(board, now, montreal, SportView(featured = FEATURED_MINE)).first().id)
        assertEquals("close", stripFor(board, now, montreal, SportView(featured = FEATURED_LIVE)).first().id)
    }

    @Test fun compte_a_rebours() {
        val t = Instant.parse("2026-11-21T00:00:00Z") // vendredi 20 nov. 2026, 19 h à Montréal
        assertEquals("Ça commence !", countdownLabel(t, t, montreal))
        assertEquals("Dans 12 min", countdownLabel(t, t - 12.minutes, montreal))
        assertEquals("Dans 2 h 15", countdownLabel(t, t - 2.hours - 15.minutes, montreal))
        assertEquals("Dans 3 h", countdownLabel(t, t - 3.hours, montreal))
        assertEquals("Vendredi 19 h", countdownLabel(t, t - 30.hours, montreal))
        assertEquals("Vendredi 19 h 30", countdownLabel(t + 30.minutes, t - 30.hours, montreal))
    }

    @Test fun classements() {
        val nhl = parseNhlStandings(res("standings-nhl.json"))
        assertEquals("3e Atlantique · 28 pts · 2 défaites de suite", nhl["nhl:MTL"])
        assertEquals("1er Pacifique · 37 pts · 6 victoires de suite", nhl["nhl:EDM"])
        val liga = parseEspnStandings(League.LIGA, res("standings-liga.json"))
        assertEquals("1er de la Liga · 33 pts", liga["soccer:83"])
        val nfl = parseEspnStandings(League.NFL, res("standings-nfl.json"))
        assertEquals("8-1 · 1er AFC · 5 victoires de suite", nfl["nfl:2"])
        val nba = parseEspnStandings(League.NBA, res("standings-nba.json"))
        assertEquals("1-13 · 15e Est · 10 défaites de suite", nba["nba:15"])
        assertTrue(parseNhlStandings("pas du json").isEmpty())
        assertNull(standingsUrl(League.UCL))
        val match = m("x", League.NHL, abbr = "MTL")
        assertEquals("nhl:MTL", standingKey(match, home = true))
    }
}
