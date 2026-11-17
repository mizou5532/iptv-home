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
        assertTrue(nfl.none { it.followed })
        // Diffuseurs d'ESPN gardés (sources de plus, après les chaînes françaises) ; logos et sigles lus.
        assertTrue(nfl.any { it.broadcasters.isNotEmpty() }); assertTrue(nfl.all { it.homeLogo != null && it.homeAbbr.isNotEmpty() })
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
        // Québec d'abord, puis anglais ; la chaîne apprise pour la ligue (RDS) n'est PAS proposée : elle ne dit rien de CE match.
        assertEquals(listOf("g-tvas", "g-snp"), c.proposed.map { it.groupId })
        assertEquals("g-rds", c.all.drop(c.likely.size).first().groupId) // mais en tête du parcours
        assertTrue(c.proposed.size <= PROPOSED_MAX)
    }

    @Test fun tsn2_ne_donne_pas_tsn1() {
        val match = m("a", Instant.parse("2026-11-17T00:30:00Z")).copy(broadcasters = listOf("TSN2", "RDS2"))
        assertEquals(listOf("g-rds2", "g-tsn2"), sportCandidates(match, emptyList(), chans).proposed.take(2).map { it.groupId })
    }

    @Test fun barca_appris_d_abord_puis_repli_sport_francais_et_parcours_complet() {
        val match = m("b", Instant.parse("2026-11-22T13:00:00Z"), true, league = League.LIGA)
        val c = sportCandidates(match, learned = listOf("g-canal", "g-bein"), channels = chans)
        // Apprises pour la ligue : pas proposées comme « diffuse ce match », mais en tête du parcours.
        assertTrue(c.proposed.isEmpty())
        assertEquals(listOf("g-canal", "g-bein"), c.all.take(2).map { it.groupId })
        // Parcours Droite : toutes les chaînes sport, une seule fois chacune, français d'abord après les apprises.
        assertEquals(c.all.map { it.groupId }.distinct(), c.all.map { it.groupId })
        assertTrue(c.all.none { it.groupId == "g-news" })
        val rest = c.all.drop(2)
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

    @Test fun equipes_favorites_cartes_et_ligues() {
        val now = Instant.parse("2026-11-16T22:00:00Z")
        val mtl = Match("nhl:1", League.NHL, Instant.parse("2026-11-17T00:30:00Z"), MatchState.UPCOMING, "Rangers", "Canadiens", true,
            homeAbbr = "NYR", awayAbbr = "MTL")
        val nj = Match("nhl:2", League.NHL, now, MatchState.LIVE, "Devils", "Kings", false, homeAbbr = "NJD", awayAbbr = "LAK", homeScore = 2, awayScore = 1)
        val barca = Match("uefa.champions:3", League.UCL, Instant.parse("2026-11-18T20:00:00Z"), MatchState.UPCOMING, "Barcelona", "Inter", true, homeId = "83", awayId = "110")
        val lakers = Match("nba:4", League.NBA, Instant.parse("2026-11-17T03:00:00Z"), MatchState.UPCOMING, "Lakers", "Celtics", false, homeId = "13", awayId = "2")
        val teams = listOf(
            home.iptv.shared.sync.SportTeam("nhl", "11", "Devils du New Jersey", "NJ"),
            home.iptv.shared.sync.SportTeam("esp.1", "83", "FC Barcelone"),
            home.iptv.shared.sync.SportTeam("nba", "13", "Lakers"),
            home.iptv.shared.sync.SportTeam("nfl", "99", "Sans match"),
        )
        val b = buildBoard(listOf(mtl, nj, barca, lakers), now, -5 * 3600, teams)
        assertEquals(listOf("nhl:2", "nba:4", "uefa.champions:3"), b.mine.map { it.id }) // en cours d'abord, puis par heure
        assertEquals(listOf("nhl:2", "uefa.champions:3", "nba:4", null), b.teams.map { it.next?.id })
        assertTrue("nhl:1" in b.tonight.map { it.id }) // les Canadiens ne sont plus suivis ici : « aussi ce soir »
        // Sans choix : Canadiens et Barça (ancien suivi).
        assertEquals(listOf("nhl:1", "uefa.champions:3"), buildBoard(listOf(mtl, barca), now, -5 * 3600).mine.map { it.id })
        assertEquals("https://a.espncdn.com/i/teamlogos/nhl/500/nj.png", nhlLogo("NJD"))
        assertTrue(League.EPL !in leaguesFor(DEFAULT_TEAMS))
        assertTrue(League.EPL in leaguesFor(listOf(home.iptv.shared.sync.SportTeam("eng.1", "359", "Arsenal"))))
        assertEquals("2e pér.", periodLabel("hockey", 2)); assertEquals("Prol.", periodLabel("basketball", 5))
    }

    @Test fun diffuseurs_espn_cherches_par_leur_nom() {
        val ch = listOf(SportChannel("g-fr", "RDS HD", "Sports FR"), SportChannel("g-tnt", "US: TNT HD", "USA Sports"), SportChannel("g-cbs", "US: CBS", "USA"))
        val m = Match("nba:5", League.NBA, Instant.parse("2026-11-17T03:00:00Z"), MatchState.UPCOMING, "Lakers", "Celtics", false, broadcasters = listOf("TNT"))
        assertEquals("g-tnt", sportCandidates(m, emptyList(), ch).proposed.first().groupId)
    }

    @Test fun chaine_preferee_en_tete_avec_ses_raisons() {
        val m = Match("nhl:7", League.NHL, Instant.parse("2026-11-17T00:00:00Z"), MatchState.LIVE, "Bruins", "Canadiens", true,
            broadcasters = listOf("SN"), homeAbbr = "BOS", awayAbbr = "MTL")
        val ch = listOf(SportChannel("g-sn", "Sportsnet HD", "CA Sports EN"), SportChannel("g-rds", "RDS HD", "CA Sports FR"), SportChannel("g-news", "LCN", "CA Info"))
        val c = sportCandidates(m, emptyList(), ch, preferred = listOf("g-rds", "g-cache"))
        assertEquals(listOf("g-rds", "g-sn"), c.likely.map { it.groupId })
        assertEquals(ChannelReason.PREFERRED, c.reasons["g-rds"])
        assertEquals(ChannelReason.OFFICIAL, c.reasons["g-sn"])
        assertEquals("g-rds", c.all.first().groupId)
    }

    @Test fun cles_de_preference_equipe_favorite_sinon_les_deux() {
        val nhl = Match("nhl:7", League.NHL, Instant.parse("2026-11-17T00:00:00Z"), MatchState.LIVE, "Bruins", "Canadiens", true, homeAbbr = "BOS", awayAbbr = "MTL", homeId = "BOS", awayId = "MTL")
        val mtl = home.iptv.shared.sync.SportTeam("nhl", "10", "Canadiens", "MTL", null, null)
        assertEquals(listOf("nhl:MTL"), preferenceKeys(nhl, listOf(mtl)))
        assertEquals(listOf("nhl:MTL", "nhl:BOS"), preferenceKeys(nhl, emptyList()))
        val ucl = Match("uefa.champions:9", League.entries.first { it.soccer }, Instant.parse("2026-11-17T00:00:00Z"), MatchState.UPCOMING, "Barcelona", "Inter", false, homeId = "83", awayId = "110")
        val barca = home.iptv.shared.sync.SportTeam("esp.1", "83", "FC Barcelone", "BAR", null, null)
        assertEquals(listOf("soccer:83"), preferenceKeys(ucl, listOf(barca)))
    }

    @Test fun couleurs_des_equipes() {
        val espn = parseEspnScoreboard(League.entries.first { it.soccer }, """{"events":[{"id":"1","date":"2026-11-17T20:00Z","status":{"type":{"state":"pre"}},
            "competitions":[{"competitors":[{"homeAway":"home","team":{"id":"83","displayName":"Barcelona","color":"a50044"}},{"homeAway":"away","team":{"id":"86","displayName":"Real Madrid","color":"zz"}}]}]}]}""").single()
        assertEquals("a50044", espn.homeColor); assertEquals(null, espn.awayColor)
        assertEquals("AF1E2D", NHL_COLORS["MTL"]); assertEquals(32, NHL_COLORS.size)
    }

    private fun g(id: String, league: League, start: String, live: Boolean = false, home: String = "H$id", homeId: String = "h$id", abbr: String = "") =
        Match(id, league, Instant.parse(start), if (live) MatchState.LIVE else MatchState.UPCOMING, home, "A$id", false, homeId = homeId, homeAbbr = abbr, awayAbbr = "X$id")

    @Test fun bande_selon_les_reglages() {
        val now = Instant.parse("2026-11-17T00:45:00Z")
        val mtl = home.iptv.shared.sync.SportTeam("nhl", "10", "Canadiens", "MTL")
        val matches = listOf(
            g("mtl", League.NHL, "2026-11-17T00:30:00Z", abbr = "MTL"),
            g("mtl2", League.NHL, "2026-11-19T00:30:00Z", abbr = "MTL"),
            g("nba", League.NBA, "2026-11-17T00:00:00Z", live = true),
            g("nfl", League.NFL, "2026-11-17T01:15:00Z"),
            g("liga", League.LIGA, "2026-11-17T02:00:00Z"),
        )
        val board = buildBoard(matches, now, montreal, listOf(mtl))
        fun ids(v: home.iptv.shared.sync.SportView?) = stripFor(board, now, montreal, v).map { it.id }
        assertEquals(listOf("mtl", "nfl", "nba", "liga"), ids(null), "par défaut : mes équipes puis hockey, football, basketball, soccer (en direct d abord dans chaque sport)")
        assertEquals(listOf("mtl", "liga", "nfl", "nba"), ids(home.iptv.shared.sync.SportView(order = listOf("soccer", "football"))))
        assertEquals(listOf("mtl", "nfl", "liga"), ids(home.iptv.shared.sync.SportView(hidden = listOf("basketball"))))
        assertEquals(listOf("nba", "mtl", "nfl", "liga"), ids(home.iptv.shared.sync.SportView(featured = "direct")), "un match en direct à l'affiche")
        // Sans « mes équipes d'abord » : tout trié par sport (soccer d'abord ici) ; mon match reste à l'affiche.
        assertEquals(listOf("mtl", "liga", "nfl", "nba"), ids(home.iptv.shared.sync.SportView(mineFirst = false, order = listOf("soccer"))))
        assertTrue("mtl2" !in ids(null), "jours suivants hors bande")
    }
}
