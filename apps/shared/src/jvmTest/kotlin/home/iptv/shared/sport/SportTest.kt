package home.iptv.shared.sport

import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class SportTest {
    private fun res(name: String) = SportTest::class.java.getResource("/sport/$name")!!.readText()
    private val montreal = -5 * 3600

    @Test fun lnh_vraie_reponse_diffuseurs_canadiens_seulement() {
        val games = parseNhlSchedule(res("nhl-api.json"))
        assertEquals(47, games.size)
        val mtl = games.filter { it.followed }
        assertEquals(4, mtl.size)
        val first = mtl.first { it.id == "nhl:2026020331" }
        assertEquals(listOf("SN+", "TVAS"), first.broadcasters)
        assertEquals(Instant.parse("2026-11-17T00:30:00Z"), first.start)
        assertEquals(MatchState.UPCOMING, first.state)
        assertTrue("Canadiens" in first.away, first.away)
        assertTrue(games.all { it.league == League.NHL })
    }

    @Test fun espn_vraies_reponses_heure_sans_secondes_et_barca() {
        val nfl = parseEspnScoreboard(League.NFL, res("nfl.json"))
        assertEquals(12, nfl.size)
        assertEquals(Instant.parse("2026-11-15T14:30:00Z"), nfl.first().start)
        assertTrue(nfl.none { it.followed }); assertTrue(nfl.all { it.broadcasters.isEmpty() })
        val liga = parseEspnScoreboard(League.LIGA, res("esp.1.json"))
        assertEquals(4, liga.size)
        val barca = parseEspnScoreboard(League.LIGA, """{"events":[{"id":"9","date":"2026-11-22T13:00Z","status":{"type":{"state":"in"}},
            "competitions":[{"competitors":[{"homeAway":"home","team":{"id":"102","displayName":"Villarreal"}},{"homeAway":"away","team":{"id":"83","displayName":"Barcelona"}}]}]}]}""")
        assertEquals(MatchState.LIVE, barca.single().state); assertTrue(barca.single().followed)
        assertEquals("Barcelona – Villarreal", barca.single().title)
    }

    @Test fun reponses_abimees_listes_vides() {
        assertTrue(parseNhlSchedule("pas du json").isEmpty())
        assertTrue(parseNhlSchedule("""{"gameWeek":[{"games":[{"id":1}]}]}""").isEmpty())
        assertTrue(parseEspnScoreboard(League.NBA, "[]").isEmpty())
        assertTrue(parseEspnScoreboard(League.NBA, """{"events":[{"id":"1","date":"pas une date"}]}""").isEmpty())
    }

    private fun m(id: String, start: Instant, followed: Boolean = false, state: MatchState = MatchState.UPCOMING, league: League = League.NHL) =
        Match(id, league, start, state, "Domicile $id", "Visiteur $id", followed)

    @Test fun tableau_mes_equipes_et_ce_soir() {
        val now = Instant.parse("2026-11-16T22:00:00Z") // 17 h à Montréal
        val board = buildBoard(listOf(
            m("live", now - 1.hours, true, MatchState.LIVE),
            m("soon", now + 2.hours, true),
            m("in6days", now + (6 * 24).hours, true),
            m("in8days", now + (8 * 24).hours, true),
            m("tonight", now + 3.hours),
            m("tomorrow", Instant.parse("2026-11-17T06:00:00Z")), // 1 h du matin à Montréal : demain
            m("over", now - 5.hours, state = MatchState.UPCOMING),
            m("final", now - 1.hours, state = MatchState.FINAL),
            m("live2", now - 2.hours, state = MatchState.LIVE),
        ), now, montreal)
        assertEquals(listOf("live", "soon", "in6days"), board.mine.map { it.id })
        assertEquals(listOf("live2", "tonight"), board.tonight.map { it.id })
        assertTrue(isOver(m("x", now - 4.hours), now))
        assertFalse(isOver(m("x", now - 3.hours, league = League.NHL), now))
    }

    private val chans = listOf(
        SportChannel("g-rds", "RDS HD", "CA| SPORTS FR"),
        SportChannel("g-rds2", "RDS 2 HD", "CA| SPORTS FR"),
        SportChannel("g-tvas", "TVA Sports HD", "CA| SPORTS FR"),
        SportChannel("g-tvas2", "TVA Sports 2", "CA| SPORTS FR"),
        SportChannel("g-snp", "Sportsnet+ HD", "CA| SPORTS EN"),
        SportChannel("g-tsn1", "TSN 1", "CA| SPORTS EN"),
        SportChannel("g-tsn2", "TSN 2", "CA| SPORTS EN"),
        SportChannel("g-bein", "beIN Sports 1", "AR| BEIN SPORTS 8K & HD"),
        SportChannel("g-beinfr", "beIN Sports 1 FR", "FR| SPORT HD/4K"),
        SportChannel("g-canal", "Canal+ Sport", "FR| CANAL+ LIVE HD"),
        SportChannel("g-news", "LCN", "CA| GENERAL FR"),
    )

    @Test fun lnh_officiels_en_francais_d_abord_puis_appris() {
        val match = m("a", Instant.parse("2026-11-17T00:30:00Z"), true).copy(broadcasters = listOf("SN+", "TVAS"))
        val c = sportCandidates(match, learned = listOf("g-rds"), channels = chans)
        assertEquals(listOf("g-tvas", "g-snp", "g-rds"), c.proposed.take(3).map { it.groupId })
        assertTrue(c.proposed.size <= PROPOSED_MAX)
    }

    @Test fun tsn2_ne_donne_pas_tsn1() {
        val match = m("a", Instant.parse("2026-11-17T00:30:00Z")).copy(broadcasters = listOf("TSN2", "RDS2"))
        assertEquals(listOf("g-rds2", "g-tsn2"), sportCandidates(match, emptyList(), chans).proposed.take(2).map { it.groupId })
    }

    @Test fun barca_appris_d_abord_puis_repli_sport_francais_et_parcours_complet() {
        val match = m("b", Instant.parse("2026-11-22T13:00:00Z"), true, league = League.LIGA)
        val c = sportCandidates(match, learned = listOf("g-canal", "g-bein"), channels = chans)
        assertEquals(listOf("g-canal", "g-bein"), c.proposed.take(2).map { it.groupId })
        // Parcours Droite : toutes les chaînes sport, une seule fois chacune, français d'abord après les proposées.
        assertEquals(c.all.map { it.groupId }.distinct(), c.all.map { it.groupId })
        assertTrue(c.all.none { it.groupId == "g-news" })
        val rest = c.all.drop(c.proposed.size)
        assertTrue(rest.takeWhile { it.french }.size + rest.dropWhile { it.french }.size == rest.size)
        assertTrue(rest.dropWhile { it.french }.none { it.french })
    }

    @Test fun jamais_une_chaine_absente_des_visibles() {
        val match = m("a", Instant.parse("2026-11-17T00:30:00Z")).copy(broadcasters = listOf("RDS"))
        val visible = chans.filter { it.groupId != "g-rds" }
        val c = sportCandidates(match, learned = listOf("g-rds", "ghost"), channels = visible)
        assertTrue(c.all.none { it.groupId == "g-rds" || it.groupId == "ghost" })
    }

    @Test fun francais_detecte() {
        assertTrue(isFrench("beIN Sports 1", "FR| SPORT HD/4K")); assertTrue(isFrench("RDS", "CA| SPORTS")); assertTrue(isFrench("TVA Sports", "Sports"))
        assertFalse(isFrench("TSN 2", "CA| SPORTS EN")); assertFalse(isFrench("Frankfurt TV", "DE| GENERAL"))
    }

    @Test fun apprentissage_plus_recent_en_tete_sans_doublon_limite() {
        var l = emptyList<String>()
        for (g in listOf("a", "b", "c", "a", "d", "e", "f", "g")) l = learn(l, g)
        assertEquals(listOf("g", "f", "e", "d", "a", "c"), l)
    }
}
