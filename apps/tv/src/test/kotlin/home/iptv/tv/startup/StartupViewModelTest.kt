package home.iptv.tv.startup

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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val PROFILES = listOf(Profile("p1", "Papa", "#2C64F0", "live", "dark", 0))
private val CREDS = XtreamCreds("http://s.example", "u", "p")

private class FakeStore : LocalStore {
    override fun loadProgressQueue(): List<home.iptv.shared.progress.QueuedProgress> = emptyList()
    override fun persistNow(): Boolean = true
    override fun saveProgressQueue(queue: List<home.iptv.shared.progress.QueuedProgress>) {}
    override fun update(transform: (List<home.iptv.shared.progress.QueuedProgress>) -> List<home.iptv.shared.progress.QueuedProgress>) {}
    override fun audioLang(profileId: String): String? = null
    override fun subtitleLang(profileId: String): String? = null
    override fun saveTrackPrefs(profileId: String, audioLang: String?, subtitleLang: String?) {}
    override fun lastTab(profileId: String): String? = null
    override fun saveLastTab(profileId: String, tab: String) {}
    private val groupCache = mutableMapOf<String, home.iptv.shared.groups.GroupSnapshot>()
    private val sourceCache = mutableMapOf<Pair<String, String>, String>()
    override fun groupSnapshot(h: String) = groupCache[h]
    override fun saveGroupSnapshot(h: String, snapshot: home.iptv.shared.groups.GroupSnapshot) { groupCache[h] = snapshot }
    override fun lastGoodSource(profileId: String, groupId: String) = sourceCache[profileId to groupId]
    override fun saveLastGoodSource(profileId: String, groupId: String, sourceId: String) { sourceCache[profileId to groupId] = sourceId }

    var hh: String? = null
    var c: XtreamCreds? = null
    var p: List<Profile> = emptyList()
    override fun householdId() = hh
    override fun saveHouseholdId(id: String) { hh = id }
    override fun creds() = c
    override fun saveCreds(c: XtreamCreds) { this.c = c }
    override fun profiles() = p
    override fun saveProfiles(p: List<Profile>) { this.p = p }
    override fun prefs(profileId: String): ProfilePrefs? = null
    override fun savePrefs(profileId: String, prefs: ProfilePrefs) {}
}

private class FakeSync(private val clock: () -> Instant) : SyncClient {
    var household: String? = null
    var creds: XtreamCreds? = CREDS
    var profiles: List<Profile> = PROFILES
    var down = false
    var codes = 0
    var householdCalls = 0
    var attachAfterCalls: Int? = null
    var hangHousehold = false
    var hangCreds = false
    override suspend fun publishSeriesCategories(householdId: String, cats: List<PublishedCategory>) {}
    override suspend fun ensureSignedIn() { if (down) throw IOException() }
    override suspend fun currentHousehold(): String? {
        if (down) throw IOException()
        if (hangHousehold) awaitCancellation()
        householdCalls++
        attachAfterCalls?.let { if (householdCalls > it) household = "h1" }
        return household
    }
    override suspend fun createPairingCode(deviceName: String): PairingState.Waiting {
        if (down) throw IOException()
        codes++
        val code = if (codes == 1) "ABC234" else "XYZ789"
        return PairingState.Waiting(code, clock() + 10.minutes)
    }
    override suspend fun credentials(householdId: String): XtreamCreds? {
        if (hangCreds) awaitCancellation()
        return creds
    }
    override suspend fun profiles(householdId: String) = profiles
    override suspend fun globalHiddenCategories(householdId: String): List<HiddenItem> = emptyList()
    override suspend fun publishLiveCategories(householdId: String, cats: List<PublishedCategory>): Map<String, String?> = emptyMap()
    override suspend fun publishLiveChannels(householdId: String, categoryId: String, fingerprint: String, chunk: Int, isLast: Boolean, channels: List<PublishedChannel>) {}
    override suspend fun profilePrefs(profileId: String): ProfilePrefs = ProfilePrefs(0, emptyList(), emptyList(), emptyList(), null)
    override suspend fun prefsRevision(householdId: String): Long = 0
}

@OptIn(ExperimentalCoroutinesApi::class)
class StartupViewModelTest {
    private fun TestScope.clock() = object : Clock {
        override fun now() = Instant.fromEpochMilliseconds(1_700_000_000_000L + testScheduler.currentTime)
    }

    private fun TestScope.vm(sync: SyncClient, store: LocalStore) = StartupViewModel(
        sync, store, "https://config.exemple", pollEvery = 3.seconds, clock = clock(), scope = backgroundScope,
    )

    @Test
    fun dejaRattacheAfficheQuiRegardeSansCreerDeCode() = runTest {
        val sync = FakeSync { clock().now() }.apply { household = "h1" }
        val store = FakeStore()
        val vm = vm(sync, store); vm.start(); runCurrent()
        assertEquals(StartupUi.Ready(PROFILES, offline = false), vm.ui.value)
        assertEquals(0, sync.codes)
        assertEquals("h1", store.hh)
    }

    @Test
    fun identifiantsModifiesCoteSupabaseSontSauvesLocalement() = runTest {
        val sync = FakeSync { clock().now() }.apply { household = "h1" }
        val store = FakeStore().apply { hh = "h1"; c = XtreamCreds("http://old", "o", "o") }
        val nouveaux = XtreamCreds("http://new.example", "n", "n")
        sync.creds = nouveaux
        val vm = vm(sync, store); vm.start(); runCurrent()
        assertEquals(nouveaux, store.c)
    }

    @Test
    fun nonRattacheeAfficheLeQrPuisReadyQuandLeFoyerApparait() = runTest {
        val sync = FakeSync { clock().now() }.apply { attachAfterCalls = 2 }
        val store = FakeStore()
        val vm = vm(sync, store); vm.start(); runCurrent()
        assertEquals(StartupUi.Pairing("ABC234", "https://config.exemple/?code=ABC234"), vm.ui.value)
        advanceTimeBy(3.seconds + 1.milliseconds); runCurrent()
        assertIs<StartupUi.Pairing>(vm.ui.value)
        advanceTimeBy(10.seconds); runCurrent()
        assertEquals(StartupUi.Ready(PROFILES, offline = false), vm.ui.value)
    }

    @Test
    fun codeExpireEstRemplaceParUnNouveau() = runTest {
        val sync = FakeSync { clock().now() }
        val vm = vm(sync, FakeStore()); vm.start(); runCurrent()
        advanceTimeBy(10.minutes + 4.seconds); runCurrent()
        assertEquals(StartupUi.Pairing("XYZ789", "https://config.exemple/?code=XYZ789"), vm.ui.value)
    }

    @Test
    fun relanceApresRattachementOuvreDirectementQuiRegarde() = runTest {
        val sync = FakeSync { clock().now() }.apply { attachAfterCalls = 1 }
        val store = FakeStore()
        val vm1 = vm(sync, store); vm1.start(); runCurrent()
        advanceTimeBy(10.seconds); runCurrent()
        assertIs<StartupUi.Ready>(vm1.ui.value)
        val codesAvant = sync.codes
        val vm2 = vm(sync, store); vm2.start(); runCurrent()
        assertEquals(StartupUi.Ready(PROFILES, offline = false), vm2.ui.value)
        assertEquals(codesAvant, sync.codes)
    }

    @Test
    fun horsLigneAvecFoyerLocalOuvreLesProfilsEnCache() = runTest {
        val sync = FakeSync { clock().now() }.apply { down = true }
        val store = FakeStore().apply { hh = "h1"; c = CREDS; p = PROFILES }
        val vm = vm(sync, store); vm.start()
        advanceTimeBy(5.seconds); runCurrent()
        assertEquals(StartupUi.Ready(PROFILES, offline = true), vm.ui.value)
    }

    @Test
    fun horsLigneSansRienEnLocalEchoueAvecLeMessageReseau() = runTest {
        val sync = FakeSync { clock().now() }.apply { down = true }
        val vm = vm(sync, FakeStore()); vm.start()
        advanceTimeBy(5.seconds); runCurrent()
        assertEquals(StartupUi.Failed("Pas de connexion internet. Vérifie le réseau de la télé."), vm.ui.value)
    }

    @Test
    fun rattacheeSansIdentifiantsEchoueAvecLeMessageIdentifiants() = runTest {
        val sync = FakeSync { clock().now() }.apply { household = "h1"; creds = null }
        val vm = vm(sync, FakeStore()); vm.start(); runCurrent()
        assertEquals(StartupUi.Failed("Ajoute tes identifiants IPTV sur la page de configuration."), vm.ui.value)
    }

    @Test
    fun reseauQuiPendAvecFoyerLocalOuvreHorsLigneSansAttenteInfinie() = runTest {
        val sync = FakeSync { clock().now() }.apply { hangHousehold = true }
        val store = FakeStore().apply { hh = "h1"; c = CREDS; p = PROFILES }
        val vm = vm(sync, store); vm.start()
        advanceTimeBy(5.seconds); runCurrent()
        assertEquals(StartupUi.Ready(PROFILES, offline = true), vm.ui.value)
    }

    @Test
    fun identifiantsQuiPendentAvecFoyerLocalOuvrentHorsLigne() = runTest {
        val sync = FakeSync { clock().now() }.apply { household = "h1"; hangCreds = true }
        val store = FakeStore().apply { hh = "h1"; c = CREDS; p = PROFILES }
        val vm = vm(sync, store); vm.start()
        advanceTimeBy(5.seconds); runCurrent()
        assertEquals(StartupUi.Ready(PROFILES, offline = true), vm.ui.value)
    }

    @Test
    fun reseauQuiPendSansRienEnLocalEchoueAvecLeMessageReseau() = runTest {
        val sync = FakeSync { clock().now() }.apply { hangHousehold = true }
        val vm = vm(sync, FakeStore()); vm.start()
        advanceTimeBy(5.seconds); runCurrent()
        assertEquals(StartupUi.Failed(MSG_OFFLINE), vm.ui.value)
    }

    @Test
    fun profilsSontSauvesLocalementPuisServisHorsLigne() = runTest {
        val sync = FakeSync { clock().now() }.apply { household = "h1" }
        val store = FakeStore()
        val vm1 = vm(sync, store); vm1.start(); runCurrent()
        assertEquals(PROFILES, store.profiles())
        sync.down = true
        val vm2 = vm(sync, store); vm2.start()
        advanceTimeBy(5.seconds); runCurrent()
        assertEquals(StartupUi.Ready(PROFILES, offline = true), vm2.ui.value)
    }
}
