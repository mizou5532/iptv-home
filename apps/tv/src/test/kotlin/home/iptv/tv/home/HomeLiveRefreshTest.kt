package home.iptv.tv.home

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import home.iptv.shared.catalog.CatalogDatabase
import home.iptv.shared.catalog.CatalogRepository
import home.iptv.shared.catalog.LiveRefresher
import home.iptv.shared.catalog.LiveSource
import home.iptv.shared.home.LiveSnapshot
import home.iptv.shared.home.SnapCategory
import home.iptv.shared.home.SnapStream
import home.iptv.shared.sync.HiddenItem
import home.iptv.shared.sync.HomeServerInfo
import home.iptv.shared.sync.PairingState
import home.iptv.shared.sync.Profile
import home.iptv.shared.sync.ProfilePrefs
import home.iptv.shared.sync.PublishedCategory
import home.iptv.shared.sync.PublishedChannel
import home.iptv.shared.sync.SyncClient
import home.iptv.shared.xtream.EpgEntry
import home.iptv.shared.xtream.EpgSource
import home.iptv.shared.xtream.LiveCategory
import home.iptv.shared.xtream.LiveStream
import home.iptv.shared.xtream.UserInfo
import home.iptv.shared.xtream.XtreamCreds
import home.iptv.tv.data.LocalStore
import home.iptv.tv.live.PrefsStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

private class RefreshStore : LocalStore by PrefsStore() {
    var token: String? = "tok-initial"
    var rev: String? = null
    var contactMs: Long? = null
    override fun homeToken(householdId: String): String? = token
    override fun saveHomeToken(householdId: String, token: String?) { this.token = token }
    override fun lastSnapshotRev(): String? = rev
    override fun saveLastSnapshotRev(rev: String?) { this.rev = rev }
    override fun lastServerContactMs(): Long? = contactMs
    override fun saveLastServerContactMs(ms: Long) { this.contactMs = ms }
}

private class RefreshSync(var info: HomeServerInfo?) : SyncClient {
    var hidden = emptyList<HiddenItem>()
    var publishedCats = 0
    override suspend fun homeServer(householdId: String): HomeServerInfo? = info
    override suspend fun issueHomeServerToken(householdId: String): String = "tok-new"
    override suspend fun ensureSignedIn() {}
    override suspend fun currentHousehold(): String? = "h1"
    override suspend fun createPairingCode(deviceName: String): PairingState.Waiting = error("inutile")
    override suspend fun credentials(householdId: String): XtreamCreds? = null
    override suspend fun profiles(householdId: String): List<Profile> = emptyList()
    override suspend fun globalHiddenCategories(householdId: String): List<HiddenItem> = hidden
    override suspend fun publishLiveCategories(householdId: String, cats: List<PublishedCategory>): Map<String, String?> {
        publishedCats += cats.size
        return emptyMap()
    }
    override suspend fun publishLiveChannels(householdId: String, categoryId: String, fingerprint: String, chunk: Int, isLast: Boolean, channels: List<PublishedChannel>) {}
    override suspend fun profilePrefs(profileId: String): ProfilePrefs = error("inutile")
    override suspend fun prefsRevision(householdId: String): Long = 0
    override suspend fun publishSeriesCategories(householdId: String, cats: List<PublishedCategory>) {}
}

private class FakeProviderSource : LiveSource {
    var categoryCalls = 0
    override suspend fun liveCategories(): List<LiveCategory> {
        categoryCalls++
        return listOf(LiveCategory("p1", "Fournisseur Sport"))
    }
    override suspend fun liveStreams(categoryId: String): List<LiveStream> =
        listOf(LiveStream("ps1", "Chaine Fournisseur", categoryId, null, null, 1))
}

class HomeLiveRefreshTest {
    private val servers = mutableListOf<MockWebServer>()

    @AfterTest fun stop() { servers.forEach { runCatching { it.shutdown() } } }

    private fun newRepo(): CatalogRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CatalogDatabase.Schema.create(driver)
        return CatalogRepository(CatalogDatabase(driver))
    }

    private fun tlsServer(): Pair<MockWebServer, String> {
        val held = HeldCertificate.Builder().commonName("serveur-maison").build()
        val hc = HandshakeCertificates.Builder().heldCertificate(held).build()
        val s = MockWebServer().apply { useHttps(hc.sslSocketFactory(), false); start() }
        servers += s
        return s to CertificatePinner.pin(held.certificate)
    }

    private fun infoOf(s: MockWebServer, pin: String) = HomeServerInfo(s.url("/").toString().trimEnd('/'), pin, "0.43.0", 0L)

    private fun sampleSnapshot(rev: String = "rev-1", account: String = "acc-1") = LiveSnapshot(
        rev = rev,
        account = account,
        builtAt = 1_000L,
        categories = listOf(SnapCategory("c1", "Sport", 0)),
        streams = listOf(SnapStream("s1", "c1", "RDS HD", null, "rds.epg", 1, 0, "ca|rds", "RDS")),
        hidden = listOf("h99"),
    )

    private fun snapBody(s: LiveSnapshot) = MockResponse().setResponseCode(200).setBody(Json.encodeToString(LiveSnapshot.serializer(), s))

    @Test fun serverSnapshotFillsCache() = runTest {
        val (server, pin) = tlsServer()
        val sync = RefreshSync(infoOf(server, pin))
        val store = RefreshStore()
        val repo = newRepo()
        val provider = FakeProviderSource()
        val local = LiveRefresher(sync, repo, { "h1" }, { UserInfo(true, 1, listOf("ts")) }, { provider })
        var now = 50_000L
        val client = HomeServerClient(sync, store, { "h1" }, OkHttpClient(), { now })
        val refresh = HomeLiveRefresh(client, repo, local, store, { "acc-1" }, { now })

        server.enqueue(snapBody(sampleSnapshot()))
        val outcome = refresh.run { _, _ -> }

        assertTrue(outcome.fromServer)
        assertEquals(1, outcome.report.categories)
        assertEquals(1, outcome.report.streams)
        assertEquals(0, provider.categoryCalls)
        assertEquals("rev-1", store.rev)
        assertEquals(50_000L, store.contactMs)
        assertEquals(setOf("h99"), refresh.lastHidden)
        assertEquals("RDS HD", repo.streams("c1").single().name)
        assertEquals("ca|rds", repo.liveKeys()["s1"]?.autoKey)
        assertEquals("RDS", repo.liveKeys()["s1"]?.title)

        // Second passage : 304 Unchanged -> garde le cache, met a jour l'heure de contact.
        now = 80_000L
        server.enqueue(MockResponse().setResponseCode(304))
        val second = refresh.run { _, _ -> }
        assertTrue(second.fromServer)
        assertEquals(80_000L, store.contactMs)
        assertEquals(0, provider.categoryCalls)
        assertEquals("/v1/live", server.takeRequest().path)
        assertEquals("/v1/live?since=rev-1", server.takeRequest().path)
    }

    @Test fun snapshotFromOtherAccountIgnored() = runTest {
        val (server, pin) = tlsServer()
        val sync = RefreshSync(infoOf(server, pin))
        val store = RefreshStore()
        val repo = newRepo()
        val provider = FakeProviderSource()
        val local = LiveRefresher(sync, repo, { "h1" }, { UserInfo(true, 1, listOf("ts")) }, { provider })
        val now = 100_000L
        val client = HomeServerClient(sync, store, { "h1" }, OkHttpClient(), { now })
        val refresh = HomeLiveRefresh(client, repo, local, store, { "acc-actuel" }, { now })

        // Le serveur maison sert encore l'ancien compte IPTV : la tele l'ignore et rafraichit si son cache est vide.
        server.enqueue(snapBody(sampleSnapshot(account = "acc-ancien")))
        val outcome = refresh.run { _, _ -> }

        assertFalse(outcome.fromServer)
        assertNull(store.rev)
        assertEquals(1, provider.categoryCalls)
        assertEquals("Fournisseur Sport", repo.categories().single().name)
    }

    @Test fun serverAbsentRecentlyKeepsCache() = runTest {
        val sync = RefreshSync(null) // serveur absent
        val store = RefreshStore().apply {
            rev = "rev-1"
            contactMs = 1_000_000L
        }
        val repo = newRepo()
        repo.replaceLive(sampleSnapshot())
        val provider = FakeProviderSource()
        val local = LiveRefresher(sync, repo, { "h1" }, { UserInfo(true, 1, listOf("ts")) }, { provider })
        // 12 h plus tard (< 24 h) : le serveur est absent, mais le cache est recent -> pas d'appel au fournisseur.
        val now = 1_000_000L + 12 * 3_600_000L
        val client = HomeServerClient(sync, store, { "h1" }, OkHttpClient(), { now })
        val refresh = HomeLiveRefresh(client, repo, local, store, { "acc-1" }, { now })

        val outcome = refresh.run { _, _ -> }

        assertTrue(outcome.fromServer, "pas de publication ni de rafraichissement fournisseur quand le cache recent est garde")
        assertEquals(0, provider.categoryCalls)
        assertEquals(1, repo.categories().size)
        assertEquals("Sport", repo.categories().single().name)
    }

    @Test fun serverAbsentOver24hRefreshesLocally() = runTest {
        val sync = RefreshSync(null)
        val store = RefreshStore().apply {
            rev = "rev-1"
            contactMs = 1_000_000L
        }
        val repo = newRepo()
        repo.replaceLive(sampleSnapshot())
        val provider = FakeProviderSource()
        val local = LiveRefresher(sync, repo, { "h1" }, { UserInfo(true, 1, listOf("ts")) }, { provider })
        // Plus de 24 h sans serveur : la tele reprend la mise a jour chez le fournisseur.
        val now = 1_000_000L + 24 * 3_600_000L + 1L
        val client = HomeServerClient(sync, store, { "h1" }, OkHttpClient(), { now })
        val refresh = HomeLiveRefresh(client, repo, local, store, { "acc-1" }, { now })

        val outcome = refresh.run { _, _ -> }

        assertFalse(outcome.fromServer)
        assertEquals(1, provider.categoryCalls)
        assertEquals("Fournisseur Sport", repo.categories().single().name)
        assertNull(store.rev, "le cache vient maintenant du fournisseur : since repart a null au retour du serveur")
    }

    @Test fun emptyCacheRefreshesLocallyAtOnce() = runTest {
        val sync = RefreshSync(null)
        val store = RefreshStore().apply {
            contactMs = 1_000_000L // meme si le serveur a repondu il y a 1 min, un cache vide force le secours
        }
        val repo = newRepo()
        val provider = FakeProviderSource()
        val local = LiveRefresher(sync, repo, { "h1" }, { UserInfo(true, 1, listOf("ts")) }, { provider })
        val now = 1_060_000L
        val client = HomeServerClient(sync, store, { "h1" }, OkHttpClient(), { now })
        val refresh = HomeLiveRefresh(client, repo, local, store, { "acc-1" }, { now })

        val outcome = refresh.run { _, _ -> }

        assertFalse(outcome.fromServer)
        assertEquals(1, provider.categoryCalls)
        assertEquals(1, repo.categories().size)
    }

    @Test fun noPublishWhenFromServer() = runTest {
        val (server, pin) = tlsServer()
        val sync = RefreshSync(infoOf(server, pin))
        val store = RefreshStore()
        val repo = newRepo()
        val provider = FakeProviderSource()
        val local = LiveRefresher(sync, repo, { "h1" }, { UserInfo(true, 1, listOf("ts")) }, { provider })
        var now = 10_000L
        val client = HomeServerClient(sync, store, { "h1" }, OkHttpClient(), { now })
        val refresh = HomeLiveRefresh(client, repo, local, store, { "acc-1" }, { now })

        // Liste recue du serveur : publishIfNeeded ne publie rien (le serveur maison l'a deja fait).
        server.enqueue(snapBody(sampleSnapshot()))
        val fromSrv = refresh.run { _, _ -> }
        refresh.publish(fromSrv.report)
        assertEquals(0, sync.publishedCats)

        // Secours local (cache vide + serveur en panne) : publish envoie la copie pour l'iPhone.
        val repo2 = newRepo()
        val local2 = LiveRefresher(sync, repo2, { "h1" }, { UserInfo(true, 1, listOf("ts")) }, { provider })
        val clientDown = HomeServerClient(RefreshSync(null), RefreshStore(), { "h1" }, OkHttpClient(), { now })
        val refresh2 = HomeLiveRefresh(clientDown, repo2, local2, RefreshStore(), { "acc-1" }, { now })
        val fromLocal = refresh2.run { _, _ -> }
        refresh2.publish(fromLocal.report)
        assertEquals(1, sync.publishedCats)
    }

    @Test fun epgFallsBackToProvider() = runTest {
        val (server, pin) = tlsServer()
        val sync = RefreshSync(infoOf(server, pin))
        val store = RefreshStore()
        var now = 10_000L
        val client = HomeServerClient(sync, store, { "h1" }, OkHttpClient(), { now })
        var providerShortCalls = 0
        var providerFullCalls = 0
        val providerEpg = object : EpgSource {
            override suspend fun shortEpg(streamId: String, limit: Int): List<EpgEntry> {
                providerShortCalls++
                return listOf(EpgEntry("Secours court $streamId", Instant.fromEpochMilliseconds(1000), Instant.fromEpochMilliseconds(2000), true))
            }
            override suspend fun fullEpg(streamId: String): List<EpgEntry> {
                providerFullCalls++
                return listOf(EpgEntry("Secours complet $streamId", Instant.fromEpochMilliseconds(1000), Instant.fromEpochMilliseconds(2000), true))
            }
        }
        val epg = homeEpgSource(client, providerEpg)

        // 1) Le serveur maison repond : le fournisseur n'est pas interroge.
        server.enqueue(MockResponse().setBody("""{"entries":[{"title":"Serveur","startMs":1000,"endMs":2000,"nowPlaying":true}]}"""))
        val res1 = epg.shortEpg("s1", 4)
        assertEquals("Serveur", res1.single().title)
        assertEquals(0, providerShortCalls)

        // 2) Le serveur maison echoue (502 sur cette chaine) : secours transparent chez le fournisseur.
        server.enqueue(MockResponse().setResponseCode(502))
        val res2 = epg.shortEpg("s1", 4)
        assertEquals("Secours court s1", res2.single().title)
        assertEquals(1, providerShortCalls)

        server.enqueue(MockResponse().setResponseCode(502))
        val res3 = epg.fullEpg("s1")
        assertEquals("Secours complet s1", res3.single().title)
        assertEquals(1, providerFullCalls)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun liveBrowseUsesPrecomputedKeysFromServerSnapshot() = runTest {
        val repo = newRepo()
        // Deux chaines aux noms totalement differents, mais regroupees sous la meme cle precalculee par le serveur maison.
        repo.replaceLive(LiveSnapshot(
            rev = "rev-keys", account = "acc-1", builtAt = 1_000L,
            categories = listOf(SnapCategory("c1", "Sport", 0)),
            streams = listOf(
                SnapStream("s1", "c1", "Nom Un", null, null, 1, 0, "cle-serveur", "Titre Serveur"),
                SnapStream("s2", "c1", "Autre Deux", null, null, 2, 1, "cle-serveur", "Titre Serveur"),
            ),
            hidden = emptyList(),
        ))
        val vm = home.iptv.tv.live.LiveBrowseViewModel(
            repo = repo,
            refresh = null,
            scope = backgroundScope,
            io = StandardTestDispatcher(testScheduler),
        )
        vm.start()
        runCurrent()
        val row = vm.loaded.value.rows.single()
        assertEquals("Titre Serveur", row.group.title)
        assertEquals(listOf("s1", "s2"), row.group.sources.map { it.id })
    }
}
