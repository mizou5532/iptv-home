package home.iptv.shared.catalog

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import home.iptv.shared.sync.PairingState
import home.iptv.shared.sync.Profile
import home.iptv.shared.sync.HiddenItem
import home.iptv.shared.sync.ProfilePrefs
import home.iptv.shared.sync.PublishedCategory
import home.iptv.shared.sync.PublishedChannel
import home.iptv.shared.sync.SyncClient
import home.iptv.shared.xtream.LiveCategory
import home.iptv.shared.xtream.LiveStream
import home.iptv.shared.xtream.XtreamCreds
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private data class ChannelCall(
    val category: String, val fingerprint: String, val chunk: Int, val isLast: Boolean, val channels: List<PublishedChannel>,
)

private class RecordingSync(
    var known: Map<String, String?> = emptyMap(),
    var failOn: String? = null,
    var cancelOn: String? = null,
) : SyncClient {
    var categoryCalls = mutableListOf<List<PublishedCategory>>()
    val channelCalls = mutableListOf<ChannelCall>()
    override suspend fun ensureSignedIn() {}
    override suspend fun currentHousehold(): String? = "h1"
    override suspend fun createPairingCode(deviceName: String): PairingState.Waiting = error("inutile")
    override suspend fun credentials(householdId: String): XtreamCreds? = null
    override suspend fun profiles(householdId: String): List<Profile> = emptyList()
    override suspend fun globalHiddenCategories(householdId: String): List<HiddenItem> = emptyList()
    override suspend fun publishLiveCategories(householdId: String, cats: List<PublishedCategory>): Map<String, String?> {
        categoryCalls += cats
        return known
    }
    override suspend fun publishLiveChannels(
        householdId: String, categoryId: String, fingerprint: String, chunk: Int, isLast: Boolean, channels: List<PublishedChannel>,
    ) {
        if (categoryId == cancelOn) throw CancellationException("annule")
        if (categoryId == failOn) throw RuntimeException("reseau")
        channelCalls += ChannelCall(categoryId, fingerprint, chunk, isLast, channels)
    }
    override suspend fun profilePrefs(profileId: String): ProfilePrefs = error("inutile")
    override suspend fun prefsRevision(householdId: String): Long = 0
}

private class Src(val cats: List<LiveCategory>, val streams: Map<String, List<LiveStream>>) : LiveSource {
    override suspend fun liveCategories() = cats
    override suspend fun liveStreams(categoryId: String) = streams[categoryId].orEmpty()
}

private fun st(id: String, cat: String, name: String = "Chaine $id", n: Int? = null) = LiveStream(id, name, cat, "http://icone", "epg", n)

class CatalogPublisherTest {
    private fun repo(): CatalogRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CatalogDatabase.Schema.create(driver)
        return CatalogRepository(CatalogDatabase(driver))
    }

    private val cats = listOf(LiveCategory("1", "Sport"), LiveCategory("2", "Films"), LiveCategory("3", "Adulte"))

    private suspend fun refreshed(
        r: CatalogRepository,
        streams: Map<String, List<LiveStream>>,
        hidden: Set<String> = setOf("3"),
    ) = r.refreshLive(Src(cats, streams), hidden)

    @Test
    fun publie_toutes_les_categories_meme_masquees() = runTest {
        val r = repo()
        val report = refreshed(r, mapOf("1" to listOf(st("a", "1"), st("b", "1")), "2" to listOf(st("c", "2"))))
        val sync = RecordingSync()
        val out = CatalogPublisher(sync, r).publish("h1", report)
        assertEquals(listOf(PublishedCategory("1", "Sport", 0), PublishedCategory("2", "Films", 1), PublishedCategory("3", "Adulte", 2)), sync.categoryCalls.single())
        assertEquals(PublishReport(3, 2, 3), out)
        assertEquals(setOf("1", "2"), sync.channelCalls.map { it.category }.toSet())
    }

    @Test
    fun chaines_sans_icone_avec_nom_normalise_et_position() = runTest {
        val r = repo()
        val report = refreshed(r, mapOf("1" to listOf(st("a", "1", "Télé  HD", 7), st("b", "1"))))
        val sync = RecordingSync()
        CatalogPublisher(sync, r).publish("h1", report)
        val call = sync.channelCalls.first { it.category == "1" }
        assertEquals(PublishedChannel("a", "Télé  HD", "tele hd", 7, 0), call.channels[0])
        assertEquals(PublishedChannel("b", "Chaine b", "chaine b", null, 1), call.channels[1])
    }

    @Test
    fun empreinte_identique_rien_n_est_renvoye() = runTest {
        val r = repo()
        val streams = mapOf("1" to listOf(st("a", "1"), st("b", "1", n = 2)), "2" to listOf(st("c", "2")))
        val report = refreshed(r, streams)
        val known = mapOf("1" to fingerprint(r.streams("1")), "2" to fingerprint(r.streams("2")), "3" to null)
        val sync = RecordingSync(known = known)
        val out = CatalogPublisher(sync, r).publish("h1", report)
        assertTrue(sync.channelCalls.isEmpty())
        assertEquals(PublishReport(3, 0, 0), out)
    }

    @Test
    fun empreinte_differente_republie_seulement_cette_categorie() = runTest {
        val r = repo()
        val report = refreshed(r, mapOf("1" to listOf(st("a", "1")), "2" to listOf(st("c", "2"))))
        val sync = RecordingSync(known = mapOf("1" to "ancienne", "2" to fingerprint(r.streams("2"))))
        CatalogPublisher(sync, r).publish("h1", report)
        assertEquals(listOf("1"), sync.channelCalls.map { it.category })
        assertEquals(fingerprint(r.streams("1")), sync.channelCalls.single().fingerprint)
    }

    @Test
    fun grande_categorie_decoupee_en_morceaux() = runTest {
        val r = repo()
        val big = (1..4500).map { st("s$it", "1") }
        val report = refreshed(r, mapOf("1" to big))
        val sync = RecordingSync()
        val out = CatalogPublisher(sync, r, chunkSize = 2000).publish("h1", report)
        val calls = sync.channelCalls.filter { it.category == "1" }
        assertEquals(listOf(0, 1, 2), calls.map { it.chunk })
        assertEquals(listOf(false, false, true), calls.map { it.isLast })
        assertEquals(listOf(2000, 2000, 500), calls.map { it.channels.size })
        assertEquals(4500, out.channels)
        assertEquals(setOf(fingerprint(r.streams("1"))), calls.map { it.fingerprint }.toSet())
    }

    @Test
    fun categorie_vide_en_cache_est_publiee_en_un_morceau_final() = runTest {
        val r = repo()
        val report = refreshed(r, mapOf("1" to emptyList()))
        val sync = RecordingSync()
        CatalogPublisher(sync, r).publish("h1", report)
        val c = sync.channelCalls.first { it.category == "1" }
        assertEquals(0, c.chunk)
        assertTrue(c.isLast)
        assertTrue(c.channels.isEmpty())
    }

    @Test
    fun erreur_sur_une_categorie_les_autres_continuent() = runTest {
        val r = repo()
        val report = refreshed(r, mapOf("1" to listOf(st("a", "1")), "2" to listOf(st("c", "2"))))
        val sync = RecordingSync(failOn = "1")
        val out = CatalogPublisher(sync, r).publish("h1", report)
        assertEquals(listOf("2"), sync.channelCalls.map { it.category })
        assertEquals(listOf("1"), out.failedCategories)
        assertEquals(1, out.publishedCategories)
    }

    @Test
    fun annulation_remonte() = runTest {
        val r = repo()
        val report = refreshed(r, mapOf("1" to listOf(st("a", "1")), "2" to listOf(st("c", "2"))))
        val sync = RecordingSync(cancelOn = "1")
        assertFailsWith<CancellationException> { CatalogPublisher(sync, r).publish("h1", report) }
        assertTrue(sync.channelCalls.isEmpty())
    }

    @Test
    fun rapport_sans_categories_ne_publie_rien() = runTest {
        val r = repo()
        val sync = RecordingSync()
        val out = CatalogPublisher(sync, r).publish("h1", RefreshReport(0, 0, emptyList()))
        assertEquals(PublishReport(0, 0, 0), out)
        assertTrue(sync.categoryCalls.isEmpty())
        assertTrue(sync.channelCalls.isEmpty())
    }

    @Test
    fun empreinte_stable_et_sensible_aux_champs() {
        val a = listOf(st("a", "1", "X", 1))
        assertEquals(fingerprint(a), fingerprint(listOf(st("a", "1", "X", 1))))
        assertTrue(fingerprint(a) != fingerprint(listOf(st("a", "1", "X", 2))))
        assertTrue(fingerprint(a) != fingerprint(listOf(st("a", "1", "Y", 1))))
        assertEquals(16, fingerprint(a).length)
    }
}
