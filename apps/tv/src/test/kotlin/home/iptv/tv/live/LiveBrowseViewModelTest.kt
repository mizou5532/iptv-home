package home.iptv.tv.live

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext
import home.iptv.shared.catalog.CatalogDatabase
import home.iptv.shared.catalog.CatalogRepository
import home.iptv.shared.catalog.LiveSource
import home.iptv.shared.catalog.RefreshReport
import home.iptv.shared.sync.HiddenItem
import home.iptv.shared.sync.PairingState
import home.iptv.shared.sync.Profile
import home.iptv.shared.sync.SyncClient
import home.iptv.shared.xtream.LiveCategory
import home.iptv.shared.xtream.LiveStream
import home.iptv.shared.xtream.UserInfo
import home.iptv.shared.xtream.XtreamCreds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun stream(id: String, cat: String, n: Int? = null) = LiveStream(id, "Chaine $id", cat, null, null, n)

private class FakeSource(
    val categories: List<LiveCategory>,
    val streams: Map<String, List<LiveStream>>,
) : LiveSource {
    val streamCalls = mutableListOf<String>()
    override suspend fun liveCategories() = categories
    override suspend fun liveStreams(categoryId: String): List<LiveStream> {
        streamCalls += categoryId
        return streams[categoryId].orEmpty()
    }
}

private class FakeSync(var hidden: List<HiddenItem> = emptyList(), var down: Boolean = false) : SyncClient {
    var hiddenCalls = 0
    override suspend fun ensureSignedIn() {}
    override suspend fun currentHousehold(): String? = "h1"
    override suspend fun createPairingCode(deviceName: String): PairingState.Waiting = error("inutile")
    override suspend fun credentials(householdId: String): XtreamCreds? = null
    override suspend fun profiles(householdId: String): List<Profile> = emptyList()
    override suspend fun globalHiddenCategories(householdId: String): List<HiddenItem> {
        hiddenCalls++
        if (down) throw IOException("hors ligne")
        return hidden
    }
}

private fun newRepo(): CatalogRepository {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    CatalogDatabase.Schema.create(driver)
    return CatalogRepository(CatalogDatabase(driver))
}

/** Marque le code exécuté par ce répartiteur (stand-in du fil d'entrée-sortie) ; le scope du ViewModel reste non marqué. */
private val inIo = ThreadLocal.withInitial { false }

private class MarkedDispatcher(private val inner: CoroutineDispatcher) : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        inner.dispatch(context) {
            inIo.set(true)
            try { block.run() } finally { inIo.set(false) }
        }
    }
}

private class RecordingDriver(private val inner: SqlDriver, val queryOnIo: MutableList<Boolean>) : SqlDriver by inner {
    override fun <R> executeQuery(
        identifier: Int?, sql: String, mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        queryOnIo += inIo.get()
        return inner.executeQuery(identifier, sql, mapper, parameters, binders)
    }
}

private val CATS = listOf(LiveCategory("1", "Sport"), LiveCategory("2", "Cine"), LiveCategory("3", "Adulte"))
private val STREAMS = mapOf(
    "1" to listOf(stream("10", "1", 1), stream("11", "1", 2)),
    "2" to listOf(stream("20", "2")),
    "3" to listOf(stream("30", "3")),
)

@OptIn(ExperimentalCoroutinesApi::class)
class LiveBrowseViewModelTest {
    private fun TestScope.vm(repo: CatalogRepository, refresh: suspend () -> RefreshReport) =
        LiveBrowseViewModel(repo, refresh, backgroundScope, StandardTestDispatcher(testScheduler))

    @Test
    fun le_cache_est_publie_avant_la_fin_du_rafraichissement() = runTest {
        val repo = newRepo()
        repo.refreshLive(FakeSource(listOf(LiveCategory("1", "Sport")), STREAMS), emptySet())
        val gate = CompletableDeferred<Unit>()
        val v = vm(repo) {
            gate.await()
            repo.refreshLive(FakeSource(CATS, STREAMS), emptySet())
        }
        v.start()
        runCurrent()
        assertEquals(listOf("Sport"), v.categories.value.map { it.name })
        assertEquals(listOf("10", "11"), v.streams.value.map { it.id })
        assertEquals("1", v.selectedId.value)
        assertTrue(v.refreshing.value)

        gate.complete(Unit)
        runCurrent()
        assertFalse(v.refreshing.value)
        assertEquals(listOf("Sport", "Cine", "Adulte"), v.categories.value.map { it.name })
    }

    @Test
    fun refreshing_repasse_a_faux_meme_si_le_rafraichissement_echoue_et_le_cache_reste() = runTest {
        val repo = newRepo()
        repo.refreshLive(FakeSource(CATS, STREAMS), emptySet())
        val v = vm(repo) { throw IOException("hors ligne") }
        v.start()
        runCurrent()
        assertFalse(v.refreshing.value)
        assertEquals(3, v.categories.value.size)
        assertEquals(2, v.streams.value.size)
    }

    @Test
    fun select_charge_les_chaines_de_la_categorie() = runTest {
        val repo = newRepo()
        repo.refreshLive(FakeSource(CATS, STREAMS), emptySet())
        val v = vm(repo) { RefreshReport(0, 0, emptyList()) }
        v.start()
        runCurrent()
        v.select("2")
        runCurrent()
        assertEquals("2", v.selectedId.value)
        assertEquals(listOf("20"), v.streams.value.map { it.id })
    }

    @Test
    fun le_rafraichissement_n_a_lieu_qu_une_fois_par_demarrage() = runTest {
        var calls = 0
        val v = vm(newRepo()) { calls++; RefreshReport(0, 0, emptyList()) }
        v.start(); runCurrent()
        v.start(); runCurrent()
        assertEquals(1, calls)
    }

    @Test
    fun cache_vide_puis_rafraichissement_remplit_et_selectionne_la_premiere_categorie() = runTest {
        val repo = newRepo()
        val v = vm(repo) { repo.refreshLive(FakeSource(CATS, STREAMS), emptySet()) }
        v.start()
        runCurrent()
        assertEquals(3, v.categories.value.size)
        assertEquals("1", v.selectedId.value)
        assertEquals(2, v.streams.value.size)
    }

    @Test
    fun rafraichissement_et_lectures_du_cache_tournent_hors_du_fil_principal() = runTest {
        val reads = mutableListOf<Boolean>()
        val driver = RecordingDriver(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY), reads)
        CatalogDatabase.Schema.create(driver)
        val repo = CatalogRepository(CatalogDatabase(driver))
        repo.refreshLive(FakeSource(CATS, STREAMS), emptySet())
        reads.clear()
        var refreshOnIo: Boolean? = null
        val mainScope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val v = LiveBrowseViewModel(
            repo,
            { refreshOnIo = inIo.get(); RefreshReport(0, 0, emptyList()) },
            mainScope,
            MarkedDispatcher(StandardTestDispatcher(testScheduler)),
        )
        v.start()
        runCurrent()
        v.select("2")
        runCurrent()
        assertEquals(true, refreshOnIo, "le rafraichissement doit tourner sur le repartiteur io")
        assertTrue(reads.isNotEmpty())
        assertTrue(reads.all { it }, "toutes les lectures du cache doivent tourner sur io : $reads")
        mainScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun restauration_du_focus_decision() {
        val list = listOf(stream("10", "1"), stream("11", "1"))
        assertEquals(RestoreAction.None, restoreAction(null, list))
        assertEquals(RestoreAction.Channel(1), restoreAction("11", list))
        assertEquals(RestoreAction.FirstCategory, restoreAction("99", list))
        assertEquals(RestoreAction.FirstCategory, restoreAction("11", emptyList()))
    }

    // --- LiveRefresher : liste masquee pour tous, format ts / m3u8 ---

    private fun refresher(sync: SyncClient, repo: CatalogRepository, source: FakeSource, info: suspend () -> UserInfo) =
        LiveRefresher(sync, repo, { "h1" }, info, { source })

    @Test
    fun liste_masquee_inaccessible_alors_aucun_telechargement_chez_le_fournisseur() = runTest {
        val repo = newRepo()
        repo.refreshLive(FakeSource(CATS, STREAMS), setOf("3"))
        val source = FakeSource(CATS, STREAMS)
        var infoCalls = 0
        val r = refresher(FakeSync(down = true), repo, source) { infoCalls++; UserInfo(true, 1, listOf("ts")) }
        val failure = runCatching { r.run() }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(source.streamCalls.isEmpty())
        assertEquals(0, infoCalls)
        assertEquals(2, repo.categories().size) // le cache reste affiche
    }

    @Test
    fun categorie_masquee_pour_tous_n_est_jamais_demandee() = runTest {
        val repo = newRepo()
        val source = FakeSource(CATS, STREAMS)
        val sync = FakeSync(hidden = listOf(HiddenItem("live_category", "3")))
        refresher(sync, repo, source) { UserInfo(true, 1, listOf("ts")) }.run()
        assertEquals(listOf("1", "2"), source.streamCalls)
        assertEquals(1, sync.hiddenCalls)
    }

    @Test
    fun choix_du_format() {
        assertEquals("ts", chooseFormat(emptyList()))
        assertEquals("ts", chooseFormat(listOf("m3u8", "ts")))
        assertEquals("m3u8", chooseFormat(listOf("m3u8", "rtmp")))
    }

    @Test
    fun le_format_est_retenu_apres_le_rafraichissement_et_ts_par_defaut() = runTest {
        val source = FakeSource(CATS, STREAMS)
        val r = refresher(FakeSync(), newRepo(), source) { UserInfo(true, 1, listOf("m3u8")) }
        assertEquals("ts", r.format)
        r.run()
        assertEquals("m3u8", r.format)

        val r2 = refresher(FakeSync(), newRepo(), source) { throw IOException("hors ligne") }
        r2.run() // userInfo en echec : on continue, format par defaut
        assertEquals("ts", r2.format)
    }
}
