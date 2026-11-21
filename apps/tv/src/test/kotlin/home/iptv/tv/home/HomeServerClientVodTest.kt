package home.iptv.tv.home

import home.iptv.shared.home.MovieCategoryReply
import home.iptv.shared.home.SeriesCategoryReply
import home.iptv.shared.home.SnapMovie
import home.iptv.shared.home.SnapMovieInfo
import home.iptv.shared.home.SnapSeries
import home.iptv.shared.home.SnapSeriesInfo
import home.iptv.shared.home.SnapVersion
import home.iptv.shared.home.SnapVersionTag
import home.iptv.shared.home.SnapVodCategory
import home.iptv.shared.home.VersionsReply
import home.iptv.shared.home.VodManifest
import home.iptv.shared.meta.MediaKind
import home.iptv.shared.sync.HiddenItem
import home.iptv.shared.sync.HomeServerInfo
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
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

private class VodFakeSync(var info: HomeServerInfo?) : SyncClient {
    override suspend fun homeServer(householdId: String): HomeServerInfo? = info
    override suspend fun issueHomeServerToken(householdId: String): String = "tok-vod"
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

private class VodTokenStore : LocalStore by PrefsStore() {
    var tok: String? = "tok-vod"
    var lastContact: Long? = null
    override fun homeToken(householdId: String): String? = tok
    override fun saveHomeToken(householdId: String, token: String?) { tok = token }
    override fun lastServerContactMs(): Long? = lastContact
    override fun saveLastServerContactMs(ms: Long) { lastContact = ms }
}

class HomeServerClientVodTest {
    private val servers = mutableListOf<MockWebServer>()
    private val j = Json { encodeDefaults = true }

    @AfterTest
    fun tearDown() { servers.forEach { runCatching { it.shutdown() } } }

    private fun tlsServer(): Pair<MockWebServer, String> {
        val cert = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val hs = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val s = MockWebServer().apply { useHttps(hs.sslSocketFactory(), false); start() }
        servers += s
        return s to CertificatePinner.pin(cert.certificate)
    }

    private fun client(server: MockWebServer, pin: String, store: VodTokenStore = VodTokenStore(), clock: () -> Long = { 5_000L }): HomeServerClient {
        val url = server.url("/").toString().trimEnd('/')
        return HomeServerClient(VodFakeSync(HomeServerInfo(url, pin, "0.2.0", 0L)), store, { "h1" }, OkHttpClient(), clock, timeoutMs = 1_000L)
    }

    @Test
    fun manifestsCategoriesInfosAndVersionsWorkEndToEnd() = runTest {
        val (s, pin) = tlsServer()
        val store = VodTokenStore()
        val c = client(s, pin, store)

        val manifest = VodManifest("mrev1", "acct1", 10L, listOf(SnapVodCategory("10", "Action", 0, "crev1")), listOf("99"))
        s.enqueue(MockResponse().setResponseCode(200).setBody(j.encodeToString(VodManifest.serializer(), manifest)))
        val mr = c.movieManifest(null)
        assertIs<VodManifestResult.Fresh>(mr)
        assertEquals("mrev1", mr.manifest.rev)
        assertEquals(5_000L, store.lastContact)

        s.enqueue(MockResponse().setResponseCode(304))
        assertEquals(VodManifestResult.Unchanged, c.movieManifest("mrev1"))

        val catReply = MovieCategoryReply("10", "crev1", "acct1", listOf(SnapMovie("501", "10", "Film 1")))
        s.enqueue(MockResponse().setResponseCode(200).setBody(j.encodeToString(MovieCategoryReply.serializer(), catReply)))
        val cr = c.movieCategory("10", null)
        assertIs<MovieCategoryResult.Fresh>(cr)
        assertEquals(listOf("501"), cr.reply.items.map { it.id })

        val sCatReply = SeriesCategoryReply("20", "srev1", "acct1", listOf(SnapSeries("801", "20", "Série 1")))
        s.enqueue(MockResponse().setResponseCode(200).setBody(j.encodeToString(SeriesCategoryReply.serializer(), sCatReply)))
        val scr = c.seriesCategory("20", null)
        assertIs<SeriesCategoryResult.Fresh>(scr)
        assertEquals(listOf("801"), scr.reply.items.map { it.id })

        val mInfo = SnapMovieInfo(SnapMovie("501", "10", "Film 1"), null)
        s.enqueue(MockResponse().setResponseCode(200).setBody(j.encodeToString(SnapMovieInfo.serializer(), mInfo)))
        assertEquals("Film 1", c.movieInfo("501")?.summary?.name)

        val sInfo = SnapSeriesInfo(SnapSeries("801", "20", "Série 1"), emptyList())
        s.enqueue(MockResponse().setResponseCode(200).setBody(j.encodeToString(SnapSeriesInfo.serializer(), sInfo)))
        assertEquals("Série 1", c.seriesInfo("801")?.summary?.name)

        val vReply = VersionsReply("v1", mapOf("film|2024" to listOf(SnapVersion("501", "10", "FR - Film", SnapVersionTag("fr")))))
        s.enqueue(MockResponse().setResponseCode(200).setBody(j.encodeToString(VersionsReply.serializer(), vReply)))
        val vr = c.versions(MediaKind.MOVIE, listOf("501"))
        assertEquals("v1", vr?.rev)
        assertEquals(1, vr?.groups?.size)

        s.enqueue(MockResponse().setResponseCode(204))
        c.invalidateVersions()
        assertIs<HomeState.Present>(c.state.value)
    }

    @Test
    fun info404Or502DoesNotMarkServerAbsent() = runTest {
        val (s, pin) = tlsServer()
        val c = client(s, pin)

        s.enqueue(MockResponse().setResponseCode(200).setBody(j.encodeToString(SnapMovieInfo.serializer(), SnapMovieInfo(SnapMovie("1", "10", "Ok")))))
        assertEquals("Ok", c.movieInfo("1")?.summary?.name)
        assertIs<HomeState.Present>(c.state.value)

        s.enqueue(MockResponse().setResponseCode(404))
        assertNull(c.movieInfo("404"))
        assertIs<HomeState.Present>(c.state.value)

        s.enqueue(MockResponse().setResponseCode(502))
        assertNull(c.seriesInfo("502"))
        assertIs<HomeState.Present>(c.state.value)
    }

    @Test
    fun slowInfoTimesOutAndMarksAbsent() = runTest {
        val (s, pin) = tlsServer()
        val c = client(s, pin)

        s.enqueue(MockResponse().setResponseCode(200).setHeadersDelay(2_000, TimeUnit.MILLISECONDS).setBody("{}"))
        assertNull(c.movieInfo("1"))
        assertEquals(HomeState.Absent, c.state.value)
    }
}
