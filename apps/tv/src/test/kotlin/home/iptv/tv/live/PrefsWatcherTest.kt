package home.iptv.tv.live

import home.iptv.shared.sync.HiddenItem
import home.iptv.shared.sync.PairingState
import home.iptv.shared.sync.Profile
import home.iptv.shared.sync.ProfilePrefs
import home.iptv.shared.sync.PublishedCategory
import home.iptv.shared.sync.PublishedChannel
import home.iptv.shared.sync.SyncClient
import home.iptv.shared.xtream.XtreamCreds
import home.iptv.tv.data.LocalStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

private fun prefs(rev: Long, vararg fav: String) = ProfilePrefs(rev, emptyList(), fav.toList(), emptyList(), null)

internal class PrefsStore(var saved: ProfilePrefs? = null) : LocalStore {
    private val groupCache = mutableMapOf<String, home.iptv.shared.groups.GroupSnapshot>()
    private val sourceCache = mutableMapOf<Pair<String, String>, String>()
    override fun groupSnapshot(h: String) = groupCache[h]
    override fun saveGroupSnapshot(h: String, snapshot: home.iptv.shared.groups.GroupSnapshot) { groupCache[h] = snapshot }
    override fun lastGoodSource(profileId: String, groupId: String) = sourceCache[profileId to groupId]
    override fun saveLastGoodSource(profileId: String, groupId: String, sourceId: String) { sourceCache[profileId to groupId] = sourceId }

    override fun householdId(): String? = "h1"
    override fun saveHouseholdId(id: String) {}
    override fun creds(): XtreamCreds? = null
    override fun saveCreds(c: XtreamCreds) {}
    override fun profiles(): List<Profile> = emptyList()
    override fun saveProfiles(p: List<Profile>) {}
    override fun prefs(profileId: String): ProfilePrefs? = saved
    override fun savePrefs(profileId: String, prefs: ProfilePrefs) { saved = prefs }
}

private class PrefsSync(var server: ProfilePrefs, var down: Boolean = false) : SyncClient {
    var revCalls = 0
    var prefsCalls = 0
    override suspend fun ensureSignedIn() {}
    override suspend fun currentHousehold(): String? = "h1"
    override suspend fun createPairingCode(deviceName: String): PairingState.Waiting = error("inutile")
    override suspend fun credentials(householdId: String): XtreamCreds? = null
    override suspend fun profiles(householdId: String): List<Profile> = emptyList()
    override suspend fun globalHiddenCategories(householdId: String): List<HiddenItem> = emptyList()
    override suspend fun publishLiveCategories(householdId: String, cats: List<PublishedCategory>): Map<String, String?> = emptyMap()
    override suspend fun publishLiveChannels(householdId: String, categoryId: String, fingerprint: String, chunk: Int, isLast: Boolean, channels: List<PublishedChannel>) {}
    override suspend fun profilePrefs(profileId: String): ProfilePrefs {
        prefsCalls++
        if (down) throw IOException("hors ligne")
        return server
    }
    override suspend fun prefsRevision(householdId: String): Long {
        revCalls++
        if (down) throw IOException("hors ligne")
        return server.rev
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PrefsWatcherTest {
    private fun TestScope.watcher(sync: SyncClient, store: LocalStore, household: String? = "h1") =
        PrefsWatcher(sync, store, { household }, "p1", backgroundScope, io = StandardTestDispatcher(testScheduler))

    @Test
    fun emet_le_local_puis_le_serveur() = runTest {
        val store = PrefsStore(prefs(1, "10"))
        val sync = PrefsSync(prefs(2, "10", "11"))
        val w = watcher(sync, store)
        assertEquals(prefs(1, "10"), w.prefs.value) // le local, tout de suite
        w.start(); runCurrent()
        assertEquals(prefs(2, "10", "11"), w.prefs.value)
        assertEquals(prefs(2, "10", "11"), store.saved)
    }

    @Test
    fun sans_local_le_serveur_est_lu_meme_a_la_revision_zero() = runTest {
        val sync = PrefsSync(prefs(0))
        val w = watcher(sync, PrefsStore())
        w.start(); runCurrent()
        assertEquals(prefs(0), w.prefs.value)
        assertEquals(1, sync.prefsCalls)
    }

    @Test
    fun revision_inchangee_ne_relit_pas() = runTest {
        val sync = PrefsSync(prefs(5, "10"))
        val w = watcher(sync, PrefsStore(prefs(5, "10")))
        w.start(); runCurrent()
        advanceTimeBy(12_000); runCurrent()
        assertEquals(0, sync.prefsCalls)
        assertEquals(true, sync.revCalls >= 3)
    }

    @Test
    fun revision_changee_relit_en_moins_de_5_s() = runTest {
        val sync = PrefsSync(prefs(5, "10"))
        val w = watcher(sync, PrefsStore(prefs(5, "10")))
        w.start(); runCurrent()
        sync.server = prefs(6, "10", "12")
        advanceTimeBy(4_999); runCurrent()
        assertEquals(prefs(6, "10", "12"), w.prefs.value)
        assertEquals(1, sync.prefsCalls)
    }

    @Test
    fun hors_ligne_garde_le_local() = runTest {
        val sync = PrefsSync(prefs(9), down = true)
        val w = watcher(sync, PrefsStore(prefs(5, "10")))
        w.start(); runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(prefs(5, "10"), w.prefs.value)
        // Le reseau revient : la boucle a survecu aux erreurs.
        sync.down = false
        advanceTimeBy(4_000); runCurrent()
        assertEquals(prefs(9), w.prefs.value)
    }

    @Test
    fun foyer_inconnu_garde_le_local_sans_interroger() = runTest {
        val sync = PrefsSync(prefs(9))
        val w = watcher(sync, PrefsStore(prefs(5, "10")), household = null)
        w.start(); runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(0, sync.revCalls)
        assertEquals(prefs(5, "10"), w.prefs.value)
    }

    @Test
    fun stop_arrete_l_interrogation() = runTest {
        val sync = PrefsSync(prefs(5))
        val w = watcher(sync, PrefsStore(prefs(5)))
        w.start(); runCurrent()
        advanceTimeBy(8_000); runCurrent()
        w.stop()
        val calls = sync.revCalls
        sync.server = prefs(6)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(calls, sync.revCalls)
        assertEquals(prefs(5), w.prefs.value)
        // Redemarrer reprend l'interrogation.
        w.start(); runCurrent()
        assertEquals(prefs(6), w.prefs.value)
    }
}
