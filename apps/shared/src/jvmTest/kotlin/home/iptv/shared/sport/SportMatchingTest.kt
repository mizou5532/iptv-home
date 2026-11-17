package home.iptv.shared.sport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class SportMatchingTest {
    private val raptors = Match("nba:1", League.NBA, Instant.parse("2026-11-15T23:00:00Z"), MatchState.UPCOMING,
        "New York Knicks", "Toronto Raptors", false, homeShort = "Knicks", awayShort = "Raptors")
    private val kings = Match("nhl:2", League.NHL, Instant.parse("2026-11-17T23:30:00Z"), MatchState.UPCOMING,
        "Boston Bruins", "Los Angeles Kings", false, homeShort = "Bruins", awayShort = "Kings")
    private val montreal = -5 * 3600

    @Test fun evenements_reels_avec_equipes_et_date() {
        assertTrue(eventMatches("Toronto Raptors vs New York Knicks @ Nov 15 6:00 PM :NBA 06", raptors, montreal))
        assertTrue(eventMatches("US (ESPN+ 459) | Hockey: Kings vs. Bruins (2026-11-17 18:30:30)", kings, montreal))
        // Mauvais jour, une seule équipe, autres équipes : non.
        assertFalse(eventMatches("Toronto Raptors vs New York Knicks @ Dec 20 6:00 PM", raptors, montreal))
        assertFalse(eventMatches("Toronto Raptors vs Boston Celtics @ Nov 15", raptors, montreal))
        assertFalse(eventMatches("NBA 06", raptors, montreal))
        // « Kings » seul ne suffit pas pour un autre match des Kings.
        assertFalse(eventMatches("Kings vs. Ducks (2026-11-17)", kings, montreal))
    }

    private val vancouver = Match("nhl:9", League.NHL, Instant.parse("2026-11-17T02:30:00Z"), MatchState.LIVE,
        "Utah Mammoth", "Vancouver Canucks", false, broadcasters = listOf("SN+"), homeShort = "Mammoth", awayShort = "Canucks")

    @Test fun noms_de_ville_des_fournisseurs() {
        assertTrue(eventMatches("NHL: Vancouver vs Utah @ Nov 16 9:30 PM :Sportsnet+  12", vancouver, montreal))
        assertTrue(eventMatches("NEXT | NHL UTAH - VANCOUVER | Mon 16 Nov 21:35 EST (US) | 8K EXCLUSIVE | US: VIAPLAY PPV 6", vancouver, montreal))
        assertTrue(eventMatches("US (ESPN+ 352) | Hockey: Canucks vs. Mammoth (National Broadcast) (2026-11-16 21:00:15)", vancouver, montreal))
        // Mêmes villes, autre sport : non.
        val bos = Match("nhl:10", League.NHL, Instant.parse("2026-11-17T00:00:00Z"), MatchState.UPCOMING, "Boston Bruins", "New York Rangers", false,
            homeShort = "Bruins", awayShort = "Rangers")
        assertFalse(eventMatches("NBA: New York vs Boston @ Nov 16 7:00 PM :Sportsnet+  05", bos, montreal))
        assertTrue(eventMatches("NHL: New York vs Boston @ Nov 16 7:00 PM :Sportsnet+  05", bos, montreal))
        // Une seule ville dans le nom : non.
        assertFalse(eventMatches("NHL: Vancouver vs Vegas @ Nov 16 9:00 PM :Sportsnet+  12", vancouver, montreal))
    }

    @Test fun dates_lues() {
        assertTrue((11 to 15) in datesIn("@ Nov 15 6:00 PM"))
        assertTrue((11 to 17) in datesIn("(2026-11-17 18:30:30)"))
        assertTrue((11 to 17) in datesIn("17/11/2026"))
        assertTrue((11 to 17) in datesIn("17 novembre"))
        assertEquals(emptySet(), datesIn("NBA 06"))
        assertEquals(11 to 17, monthDay(20774)) // 2026-11-17
        assertEquals(1 to 1, monthDay(0))
        assertEquals(2 to 29, monthDay(19782))
    }

    @Test fun regions_des_chaines() {
        assertEquals(Region.QC, regionOf(SportChannel("a", "RDS HD", "CA| SPORTS FR")))
        assertEquals(Region.QC, regionOf(SportChannel("a", "RDS", "CA| GENERAL FR")))
        assertEquals(Region.CA, regionOf(SportChannel("a", "TSN 2", "CA| SPORTS EN")))
        assertEquals(Region.FR, regionOf(SportChannel("a", "Canal+ Sport", "FR| CANAL+ LIVE HD")))
        assertEquals(Region.US, regionOf(SportChannel("a", "US: MONTREAL CANADIENS", "US| NHL TEAM PPV")))
        assertEquals(Region.US, regionOf(SportChannel("a", "US (ESPN+ 459) | Hockey: Kings vs. Bruins", "US| ESPN+ PPV")))
        assertEquals(Region.OTHER, regionOf(SportChannel("a", "beIN Sports 1", "AR| BEIN SPORTS")))
        assertEquals(listOf(Region.US, Region.QC, Region.FR, Region.CA, Region.OTHER), regionOrder(listOf("us", "qc", "x")))
        assertEquals(listOf(Region.QC, Region.FR, Region.US, Region.CA, Region.OTHER), regionOrder(null))
    }

    @Test fun chaines_d_equipe() {
        val mtl = Match("nhl:3", League.NHL, Instant.parse("2026-11-17T00:00:00Z"), MatchState.UPCOMING, "Montreal Canadiens", "Boston Bruins", true)
        assertTrue(teamChannelFor(SportChannel("a", "US: MONTREAL CANADIENS", "US| NHL TEAM PPV"), mtl))
        assertFalse(teamChannelFor(SportChannel("a", "US: MONTREAL CANADIENS", "US| NHL PPV"), mtl))
        assertFalse(teamChannelFor(SportChannel("a", "US: TORONTO MAPLE LEAFS", "US| NHL TEAM PPV"), mtl))
        assertFalse(teamChannelFor(SportChannel("a", "Montreal Canadiens vs Toronto", "US| NHL TEAM PPV"), mtl))
    }

    @Test fun categories_par_defaut() {
        assertTrue(isDefaultSportCategory("US| NHL TEAM PPV"))
        assertTrue(isDefaultSportCategory("US| ESPN+ PPV"))
        assertTrue(isDefaultSportCategory("CA| TSN+"))
        assertTrue(isDefaultSportCategory("US| NBA PASS"))
        assertFalse(isDefaultSportCategory("US| NBA PASS VIP"))
        assertFalse(isDefaultSportCategory("AR| BEIN PPV"))
        assertFalse(isDefaultSportCategory("CA| GENERAL FR"))
        assertFalse(isDefaultSportCategory("US| NFL PPV REPLAY"))
    }

    @Test fun guide_confirme() {
        val p = listOf("NHL Hockey: Kings at Bruins" to Instant.parse("2026-11-17T23:30:00Z"), "Nouvelles" to Instant.parse("2026-11-18T03:00:00Z"))
        val e = listOf(Instant.parse("2026-11-18T02:30:00Z"), Instant.parse("2026-11-18T04:00:00Z"))
        assertTrue(guideConfirms(p, e, kings))
        assertFalse(guideConfirms(p.drop(1), e.drop(1), kings))
    }

    @Test fun classement_evenement_equipe_votes_et_langue() {
        val visible = listOf(SportChannel("g-tsn", "TSN 1", "CA| SPORTS EN"), SportChannel("g-rds", "RDS HD", "CA| SPORTS FR"),
            SportChannel("g-bad", "Sportsnet One", "CA| SPORTS EN"))
        val extra = listOf(
            SportChannel("e-1", "US (ESPN+ 459) | Hockey: Kings vs. Bruins (2026-11-17 18:30:30)", "US| ESPN+ PPV", direct = true),
            SportChannel("e-2", "US (ESPN+ 460) | Hockey: Kings vs. Ducks (2026-11-17 22:00)", "US| ESPN+ PPV", direct = true),
            SportChannel("t-1", "US: BOSTON BRUINS", "US| NHL TEAM PPV", direct = true),
        )
        val idx = SportChannelIndex(visible)
        val c = sportCandidates(kings, emptyList(), idx, extra = extra, votes = mapOf(channelKey("RDS HD") to 2, channelKey("Sportsnet One") to -3),
            confirmed = setOf("g-tsn"), offsetSeconds = montreal)
        assertEquals(listOf("g-rds", "e-1", "t-1", "g-tsn"), c.likely.map { it.groupId }) // Québec, puis É.-U. (événement > équipe), puis Canada anglais
        assertEquals(ChannelReason.VOTED, c.reasons["g-rds"]); assertEquals(ChannelReason.EVENT, c.reasons["e-1"])
        assertEquals(ChannelReason.TEAM, c.reasons["t-1"]); assertEquals(ChannelReason.CONFIRMED, c.reasons["g-tsn"])
        assertTrue(c.all.none { it.groupId == "g-bad" || it.groupId == "e-2" }) // écartée par la maison ; autre match
        // Anglais d'abord si le profil le choisit.
        val en = sportCandidates(kings, emptyList(), idx, extra = extra, confirmed = setOf("g-tsn"), regions = regionOrder(listOf("ca", "us")), offsetSeconds = montreal)
        assertEquals("g-tsn", en.likely.first().groupId)
    }
}
