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
import home.iptv.shared.sync.PlaylistPrefs
import home.iptv.shared.sync.Profile
import home.iptv.shared.sync.ProfilePrefs
import home.iptv.shared.sync.PublishedCategory
import home.iptv.shared.sync.PublishedChannel
import home.iptv.shared.sync.SyncClient
import home.iptv.shared.xtream.LiveCategory
import home.iptv.shared.xtream.LiveStream
import home.iptv.shared.xtream.UserInfo
import home.iptv.shared.xtream.XtreamCreds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull

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
    override suspend fun publishSeriesCategories(householdId: String, cats: List<PublishedCategory>) {}
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
    override suspend fun publishLiveCategories(householdId: String, cats: List<PublishedCategory>): Map<String, String?> = emptyMap()
    override suspend fun publishLiveChannels(householdId: String, categoryId: String, fingerprint: String, chunk: Int, isLast: Boolean, channels: List<PublishedChannel>) {}
    override suspend fun profilePrefs(profileId: String): ProfilePrefs = ProfilePrefs(0, emptyList(), emptyList(), emptyList(), null)
    override suspend fun prefsRevision(householdId: String): Long = 0
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

private class GatedIo(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
    var blocked = false
    private val parked = mutableListOf<Pair<CoroutineContext, Runnable>>()
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (blocked) parked += context to block else delegate.dispatch(context, block)
    }
    fun releaseOne() {
        val (context, block) = parked.removeAt(0)
        delegate.dispatch(context, block)
    }
    fun release() {
        blocked = false
        val waiting = parked.toList()
        parked.clear()
        waiting.forEach { (context, block) -> delegate.dispatch(context, block) }
    }
}

private class RecordingDriver(private val inner: SqlDriver, val queryOnIo: MutableList<Boolean>, val groupReads: MutableList<String> = mutableListOf()) : SqlDriver by inner {
    override fun <R> executeQuery(
        identifier: Int?, sql: String, mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        queryOnIo += inIo.get()
        if (sql.contains("JOIN live_category")) groupReads += sql
        return inner.executeQuery(identifier, sql, mapper, parameters, binders)
    }
}

private val CATS = listOf(LiveCategory("1", "Sport"), LiveCategory("2", "Cine"), LiveCategory("3", "Adulte"))
private val STREAMS = mapOf(
    "1" to listOf(stream("10", "1", 1), stream("11", "1", 2)),
    "2" to listOf(stream("20", "2")),
    "3" to listOf(stream("30", "3")),
)

/** Retient la [held]-ième tâche d'entrée-sortie (0 = la première) jusqu'à [releaseAll] ; les autres passent. */
private class Parked(private val delegate: CoroutineDispatcher, private val held: Int) : CoroutineDispatcher() {
    private var count = 0
    private var kept: Pair<CoroutineContext, Runnable>? = null
    private var holding = true
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (holding && count++ == held) kept = context to block else delegate.dispatch(context, block)
    }
    fun releaseAll() { holding = false; kept?.let { (c, b) -> delegate.dispatch(c, b) }; kept = null }
}

/** Compte les lectures des chaînes d'une catégorie. */
private class StreamReads(private val inner: SqlDriver, val reads: MutableList<String>) : SqlDriver by inner {
    override fun <R> executeQuery(
        identifier: Int?, sql: String, mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        if (sql.contains("WHERE category_id = ?") && sql.contains("FROM live_stream")) reads += sql
        return inner.executeQuery(identifier, sql, mapper, parameters, binders)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.settle() { runCurrent(); advanceTimeBy(5_000); runCurrent() }

@OptIn(ExperimentalCoroutinesApi::class)
class LiveBrowseViewModelTest {
    private fun TestScope.vm(repo: CatalogRepository, refresh: suspend (suspend (Int, Int) -> Unit) -> RefreshReport) =
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
    fun les_categories_apparaissent_des_la_premiere_pendant_un_premier_rafraichissement() = runTest {
        val repo = newRepo() // premier lancement : cache vide
        val gate = CompletableDeferred<Unit>()
        val source = object : LiveSource {
            override suspend fun liveCategories() = CATS
            override suspend fun liveStreams(categoryId: String): List<LiveStream> {
                if (categoryId != "1") gate.await() // la deuxieme categorie reste suspendue
                return STREAMS[categoryId].orEmpty()
            }
        }
        val v = vm(repo) { p -> repo.refreshLive(source, emptySet(), p) }
        v.start()
        runCurrent()
        assertTrue(v.refreshing.value, "le rafraichissement est toujours en cours")
        assertEquals(listOf("Sport"), v.categories.value.map { it.name })
        assertEquals("1", v.selectedId.value)
        assertEquals(listOf("10", "11"), v.streams.value.map { it.id })

        gate.complete(Unit)
        runCurrent()
        assertEquals(3, v.categories.value.size)
        assertFalse(v.refreshing.value)
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
    fun un_rafraichissement_qui_ne_finit_jamais_ne_laisse_pas_mise_a_jour_affiche() = runTest {
        // Essai TCL 0.4.0 : « Mise à jour » affiché plus de 5 min. Un fournisseur qui répond une catégorie toutes les
        // 15 s (délai dépassé à chaque fois) sur 2 000 catégories : plus de 8 h sans borne.
        val repo = newRepo()
        repo.refreshLive(FakeSource(CATS, STREAMS), emptySet())
        var progressCalls = 0
        val v = vm(repo) { p -> var i = 0; while (i >= 0) { kotlinx.coroutines.delay(15_000); progressCalls++; p(++i, 2_000) }; error("jamais") }
        v.start()
        runCurrent()
        assertTrue(v.refreshing.value)
        advanceTimeBy(10 * 60_000L + 40_000L); runCurrent()
        assertFalse(v.refreshing.value, "« Mise à jour » toujours affiché après près de 11 min")
        assertEquals(REFRESH_TOO_LONG, v.refreshNotice.value, "l'arrêt est signalé")
        assertEquals(3, v.categories.value.size, "les chaînes du cache restent affichées")
        val calls = progressCalls
        advanceTimeBy(5 * 60_000L); runCurrent()
        assertEquals(calls, progressCalls, "le rafraichissement abandonné ne continue pas en arrière-plan")
    }

    @Test
    fun le_guide_d_une_copie_sans_guide_est_cherche_dans_les_autres_copies() = runTest {
        // Copies regroupées d'une même chaîne : le fournisseur ne relie le guide (epg_channel_id) qu'à certaines.
        val repo = newRepo()
        repo.refreshLive(FakeSource(listOf(LiveCategory("A", "Sport")), mapOf("A" to listOf(
            LiveStream("a", "Sport 1 HD", "A", null, null, null),
            LiveStream("b", "Sport 1 FHD", "A", null, "sport1.test", null),
            LiveStream("c", "Sport 1 4K", "A", null, "sport1.test", null),
            LiveStream("d", "Sport 1 SD", "A", null, "sport1-sd.test", null),
            LiveStream("z", "Cinema", "A", null, null, null),
        ))), emptySet())
        val v = vm(repo) { RefreshReport(0, 0, emptyList()) }
        v.start(); settle()
        assertEquals(listOf("b", "d"), v.guideCandidates("a"), "copies avec un guide, un essai par guide distinct")
        assertEquals(listOf("b", "d"), v.guideCandidates("b"), "elle-même d'abord quand elle a un guide")
        assertEquals(listOf("z"), v.guideCandidates("z"), "sans copie ni guide connu : elle seule")
    }

    @Test
    fun un_echec_du_rafraichissement_est_signale_sans_bloquer_puis_s_efface() = runTest {
        val repo = newRepo()
        repo.refreshLive(FakeSource(CATS, STREAMS), emptySet())
        val v = vm(repo) { throw IOException("hors ligne") }
        v.start()
        runCurrent()
        assertFalse(v.refreshing.value)
        assertEquals(REFRESH_FAILED, v.refreshNotice.value)
        assertEquals(3, v.categories.value.size)
        v.selectSection("cat:2"); runCurrent()
        assertEquals(listOf("20"), v.streams.value.map { it.id }, "la navigation reste possible")
        advanceTimeBy(31_000); runCurrent()
        assertNull(v.refreshNotice.value, "le message s'efface tout seul")
    }

    @Test
    fun une_mise_a_jour_incomplete_est_signalee() = runTest {
        val repo = newRepo()
        repo.refreshLive(FakeSource(CATS, STREAMS), emptySet())
        val v = vm(repo) { RefreshReport(3, 4, listOf("2", "3")) }
        v.start()
        runCurrent()
        assertFalse(v.refreshing.value)
        assertEquals(refreshPartial(2), v.refreshNotice.value)
        val ok = vm(repo) { RefreshReport(3, 4, emptyList()) }
        ok.start(); runCurrent()
        assertNull(ok.refreshNotice.value, "rien à signaler quand tout est à jour")
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
    fun pendant_le_chargement_les_chaines_de_l_ancienne_section_ne_sont_pas_exposees() = runTest {
        val repo = newRepo()
        repo.refreshLive(FakeSource(CATS, STREAMS), emptySet())
        val v = vm(repo) { RefreshReport(0, 0, emptyList()) }
        v.start()
        runCurrent()
        assertEquals("cat:1", v.loaded.value.key)
        v.selectSection("cat:2")
        assertEquals("cat:2", v.selectedKey.value)
        assertTrue(v.streams.value.isEmpty())
        assertTrue(v.loaded.value.key != v.selectedKey.value)
        runCurrent()
        assertEquals("cat:2", v.loaded.value.key)
        assertEquals(listOf("20"), v.loaded.value.streams.map { it.id })
        assertEquals(listOf("20"), v.streams.value.map { it.id })
    }

    @Test
    fun pendant_un_long_rafraichissement_le_cache_n_est_pas_relu_a_chaque_categorie() = runTest {
        // Essai TCL 0.4.0 : chaque relecture reconstruit tout le catalogue ; une par seconde rendait le rafraichissement
        // interminable (« Mise à jour ») et bloquait l'affichage des catégories (lenteur à chaque focus).
        var now = 0L
        val reads = mutableListOf<String>()
        val inner = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { CatalogDatabase.Schema.create(it) }
        val repo = CatalogRepository(CatalogDatabase(RecordingDriver(inner, mutableListOf(), reads)))
        repo.refreshLive(FakeSource(CATS, STREAMS), emptySet())
        reads.clear()
        val v = LiveBrowseViewModel(repo, { p -> repeat(200) { now += 2_000; p(it + 1, 200) }; RefreshReport(3, 4, emptyList()) },
            backgroundScope, StandardTestDispatcher(testScheduler), nowMs = { now })
        v.start()
        settle()
        assertTrue(reads.size <= 40, "relectures complètes du catalogue : ${reads.size} pour 200 catégories")
        assertFalse(v.refreshing.value)
        assertEquals(3, v.categories.value.size)
    }

    @Test
    fun liste_deja_enregistree_relue_seulement_a_l_ouverture_et_a_la_fin_de_la_mise_a_jour() = runTest {
        // Essai TCL 0.32.0 : chaque relecture regroupe tout le catalogue (plusieurs secondes sur la télé) ; avec une
        // liste déjà affichée, les relectures pendant la mise à jour n'apportaient rien et saturaient la télé.
        var now = 0L
        val reads = mutableListOf<String>()
        val inner = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { CatalogDatabase.Schema.create(it) }
        val repo = CatalogRepository(CatalogDatabase(RecordingDriver(inner, mutableListOf(), reads)))
        repo.refreshLive(FakeSource(CATS, STREAMS), emptySet())
        reads.clear()
        val v = LiveBrowseViewModel(repo, { p -> repeat(200) { now += 2_000; p(it + 1, 200) }; RefreshReport(3, 4, emptyList()) },
            backgroundScope, StandardTestDispatcher(testScheduler), nowMs = { now })
        v.start()
        settle()
        assertEquals(2, reads.size, "relectures complètes du catalogue : $reads")
        assertEquals(3, v.categories.value.size)
    }

    @Test
    fun chargement_affiche_avant_la_premiere_lecture_de_la_liste_jamais_aucune_chaine() = runTest {
        val repo = newRepo()
        repo.refreshLive(FakeSource(CATS, STREAMS), emptySet())
        val v = LiveBrowseViewModel(repo, null, backgroundScope, StandardTestDispatcher(testScheduler))
        assertFalse(v.listShown.value)
        v.start(); settle()
        assertTrue(v.listShown.value)
        assertEquals(3, v.categories.value.size)
    }

    @Test
    fun parcourir_les_sections_au_focus_ne_charge_que_celle_ou_l_on_s_arrete() = runTest {
        val streamReads = mutableListOf<String>()
        val inner = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { CatalogDatabase.Schema.create(it) }
        val repo = CatalogRepository(CatalogDatabase(StreamReads(inner, streamReads)))
        repo.refreshLive(FakeSource(CATS, STREAMS), emptySet())
        val v = vm(repo) { RefreshReport(0, 0, emptyList()) }
        v.start(); settle()
        streamReads.clear()
        v.focusSection("cat:2"); runCurrent()
        v.focusSection("cat:3"); runCurrent()
        v.focusSection("cat:2"); runCurrent()
        assertEquals("cat:2", v.selectedKey.value, "la sélection suit le focus tout de suite")
        settle()
        assertEquals(1, streamReads.size, "une seule lecture, pour la section où le focus s'arrête : $streamReads")
        assertEquals(listOf("20"), v.streams.value.map { it.id })
    }

    @Test
    fun revenir_sur_une_section_deja_vue_est_immediat() = runTest {
        val streamReads = mutableListOf<String>()
        val inner = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { CatalogDatabase.Schema.create(it) }
        val repo = CatalogRepository(CatalogDatabase(StreamReads(inner, streamReads)))
        repo.refreshLive(FakeSource(CATS, STREAMS), emptySet())
        val v = vm(repo) { RefreshReport(0, 0, emptyList()) }
        v.start(); settle()
        v.selectSection("cat:2"); settle()
        streamReads.clear()
        v.selectSection("cat:1")
        assertEquals(listOf("10", "11"), v.streams.value.map { it.id }, "section déjà vue : affichée sans attendre")
        v.selectSection("cat:2")
        assertEquals(listOf("20"), v.streams.value.map { it.id })
        settle()
        assertTrue(streamReads.isEmpty(), "aucune relecture du disque : $streamReads")
    }

    @Test
    fun a_l_ouverture_jamais_de_favoris_seuls_et_vides_avant_la_lecture_du_cache() = runTest {
        // Essai TCL 0.4.0 : à l'ouverture, parfois seulement « Favoris », vide, avant que tout apparaisse plus tard.
        // Ici la lecture du cache (gros catalogue) est plus lente que la première reconstruction déclenchée par les
        // préférences : celle-ci ne doit rien publier tant que le cache n'a pas été lu.
        val repo = newRepo()
        repo.refreshLive(FakeSource(CATS, STREAMS), emptySet())
        val p = MutableStateFlow<ProfilePrefs?>(prefs(favorites = listOf("20")))
        // Tâches d'E/S dans l'ordre : index de la 1re reconstruction (préférences), puis lecture du cache (retenue).
        val io = Parked(StandardTestDispatcher(testScheduler), held = 1)
        val v = LiveBrowseViewModel(repo, { RefreshReport(0, 0, emptyList()) }, backgroundScope, io, prefs = p)
        val seen = mutableListOf<Pair<List<String>, SectionStreams>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            kotlinx.coroutines.flow.combine(v.sections, v.loaded) { s, l -> s.map { it.key } to l }.collect { seen += it }
        }
        v.start(); runCurrent()
        runCurrent()
        io.releaseAll(); runCurrent()
        val broken = seen.filter { (keys, loaded) -> keys == listOf("fav") || (loaded.key == "fav" && loaded.streams.isEmpty()) }
        assertTrue(broken.isEmpty(), "états affichés avant la lecture du cache : $broken")
        assertEquals(listOf("fav", "cat:1", "cat:2", "cat:3"), v.sections.value.map { it.key })
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
    fun copies_same_category_collapse_and_keep_first_favorite_anchor() = runTest {
        val repo = newRepo()
        repo.refreshLive(FakeSource(listOf(LiveCategory("A", "Sport")), mapOf("A" to listOf(
            LiveStream("a", "Sport HD", "A", null, null, null),
            LiveStream("b", "Sport FHD", "A", null, null, null),
            LiveStream("c", "Cinema", "A", null, null, null),
        ))), emptySet())
        val p = MutableStateFlow<ProfilePrefs?>(ProfilePrefs(1, emptyList(), listOf("b", "a", "c"), emptyList(), null))
        val v = vmWith(repo, p)
        v.start(); runCurrent()
        assertEquals(listOf("b", "c"), v.streams.value.map { it.id })
        v.select("A"); runCurrent()
        assertEquals(listOf("a", "c"), v.streams.value.map { it.id })
    }

    @Test
    fun groups_cross_categories_keep_favorites_hidden_and_last_choice_visible_only() = runTest {
        val repo = newRepo()
        repo.refreshLive(FakeSource(listOf(LiveCategory("A", "Sport"), LiveCategory("B", "Autre")), mapOf(
            "A" to listOf(LiveStream("a", "Sport HD", "A", null, null, null)),
            "B" to listOf(LiveStream("b", "Sport FHD", "B", null, null, null)),
        )), emptySet())
        val p = MutableStateFlow<ProfilePrefs?>(ProfilePrefs(1, emptyList(), listOf("a"), emptyList(), null))
        val store = PrefsStore()
        val v = LiveBrowseViewModel(repo, null, backgroundScope, StandardTestDispatcher(testScheduler), prefs = p, store = store, profileId = "p")
        v.start(); runCurrent()
        assertEquals(listOf("a", "b"), v.loaded.value.rows.single().group.sources.map { it.id })
        assertEquals("b", v.playbackSource("a", "b")?.id)
        assertNull(v.playbackSource("a", "unknown"))
        v.saveLastGoodSource("auto:v1:sport", "b")
        assertEquals("b", v.playbackSource("a")?.id)
        p.value = p.value!!.copy(rev = 2, hidden = listOf(HiddenItem("live_category", "B")))
        // OK immediately after masking must reject remembered hidden source, even before collector runs.
        assertEquals("a", v.playbackSource("a")?.id)
        assertNull(v.playbackSource("a", "b"))
        runCurrent()
        assertEquals(listOf("a"), v.loaded.value.rows.single().group.sources.map { it.id })
        p.value = p.value!!.copy(rev = 3, hidden = listOf(HiddenItem("channel", "a")))
        assertEquals(null, v.playbackSource("a"))
        runCurrent()
        assertTrue(v.loaded.value.rows.isEmpty()) // visible b does not revive hidden favorite a
        v.selectSection("cat:B"); runCurrent()
        assertEquals(listOf("b"), v.streams.value.map { it.id })
    }

    @Test
    fun profile_toggle_and_household_manual_corrections_rebuild_existing_rows() = runTest {
        val repo = newRepo()
        repo.refreshLive(FakeSource(listOf(LiveCategory("A", "Sport")), mapOf("A" to listOf(
            LiveStream("a", "Sport HD", "A", null, null, null),
            LiveStream("b", "Sport FHD", "A", null, null, null),
        ))), emptySet())
        val p = MutableStateFlow<ProfilePrefs?>(ProfilePrefs(1, emptyList(), emptyList(), emptyList(), null))
        val groups = MutableStateFlow<home.iptv.shared.groups.GroupSnapshot?>(null)
        val v = LiveBrowseViewModel(repo, null, backgroundScope, StandardTestDispatcher(testScheduler), prefs = p, groupSnapshot = groups)
        v.start(); runCurrent()
        assertEquals(1, v.loaded.value.rows.size)
        p.value = p.value!!.copy(groupCopies = false); runCurrent()
        assertEquals(listOf("a", "b"), v.streams.value.map { it.id })
        p.value = p.value!!.copy(groupCopies = true)
        groups.value = home.iptv.shared.groups.GroupSnapshot(2, 1, listOf(home.iptv.shared.groups.ManualGroup("m", "Mon sport", listOf("a", "b", "absent"), emptyList())))
        runCurrent()
        assertEquals("manual:m", v.loaded.value.rows.single().group.id)
        assertEquals("Mon sport", v.loaded.value.rows.single().group.title)
        assertEquals(listOf("a", "b"), v.loaded.value.rows.single().group.sources.map { it.id })
    }

    @Test
    fun global_index_is_read_on_io_once_per_catalog_reload_not_focus() = runTest {
        val reads = mutableListOf<Boolean>()
        val global = mutableListOf<String>()
        val driver = RecordingDriver(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY), reads, global)
        CatalogDatabase.Schema.create(driver)
        val repo = CatalogRepository(CatalogDatabase(driver))
        repo.refreshLive(FakeSource(CATS, STREAMS), emptySet())
        reads.clear(); global.clear()
        val v = LiveBrowseViewModel(repo, null, backgroundScope, MarkedDispatcher(StandardTestDispatcher(testScheduler)))
        v.start(); runCurrent()
        assertEquals(1, global.size)
        assertTrue(reads.all { it })
        repeat(20) { v.select(if (it % 2 == 0) "2" else "1"); runCurrent() }
        assertEquals(1, global.size)
    }

    @Test
    fun source_disappeared_during_catalog_refresh_cannot_revive_favorite() = runTest {
        val repo = newRepo()
        val cats = listOf(LiveCategory("A", "Sport"), LiveCategory("B", "Autre"))
        val a = LiveStream("a", "Sport HD", "A", null, null, null)
        val b = LiveStream("b", "Sport FHD", "B", null, null, null)
        repo.refreshLive(FakeSource(cats, mapOf("A" to listOf(a), "B" to listOf(b))), emptySet())
        val gate = CompletableDeferred<Unit>()
        val p = MutableStateFlow<ProfilePrefs?>(ProfilePrefs(1, emptyList(), listOf("a"), emptyList(), null))
        val v = LiveBrowseViewModel(repo, { progress -> gate.await(); repo.refreshLive(FakeSource(cats, mapOf("B" to listOf(b))), emptySet(), progress) },
            backgroundScope, StandardTestDispatcher(testScheduler), prefs = p)
        v.start(); runCurrent()
        assertEquals(listOf("a", "b"), v.loaded.value.rows.single().group.sources.map { it.id })
        gate.complete(Unit); runCurrent()
        assertTrue(v.loaded.value.rows.isEmpty())
        assertNull(v.playbackSource("a"))
        v.select("B"); runCurrent()
        assertEquals(listOf("b"), v.streams.value.map { it.id })
        assertEquals(listOf("b"), v.loaded.value.rows.single().group.sources.map { it.id })
    }

    @Test
    fun stale_io_rebuild_never_emits_sources_or_sections_after_new_masks() = runTest {
        val repo = newRepo()
        val a = LiveStream("a", "Sport HD", "A", null, null, null)
        val b = LiveStream("b", "Sport FHD", "B", null, null, null)
        repo.refreshLive(FakeSource(listOf(LiveCategory("A", "Sport"), LiveCategory("B", "Autre")),
            mapOf("A" to listOf(a), "B" to listOf(b))), emptySet())
        val p = MutableStateFlow<ProfilePrefs?>(ProfilePrefs(1, emptyList(), listOf("a", "b"), emptyList(), null))
        val io = GatedIo(StandardTestDispatcher(testScheduler))
        val v = LiveBrowseViewModel(repo, null, backgroundScope, io, prefs = p)
        v.start(); runCurrent()
        val emittedRows = mutableListOf<SectionStreams>()
        val emittedSections = mutableListOf<List<LiveSection>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { v.loaded.collect { emittedRows += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { v.sections.collect { emittedSections += it } }
        io.blocked = true
        p.value = p.value!!.copy(rev = 2, favorites = listOf("b", "a"))
        runCurrent() // old preferences are captured, IO construction suspended
        emittedRows.clear(); emittedSections.clear()
        p.value = p.value!!.copy(rev = 3, hidden = listOf(HiddenItem("live_category", "B")))
        runCurrent()
        io.release(); runCurrent()
        assertTrue(emittedRows.isNotEmpty())
        assertTrue(emittedRows.all { emission -> emission.rows.all { row -> row.group.sources.none { it.categoryId == "B" } } }, emittedRows.toString())
        assertTrue(emittedSections.all { sections -> sections.none { it.key == "cat:B" } }, emittedSections.toString())
        assertEquals(listOf("a"), v.loaded.value.rows.map { it.anchorSourceId })

        io.blocked = true
        p.value = p.value!!.copy(rev = 4, hidden = emptyList())
        runCurrent()
        io.releaseOne(); runCurrent() // index termine, lecture de section suspendue
        emittedRows.clear(); emittedSections.clear()
        p.value = p.value!!.copy(rev = 5, hidden = listOf(HiddenItem("live_category", "B")))
        runCurrent()
        io.release(); runCurrent()
        assertTrue(emittedRows.all { emission -> emission.rows.all { row -> row.group.sources.none { it.categoryId == "B" } } }, emittedRows.toString())
        assertTrue(emittedSections.all { sections -> sections.none { it.key == "cat:B" } }, emittedSections.toString())
        assertEquals(listOf("a"), v.loaded.value.rows.map { it.anchorSourceId })
    }

    @Test
    fun playback_default_uses_provider_order_while_favorite_anchor_stays_first_present() = runTest {
        val repo = newRepo()
        val sources = listOf("a", "b", "c").map { LiveStream(it, "Sport HD", "A", null, null, null) }
        repo.refreshLive(FakeSource(listOf(LiveCategory("A", "Sport")), mapOf("A" to sources)), emptySet())
        val p = MutableStateFlow<ProfilePrefs?>(ProfilePrefs(1, emptyList(), listOf("b", "a"), emptyList(), null))
        val store = PrefsStore()
        val v = LiveBrowseViewModel(repo, null, backgroundScope, StandardTestDispatcher(testScheduler), prefs = p, store = store, profileId = "p")
        v.start(); runCurrent()
        assertEquals("b", v.loaded.value.rows.single().anchorSourceId)
        assertEquals("a", v.playbackSource("b")?.id)
        store.saveLastGoodSource("p", "auto:v1:sport", "c")
        assertEquals("c", v.playbackSource("b")?.id)
        p.value = p.value!!.copy(rev = 2, hidden = listOf(HiddenItem("channel", "c")))
        runCurrent()
        assertEquals("a", v.playbackSource("b")?.id)
        store.saveLastGoodSource("p", "auto:v1:sport", "absent")
        assertEquals("a", v.playbackSource("b")?.id)
        p.value = p.value!!.copy(rev = 3, hidden = listOf(HiddenItem("channel", "b")))
        assertNull(v.playbackSource("b"))
    }

    @Test
    fun section_rows_group_of_and_playback_for_work_across_sections_and_mask_wins() = runTest {
        val repo = newRepo()
        repo.refreshLive(FakeSource(listOf(LiveCategory("A", "Sport"), LiveCategory("B", "Autre")), mapOf(
            "A" to listOf(LiveStream("a", "Sport HD", "A", null, null, null)),
            "B" to listOf(LiveStream("b", "Sport FHD", "B", null, null, null), LiveStream("z", "Zzz", "B", null, null, null)),
        )), emptySet())
        val p = MutableStateFlow<ProfilePrefs?>(ProfilePrefs(1, emptyList(), emptyList(), emptyList(), null))
        val v = LiveBrowseViewModel(repo, null, backgroundScope, StandardTestDispatcher(testScheduler), prefs = p)
        v.start(); runCurrent()
        val rev = v.groupsRevision.value
        // Une autre section que celle affichee : lignes dans l'ordre, null si la section n'existe pas.
        val rowsB = v.sectionRows("cat:B")!!
        assertEquals(listOf("b", "z"), rowsB.map { it.anchorSourceId })
        assertNull(v.sectionRows("cat:nope"))
        assertEquals("a", v.playbackFor(rowsB.first())?.id) // ordre fournisseur, ancre b
        assertEquals(listOf("a", "b"), v.groupOf("b")?.sources?.map { it.id })
        // Masque gagne, y compris avant la reconstruction asynchrone.
        p.value = p.value!!.copy(rev = 2, hidden = listOf(HiddenItem("channel", "b")))
        assertNull(v.groupOf("b"))
        assertNull(v.playbackFor(rowsB.first()))
        assertEquals("a", v.groupOf("a")?.sources?.single()?.id)
        runCurrent()
        assertTrue(v.groupsRevision.value > rev)
    }

    private suspend fun manualVm(
        scope: TestScope, p: MutableStateFlow<ProfilePrefs?>, groups: MutableStateFlow<home.iptv.shared.groups.GroupSnapshot?>,
        io: CoroutineDispatcher = StandardTestDispatcher(scope.testScheduler),
    ): LiveBrowseViewModel {
        val repo = newRepo()
        repo.refreshLive(FakeSource(listOf(LiveCategory("A", "Sport")), mapOf("A" to listOf(
            LiveStream("a", "Sport HD", "A", null, null, null),
            LiveStream("b", "Sport FHD", "A", null, null, null),
            LiveStream("c", "Autre chose", "A", null, null, null),
        ))), emptySet())
        return LiveBrowseViewModel(repo, null, scope.backgroundScope, io, prefs = p, groupSnapshot = groups)
    }

    @Test
    fun choosing_a_copy_added_to_the_playing_group_resolves_against_the_current_group() = runTest {
        val p = MutableStateFlow<ProfilePrefs?>(ProfilePrefs(1, emptyList(), emptyList(), emptyList(), null))
        val groups = MutableStateFlow<home.iptv.shared.groups.GroupSnapshot?>(
            home.iptv.shared.groups.GroupSnapshot(1, 1, listOf(home.iptv.shared.groups.ManualGroup("m", "Mon sport", listOf("a", "b"), emptyList()))))
        val v = manualVm(this, p, groups)
        v.start(); runCurrent()
        val oldRow = v.loaded.value.rows.first { it.group.id == "manual:m" }
        assertEquals(listOf("a", "b"), oldRow.group.sources.map { it.id })
        // L'iPhone ajoute la copie "c" au meme groupe (identifiant conserve) ; la ligne de la tele est perimee.
        groups.value = home.iptv.shared.groups.GroupSnapshot(2, 1, listOf(home.iptv.shared.groups.ManualGroup("m", "Mon sport", listOf("a", "b", "c"), emptyList())))
        runCurrent()
        assertEquals("c", v.playbackFor(oldRow, "c")?.id)
    }

    @Test
    fun preferences_changing_during_resolution_retry_once_and_never_play_a_hidden_copy() = runTest {
        val p = MutableStateFlow<ProfilePrefs?>(ProfilePrefs(1, emptyList(), emptyList(), emptyList(), null))
        val groups = MutableStateFlow<home.iptv.shared.groups.GroupSnapshot?>(
            home.iptv.shared.groups.GroupSnapshot(1, 1, listOf(home.iptv.shared.groups.ManualGroup("m", "Mon sport", listOf("a", "b"), emptyList()))))
        val io = GatedIo(StandardTestDispatcher(testScheduler))
        val v = manualVm(this, p, groups, io)
        v.start(); runCurrent()
        val row = v.loaded.value.rows.first { it.group.id == "manual:m" }
        // Une autre copie du groupe est masquee pendant la resolution : nouvel essai, sans erreur.
        io.blocked = true
        var result: LiveStream? = null
        val job = launch { result = v.playbackFor(row) }
        runCurrent()
        p.value = p.value!!.copy(rev = 2, hidden = listOf(HiddenItem("channel", "b")))
        io.release(); runCurrent(); job.join()
        assertEquals("a", result?.id)
        // La copie demandee est masquee pendant la resolution : jamais lue.
        io.blocked = true
        var hidden: LiveStream? = LiveStream("x", "x", "A", null, null, null)
        val job2 = launch { hidden = v.playbackFor(row, "a") }
        runCurrent()
        p.value = p.value!!.copy(rev = 3, hidden = listOf(HiddenItem("channel", "a")))
        io.release(); runCurrent(); job2.join()
        assertNull(hidden)
    }

    // --- Organisation du profil ---

    private fun prefs(
        hidden: List<HiddenItem> = emptyList(),
        favorites: List<String> = emptyList(),
        playlists: List<PlaylistPrefs> = emptyList(),
        order: List<String>? = null,
    ) = ProfilePrefs(1, hidden, favorites, playlists, order)

    private suspend fun cached(): CatalogRepository {
        val repo = newRepo()
        repo.refreshLive(FakeSource(CATS, STREAMS), emptySet())
        return repo
    }

    private fun TestScope.vmWith(
        repo: CatalogRepository,
        prefs: MutableStateFlow<ProfilePrefs?>,
        publish: suspend (RefreshReport) -> Unit = {},
        refresh: suspend (suspend (Int, Int) -> Unit) -> RefreshReport = { RefreshReport(0, 0, emptyList()) },
    ) = LiveBrowseViewModel(repo, refresh, backgroundScope, StandardTestDispatcher(testScheduler), prefs = prefs, publish = publish)

    @Test
    fun preferences_changent_sections_mises_a_jour() = runTest {
        val p = MutableStateFlow<ProfilePrefs?>(prefs(order = listOf("2", "1")))
        val v = vmWith(cached(), p)
        v.start(); runCurrent()
        assertEquals(listOf("cat:2", "cat:1", "cat:3"), v.sections.value.map { it.key })
        assertEquals("cat:2", v.selectedKey.value)
        assertEquals(listOf("20"), v.streams.value.map { it.id })

        // Le telephone ajoute un favori, masque une chaine et une categorie, change l'ordre.
        p.value = prefs(
            hidden = listOf(HiddenItem("live_category", "3"), HiddenItem("channel", "11")),
            favorites = listOf("20", "11", "10"),
            playlists = listOf(PlaylistPrefs("pl1", "Soir", listOf("10", "20"))),
        )
        runCurrent()
        assertEquals(listOf("fav", "pl:pl1", "cat:1", "cat:2"), v.sections.value.map { it.key })
        assertEquals(listOf("1", "2"), v.categories.value.map { it.id })

        v.selectSection("fav"); runCurrent()
        assertEquals(listOf("20", "10"), v.streams.value.map { it.id }) // ordre des favoris, "11" masquee
        v.selectSection("pl:pl1"); runCurrent()
        assertEquals(listOf("10", "20"), v.streams.value.map { it.id })
        v.selectSection("cat:1"); runCurrent()
        assertEquals(listOf("10"), v.streams.value.map { it.id })

        // La section affichee disparait (categorie masquee) : on retombe sur la premiere section.
        p.value = prefs(hidden = listOf(HiddenItem("live_category", "1")))
        runCurrent()
        assertEquals("cat:2", v.selectedKey.value)
        assertEquals(listOf("20"), v.streams.value.map { it.id })
    }

    @Test
    fun favori_dans_categorie_masquee_globale_absent() = runTest {
        // La categorie 3 est masquee pour tout le monde depuis le telephone, mais encore en cache
        // (la tele n'a pas rafraichi) : ses chaines ne doivent apparaitre nulle part.
        val p = MutableStateFlow<ProfilePrefs?>(
            prefs(hidden = listOf(HiddenItem("live_category", "3")), favorites = listOf("30", "10"), playlists = listOf(PlaylistPrefs("pl", "P", listOf("30", "20")))),
        )
        val v = vmWith(cached(), p)
        v.start(); runCurrent()
        assertEquals(listOf("fav", "pl:pl", "cat:1", "cat:2"), v.sections.value.map { it.key })
        assertEquals(listOf("10"), v.streams.value.map { it.id })
        v.selectSection("pl:pl"); runCurrent()
        assertEquals(listOf("20"), v.streams.value.map { it.id })
    }

    @Test
    fun profil_sans_preferences_affiche_le_fournisseur() = runTest {
        val v = vmWith(cached(), MutableStateFlow(null))
        v.start(); runCurrent()
        assertEquals(listOf("cat:1", "cat:2", "cat:3"), v.sections.value.map { it.key })
        assertEquals("cat:1", v.selectedKey.value)
        assertEquals(listOf("10", "11"), v.streams.value.map { it.id })
    }

    @Test
    fun les_preferences_arrivent_apres_le_cache_vide_sans_ecran_vide() = runTest {
        val p = MutableStateFlow<ProfilePrefs?>(null)
        val repo = newRepo()
        val v = vmWith(repo, p, refresh = { repo.refreshLive(FakeSource(CATS, STREAMS), emptySet()) })
        v.start(); runCurrent()
        assertEquals(3, v.sections.value.size)
        p.value = prefs(favorites = listOf("20"))
        runCurrent()
        assertEquals("fav", v.sections.value.first().key)
        assertEquals("cat:1", v.selectedKey.value) // la selection de l'utilisateur ne saute pas
    }

    @Test
    fun la_publication_vient_apres_refreshing_faux_et_hors_du_fil_principal() = runTest {
        val repo = cached()
        val events = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        var publishOnIo: Boolean? = null
        val mainScope = CoroutineScope(StandardTestDispatcher(testScheduler))
        lateinit var v: LiveBrowseViewModel
        v = LiveBrowseViewModel(
            repo,
            { RefreshReport(3, 4, emptyList(), CATS) },
            mainScope,
            MarkedDispatcher(StandardTestDispatcher(testScheduler)),
            publish = { report ->
                publishOnIo = inIo.get()
                events += "publish(refreshing=${v.refreshing.value}, cats=${v.categories.value.size}, ${report.providerCategories.size})"
                gate.await()
                events += "publie"
            },
        )
        v.start(); runCurrent()
        assertEquals(listOf("publish(refreshing=false, cats=3, 3)"), events)
        assertFalse(v.refreshing.value, "une publication en cours ne retient pas refreshing")
        assertEquals(true, publishOnIo)
        gate.complete(Unit); runCurrent()
        mainScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun publication_en_erreur_ou_rafraichissement_en_echec() = runTest {
        var calls = 0
        val failing = vmWith(cached(), MutableStateFlow(null), publish = { calls++; throw IOException("coupe") })
        failing.start(); runCurrent()
        assertFalse(failing.refreshing.value)
        assertEquals(3, failing.categories.value.size)
        assertEquals(1, calls)

        calls = 0
        val offline = vmWith(cached(), MutableStateFlow(null), publish = { calls++ }, refresh = { throw IOException("hors ligne") })
        offline.start(); runCurrent()
        assertEquals(0, calls, "rien a publier quand le rafraichissement a echoue")
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
