package home.iptv.tv.live

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import home.iptv.shared.catalog.CatalogDatabase
import home.iptv.shared.catalog.CatalogRepository
import home.iptv.shared.catalog.LiveSource
import home.iptv.shared.catalog.RefreshReport
import home.iptv.shared.sync.HiddenItem
import home.iptv.shared.sync.PairingState
import home.iptv.shared.sync.Profile
import home.iptv.shared.sync.ProfilePrefs
import home.iptv.shared.sync.PublishedCategory
import home.iptv.shared.sync.PublishedChannel
import home.iptv.shared.sync.SyncClient
import home.iptv.shared.xtream.LiveCategory
import home.iptv.shared.xtream.LiveStream
import home.iptv.shared.xtream.XtreamCreds
import home.iptv.tv.data.LocalStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val SCATS = listOf(LiveCategory("1", "Sport"), LiveCategory("2", "Cine"))
private val SSTREAMS = mapOf(
    "1" to listOf(LiveStream("10", "Chaine 10", "1", null, null, null), LiveStream("11", "Chaine 11", "1", null, null, null)),
    "2" to listOf(LiveStream("20", "Chaine 20", "2", null, null, null)),
)
private val PA = Profile("pa", "A", "#000", "live", "dark", 0)
private val PB = Profile("pb", "B", "#000", "live", "dark", 1)

private fun sp(rev: Long, vararg fav: String) = ProfilePrefs(rev, emptyList(), fav.toList(), emptyList(), null)

private class MapStore : LocalStore {
    val map = mutableMapOf<String, ProfilePrefs>()
    override fun householdId(): String? = "h1"
    override fun saveHouseholdId(id: String) {}
    override fun creds(): XtreamCreds? = null
    override fun saveCreds(c: XtreamCreds) {}
    override fun profiles(): List<Profile> = emptyList()
    override fun saveProfiles(p: List<Profile>) {}
    override fun prefs(profileId: String): ProfilePrefs? = map[profileId]
    override fun savePrefs(profileId: String, prefs: ProfilePrefs) { map[profileId] = prefs }
}

private class SessionSync : SyncClient {
    val server = mutableMapOf<String, ProfilePrefs>()
    var rev = 1L
    var revCalls = 0
    val fetched = mutableListOf<String>()
    override suspend fun ensureSignedIn() {}
    override suspend fun currentHousehold(): String? = "h1"
    override suspend fun createPairingCode(deviceName: String): PairingState.Waiting = error("inutile")
    override suspend fun credentials(householdId: String): XtreamCreds? = null
    override suspend fun profiles(householdId: String): List<Profile> = emptyList()
    override suspend fun globalHiddenCategories(householdId: String): List<HiddenItem> = emptyList()
    override suspend fun publishLiveCategories(householdId: String, cats: List<PublishedCategory>): Map<String, String?> = emptyMap()
    override suspend fun publishLiveChannels(householdId: String, categoryId: String, fingerprint: String, chunk: Int, isLast: Boolean, channels: List<PublishedChannel>) {}
    override suspend fun profilePrefs(profileId: String): ProfilePrefs { fetched += profileId; return server.getValue(profileId) }
    override suspend fun prefsRevision(householdId: String): Long { revCalls++; return rev }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LiveSessionsTest {
    private val sync = SessionSync()
    private val store = MapStore()
    private var refreshCalls = 0
    private var publishCalls = 0
    private var refreshBody: suspend () -> Unit = {}

    private suspend fun cachedRepo(): CatalogRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CatalogDatabase.Schema.create(driver)
        val repo = CatalogRepository(CatalogDatabase(driver))
        repo.refreshLive(object : LiveSource {
            override suspend fun liveCategories() = SCATS
            override suspend fun liveStreams(categoryId: String) = SSTREAMS[categoryId].orEmpty()
        }, emptySet())
        return repo
    }

    private suspend fun TestScope.sessions() = LiveSessions(
        backgroundScope, sync, store, cachedRepo(), { "h1" },
        refresh = { refreshCalls++; refreshBody(); RefreshReport(2, 3, emptyList(), SCATS) },
        publish = { publishCalls++ },
        io = StandardTestDispatcher(testScheduler),
    ).also {
        sync.server["pa"] = sp(1, "10"); sync.server["pb"] = sp(1, "20")
    }

    private fun LiveSession.favorites() = vm.streams.value.map { it.id }

    @Test
    fun deux_profils_chacun_ses_preferences() = runTest {
        val s = sessions()
        store.map["pa"] = sp(1, "10"); store.map["pb"] = sp(1, "20")
        val a = s.open(PA); a.vm.start(); runCurrent()
        assertEquals(listOf("fav", "cat:1", "cat:2"), a.vm.sections.value.map { it.key })
        assertEquals(listOf("10"), a.favorites())

        val b = s.open(PB); b.vm.start(); runCurrent()
        assertEquals(listOf("20"), b.favorites()) // les favoris locaux de B, pas ceux de A
        sync.server["pb"] = sp(2, "11", "20"); sync.rev = 2
        advanceTimeBy(4_001); runCurrent()
        assertEquals(listOf("11", "20"), b.favorites())
        assertEquals(listOf("10"), a.favorites())
    }

    @Test
    fun un_seul_veilleur_actif_apres_changement() = runTest {
        val s = sessions()
        val a = s.open(PA); val b = s.open(PB)
        s.close(a) // l'ancien ecran se ferme APRES l'ouverture du nouveau : B reste actif
        runCurrent()
        val before = sync.revCalls
        advanceTimeBy(20_000); runCurrent()
        assertEquals(5, sync.revCalls - before, "un seul veilleur interroge : B")
        sync.server["pb"] = sp(2, "11"); sync.server["pa"] = sp(2, "10", "11"); sync.rev = 2
        sync.fetched.clear()
        advanceTimeBy(4_001); runCurrent()
        assertEquals(listOf("pb"), sync.fetched)
        s.close(b)
        val after = sync.revCalls
        advanceTimeBy(20_000); runCurrent()
        assertEquals(after, sync.revCalls)
    }

    @Test
    fun onStop_arrete_et_onStart_reprend_le_veilleur_courant() = runTest {
        val s = sessions()
        s.open(PA); runCurrent()
        s.stop()
        val n = sync.revCalls
        advanceTimeBy(20_000); runCurrent()
        assertEquals(n, sync.revCalls)
        s.resume(); runCurrent()
        assertTrue(sync.revCalls > n)
    }

    @Test
    fun ancienne_session_annulee() = runTest {
        var cancelled = false
        refreshBody = { try { awaitCancellation() } finally { cancelled = true } }
        val s = sessions()
        store.map["pa"] = sp(1, "10")
        val a = s.open(PA); a.vm.start(); runCurrent()
        assertTrue(a.isActive)
        refreshBody = {}
        val b = s.open(PB); b.vm.start(); runCurrent()
        assertFalse(a.isActive)
        assertTrue(cancelled, "le rafraichissement de A est annule")
        // Le collecteur de A est arrete : plus aucune mise a jour.
        val seen = a.favorites()
        store.map["pa"] = sp(1, "10")
        sync.server["pa"] = sp(5, "11"); sync.rev = 5
        advanceTimeBy(10_000); runCurrent()
        assertEquals(seen, a.favorites())
        assertTrue(b.isActive)
    }

    @Test
    fun rafraichissement_une_seule_fois_par_processus() = runTest {
        val s = sessions()
        val a = s.open(PA); a.vm.start(); runCurrent()
        s.close(a)
        val b = s.open(PB); b.vm.start(); runCurrent()
        assertEquals(1, refreshCalls)
        assertEquals(1, publishCalls)
        assertEquals(2, b.vm.categories.value.size) // B affiche le cache
    }
}
