package home.iptv.tv.sport

import home.iptv.shared.home.SportBoardReply
import home.iptv.shared.home.SportBoardRequest
import home.iptv.shared.home.toSnapMatch
import home.iptv.shared.home.toSnapSportCandidates
import home.iptv.shared.sport.ChannelReason
import home.iptv.shared.sport.League
import home.iptv.shared.sport.Match
import home.iptv.shared.sport.MatchState
import home.iptv.shared.sport.SportCandidates
import home.iptv.shared.sport.SportChannel
import home.iptv.shared.sync.Profile
import home.iptv.shared.sync.ProfilePrefs
import home.iptv.shared.sync.ProfileSettings
import home.iptv.shared.sync.SportMemory
import home.iptv.shared.sync.SportTeam
import home.iptv.shared.sync.SportView
import home.iptv.tv.home.HomeSportClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class HomeSportTest {
    private class MemoryStore : SportStore {
        private val cache = HashMap<String, Pair<String, Long>>()
        private var lastAt = 0L
        private val learnedMap = HashMap<String, List<String>>()
        private val prefMap = HashMap<String, String>()
        private val merged = HashSet<String>()

        override fun cached(key: String) = cache[key]?.first
        override fun saveCached(key: String, text: String, at: Long) { cache[key] = text to at; lastAt = at }
        override fun cachedAt() = lastAt
        override fun savedAt(key: String) = cache[key]?.second
        override fun learned(profileId: String, league: League) = learnedMap["$profileId:${league.key}"].orEmpty()
        override fun saveLearned(profileId: String, league: League, groups: List<String>) {
            learnedMap["$profileId:${league.key}"] = groups
        }
        override fun preferred(profileId: String, teamKey: String) = prefMap["$profileId:$teamKey"]
        override fun savePreferred(profileId: String, teamKey: String, groupId: String?) {
            if (groupId == null) prefMap.remove("$profileId:$teamKey") else prefMap["$profileId:$teamKey"] = groupId
        }
        override fun allPreferred(profileId: String): Map<String, String> =
            prefMap.filterKeys { it.startsWith("$profileId:") }.mapKeys { it.key.removePrefix("$profileId:") }
        override fun allLearned(profileId: String): Map<String, List<String>> =
            learnedMap.filterKeys { it.startsWith("$profileId:") }.mapKeys { it.key.removePrefix("$profileId:") }
        override fun isMemoryMerged(profileId: String) = profileId in merged
        override fun markMemoryMerged(profileId: String) { merged += profileId }
        override fun applySharedMemory(profileId: String, memory: SportMemory) {
            prefMap.keys.removeAll { it.startsWith("$profileId:") }
            learnedMap.keys.removeAll { it.startsWith("$profileId:") }
            for ((k, v) in memory.preferred) prefMap["$profileId:$k"] = v
            for ((k, v) in memory.learned) learnedMap["$profileId:$k"] = v
        }
    }

    private class FakeHomeSport(var handler: suspend (SportBoardRequest) -> SportBoardReply?) : HomeSportClient {
        val requests = mutableListOf<SportBoardRequest>()
        val invalidations = mutableListOf<String?>()
        override suspend fun sportBoard(request: SportBoardRequest): SportBoardReply? {
            requests += request
            return handler(request)
        }
        override suspend fun invalidateSport(profileId: String?) {
            invalidations += profileId
        }
    }

    private class FakeMemorySync(
        var shared: SportMemory = SportMemory(),
        var failMerge: Boolean = false,
    ) : SportMemorySync {
        var mergeCalls = 0
        val setPrefCalls = mutableListOf<Triple<String, String, String?>>()
        val learnCalls = mutableListOf<Triple<String, String, String>>()
        val forgetCalls = mutableListOf<Triple<String, String, String>>()

        override suspend fun get(profileId: String): SportMemory = shared
        override suspend fun merge(profileId: String, local: SportMemory): SportMemory {
            mergeCalls++
            if (failMerge) throw java.io.IOException("offline")
            val mergedPref = local.preferred + shared.preferred
            val mergedLearn = (local.learned.keys + shared.learned.keys).associateWith { k ->
                (shared.learned[k].orEmpty() + local.learned[k].orEmpty()).distinct().take(6)
            }
            shared = SportMemory(mergedPref, mergedLearn)
            return shared
        }
        override suspend fun setPreferred(profileId: String, teamKey: String, groupId: String?) {
            setPrefCalls += Triple(profileId, teamKey, groupId)
            val next = shared.preferred.toMutableMap()
            if (groupId == null) next.remove(teamKey) else next[teamKey] = groupId
            shared = shared.copy(preferred = next)
        }
        override suspend fun learn(profileId: String, leagueKey: String, groupId: String) {
            learnCalls += Triple(profileId, leagueKey, groupId)
            val next = shared.learned.toMutableMap()
            next[leagueKey] = (listOf(groupId) + next[leagueKey].orEmpty().filter { it != groupId }).take(6)
            shared = shared.copy(learned = next)
        }
        override suspend fun forgetLearned(profileId: String, leagueKey: String, groupId: String) {
            forgetCalls += Triple(profileId, leagueKey, groupId)
            val next = shared.learned.toMutableMap()
            next[leagueKey] = next[leagueKey].orEmpty().filter { it != groupId }
            shared = shared.copy(learned = next)
        }
    }

    private val now = Instant.parse("2026-11-21T23:30:00Z")
    private val mtlTeam = SportTeam("nhl", "10", "Canadiens de Montréal", "MTL")
    private val sampleMatch = Match(
        id = "nhl:2026020300",
        league = League.NHL,
        start = Instant.parse("2026-11-21T23:00:00Z"),
        state = MatchState.LIVE,
        home = "Canadiens",
        away = "Maple Leafs",
        homeId = "8",
        awayId = "10",
        homeAbbr = "MTL",
        awayAbbr = "TOR",
        homeScore = 3,
        awayScore = 2,
        detail = "2e",
        followed = true,
    )
    private val rds = SportChannel("auto:v1:rds", "RDS", "CA| SPORT", "s1")
    private val tva = SportChannel("auto:v1:tva sports", "TVA Sports", "CA| SPORT", "s2")
    private val sampleCands = SportCandidates(
        proposed = listOf(tva, rds),
        all = listOf(tva, rds),
        likely = listOf(tva, rds),
        reasons = mapOf(tva.groupId to ChannelReason.PREFERRED, rds.groupId to ChannelReason.OFFICIAL),
    )

    @Test
    fun usesHomeServerBoardWithoutLocalScheduleOrChannels() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + io)
        try {
            val store = MemoryStore()
            var localFetchCalls = 0
            var localChannelCalls = 0
            val home = FakeHomeSport {
                SportBoardReply(
                    rev = "r1",
                    account = "acct1",
                    fetchedAtMs = now.toEpochMilliseconds(),
                    matches = listOf(sampleMatch.toSnapMatch()),
                    standings = mapOf("nhl:MTL" to "2e Atlantique"),
                    candidates = mapOf(sampleMatch.id to sampleCands.toSnapSportCandidates()),
                    memory = SportMemory(preferred = mapOf("nhl:MTL" to tva.groupId)),
                    offline = false,
                )
            }
            val vm = SportViewModel(
                profileId = "p1",
                schedule = SportSchedule({ localFetchCalls++; error("should not fetch") }, store, now = { now }),
                channels = { localChannelCalls++; error("should not compute channels") },
                favorites = { emptyList() },
                store = store,
                prefs = MutableStateFlow(ProfilePrefs(1, emptyList(), emptyList(), emptyList(), null)),
                scope = scope,
                io = io,
                now = { now },
                offsetSeconds = { -18000 },
                teams = { listOf(mtlTeam) },
                homeSport = home,
                account = { "acct1" },
            )

            vm.start()
            advanceUntilIdle()

            assertEquals(0, localFetchCalls)
            assertEquals(0, localChannelCalls)
            assertEquals(1, home.requests.size)
            assertFalse(vm.ui.value.loading)
            assertEquals(1, vm.ui.value.strip.size)
            assertEquals(3, vm.ui.value.strip.single().homeScore)
            assertEquals("2e Atlantique", vm.ui.value.standings["nhl:MTL"])
            assertEquals(tva.groupId, vm.ui.value.candidates[sampleMatch.id]?.proposed?.first()?.groupId)
            assertEquals(tva.groupId, store.preferred("p1", "nhl:MTL"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun hideScoresAndRevealWorkLocallyOnServerMatches() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + io)
        try {
            val store = MemoryStore()
            val home = FakeHomeSport {
                SportBoardReply(
                    rev = "r1",
                    account = "acct1",
                    fetchedAtMs = now.toEpochMilliseconds(),
                    matches = listOf(sampleMatch.toSnapMatch()),
                    standings = mapOf("nhl:MTL" to "2e Atlantique"),
                    candidates = mapOf(sampleMatch.id to sampleCands.toSnapSportCandidates()),
                )
            }
            val vm = SportViewModel(
                profileId = "p1",
                schedule = SportSchedule({ "{}" }, store, now = { now }),
                channels = { emptyList() },
                favorites = { emptyList() },
                store = store,
                prefs = MutableStateFlow(ProfilePrefs(1, emptyList(), emptyList(), emptyList(), null)),
                scope = scope,
                io = io,
                now = { now },
                offsetSeconds = { -18000 },
                teams = { listOf(mtlTeam) },
                view = { SportView(hideScores = true) },
                homeSport = home,
                account = { "acct1" },
            )

            vm.start()
            advanceUntilIdle()

            assertTrue(vm.ui.value.hideScores)
            assertNull(vm.ui.value.strip.single().homeScore)
            assertTrue(vm.ui.value.standings.isEmpty())

            vm.reveal(sampleMatch.id)
            advanceUntilIdle()

            // Dévoilement local instantané, aucun second appel au serveur.
            assertEquals(1, home.requests.size)
            assertEquals(3, vm.ui.value.strip.single().homeScore)
            assertEquals(2, vm.ui.value.strip.single().awayScore)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun fallsBackToLocalWhenServerUnavailableOrAccountMismatch() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + io)
        try {
            val store = MemoryStore()
            var localFetchCalls = 0
            val home = FakeHomeSport {
                // Mauvais compte IPTV -> doit être ignoré !
                SportBoardReply(
                    rev = "r1",
                    account = "autre-compte",
                    fetchedAtMs = now.toEpochMilliseconds(),
                    matches = listOf(sampleMatch.toSnapMatch()),
                    candidates = mapOf(sampleMatch.id to sampleCands.toSnapSportCandidates()),
                )
            }
            val vm = SportViewModel(
                profileId = "p1",
                schedule = SportSchedule({ localFetchCalls++; """{"gameWeek":[],"events":[]}""" }, store, now = { now }),
                channels = { listOf(rds) },
                favorites = { emptyList() },
                store = store,
                prefs = MutableStateFlow(ProfilePrefs(1, emptyList(), emptyList(), emptyList(), null)),
                scope = scope,
                io = io,
                now = { now },
                offsetSeconds = { -18000 },
                teams = { listOf(mtlTeam) },
                homeSport = home,
                account = { "acct1" },
            )

            vm.start()
            advanceUntilIdle()

            assertTrue("Local fallback should have fetched schedules", localFetchCalls > 0)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun mergesLocalMemoryOnceAndSyncsPreferredLearnedWrongChannel() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + io)
        try {
            val store = MemoryStore()
            // Étoile déjà présente localement avant la mise à jour vers 0.45.0.
            store.savePreferred("p1", "nhl:MTL", rds.groupId)
            val memSync = FakeMemorySync(shared = SportMemory(learned = mapOf("nhl" to listOf(tva.groupId))))
            val home = FakeHomeSport {
                SportBoardReply(
                    rev = "r1",
                    account = "acct1",
                    fetchedAtMs = now.toEpochMilliseconds(),
                    matches = listOf(sampleMatch.toSnapMatch()),
                    candidates = mapOf(sampleMatch.id to sampleCands.toSnapSportCandidates()),
                    memory = memSync.shared,
                )
            }
            val vm = SportViewModel(
                profileId = "p1",
                schedule = SportSchedule({ "{}" }, store, now = { now }),
                channels = { listOf(rds, tva) },
                favorites = { emptyList() },
                store = store,
                prefs = MutableStateFlow(ProfilePrefs(1, emptyList(), emptyList(), emptyList(), null)),
                scope = scope,
                io = io,
                now = { now },
                offsetSeconds = { -18000 },
                teams = { listOf(mtlTeam) },
                homeSport = home,
                account = { "acct1" },
                memorySync = memSync,
            )

            vm.start()
            advanceUntilIdle()

            assertEquals(1, memSync.mergeCalls)
            assertTrue(store.isMemoryMerged("p1"))
            assertEquals(rds.groupId, memSync.shared.preferred["nhl:MTL"])
            assertEquals(listOf(tva.groupId), store.learned("p1", League.NHL))

            // Étoile changée vers TVA Sports -> synchronisée + invalidation serveur.
            vm.togglePreferred(sampleMatch, tva.groupId)
            advanceUntilIdle()
            assertEquals(tva.groupId, memSync.shared.preferred["nhl:MTL"])
            assertTrue(home.invalidations.contains("p1"))

            // Chaîne apprise puis marquée mauvaise -> synchronisée.
            vm.learned(sampleMatch, rds)
            advanceUntilIdle()
            assertTrue(memSync.learnCalls.any { it.second == "nhl" && it.third == rds.groupId })

            vm.wrongChannel(sampleMatch, rds)
            advanceUntilIdle()
            assertTrue(memSync.forgetCalls.any { it.second == "nhl" && it.third == rds.groupId })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun prewarmSkipsWhenHomeServerActive() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val store = MemoryStore()
        var fetchCalls = 0
        val prep = SportPrep(
            store = store,
            fetch = { fetchCalls++; "{}" },
            sources = NoSportSources,
            now = { now },
            skipWhen = { true },
        )
        val profile = Profile("p1", "Alex", "#3B82F6", "sport", "sombre", 0, ProfileSettings())
        val job = prep.prewarm(listOf(profile), backgroundScope, io)
        advanceUntilIdle()
        assertNull(job)
        assertEquals(0, fetchCalls)
        assertEquals(0, prep.runs)
    }

    @Test
    fun hidesDirectChannelsWhenPrefsNullAndPreservesLocalStarsOnOfflineMerge() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + io)
        try {
            val store = MemoryStore()
            store.savePreferred("p1", "nhl:MTL", rds.groupId)
            val memSync = FakeMemorySync(failMerge = true)
            val prefsFlow = MutableStateFlow<ProfilePrefs?>(null)
            val directEvent = SportChannel("x:99", "NHL 01: MTL vs TOR", "CA| NHL PPV", "99", direct = true)
            val mixedCands = SportCandidates(
                proposed = listOf(rds, directEvent),
                all = listOf(rds, directEvent),
                likely = listOf(rds, directEvent),
                reasons = mapOf(rds.groupId to ChannelReason.OFFICIAL, directEvent.groupId to ChannelReason.EVENT),
            )
            val home = FakeHomeSport {
                SportBoardReply(
                    rev = "r1",
                    account = "acct1",
                    fetchedAtMs = now.toEpochMilliseconds(),
                    matches = listOf(sampleMatch.toSnapMatch()),
                    candidates = mapOf(sampleMatch.id to mixedCands.toSnapSportCandidates()),
                    memory = SportMemory(), // vide côté serveur car la fusion a échoué hors-ligne
                )
            }
            val vm = SportViewModel(
                profileId = "p1",
                schedule = SportSchedule({ "{}" }, store, now = { now }),
                channels = { emptyList() },
                favorites = { emptyList() },
                store = store,
                prefs = prefsFlow,
                scope = scope,
                io = io,
                now = { now },
                offsetSeconds = { -18000 },
                teams = { listOf(mtlTeam) },
                homeSport = home,
                account = { "acct1" },
                memorySync = memSync,
            )

            vm.start()
            advanceUntilIdle()

            // Fusion hors-ligne -> l'étoile locale NE DOIT PAS être effacée par reply.memory vide !
            assertFalse(store.isMemoryMerged("p1"))
            assertEquals(rds.groupId, store.preferred("p1", "nhl:MTL"))
            // prefs == null -> aucune chaîne du Direct proposée (règle « masqué gagne »), seule la chaîne Sport directe passe.
            val candsBeforePrefs = vm.ui.value.candidates[sampleMatch.id]!!
            assertEquals(listOf(directEvent.groupId), candsBeforePrefs.proposed.map { it.groupId })

            // Arrivée des préférences -> le tableau est rechargé avec les chaînes visibles du profil.
            prefsFlow.value = ProfilePrefs(1, emptyList(), emptyList(), emptyList(), null)
            advanceUntilIdle()
            val candsAfterPrefs = vm.ui.value.candidates[sampleMatch.id]!!
            assertEquals(listOf(rds.groupId, directEvent.groupId), candsAfterPrefs.proposed.map { it.groupId })
        } finally {
            scope.cancel()
        }
    }
}
