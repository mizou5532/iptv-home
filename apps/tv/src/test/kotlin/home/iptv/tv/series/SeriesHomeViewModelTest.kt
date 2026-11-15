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
        var publishGate: CompletableDeferred<Unit>? = null,
        val prefs: MutableStateFlow<ProfilePrefs?> = MutableStateFlow(prefs()),
        var clock: Long = 1_000_000_000L,
    ) {
        init { rigs += this }
        // backgroundScope n'est pas avancé par advanceUntilIdle : un scope ordinaire, fermé en fin de test.
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)
        private val vmScope = CoroutineScope(SupervisorJob() + dispatcher)
        fun close() = vmScope.cancel()
        var localCalls = 0
        val published = mutableListOf<List<PublishedCategory>>()
        var publishFails = false
        fun vm(): SeriesHomeViewModel {
            return SeriesHomeViewModel(
                catalog, { server() }, { localCalls++; local }, { cats -> publishGate?.await(); if (publishFails) throw IOException("hors ligne"); published += cats },
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

    @Test
    fun reprendre_connait_le_titre_et_l_ensuite_d_une_serie_en_cours() = runTest {
        val rig = Rig(this, server = { listOf(row("a", ep = 2, watched = false, minute = 5)) })
        val vm = rig.vm()
        vm.start()
        advanceUntilIdle()
        val r = vm.ui.value.home.resume
        assertEquals("Titre 2", r?.episode?.title)
        assertEquals(3, r?.then?.number)
    }

    @Test
    fun sans_preferences_ni_rangee_ni_chargement_de_categorie() = runTest {
        val rig = Rig(this, prefs = MutableStateFlow(null), server = { listOf(row("a", minute = 5)) })
        val vm = rig.vm()
        vm.start()
        vm.loadCategory("1")
        advanceUntilIdle()
        assertEquals(emptyList(), rig.catalog.seriesCalls)
        assertEquals(emptyList(), vm.ui.value.home.categoryRows)
        assertNull(vm.ui.value.home.resume)
        assertTrue(vm.ui.value.loading)
        rig.prefs.value = prefs()
        advanceUntilIdle()
        assertEquals(3, vm.ui.value.home.categoryRows.size)
        assertEquals("a", vm.ui.value.home.resume?.seriesId)
        assertFalse(vm.ui.value.loading)
    }

    @Test
    fun ma_liste_a_son_propre_budget_de_fiches() = runTest {
        val cont = (1..25).map { row("s$it", minute = it) }
        val list = (1..25).map { "l$it" }
        val rig = Rig(this, server = { cont }, prefs = MutableStateFlow(prefs(list = list)))
        val vm = rig.vm()
        vm.start()
        advanceUntilIdle()
        assertEquals(20, rig.catalog.infoCalls.count { it.startsWith("s") })
        assertEquals(20, rig.catalog.infoCalls.count { it.startsWith("l") })
        // Les plus récentes d'abord.
        assertTrue("s25" in rig.catalog.infoCalls && "s5" !in rig.catalog.infoCalls)
    }

    @Test
    fun le_chargement_paresseux_est_annulable_et_borne() = runTest {
        val rig = Rig(this)
        val vm = rig.vm()
        vm.start()
        advanceUntilIdle()
        rig.catalog.seriesCalls.clear()
        (1..10).forEach { vm.loadCategory("c$it") }
        (1..8).forEach { vm.cancelCategory("c$it") }
        advanceUntilIdle()
        assertEquals(listOf("c9", "c10"), rig.catalog.seriesCalls)
        // Une rangée annulée peut être redemandée plus tard.
        vm.loadCategory("c1")
        advanceUntilIdle()
        assertEquals(listOf("c9", "c10", "c1"), rig.catalog.seriesCalls)
    }

    @Test
    fun la_progression_locale_est_lue_une_fois_par_ouverture() = runTest {
        val rig = Rig(this, server = { (1..5).map { row("s$it", minute = it) } })
        val vm = rig.vm()
        vm.start()
        advanceUntilIdle()
        vm.loadCategory("1")
        advanceUntilIdle()
        assertEquals(1, rig.localCalls)
        vm.reloadProgress()
        advanceUntilIdle()
        assertEquals(2, rig.localCalls)
    }

    @Test
    fun la_verification_quotidienne_n_est_pas_validee_sans_aucune_fiche() = runTest {
        val rig = Rig(this, server = { listOf(row("a", watched = true, minute = 5)) })
        rig.catalog.down = true
        val vm = rig.vm()
        vm.start()
        advanceUntilIdle()
        assertEquals(0, rig.state.checkSaves)
        rig.catalog.down = false
        vm.reloadProgress()
        advanceUntilIdle()
        assertEquals(1, rig.state.checkSaves)
    }

    @Test
    fun une_publication_lente_ne_retarde_pas_les_fiches() = runTest {
        val rig = Rig(this, server = { listOf(row("a", watched = true, minute = 5)) }, publishGate = CompletableDeferred())
        val vm = rig.vm()
        vm.start()
        advanceUntilIdle()
        assertEquals(listOf("a"), rig.catalog.infoCalls)
        assertEquals("a", vm.ui.value.home.continueRow.first().seriesId)
    }

    @Test
    fun une_liste_serveur_vide_fait_foi_malgre_l_ancienne_copie() = runTest {
        val rig = Rig(this, server = { emptyList() })
        rig.state.saved = mapOf("p1" to listOf(row("old", minute = 5)))
        val vm = rig.vm()
        vm.start()
        advanceUntilIdle()
        assertNull(vm.ui.value.home.resume)
    }
}
