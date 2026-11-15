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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
    val cached = mutableMapOf<String, String>()
    var series: Map<String, List<SeriesSummary>> = mapOf("1" to (1..30).map { summary("x$it", "1") })
    /** Demande d'une catégorie retenue jusqu'à ouverture (réseau lent). */
    var gate: CompletableDeferred<Unit>? = null
    /** Catégories dont la demande échoue. */
    var failing: Set<String> = emptySet()
    override suspend fun categories(): List<SeriesCategory> { if (down) throw IOException("hors ligne"); return categoryList }
    override suspend fun seriesOf(categoryId: String, hidden: Set<String>): List<SeriesSummary> {
        seriesCalls += categoryId
        gate?.await()
        if (down || categoryId in failing) throw IOException("hors ligne")
        return series[categoryId].orEmpty()
    }
    override fun cachedCategory(seriesId: String): String? = cached[seriesId]
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
        // « 1 » à l'écran, « 2 » préchargée juste en dessous ; « 9 » (masquée) jamais.
        assertEquals(listOf("1", "2"), rig.catalog.seriesCalls)
        assertEquals(30, vm.ui.value.rowSeries["1"]?.size) // toute la catégorie (rangée paresseuse), plus de plafond à 20
    }

    // ---- Rangées de catégories (essai TCL 0.4.0 : « 2 rangées d'environ 10 séries, en arabe ») ----

    private fun cats(n: Int) = (1..n).map { SeriesCategory("c$it", "Cat $it") }
    private fun seriesFor(n: Int, per: Int = 3) = (1..n).associate { c -> "c$c" to (1..per).map { summary("c$c-$it", "c$c") } }

    /** L'écran : les rangées [from]..[to] sont composées (à l'écran ou tout près), les autres non. */
    private class Screen(val vm: SeriesHomeViewModel) {
        val shown = LinkedHashSet<String>()
        fun show(from: Int, to: Int) {
            val wanted = (from..to).map { "c$it" }
            (shown - wanted.toSet()).forEach { vm.cancelCategory(it); shown -= it }
            wanted.filter { it !in shown }.forEach { vm.loadCategory(it); shown += it }
        }
    }

    @Test
    fun toutes_les_categories_non_masquees_deviennent_des_rangees_dans_l_ordre_du_profil() = runTest {
        val rig = Rig(this, prefs = MutableStateFlow(prefs(hidden = listOf("c3")).copy(seriesOrder = listOf("c5", "c2"))))
        rig.catalog.categoryList = cats(300)
        val vm = rig.vm()
        vm.start()
        advanceUntilIdle()
        val expected = listOf("c5", "c2") + (1..300).map { "c$it" }.filter { it !in setOf("c5", "c2", "c3") }
        assertEquals(expected, vm.ui.value.home.categoryRows.map { it.id })
        // Ouvrir l'écran ne demande aucune série : seules les rangées vues (et juste en dessous) le feront.
        assertEquals(emptyList(), rig.catalog.seriesCalls)
    }

    @Test
    fun la_rangee_juste_sous_l_ecran_est_prechargee_et_pas_plus() = runTest {
        val rig = Rig(this)
        rig.catalog.categoryList = cats(50)
        rig.catalog.series = seriesFor(50)
        val vm = rig.vm()
        vm.start()
        advanceUntilIdle()
        Screen(vm).show(1, 2)
        advanceUntilIdle()
        // Bas trouve toujours des affiches prêtes : deux rangées d'avance, jamais tout le catalogue.
        assertEquals(listOf("c1", "c2", "c3", "c4"), rig.catalog.seriesCalls)
        assertEquals(setOf("c1", "c2", "c3", "c4"), vm.ui.value.rowSeries.keys)
    }

    @Test
    fun descendre_rangee_par_rangee_charge_chaque_rangee_une_fois_sans_tout_le_catalogue() = runTest {
        val rig = Rig(this)
        rig.catalog.categoryList = cats(120)
        rig.catalog.series = seriesFor(120)
        val vm = rig.vm()
        vm.start()
        advanceUntilIdle()
        val screen = Screen(vm)
        for (top in 1..60) {
            screen.show(top, top + 1)
            advanceUntilIdle()
            assertEquals(3, vm.ui.value.rowSeries["c$top"]?.size, "rangée c$top chargée")
            assertEquals(3, vm.ui.value.rowSeries["c${top + 1}"]?.size, "rangée c${top + 1} chargée")
        }
        val calls = rig.catalog.seriesCalls
        assertEquals(calls.distinct(), calls, "chaque catégorie demandée une seule fois")
        assertEquals((1..63).map { "c$it" }, calls)
        // Mémoire bornée : seules les dernières rangées restent en mémoire, celles à l'écran toujours.
        val inMemory = vm.ui.value.rowSeries.keys
        assertTrue(inMemory.size <= MAX_ROWS_IN_MEMORY, "rangées en mémoire : ${inMemory.size}")
        assertTrue("c60" in inMemory && "c61" in inMemory)
        // Remonter tout en haut : la rangée revient (depuis le cache local du dépôt en production).
        screen.show(1, 2)
        advanceUntilIdle()
        assertEquals(3, vm.ui.value.rowSeries["c1"]?.size)
    }

    @Test
    fun le_prechargement_ne_demande_jamais_une_categorie_masquee() = runTest {
        val rig = Rig(this, prefs = MutableStateFlow(prefs(hidden = listOf("c3", "c4"))))
        rig.catalog.categoryList = cats(10)
        rig.catalog.series = seriesFor(10)
        val vm = rig.vm()
        vm.start()
        advanceUntilIdle()
        Screen(vm).show(1, 2)
        vm.loadCategory("c3") // même si l'écran la demandait
        advanceUntilIdle()
        assertEquals(listOf("c1", "c2", "c5", "c6"), rig.catalog.seriesCalls)
    }

    @Test
    fun une_rangee_sortie_de_l_ecran_pendant_sa_demande_garde_son_resultat() = runTest {
        val rig = Rig(this)
        val vm = rig.vm()
        vm.start()
        advanceUntilIdle()
        rig.catalog.gate = CompletableDeferred()
        vm.loadCategory("1")
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf("1"), rig.catalog.seriesCalls) // la demande est partie (réseau lent)
        vm.cancelCategory("1") // la rangée quitte l'écran avant la réponse
        rig.catalog.gate!!.complete(Unit)
        advanceUntilIdle()
        // La réponse n'est pas jetée : revenir sur la rangée l'affiche sans nouvelle demande.
        assertEquals(30, vm.ui.value.rowSeries["1"]?.size)
        val before = rig.catalog.seriesCalls.count { it == "1" }
        vm.loadCategory("1")
        advanceUntilIdle()
        assertEquals(before, rig.catalog.seriesCalls.count { it == "1" })
    }

    @Test
    fun une_rangee_en_echec_n_est_pas_redemandee_en_boucle_mais_l_est_a_son_retour_a_l_ecran() = runTest {
        val rig = Rig(this)
        rig.catalog.categoryList = cats(6)
        rig.catalog.series = seriesFor(6)
        rig.catalog.failing = setOf("c2")
        val vm = rig.vm()
        vm.start()
        advanceUntilIdle()
        val screen = Screen(vm)
        screen.show(1, 1)
        advanceUntilIdle()
        assertEquals(listOf("c1", "c2", "c3"), rig.catalog.seriesCalls)
        assertTrue("c2" in vm.ui.value.failedRows)
        rig.catalog.failing = emptySet()
        screen.show(1, 2) // la rangée en échec arrive à l'écran : nouvel essai
        advanceUntilIdle()
        assertEquals(listOf("c1", "c2", "c3", "c2", "c4"), rig.catalog.seriesCalls)
        assertFalse("c2" in vm.ui.value.failedRows)
    }

    @Test
    fun une_rangee_composee_avant_les_preferences_se_charge_des_qu_elles_arrivent() = runTest {
        val rig = Rig(this, prefs = MutableStateFlow(null))
        val vm = rig.vm()
        vm.start()
        vm.loadCategory("1")
        advanceUntilIdle()
        assertEquals(emptyList(), rig.catalog.seriesCalls)
        rig.prefs.value = prefs()
        advanceUntilIdle()
        assertEquals("1", rig.catalog.seriesCalls.firstOrNull())
        assertEquals(30, vm.ui.value.rowSeries["1"]?.size)
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
        // Masque gagne : la serie masquee n'est jamais demandee au fournisseur (ni via Reprendre, ni via Ma liste).
        assertEquals(listOf("b"), rig.catalog.infoCalls)
    }

    @Test
    fun serie_de_ma_liste_sans_progression_dont_le_cache_connait_la_categorie_masquee_n_est_pas_demandee() = runTest {
        val rig = Rig(this, prefs = MutableStateFlow(prefs(hidden = listOf("9"), list = listOf("a", "b"))))
        rig.catalog.cached["a"] = "9"
        val vm = rig.vm()
        vm.start()
        advanceUntilIdle()
        assertEquals(listOf("b"), rig.catalog.infoCalls)
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
