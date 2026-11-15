package home.iptv.tv.live

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import home.iptv.shared.catalog.CatalogDatabase
import home.iptv.shared.catalog.CatalogRepository
import home.iptv.shared.catalog.LiveSource
import home.iptv.shared.sync.HiddenItem
import home.iptv.shared.sync.PairingState
import home.iptv.shared.sync.Profile
import home.iptv.shared.sync.ProfilePrefs
import home.iptv.shared.sync.PublishedCategory
import home.iptv.shared.sync.PublishedChannel
import home.iptv.shared.sync.SyncClient
import home.iptv.shared.xtream.LiveCategory
import home.iptv.shared.xtream.LiveStream
import home.iptv.shared.xtream.UserInfo
import home.iptv.shared.xtream.XtreamCreds
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val RCATS = listOf(LiveCategory("1", "Sport"), LiveCategory("2", "Cine"), LiveCategory("3", "Adulte"))
private val RSTREAMS = mapOf(
    "1" to listOf(LiveStream("10", "Chaine 10", "1", null, null, 1)),
    "2" to listOf(LiveStream("20", "Chaine 20", "2", null, null, null)),
    "3" to listOf(LiveStream("30", "Chaine 30", "3", null, null, null)),
)

private class RSource : LiveSource {
    val streamCalls = mutableListOf<String>()
    override suspend fun liveCategories() = RCATS
    override suspend fun liveStreams(categoryId: String): List<LiveStream> {
        streamCalls += categoryId
        return RSTREAMS[categoryId].orEmpty()
    }
}

private class RSync(
    val hidden: List<HiddenItem> = emptyList(),
    var failCategories: Boolean = false,
) : SyncClient {
    val publishedCategories = mutableListOf<List<String>>()
    val publishedChannels = mutableListOf<String>()
    override suspend fun publishSeriesCategories(householdId: String, cats: List<PublishedCategory>) {}
    override suspend fun ensureSignedIn() {}
    override suspend fun currentHousehold(): String? = "h1"
    override suspend fun createPairingCode(deviceName: String): PairingState.Waiting = error("inutile")
    override suspend fun credentials(householdId: String): XtreamCreds? = null
    override suspend fun profiles(householdId: String): List<Profile> = emptyList()
    override suspend fun globalHiddenCategories(householdId: String): List<HiddenItem> = hidden
    override suspend fun publishLiveCategories(householdId: String, cats: List<PublishedCategory>): Map<String, String?> {
        if (failCategories) throw IOException("coupe")
        publishedCategories += cats.map { it.id }
        return emptyMap()
    }
    override suspend fun publishLiveChannels(householdId: String, categoryId: String, fingerprint: String, chunk: Int, isLast: Boolean, channels: List<PublishedChannel>) {
        publishedChannels += categoryId
    }
    override suspend fun profilePrefs(profileId: String): ProfilePrefs = error("inutile")
    override suspend fun prefsRevision(householdId: String): Long = 0
}

class LiveRefresherTest {
    private fun repo(): CatalogRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CatalogDatabase.Schema.create(driver)
        return CatalogRepository(CatalogDatabase(driver))
    }

    private fun refresher(sync: SyncClient, repo: CatalogRepository, source: RSource, household: String? = "h1") =
        LiveRefresher(sync, repo, { household }, { UserInfo(true, 1, listOf("ts")) }, { source })

    @Test
    fun une_liste_des_masquees_qui_ne_repond_jamais_abandonne_sans_toucher_au_fournisseur() = runTest {
        // Essai TCL 0.4.0 : « Mise à jour » affiché en permanence. Un appel de synchro sans réponse ne doit jamais
        // retenir le rafraîchissement : il abandonne (le cache reste affiché) et le fournisseur n'est pas interrogé.
        val sync = object : SyncClient by RSync() {
            override suspend fun globalHiddenCategories(householdId: String): List<HiddenItem> = kotlinx.coroutines.awaitCancellation()
        }
        val source = RSource()
        val failure = runCatching { refresher(sync, repo(), source).run() }.exceptionOrNull()
        assertTrue(failure != null && failure !is kotlinx.coroutines.CancellationException, "abandon attendu (erreur ordinaire, pas une annulation) : $failure")
        assertTrue(source.streamCalls.isEmpty(), "fournisseur interrogé sans la liste des masquées : ${source.streamCalls}")
    }

    @Test
    fun publication_en_erreur_n_echoue_pas_le_rafraichissement() = runTest {
        val sync = RSync(failCategories = true)
        val repo = repo()
        val r = refresher(sync, repo, RSource())
        val report = r.run()
        assertEquals(3, report.categories)
        r.publish(report) // ne leve pas
        assertEquals(3, repo.categories().size)
    }

    @Test
    fun publication_sans_foyer_ou_sans_categories_ne_fait_rien() = runTest {
        val sync = RSync()
        val repo = repo()
        val report = refresher(sync, repo, RSource()).run()
        refresher(sync, repo, RSource(), household = null).publish(report)
        refresher(sync, repo, RSource()).publish(report.copy(providerCategories = emptyList()))
        assertTrue(sync.publishedCategories.isEmpty())
    }

    @Test
    fun la_publication_envoie_les_categories_et_les_chaines_du_cache() = runTest {
        val sync = RSync()
        val repo = repo()
        val r = refresher(sync, repo, RSource())
        r.publish(r.run())
        assertEquals(listOf(listOf("1", "2", "3")), sync.publishedCategories)
        assertEquals(listOf("1", "2", "3"), sync.publishedChannels)
    }

    @Test
    fun categorie_masquee_globale_jamais_demandee_au_fournisseur() = runTest {
        val sync = RSync(hidden = listOf(HiddenItem("live_category", "3")))
        val repo = repo()
        val source = RSource()
        val r = refresher(sync, repo, source)
        r.publish(r.run())
        assertEquals(listOf("1", "2"), source.streamCalls)
        // Ses chaines ne sont pas effacees de la copie publiee : aucune publication de chaines pour elle (la categorie reste listee).
        assertEquals(listOf("1", "2"), sync.publishedChannels)
        assertEquals(listOf(listOf("1", "2", "3")), sync.publishedCategories)
    }
}
