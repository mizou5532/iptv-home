package home.iptv.tv.series

import home.iptv.shared.progress.ProgressRow
import home.iptv.shared.sync.HiddenItem
import home.iptv.shared.sync.ProfilePrefs
import home.iptv.shared.sync.PublishedCategory
import home.iptv.shared.xtream.Episode
import home.iptv.shared.xtream.Season
import home.iptv.shared.xtream.SeriesCategory
import home.iptv.shared.xtream.SeriesInfo
import home.iptv.shared.xtream.SeriesSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

private fun summary(id: String, cat: String) = SeriesSummary(id, cat, "Serie $id", null, null, null, null, null)

private fun fakeInfo(id: String, cat: String = "1") = SeriesInfo(
    summary(id, cat),
    listOf(Season(1, "Saison 1", (1..3).map { Episode("$id-s1e$it", 1, it, "Titre $it", 2400, "mkv", null) })),
)

private fun row(series: String, ep: Int = 1, watched: Boolean = false, minute: Int = 0, category: String? = "1") = ProgressRow(
    itemId = "$series-s1e$ep", seriesId = series, season = 1, episode = ep, positionS = 300, durationS = 2400,
    watched = watched, clientUpdatedAt = Instant.parse("2026-11-15T10:00:00Z") + kotlin.time.Duration.parse("${minute}m"),
    seriesName = "Serie $series", seriesCategory = category,
)

private class FakeCatalog : SeriesCatalog {
    var categoryList = listOf("1", "2", "9").map { SeriesCategory(it, "Cat $it") }
    var down = false
    val seriesCalls = mutableListOf<String>()
    val infoCalls = mutableListOf<String>()
    var series: Map<String, List<SeriesSummary>> = mapOf("1" to (1..30).map { summary("x$it", "1") })
    override suspend fun categories(): List<SeriesCategory> { if (down) throw IOException("hors ligne"); return categoryList }
    override suspend fun seriesOf(categoryId: String, hidden: Set<String>): List<SeriesSummary> {
        seriesCalls += categoryId
        if (down) throw IOException("hors ligne")
        return series[categoryId].orEmpty()
    }
    override suspend fun info(seriesId: String, categoryId: String?): SeriesInfo? {
        infoCalls += seriesId
        if (down) throw IOException("hors ligne")
        return fakeInfo(seriesId, categoryId ?: "1")
    }
}

private class FakeState : SeriesHomeState {
    var saved = mapOf<String, List<ProgressRow>>()
    var checkAt = 0L
    var checkSaves = 0
    var ids = emptySet<String>()
    var published = 0L
    override fun savedProgress(profileId: String) = saved[profileId].orEmpty()
    override fun saveProgress(profileId: String, rows: List<ProgressRow>) { saved = saved + (profileId to rows) }
    override fun lastNewCheck(profileId: String) = checkAt
    override fun newEpisodeIds(profileId: String) = ids
    override fun saveNewCheck(profileId: String, atMs: Long, ids: Set<String>) { checkAt = atMs; checkSaves++; this.ids = ids }
    override fun lastPublish(householdId: String) = published
    override fun savePublished(householdId: String, atMs: Long) { published = atMs }
}

private fun prefs(hidden: List<String> = emptyList(), list: List<String> = emptyList()) = ProfilePrefs(
    rev = 1, hidden = hidden.map { HiddenItem("series_category", it) }, favorites = emptyList(),
    playlists = emptyList(), order = null, seriesList = list,
)

@OptIn(ExperimentalCoroutinesApi::class)
class SeriesHomeViewModelTest {
    private val rigs = mutableListOf<Rig>()

    @kotlin.test.AfterTest
    fun closeRigs() { rigs.forEach { it.close() } }

    private inner class Rig(
        val scope: TestScope,
        val catalog: FakeCatalog = FakeCatalog(),
        val state: FakeState = FakeState(),
        var server: suspend () -> List<ProgressRow> = { emptyList() },
        var local: List<ProgressRow> = emptyList(),
        val prefs: MutableStateFlow<ProfilePrefs?> = MutableStateFlow(null),
        var clock: Long = 1_000_000_000L,
    ) {
        init { rigs += this }
        // backgroundScope n'est pas avancé par advanceUntilIdle : un scope ordinaire, fermé en fin de test.
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)
        private val vmScope = CoroutineScope(SupervisorJob() + dispatcher)
        fun close() = vmScope.cancel()
        val published = mutableListOf<List<PublishedCategory>>()
        var publishFails = false
        fun vm(): SeriesHomeViewModel {
            return SeriesHomeViewModel(
                catalog, { server() }, { local }, { cats -> if (publishFails) throw IOException("hors ligne"); published += cats },
                state, "p1", { "h1" }, prefs, vmScope, dispatcher, { clock },
            )
        }
    }

    @Test
    fun ouverture_sans_reseau_affiche_le_cache_local_avec_un_message() = runTest {
        val rig = Rig(this)
        rig.catalog.down = true
        rig.server = { throw IOException("hors ligne") }
        rig.state.saved = mapOf("p1" to listOf(row("a", watched = false, minute = 5)))
        val vm = rig.vm()
        vm.start()
        advanceUntilIdle()
        val ui = vm.ui.value
        assertEquals("a", ui.home.resume?.seriesId)
        assertFalse(ui.loading)
        assertNotNull(ui.message)
    }

    @Test
    fun sans_cache_ni_reseau_jamais_d_ecran_vide_sans_message() = runTest {
        val rig = Rig(this)
        rig.catalog.down = true
        rig.server = { throw IOException("hors ligne") }
        val vm = rig.vm()
        vm.start()
        advanceUntilIdle()
        assertNull(vm.ui.value.home.resume)
        assertTrue(vm.ui.value.message.orEmpty().isNotBlank())
    }

    @Test
    fun la_progression_locale_s_affiche_avant_la_reponse_du_serveur() = runTest {
        val gate = CompletableDeferred<List<ProgressRow>>()
        val rig = Rig(this, server = { gate.await() }, local = listOf(row("loc", minute = 40)))
        val vm = rig.vm()
        vm.start()
        advanceUntilIdle()
        assertEquals("loc", vm.ui.value.home.resume?.seriesId)
        gate.complete(listOf(row("srv", minute = 50)))
        advanceUntilIdle()
        assertEquals("srv", vm.ui.value.home.resume?.seriesId)
        assertEquals(listOf("p1"), rig.state.saved.keys.toList())
    }

    @Test
    fun une_categorie_masquee_n_est_jamais_demandee() = runTest {
        val rig = Rig(this, prefs = MutableStateFlow(prefs(hidden = listOf("9"))))
        rig.catalog.series = mapOf("1" to (1..30).map { summary("x$it", "1") }, "9" to listOf(summary("h", "9")))
        val vm = rig.vm()
        vm.start()
        advanceUntilIdle()
        assertEquals(listOf("1", "2"), vm.ui.value.home.categoryRows.map { it.id })
        vm.loadCategory("9")
        vm.loadCategory("1")
        vm.loadCategory("1")
        advanceUntilIdle()
        assertEquals(listOf("1"), rig.catalog.seriesCalls)
        assertEquals(20, vm.ui.value.rowSeries["1"]?.size)
    }

    @Test
    fun les_categories_sont_publiees_masquees_comprises_et_un_echec_ne_casse_rien() = runTest {
        val rig = Rig(this, prefs = MutableStateFlow(prefs(hidden = listOf("9"))))
        val vm = rig.vm()
        vm.start()
        advanceUntilIdle()
        assertEquals(listOf("1", "2", "9"), rig.published.single().map { it.id })
        assertEquals(listOf("1", "2"), vm.ui.value.home.categoryRows.map { it.id })

        val failing = Rig(this)
        failing.publishFails = true
        val vm2 = failing.vm()
        vm2.start()
        advanceUntilIdle()
        assertEquals(3, vm2.ui.value.home.categoryRows.size)
        assertEquals(0L, failing.state.published) // réessayé à la prochaine ouverture
    }

    @Test
    fun la_verification_des_nouveaux_episodes_est_quotidienne_et_limitee_a_20_series() = runTest {
        val many = (1..30).map { row("s$it", watched = true, minute = it) }
        val rig = Rig(this, server = { many })
        val vm = rig.vm()
        vm.start()
        advanceUntilIdle()
        assertEquals(20, rig.catalog.infoCalls.size)
        assertEquals(1, rig.state.checkSaves)
        assertEquals(20, vm.ui.value.home.newEpisodes.size)

        rig.clock += 3_600_000 // une heure plus tard : pas de nouvelle vérification
        rig.vm().also { it.start() }
        advanceUntilIdle()
        assertEquals(1, rig.state.checkSaves)

        rig.clock += 25L * 3_600_000
        rig.vm().also { it.start() }
        advanceUntilIdle()
        assertEquals(2, rig.state.checkSaves)
    }

    @Test
    fun masque_gagne_aussi_pour_les_series_de_ma_liste_et_de_reprendre() = runTest {
        val rig = Rig(
            this,
            server = { listOf(row("a", category = "9", minute = 9), row("b", category = "1", minute = 3)) },
            prefs = MutableStateFlow(prefs(hidden = listOf("9"), list = listOf("a", "b"))),
        )
        val vm = rig.vm()
        vm.start()
        advanceUntilIdle()
        assertEquals(listOf("b"), vm.ui.value.home.continueRow.map { it.seriesId })
        assertEquals(listOf("b"), vm.ui.value.home.myList.map { it.seriesId })
    }
}
