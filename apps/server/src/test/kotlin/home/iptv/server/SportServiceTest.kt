package home.iptv.server

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import home.iptv.shared.catalog.CatalogDatabase
import home.iptv.shared.catalog.CatalogRepository
import home.iptv.shared.home.LiveSnapshot
import home.iptv.shared.home.SnapCategory
import home.iptv.shared.home.SnapStream
import home.iptv.shared.home.SportBoardRequest
import home.iptv.shared.home.toSportCandidates
import home.iptv.shared.sport.ChannelReason
import home.iptv.shared.sync.HiddenItem
import home.iptv.shared.sync.HouseholdSettings
import home.iptv.shared.sync.PairingState
import home.iptv.shared.sync.Profile
import home.iptv.shared.sync.ProfilePrefs
import home.iptv.shared.sync.PublishedCategory
import home.iptv.shared.sync.PublishedChannel
import home.iptv.shared.sync.SportMemory
import home.iptv.shared.sync.SportTeam
import home.iptv.shared.sync.SportVote
import home.iptv.shared.sync.SyncClient
import home.iptv.shared.xtream.LiveCategory
import home.iptv.shared.xtream.LiveStream
import home.iptv.shared.xtream.XtreamCreds
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal class SportFakeSync(
    var memory: SportMemory = SportMemory(),
    var votes: List<SportVote> = emptyList(),
) : SyncClient {
    var memoryCalls = 0
    override suspend fun ensureSignedIn() {}
    override suspend fun currentHousehold(): String? = "h1"
    override suspend fun createPairingCode(deviceName: String): PairingState.Waiting = error("inutile")
    override suspend fun credentials(householdId: String): XtreamCreds? = null
    override suspend fun profiles(householdId: String): List<Profile> = emptyList()
    override suspend fun globalHiddenCategories(householdId: String): List<HiddenItem> = emptyList()
    override suspend fun publishLiveCategories(householdId: String, cats: List<PublishedCategory>): Map<String, String?> = emptyMap()
    override suspend fun publishLiveChannels(householdId: String, categoryId: String, fingerprint: String, chunk: Int, isLast: Boolean, channels: List<PublishedChannel>) {}
    override suspend fun profilePrefs(profileId: String): ProfilePrefs = error("inutile")
    override suspend fun prefsRevision(householdId: String): Long = 0
    override suspend fun publishSeriesCategories(householdId: String, cats: List<PublishedCategory>) {}
    override suspend fun sportVotes(householdId: String): List<SportVote> = votes
    override suspend fun sportMemory(profileId: String): SportMemory {
        memoryCalls++
        return memory
    }
    var sportCats: List<String>? = null
    var failSettings: Boolean = false
    override suspend fun householdSettings(householdId: String): home.iptv.shared.sync.HouseholdSettings {
        if (failSettings) throw java.io.IOException("supabase offline")
        return home.iptv.shared.sync.HouseholdSettings(sportCategories = sportCats)
    }
}

class SportServiceTest {
    private val nhlJson = """
        {
          "gameWeek": [
            {
              "date": "2026-11-21",
              "games": [
                {
                  "id": 2026020300,
                  "startTimeUTC": "2026-11-22T00:00:00Z",
                  "gameState": "FUT",
                  "homeTeam": {"id": 8, "abbrev": "MTL", "placeName": {"default": "Montréal"}, "commonName": {"default": "Canadiens"}},
                  "awayTeam": {"id": 10, "abbrev": "TOR", "placeName": {"default": "Toronto"}, "commonName": {"default": "Maple Leafs"}},
                  "tvBroadcasts": [{"countryCode": "CA", "network": "RDS"}]
                }
              ]
            }
          ]
        }
    """.trimIndent()

    private fun setup(
        sync: SportFakeSync = SportFakeSync(),
        ready: Boolean = true,
        fetchCount: MutableList<String> = mutableListOf(),
    ): SportService {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CatalogDatabase.Schema.create(driver)
        val repo = CatalogRepository(CatalogDatabase(driver))
        val snap = LiveSnapshot(
            rev = "lrev1", account = "acct1", builtAt = 100L,
            categories = listOf(SnapCategory("1", "CA| SPORT", 0)),
            streams = listOf(
                SnapStream("s1", "1", "CA: RDS HD", null, null, 1, 0, "rds", "RDS"),
                SnapStream("s2", "1", "CA: TVA Sports HD", null, null, 2, 1, "tva sports", "TVA Sports"),
            ),
            hidden = emptyList(),
        )
        repo.replaceLive(snap)
        return SportService(
            liveRepo = repo,
            sync = sync,
            householdId = "h1",
            liveSnapshot = { if (ready) snap else null },
            fetchUrl = { url ->
                fetchCount += url
                if ("nhle.com/v1/schedule" in url) nhlJson else """{"events":[],"standings":[]}"""
            },
            liveCategories = { listOf(LiveCategory("sp1", "CA| NHL PPV")) },
            liveStreams = { listOf(LiveStream("ex1", "NHL 01: Canadiens vs Maple Leafs", "sp1", null, null, 1)) },
            clockMs = { 1_795_220_000_000L }, // 2026-11-21
        )
    }

    @Test
    fun buildsBoardWithSharedPreferredMemoryAndCachesSchedules() = runTest {
        val sync = SportFakeSync(
            memory = SportMemory(
                preferred = mapOf("nhl:MTL" to "auto:v1:tva sports"),
                learned = mapOf("nhl" to listOf("auto:v1:rds")),
            ),
        )
        val fetches = mutableListOf<String>()
        val svc = setup(sync = sync, fetchCount = fetches)

        val req = SportBoardRequest(
            profileId = "p1",
            offsetSeconds = -18000,
            teams = listOf(SportTeam("nhl", "10", "Canadiens de Montréal", "MTL")),
        )
        val reply1 = svc.board(req)
        assertEquals("acct1", reply1.account)
        assertEquals(1, reply1.matches.size)
        val matchId = reply1.matches.single().id
        val cand1 = reply1.candidates[matchId]!!.toSportCandidates()
        // TVA Sports est en étoile -> en tête avec PREFERRED !
        assertEquals("TVA Sports", cand1.proposed.first().title)
        assertEquals(ChannelReason.PREFERRED, cand1.reasons[cand1.proposed.first().groupId])

        // Deuxième appel dans les 2 min -> zéro nouvel appel réseau !
        val firstFetchCount = fetches.size
        val reply2 = svc.board(req)
        assertEquals(firstFetchCount, fetches.size)
        assertEquals(1, sync.memoryCalls)

        // Invalidation après changement d'étoile -> relit aussitôt la mémoire partagée.
        sync.memory = SportMemory(preferred = emptyMap())
        svc.invalidate("p1")
        val reply3 = svc.board(req)
        assertEquals(2, sync.memoryCalls)
        assertTrue(reply3.memory.preferred.isEmpty())
    }

    @Test
    fun notReadyThrowsSportNotReady() = runTest {
        val svc = setup(ready = false)
        assertFailsWith<SportNotReadyException> { svc.board(SportBoardRequest("p1", -18000)) }
    }

    @Test
    fun respectsEmptySportCategoriesAndAccountChangeAndWarmLiveFlag() = runTest {
        val sync = SportFakeSync().apply { sportCats = emptyList() }
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CatalogDatabase.Schema.create(driver)
        val repo = CatalogRepository(CatalogDatabase(driver))
        var currentSnap = LiveSnapshot(
            rev = "lrev1", account = "acct1", builtAt = 100L,
            categories = listOf(SnapCategory("1", "CA| SPORT", 0)),
            streams = listOf(SnapStream("s1", "1", "CA: RDS HD", null, null, 1, 0, "rds", "RDS")),
            hidden = emptyList(),
        )
        repo.replaceLive(currentSnap)
        var extraStreamFetches = 0
        val svc = SportService(
            liveRepo = repo,
            sync = sync,
            householdId = "h1",
            liveSnapshot = { currentSnap },
            fetchUrl = { url -> if ("nhle.com/v1/schedule" in url) nhlJson else """{"events":[],"standings":[]}""" },
            liveCategories = { listOf(LiveCategory("sp1", "CA| NHL PPV")) },
            liveStreams = { extraStreamFetches++; listOf(LiveStream("ex1", "NHL 01: Canadiens vs Maple Leafs", "sp1", null, null, 1)) },
            clockMs = { 1_795_220_000_000L },
        )
        // sport_categories = [] (explicitement vidé par le foyer) -> 0 téléchargement de chaînes Sport !
        val hasLive = svc.warmOnce()
        assertEquals(0, extraStreamFetches)
        assertEquals(false, hasLive)

        // Si le foyer repasse en automatique (null) sur un nouveau compte -> charge les chaînes du nouveau compte.
        sync.sportCats = null
        currentSnap = currentSnap.copy(account = "acct2")
        svc.warmOnce()
        assertEquals(1, extraStreamFetches)

        // Changement de compte -> board() ne sert jamais les chaînes extra de l'ancien compte !
        currentSnap = currentSnap.copy(account = "acct3")
        val reply = svc.board(SportBoardRequest(profileId = "p1", offsetSeconds = -18000, teams = listOf(SportTeam("nhl", "10", "Canadiens de Montréal", "MTL"))))
        assertEquals("acct3", reply.account)
        val allGroupIds = reply.candidates.values.flatMap { c -> c.all.map { it.groupId } }
        assertTrue(allGroupIds.none { it.startsWith("x:") })
    }
}
