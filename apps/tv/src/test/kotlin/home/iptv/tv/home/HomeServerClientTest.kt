package home.iptv.tv.home

import home.iptv.shared.home.LiveSnapshot
import home.iptv.shared.home.SnapCategory
import home.iptv.shared.home.SnapStream
import home.iptv.shared.sync.HomeServerInfo
import home.iptv.shared.sync.HiddenItem
import home.iptv.shared.sync.PairingState
import home.iptv.shared.sync.Profile
import home.iptv.shared.sync.ProfilePrefs
import home.iptv.shared.sync.PublishedCategory
import home.iptv.shared.sync.PublishedChannel
import home.iptv.shared.sync.SyncClient
import home.iptv.shared.xtream.XtreamCreds
import home.iptv.tv.data.LocalStore
import home.iptv.tv.live.PrefsStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import okio.GzipSink
import okio.buffer
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SUPABASE_JWT = "supabase-session-jwt-ne-doit-jamais-partir"

private class FakeSync(var info: HomeServerInfo?) : SyncClient {
    val infos = ArrayDeque<HomeServerInfo?>()
    var infoCalls = 0
    var issued = 0
    // Jamais transmis au client : il ne doit pas le connaitre.
    val sessionToken = SUPABASE_JWT
    override suspend fun homeServer(householdId: String): HomeServerInfo? { infoCalls++; return infos.removeFirstOrNull() ?: info }
    override suspend fun issueHomeServerToken(householdId: String): String = "tok-${++issued}"
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
}

private class TokenStore(var token: String? = null) : LocalStore by PrefsStore() {
    override fun homeToken(householdId: String): String? = token
    override fun saveHomeToken(householdId: String, token: String?) { this.token = token }
}

private val snapshot = LiveSnapshot(
    rev = "rev1", account = "acc", builtAt = 1L,
    categories = listOf(SnapCategory("c1", "Sport", 0)),
    streams = listOf(SnapStream("s1", "c1", "Chaine", null, "epg1", 1, 0, null, "Chaine")),
    hidden = emptyList(),
)

private fun gzip(text: String): Buffer {
    val out = Buffer()
    GzipSink(out).buffer().use { it.writeUtf8(text) }
    return out
}

class HomeServerClientTest {
    private val servers = mutableListOf<MockWebServer>()

    @AfterTest fun stop() { servers.forEach { runCatching { it.shutdown() } } }

    private class Tls(val cert: HeldCertificate) {
        val pin: String = CertificatePinner.pin(cert.certificate)
    }

    private fun tlsServer(): Pair<MockWebServer, Tls> {
        val held = HeldCertificate.Builder().commonName("serveur-maison").build()
        val hc = HandshakeCertificates.Builder().heldCertificate(held).build()
        val s = MockWebServer().apply { useHttps(hc.sslSocketFactory(), false); start() }
        servers += s
        return s to Tls(held)
    }

    private fun infoOf(s: MockWebServer, pin: String) = HomeServerInfo(s.url("/").toString().trimEnd('/'), pin, "1.2.3", 0L)

    private class Clock(var ms: Long = 1_000_000L) { fun now() = ms }

    private fun client(sync: SyncClient, store: LocalStore, clock: Clock, timeoutMs: Long = 1_500) =
        HomeServerClient(sync, store, { "h1" }, OkHttpClient(), clock::now, timeoutMs)

    private fun liveJson() = Json.encodeToString(LiveSnapshot.serializer(), snapshot)
    private fun gzipLive() = MockResponse().setHeader("Content-Encoding", "gzip").setBody(gzip(liveJson()))

    @Test fun freshThenUnchangedWith304() = runTest {
        val (server, tls) = tlsServer()
        val sync = FakeSync(infoOf(server, tls.pin))
        val c = client(sync, TokenStore(), Clock())
        server.enqueue(gzipLive())
        server.enqueue(MockResponse().setResponseCode(304))

        assertEquals(HomeState.Unknown, c.state.value)
        assertEquals(LiveSnapshotResult.Fresh(snapshot), c.liveSnapshot(null))
        assertEquals(HomeState.Present("1.2.3"), c.state.value)
        assertEquals(LiveSnapshotResult.Unchanged, c.liveSnapshot("rev1"))

        val first = server.takeRequest(); val second = server.takeRequest()
        assertEquals("/v1/live", first.path)
        assertEquals("/v1/live?since=rev1", second.path)
        assertEquals("Bearer tok-1", first.getHeader("Authorization"))
        assertEquals("1", first.getHeader("X-Home-Protocol"))
        assertEquals(1, sync.issued)
        assertEquals(1, sync.infoCalls) // adresse gardee 10 min
    }

    @Test fun epgMapsEntries() = runTest {
        val (server, tls) = tlsServer()
        val c = client(FakeSync(infoOf(server, tls.pin)), TokenStore("t"), Clock())
        server.enqueue(MockResponse().setBody("""{"entries":[{"title":"Journal","startMs":1000,"endMs":2000,"nowPlaying":true}]}"""))
        val list = c.epg("s 1", 3, true)!!
        assertEquals("Journal", list.single().title)
        assertEquals(1000L, list.single().start.toEpochMilliseconds())
        assertTrue(list.single().nowPlaying)
        assertEquals("/v1/epg/s%201?limit=3&full=1", server.takeRequest().path)
    }

    @Test fun wrongPinRefused() = runTest {
        val (server, _) = tlsServer()
        val other = tlsServer().second // empreinte d'un autre certificat
        val sync = FakeSync(infoOf(server, other.pin))
        val clock = Clock()
        val c = client(sync, TokenStore("t"), clock)
        server.enqueue(gzipLive())

        assertEquals(LiveSnapshotResult.Unavailable, c.liveSnapshot(null))
        assertEquals(HomeState.Absent, c.state.value)
        assertEquals(0, server.requestCount) // la requete (et le jeton) n'est jamais partie
        assertEquals(2, sync.infoCalls) // une seule relecture
    }

    @Test fun pinMismatchRereadsHomeServerOnce() = runTest {
        val (server, tls) = tlsServer()
        val stale = tlsServer().second
        val sync = FakeSync(infoOf(server, stale.pin))
        sync.infos.add(infoOf(server, stale.pin)) // 1re lecture : ancienne empreinte
        sync.infos.add(infoOf(server, tls.pin)) // relecture : serveur reinstalle
        val c = client(sync, TokenStore("t"), Clock())
        server.enqueue(gzipLive())

        assertEquals(LiveSnapshotResult.Fresh(snapshot), c.liveSnapshot(null))
        assertEquals(2, sync.infoCalls)
        assertEquals(1, server.requestCount)
    }

    @Test fun expiredTokenRenewedOnce() = runTest {
        val (server, tls) = tlsServer()
        val sync = FakeSync(infoOf(server, tls.pin))
        val store = TokenStore("vieux")
        val clock = Clock()
        val c = client(sync, store, clock)
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(gzipLive())

        assertEquals(LiveSnapshotResult.Fresh(snapshot), c.liveSnapshot(null))
        assertEquals(1, sync.issued)
        assertEquals("tok-1", store.token)
        assertEquals("Bearer vieux", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer tok-1", server.takeRequest().getHeader("Authorization"))

        // Encore refuse apres renouvellement : Absent, sans nouveau jeton.
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401))
        sync.issued = 0
        val c2 = client(sync, TokenStore("vieux"), clock)
        assertEquals(LiveSnapshotResult.Unavailable, c2.liveSnapshot(null))
        assertEquals(HomeState.Absent, c2.state.value)
        assertEquals(1, sync.issued)
    }

    @Test fun slowServerMarkedAbsentForOneMinute() = runTest {
        val (server, tls) = tlsServer()
        val sync = FakeSync(infoOf(server, tls.pin))
        val clock = Clock()
        val c = client(sync, TokenStore("t"), clock)
        server.enqueue(gzipLive().setHeadersDelay(2, TimeUnit.SECONDS))

        val t0 = System.nanoTime()
        assertEquals(LiveSnapshotResult.Unavailable, c.liveSnapshot(null))
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue(elapsedMs in 1_400..1_950, "delai $elapsedMs ms")
        assertEquals(HomeState.Absent, c.state.value)
        assertEquals(1, server.requestCount)

        // 59 s plus tard : aucune demande.
        clock.ms += 59_000
        assertEquals(LiveSnapshotResult.Unavailable, c.liveSnapshot(null))
        assertNull(c.epg("s1", 1, false))
        assertEquals(1, server.requestCount)
        assertEquals(1, sync.infoCalls)

        // Passe 60 s : nouvel essai.
        clock.ms += 1_001
        server.enqueue(gzipLive())
        assertEquals(LiveSnapshotResult.Fresh(snapshot), c.liveSnapshot(null))
        assertEquals(HomeState.Present("1.2.3"), c.state.value)
    }

    @Test fun noHomeServerRowMeansAbsent() = runTest {
        val sync = FakeSync(null)
        val clock = Clock()
        val c = client(sync, TokenStore(), clock)
        assertEquals(LiveSnapshotResult.Unavailable, c.liveSnapshot(null))
        assertEquals(HomeState.Absent, c.state.value)
        assertEquals(0, sync.issued)
        assertNull(c.epg("s1", 1, false))
        assertEquals(1, sync.infoCalls) // pas de relecture pendant 60 s
    }

    @Test fun supabaseSessionTokenNeverSent() = runTest {
        val (server, tls) = tlsServer()
        val sync = FakeSync(infoOf(server, tls.pin))
        val c = client(sync, TokenStore(), Clock())
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(gzipLive())
        server.enqueue(MockResponse().setBody("""{"entries":[]}"""))
        c.liveSnapshot("r")
        c.epg("s1", 2, false)
        repeat(server.requestCount) {
            val r = server.takeRequest()
            val everything = r.headers.toString() + r.requestUrl.toString() + r.body.readUtf8()
            assertFalse(everything.contains(sync.sessionToken))
            assertTrue(r.getHeader("Authorization")!!.startsWith("Bearer tok-"))
        }
        assertIs<HomeState.Present>(c.state.value)
    }
}
