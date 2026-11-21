package home.iptv.shared.home

import home.iptv.shared.sport.ChannelReason
import home.iptv.shared.sport.League
import home.iptv.shared.sport.Match
import home.iptv.shared.sport.MatchState
import home.iptv.shared.sport.SportCandidates
import home.iptv.shared.sport.SportChannel
import home.iptv.shared.sport.epochDayToIso
import home.iptv.shared.sync.SportMemory
import home.iptv.shared.sync.SportTeam
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class HomeProtocolSportTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun epochDayToIsoMatchesKnownDates() {
        assertEquals("1970-01-01", epochDayToIso(0))
        assertEquals("2024-02-29", epochDayToIso(19782))
        assertEquals("2026-11-21", epochDayToIso(20778))
    }

    @Test
    fun sportRequestAndReplyRoundTrip() {
        val req = SportBoardRequest(
            profileId = "p1",
            offsetSeconds = -18000,
            teams = listOf(SportTeam("nhl", "10", "MTL", "Canadiens")),
            hiddenCategories = listOf("99"),
            favorites = listOf("auto:ca:rds"),
            groupCopies = true,
        )
        assertEquals(req, json.decodeFromString(SportBoardRequest.serializer(), json.encodeToString(SportBoardRequest.serializer(), req)))

        val match = Match(
            id = "nhl:123", league = League.NHL, start = Instant.fromEpochMilliseconds(1_700_000_000_000L),
            state = MatchState.LIVE, home = "Canadiens de Montréal", away = "Maple Leafs de Toronto",
            followed = true, broadcasters = listOf("RDS", "SN"), homeId = "10", awayId = "11",
            homeAbbr = "MTL", awayAbbr = "TOR", homeScore = 3, awayScore = 1, detail = "2e · 12:34",
            homeColor = "#AF1E2D", awayColor = "#00205B", homeShort = "Canadiens", awayShort = "Maple Leafs", period = 2,
        )
        val ch = SportChannel("auto:ca:rds", "RDS", "CA| SPORT", "s1", direct = false)
        val cand = SportCandidates(listOf(ch), listOf(ch), listOf(ch), mapOf("auto:ca:rds" to ChannelReason.PREFERRED))
        val reply = SportBoardReply(
            rev = "srev1",
            account = "acct1",
            fetchedAtMs = 1234L,
            matches = listOf(match.toSnapMatch()),
            standings = mapOf("nhl:MTL" to "10-5-2 · 22 pts"),
            candidates = mapOf("nhl:123" to cand.toSnapSportCandidates()),
            memory = SportMemory(preferred = mapOf("nhl:MTL" to "auto:ca:rds"), learned = mapOf("nhl" to listOf("auto:ca:rds"))),
        )
        val decoded = json.decodeFromString(SportBoardReply.serializer(), json.encodeToString(SportBoardReply.serializer(), reply))
        assertEquals(reply, decoded)
        assertEquals(match, decoded.matches.single().toMatch())
        assertEquals(cand, decoded.candidates["nhl:123"]?.toSportCandidates())
    }
}
