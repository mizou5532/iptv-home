package home.iptv.server

import home.iptv.shared.groups.cachedAutoKey
import home.iptv.shared.groups.cachedTitle
import home.iptv.shared.groups.categoryCountries
import home.iptv.shared.sync.HiddenItem
import home.iptv.shared.xtream.LiveCategory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import home.iptv.shared.xtream.XtreamCreds
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LiveJobTest {
    private val dir: File = createTempDirectory("livejob").toFile().also { it.deleteOnExit() }
    private val file = File(dir, "live-snapshot.json")

    private fun job(
        sync: FakeLiveSync,
        source: FakeLiveSource,
        repo: home.iptv.shared.catalog.CatalogRepository = memoryRepo(),
        account: () -> String? = { home.iptv.shared.home.accountFingerprint(sync.creds.server, sync.creds.username) },
        clock: () -> Long = { 1_000L },
    ) = LiveJob(fakeRefresher(sync, repo, source), repo, sync, "h1", account, clock, file = file)

    @Test
    fun hiddenCategoryNeverRequested() = runTest {
        val source = FakeLiveSource()
        val j = job(FakeLiveSync(hidden = listOf(HiddenItem("live_category", "3"))), source)
        j.runOnce()
        assertEquals(listOf("1", "2"), source.streamCalls.sorted())
        val s = assertNotNull(j.snapshot.value)
        assertEquals(listOf("1", "2"), s.categories.map { it.id })
        assertTrue(s.streams.none { it.categoryId == "3" })
        assertEquals(listOf("3"), s.hidden)
    }

    @Test
    fun snapshotCarriesSameKeysAsTv() = runTest {
        val source = FakeLiveSource()
        val j = job(FakeLiveSync(), source)
        j.runOnce()
        val s = assertNotNull(j.snapshot.value)
        val countries = categoryCountries(s.categories.map { LiveCategory(it.id, it.name) })
        assertTrue(s.streams.isNotEmpty())
        s.streams.forEach {
            assertEquals(cachedAutoKey(it.name, countries[it.categoryId]), it.autoKey)
            assertEquals(cachedTitle(it.name), it.title)
        }
        assertEquals(listOf(0, 1, 2), s.categories.map { it.position })
        assertEquals(listOf(0, 1), s.streams.filter { it.categoryId == "1" }.map { it.position })
        assertEquals(home.iptv.shared.home.accountFingerprint("http://fournisseur.example", "alice"), s.account)
    }

    @Test
    fun publishesForIphoneAfterRefresh() = runTest {
        val sync = FakeLiveSync()
        job(sync, FakeLiveSource()).runOnce()
        assertEquals(listOf(listOf("1", "2", "3")), sync.publishedCategories)
    }

    @Test
    fun hiddenChangeTriggersRefresh() = runTest {
        val sync = FakeLiveSync()
        val source = FakeLiveSource()
        val j = job(sync, source, clock = { currentTime })
        backgroundScope.launch { j.loop() }
        runCurrent()
        assertEquals(3, source.streamCalls.size) // première mise à jour au démarrage
        advanceTimeBy(90_000) // rien n'a changé : pas de nouvelle mise à jour
        assertEquals(3, source.streamCalls.size)
        sync.hidden = listOf(HiddenItem("live_category", "3"))
        sync.revision = 1
        advanceTimeBy(31_000)
        assertEquals(5, source.streamCalls.size)
        assertEquals(listOf("3"), j.snapshot.value?.hidden)
    }

    @Test
    fun accountChangeWipesServerCache() = runTest {
        val repo = memoryRepo()
        val sync = FakeLiveSync()
        val source = FakeLiveSource()
        val j = job(sync, source, repo)
        j.runOnce()
        assertEquals(3, repo.categories().size)
        sync.creds = XtreamCreds("http://fournisseur.example", "bob", "x")
        source.fail = true // le nouveau fournisseur ne répond pas : rien de l'ancien compte ne doit rester
        assertFailsWith<java.io.IOException> { j.runOnce() }
        assertTrue(repo.categories().isEmpty())
        assertNull(j.snapshot.value)
        assertTrue(!file.exists())
        source.fail = false
        j.runOnce()
        assertEquals(home.iptv.shared.home.accountFingerprint("http://fournisseur.example", "bob"), j.snapshot.value?.account)
    }

    @Test
    fun providerFailureKeepsLastSnapshot() = runTest {
        val source = FakeLiveSource()
        val j = job(FakeLiveSync(), source)
        j.runOnce()
        val before = assertNotNull(j.snapshot.value)
        source.fail = true
        assertFailsWith<java.io.IOException> { j.runOnce() }
        assertEquals(before, j.snapshot.value)
    }

    @Test
    fun snapshotReloadedFromDisk() = runTest {
        val j = job(FakeLiveSync(), FakeLiveSource())
        j.runOnce()
        val reloaded = job(FakeLiveSync(), FakeLiveSource())
        assertEquals(j.snapshot.value, reloaded.snapshot.value)
        assertNotNull(reloaded.snapshot.value)
        assertTrue(!file.readText().contains("secret") && !file.readText().contains("alice"))
    }

    @Test
    fun freshKeptSnapshotSkipsStartupRefresh() = runTest {
        job(FakeLiveSync(), FakeLiveSource(), clock = { 1_000 }).runOnce()
        val source = FakeLiveSource()
        val j = job(FakeLiveSync(), source, clock = { 2_000 + currentTime })
        backgroundScope.launch { j.loop() }
        runCurrent()
        assertTrue(source.streamCalls.isEmpty())
    }

    @Test
    fun failedRefreshRetriesAfterFiveMinutes() = runTest {
        val source = FakeLiveSource(fail = true)
        val j = job(FakeLiveSync(), source, clock = { currentTime })
        backgroundScope.launch { j.loop() }
        runCurrent()
        assertEquals(1, source.categoryCalls)
        advanceTimeBy(4 * 60_000L)
        assertEquals(1, source.categoryCalls)
        advanceTimeBy(61_000L)
        assertEquals(2, source.categoryCalls)
        source.fail = false
        advanceTimeBy(5 * 60_000L + 1_000L)
        assertNotNull(j.snapshot.value)
    }

    @Test
    fun hiddenChangeRetriedAfterFailure() = runTest {
        val sync = FakeLiveSync()
        val source = FakeLiveSource()
        val j = job(sync, source, clock = { currentTime })
        backgroundScope.launch { j.loop() }
        runCurrent()
        sync.hidden = listOf(HiddenItem("live_category", "3"))
        sync.revision = 1
        source.fail = true
        advanceTimeBy(31_000)
        assertEquals(emptyList(), j.snapshot.value?.hidden)
        source.fail = false
        advanceTimeBy(31_000)
        assertEquals(listOf("3"), j.snapshot.value?.hidden)
    }

    @Test
    fun accountChangeSeenWithinOnePoll() = runTest {
        val sync = FakeLiveSync()
        val j = job(sync, FakeLiveSource(), clock = { currentTime })
        backgroundScope.launch { j.loop() }
        runCurrent()
        val avant = assertNotNull(j.snapshot.value).account
        sync.creds = XtreamCreds("http://fournisseur.example", "bob", "x")
        advanceTimeBy(31_000)
        val apres = assertNotNull(j.snapshot.value).account
        assertTrue(avant != apres)
    }
}
