package home.iptv.server

import home.iptv.shared.sync.HiddenItem
import home.iptv.shared.sync.PairingState
import home.iptv.shared.sync.Profile
import home.iptv.shared.sync.ProfilePrefs
import home.iptv.shared.sync.PublishedCategory
import home.iptv.shared.sync.PublishedChannel
import home.iptv.shared.sync.SyncClient
import home.iptv.shared.xtream.XtreamCreds
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** Faux SyncClient : seul l'appairage est simulé, le reste n'est jamais appelé. */
private class FakeSync(foyers: List<String?>) : SyncClient {
    private val file = ArrayDeque(foyers)
    val appels = mutableListOf<String>()

    override suspend fun ensureSignedIn() { appels += "signin" }
    override suspend fun currentHousehold(): String? = file.removeFirst().also { appels += "household" }
    override suspend fun createPairingCode(deviceName: String): PairingState.Waiting {
        appels += "code:$deviceName"
        return PairingState.Waiting("ABC123", Instant.fromEpochSeconds(0))
    }
    override suspend fun credentials(householdId: String): XtreamCreds? = error("inattendu")
    override suspend fun profiles(householdId: String): List<Profile> = error("inattendu")
    override suspend fun globalHiddenCategories(householdId: String): List<HiddenItem> = error("inattendu")
    override suspend fun publishLiveCategories(householdId: String, cats: List<PublishedCategory>): Map<String, String?> = error("inattendu")
    override suspend fun publishLiveChannels(
        householdId: String, categoryId: String, fingerprint: String, chunk: Int, isLast: Boolean, channels: List<PublishedChannel>,
    ) = error("inattendu")
    override suspend fun profilePrefs(profileId: String): ProfilePrefs = error("inattendu")
    override suspend fun prefsRevision(householdId: String): Long = error("inattendu")
    override suspend fun publishSeriesCategories(householdId: String, cats: List<PublishedCategory>) = error("inattendu")
}

class PairingTest {
    @Test
    fun waitsUntilApprovedThenReturnsHousehold() = runTest {
        val sync = FakeSync(listOf(null, null, "foyer-1"))
        val codes = mutableListOf<String>()
        val id = Pairing(sync, pollMs = 1).awaitHousehold { codes += it }
        assertEquals("foyer-1", id)
        assertEquals(listOf("ABC123"), codes)
        assertEquals(listOf("signin", "household", "code:Serveur maison", "household", "household"), sync.appels)
    }

    @Test
    fun alreadyPairedSkipsCode() = runTest {
        val sync = FakeSync(listOf("foyer-9"))
        val codes = mutableListOf<String>()
        assertEquals("foyer-9", Pairing(sync, pollMs = 1).awaitHousehold { codes += it })
        assertEquals(emptyList(), codes)
        assertEquals(listOf("signin", "household"), sync.appels)
    }
}
