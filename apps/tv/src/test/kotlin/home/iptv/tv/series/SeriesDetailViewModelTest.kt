package home.iptv.tv.series

import home.iptv.shared.progress.ProgressClient
import home.iptv.shared.progress.ProgressEntry
import home.iptv.shared.progress.ProgressRow
import home.iptv.shared.progress.WatchedItem
import home.iptv.shared.sync.HiddenItem
import home.iptv.shared.sync.ProfilePrefs
import home.iptv.shared.xtream.Episode
import home.iptv.shared.xtream.Season
import home.iptv.shared.xtream.SeriesCategory
import home.iptv.shared.xtream.SeriesInfo
import home.iptv.shared.xtream.SeriesSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

private fun ep(season: Int, n: Int, duration: Int? = 2400) = Episode("s${season}e$n", season, n, "Titre $n", duration, "mkv", null)

private fun infoOf(seasons: List<Season>, cat: String = "7") = SeriesInfo(
    SeriesSummary("42", cat, "Les Rivages", "http://img/c.jpg", "2021", "Drame", "Resume", null), seasons,
)

private fun threeSeasons() = listOf(
    Season(1, "Saison 1", (1..8).map { ep(1, it) }),
    Season(2, "Saison 2", (1..8).map { ep(2, it) }),
    Season(3, "Saison 3", (1..4).map { ep(3, it) }),
)

private fun row(id: String, season: Int, n: Int, pos: Int, dur: Int = 2400, watched: Boolean = false, minute: Int = 0) = ProgressRow(
    itemId = id, seriesId = "42", season = season, episode = n, positionS = pos, durationS = dur, watched = watched,
    clientUpdatedAt = Instant.parse("2026-11-15T10:00:00Z") + kotlin.time.Duration.parse("${minute}m"), seriesCategory = "7",
)

private class Catalog(var info: SeriesInfo? = infoOf(threeSeasons()), var down: Boolean = false) : SeriesCatalog {
    val infoCalls = mutableListOf<Pair<String, String?>>()
    var cached: String? = null
    override fun cachedCategory(seriesId: String): String? = cached
    override suspend fun categories(): List<SeriesCategory> = emptyList()
    override suspend fun seriesOf(categoryId: String, hidden: Set<String>): List<SeriesSummary> = emptyList()
    override suspend fun info(seriesId: String, categoryId: String?): SeriesInfo? {
        infoCalls += seriesId to categoryId
        if (down) throw IOException("hors ligne")
        return info
    }
}

private class Progress : ProgressClient {
    var fail = false
    val watchedCalls = mutableListOf<Pair<List<WatchedItem>, Boolean>>()
    val listCalls = mutableListOf<Boolean>()
    override suspend fun record(p: String, entry: ProgressEntry) = true
    override suspend fun continueWatching(p: String, max: Int): List<ProgressRow> = emptyList()
    override suspend fun setWatched(p: String, items: List<WatchedItem>, watched: Boolean) {
        if (fail) throw IOException("hors ligne")
        watchedCalls += items to watched
    }
    override suspend fun seriesProgress(p: String, seriesId: String): List<ProgressRow> = emptyList()
    override suspend fun setSeriesList(p: String, seriesId: String, on: Boolean) {
        if (fail) throw IOException("hors ligne")
        listCalls += on
    }
}

private fun prefs(hidden: List<String> = emptyList(), list: List<String> = emptyList()) = ProfilePrefs(
    rev = 1, hidden = hidden.map { HiddenItem("series_category", it) }, favorites = emptyList(),
    playlists = emptyList(), order = null, seriesList = list,
)

@OptIn(ExperimentalCoroutinesApi::class)
class SeriesDetailViewModelTest {
    private val scopes = mutableListOf<CoroutineScope>()

    @kotlin.test.AfterTest
    fun close() { scopes.forEach { it.cancel() } }

    private inner class Rig(
        val test: TestScope,
        val catalog: Catalog = Catalog(),
        val progress: Progress = Progress(),
        var server: List<ProgressRow> = emptyList(),
        var serverDown: Boolean = false,
        var local: List<ProgressRow> = emptyList(),
        var serverGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null,
        val prefs: MutableStateFlow<ProfilePrefs?> = MutableStateFlow(prefs()),
        val categoryId: String? = "7",
    ) {
        private val dispatcher = StandardTestDispatcher(test.testScheduler)
        private val scope = CoroutineScope(SupervisorJob() + dispatcher).also { scopes += it }
        fun vm() = SeriesDetailViewModel(
            "42", categoryId, catalog, progress, "p1",
            { serverGate?.await(); if (serverDown) throw IOException("hors ligne"); server }, { local },
            { ids -> dropped += ids; local = local.filterNot { it.itemId in ids } }, prefs, scope, dispatcher,
        )
        val dropped = mutableSetOf<String>()
    }

    private suspend fun TestScope.opened(rig: Rig): SeriesDetailViewModel {
        val vm = rig.vm()
        vm.start()
        advanceUntilIdle()
        return vm
    }

    @Test fun movieWithSameIdCannotMarkEpisodeWatched() = runTest {
        val rig = Rig(this, server = listOf(row("s1e1", 1, 1, 2300, watched = true).copy(itemType = "movie")))
        val vm = opened(rig)
        assertEquals(EpisodeStatus.Upcoming, vm.state.value.episodes.first().status)
    }

    @Test
    fun saison_initiale_est_celle_de_l_episode_en_cours() = runTest {
        val rig = Rig(this, server = listOf(
            row("s1e8", 1, 8, 0, watched = true, minute = 1),
            row("s2e3", 2, 3, 600, minute = 5),
        ))
        val vm = opened(rig)
        assertEquals(2, vm.state.value.selectedSeason)
        assertTrue(vm.state.value.episodes.first { it.number == 3 }.status is EpisodeStatus.InProgress)
    }

    @Test
    fun sans_episode_en_cours_la_premiere_saison_non_vue() = runTest {
        val rig = Rig(this, server = (1..8).map { row("s1e$it", 1, it, 0, watched = true) })
        val vm = opened(rig)
        assertEquals(2, vm.state.value.selectedSeason)
        assertEquals(true, vm.state.value.seasons.first { it.number == 1 }.allWatched)
    }

    @Test
    fun compte_des_episodes_vus_par_saison() = runTest {
        val rig = Rig(this, server = (1..4).map { row("s1e$it", 1, it, 0, watched = true) })
        val vm = opened(rig)
        val s1 = vm.state.value.seasons.first { it.number == 1 }
        assertEquals(4, s1.watchedCount)
        assertEquals(8, s1.total)
        assertEquals(1, vm.state.value.selectedSeason)
    }

    @Test
    fun regle_des_90_pour_cent_et_file_locale_fusionnees() = runTest {
        val rig = Rig(
            this,
            server = listOf(row("s1e1", 1, 1, 300, minute = 1)),
            // Plus récent sur la télé : 95 % = vu.
            local = listOf(row("s1e1", 1, 1, 2300, minute = 9), row("s1e2", 1, 2, 100, minute = 8)),
        )
        val vm = opened(rig)
        val eps = vm.state.value.episodes
        assertEquals(EpisodeStatus.Watched, eps.first { it.number == 1 }.status)
        assertEquals(EpisodeStatus.InProgress(100, 2400), eps.first { it.number == 2 }.status)
        assertEquals(EpisodeStatus.Upcoming, eps.first { it.number == 3 }.status)
    }

    @Test
    fun marquer_la_saison_vue_puis_non_vue_envoie_les_champs_d_enrichissement() = runTest {
        val rig = Rig(this)
        val vm = opened(rig)
        vm.markSeasonWatched(true)
        advanceUntilIdle()
        assertEquals(8, vm.state.value.seasons.first { it.number == 1 }.watchedCount)
        val (items, flag) = rig.progress.watchedCalls.single()
        assertTrue(flag)
        assertEquals(8, items.size)
        val first = items.first()
        assertEquals("Les Rivages", first.seriesName)
        assertEquals("http://img/c.jpg", first.seriesCover)
        assertEquals("7", first.seriesCategory)
        assertEquals("42", first.seriesId)
        vm.markSeasonWatched(false)
        advanceUntilIdle()
        assertEquals(0, vm.state.value.seasons.first { it.number == 1 }.watchedCount)
        assertEquals(false, rig.progress.watchedCalls.last().second)
    }

    @Test
    fun echec_d_envoi_annule_la_marque_et_explique() = runTest {
        val rig = Rig(this)
        val vm = opened(rig)
        rig.progress.fail = true
        vm.markEpisodeWatched("s1e1", true)
        advanceUntilIdle()
        assertEquals(EpisodeStatus.Upcoming, vm.state.value.episodes.first { it.number == 1 }.status)
        assertNotNull(vm.state.value.message)
    }

    @Test
    fun duree_absente_acceptee() = runTest {
        val rig = Rig(this, catalog = Catalog(infoOf(listOf(Season(1, "", listOf(ep(1, 1, duration = null), ep(1, 2, duration = null)))))))
        val vm = opened(rig)
        assertEquals(2, vm.state.value.episodes.size)
        assertNull(vm.state.value.episodes.first().durationS)
        assertEquals("Saison 1", vm.state.value.seasons.single().name)
        vm.markEpisodeWatched("s1e1", true)
        advanceUntilIdle()
        assertNull(rig.progress.watchedCalls.single().first.single().durationS)
        assertEquals(EpisodeStatus.Watched, vm.state.value.episodes.first().status)
    }

    @Test
    fun saison_sans_episode_ne_plante_pas() = runTest {
        val rig = Rig(this, catalog = Catalog(infoOf(listOf(Season(1, "Saison 1", emptyList()), Season(2, "Saison 2", listOf(ep(2, 1)))))))
        val vm = opened(rig)
        assertEquals(2, vm.state.value.selectedSeason)
        vm.selectSeason(1)
        advanceUntilIdle()
        assertTrue(vm.state.value.episodes.isEmpty())
        assertNotNull(vm.state.value.message)
        vm.markSeasonWatched(true)
        advanceUntilIdle()
        assertTrue(rig.progress.watchedCalls.isEmpty())
    }

    @Test
    fun fiche_absente_sans_cache_affiche_un_message_sans_planter() = runTest {
        val rig = Rig(this, catalog = Catalog(down = true))
        val vm = opened(rig)
        assertFalse(vm.state.value.loading)
        assertTrue(vm.state.value.message.orEmpty().startsWith("Fiche indisponible"))
        assertNull(vm.play("s1e1", false))
    }

    @Test
    fun fiche_en_cache_perime_servie_quand_le_fournisseur_est_coupe() = runTest {
        // Vrai depot (base en memoire) : la fiche est gardee, 7 h plus tard le fournisseur est coupe, le cache perime est servi.
        val driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY)
        home.iptv.shared.catalog.CatalogDatabase.Schema.create(driver)
        var down = false
        val clock = object : kotlin.time.Clock { var now = Instant.parse("2026-11-15T10:00:00Z"); override fun now() = now }
        val source = object : home.iptv.shared.series.SeriesSource {
            override suspend fun seriesCategories(): List<SeriesCategory> = emptyList()
            override suspend fun series(categoryId: String): List<SeriesSummary> = emptyList()
            override suspend fun seriesInfo(seriesId: String): SeriesInfo? { if (down) throw IOException("hors ligne"); return infoOf(threeSeasons()) }
        }
        val catalog = RepoSeriesCatalog(home.iptv.shared.series.SeriesRepository(home.iptv.shared.catalog.CatalogDatabase(driver), source, clock))
        catalog.info("42", "7") // premiere ouverture : remplit le cache
        clock.now = clock.now + kotlin.time.Duration.parse("7h")
        down = true
        val rig = Rig(this, serverDown = true)
        val vm = SeriesDetailViewModel(
            "42", "7", catalog, rig.progress, "p1", { throw IOException("hors ligne") }, { emptyList() }, {},
            rig.prefs, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)).also { scopes += it },
            StandardTestDispatcher(testScheduler),
        )
        vm.start()
        advanceUntilIdle()
        assertEquals("Les Rivages", vm.state.value.title)
        assertEquals(3, vm.state.value.seasons.size)
        assertTrue(vm.state.value.message.orEmpty().startsWith("Progression indisponible"))
    }

    @Test
    fun categorie_masquee_refuse_la_lecture_et_ne_demande_rien() = runTest {
        val rig = Rig(this, prefs = MutableStateFlow(prefs(hidden = listOf("7"))))
        val vm = opened(rig)
        assertTrue(vm.state.value.masked)
        assertEquals("Série masquée", vm.state.value.message)
        assertTrue(rig.catalog.infoCalls.isEmpty())
        assertNull(vm.play("s1e1", false))
    }

    @Test
    fun categorie_masquee_connue_du_cache_seul_n_est_jamais_demandee() = runTest {
        val rig = Rig(this, categoryId = null, prefs = MutableStateFlow(prefs(hidden = listOf("7"))))
        rig.catalog.cached = "7"
        val vm = opened(rig)
        assertTrue(vm.state.value.masked)
        assertTrue(rig.catalog.infoCalls.isEmpty())
        assertNull(vm.play("s1e1", false))
        vm.reload()
        advanceUntilIdle()
        assertTrue(rig.catalog.infoCalls.isEmpty(), "reload ne demande rien non plus")
    }

    @Test
    fun masquage_apparu_pendant_l_ecran_refuse_la_lecture() = runTest {
        val rig = Rig(this, categoryId = null)
        val vm = opened(rig)
        assertNotNull(vm.play("s1e1", false))
        rig.prefs.value = prefs(hidden = listOf("7"))
        advanceUntilIdle()
        assertTrue(vm.state.value.masked)
        assertNull(vm.play("s1e1", false))
    }

    @Test
    fun la_categorie_connue_est_transmise_au_depot() = runTest {
        val rig = Rig(this)
        opened(rig)
        assertEquals("42" to "7", rig.catalog.infoCalls.single())
    }

    @Test
    fun lecture_reprend_a_la_position_ou_recommence() = runTest {
        val rig = Rig(this, server = listOf(row("s1e2", 1, 2, 700, minute = 3)))
        val vm = opened(rig)
        val resume = assertNotNull(vm.play("s1e2", false))
        assertEquals(700, resume.startS)
        assertFalse(resume.restart)
        val again = assertNotNull(vm.play("s1e2", true))
        assertEquals(0, again.startS)
        assertTrue(again.restart)
        assertEquals(0, assertNotNull(vm.play("s1e3", false)).startS)
        assertNull(vm.play("inconnu", false))
    }

    @Test
    fun dans_ma_liste_est_optimiste_et_annule_en_cas_d_echec() = runTest {
        val rig = Rig(this)
        val vm = opened(rig)
        assertFalse(vm.state.value.inMyList)
        vm.toggleMyList()
        advanceUntilIdle()
        assertTrue(vm.state.value.inMyList)
        assertEquals(listOf(true), rig.progress.listCalls)
        rig.progress.fail = true
        vm.toggleMyList()
        advanceUntilIdle()
        assertTrue(vm.state.value.inMyList)
        assertNotNull(vm.state.value.message)
    }

    @Test
    fun chargement_tant_que_la_progression_n_est_pas_arrivee() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val rig = Rig(this, server = listOf(row("s2e3", 2, 3, 600, minute = 5)), serverGate = gate)
        val vm = rig.vm()
        vm.start()
        advanceUntilIdle()
        // La fiche est arrivée mais pas la progression : aucun épisode publié, aucune saison choisie.
        assertTrue(vm.state.value.loading)
        assertTrue(vm.state.value.episodes.isEmpty())
        assertNull(vm.state.value.selectedSeason)
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.state.value.loading)
        assertEquals(2, vm.state.value.selectedSeason)
    }

    @Test
    fun progression_en_echec_met_fin_au_chargement() = runTest {
        val rig = Rig(this, serverDown = true)
        val vm = opened(rig)
        assertFalse(vm.state.value.loading)
        assertEquals(3, vm.state.value.seasons.size)
    }

    @Test
    fun marquer_le_dernier_episode_non_vu_garde_la_saison() = runTest {
        val rig = Rig(this, server = (1..7).map { row("s1e$it", 1, it, 0, watched = true) })
        val vm = opened(rig)
        assertEquals(1, vm.state.value.selectedSeason)
        vm.markEpisodeWatched("s1e8", true)
        advanceUntilIdle()
        assertEquals(1, vm.state.value.selectedSeason)
        assertTrue(vm.state.value.seasons.first().allWatched)
    }

    @Test
    fun demarquer_retire_la_position_locale_plus_recente_que_le_serveur() = runTest {
        val rig = Rig(
            this,
            server = listOf(row("s1e1", 1, 1, 300, minute = 1)),
            local = listOf(row("s1e1", 1, 1, 2300, minute = 9)),
        )
        val vm = opened(rig)
        assertEquals(EpisodeStatus.Watched, vm.state.value.episodes.first().status)
        vm.markEpisodeWatched("s1e1", false)
        advanceUntilIdle()
        assertEquals(EpisodeStatus.Upcoming, vm.state.value.episodes.first().status)
        assertEquals(setOf("s1e1"), rig.dropped)
        assertTrue(rig.local.isEmpty())
    }

    @Test
    fun echec_d_envoi_garde_la_file_locale() = runTest {
        val rig = Rig(this, local = listOf(row("s1e1", 1, 1, 100, minute = 9)))
        val vm = opened(rig)
        rig.progress.fail = true
        vm.markEpisodeWatched("s1e1", true)
        advanceUntilIdle()
        assertTrue(rig.dropped.isEmpty())
        assertEquals(1, rig.local.size)
    }

    @Test
    fun fin_de_lecture_relit_la_progression_et_efface_la_marque() = runTest {
        val rig = Rig(this)
        val vm = opened(rig)
        vm.markEpisodeWatched("s1e1", true)
        advanceUntilIdle()
        assertEquals(EpisodeStatus.Watched, vm.state.value.episodes.first { it.number == 1 }.status)
        // Pendant la lecture : position enregistree sur la tele, et un autre episode vu cote serveur.
        rig.local = listOf(row("s1e1", 1, 1, 700, minute = 20))
        rig.server = listOf(row("s1e2", 1, 2, 0, watched = true, minute = 1))
        vm.playbackClosed("s1e1")
        advanceUntilIdle()
        val eps = vm.state.value.episodes
        assertEquals(EpisodeStatus.InProgress(700, 2400), eps.first { it.number == 1 }.status)
        assertEquals(EpisodeStatus.Watched, eps.first { it.number == 2 }.status)
    }

    @Test
    fun saisons_et_episodes_en_double_ne_cassent_pas_les_cles() = runTest {
        val dup = infoOf(listOf(
            Season(1, "Saison 1", listOf(ep(1, 1), ep(1, 1), ep(1, 2))),
            Season(1, "Saison 1 bis", listOf(ep(1, 3))),
        ))
        val vm = opened(Rig(this, catalog = Catalog(dup)))
        assertEquals(1, vm.state.value.seasons.size)
        assertEquals(listOf("s1e1", "s1e2"), vm.state.value.episodes.map { it.id })
    }

    @Test
    fun sans_categorie_connue_la_progression_decide_avant_de_demander_la_fiche() = runTest {
        val rig = Rig(
            this, categoryId = null, server = listOf(row("s1e1", 1, 1, 100)),
            prefs = MutableStateFlow(prefs(hidden = listOf("7"))),
        )
        val vm = opened(rig)
        assertTrue(vm.state.value.masked)
        assertTrue(rig.catalog.infoCalls.isEmpty())
    }

    @Test
    fun message_d_action_efface_au_changement_de_saison() = runTest {
        val rig = Rig(this)
        val vm = opened(rig)
        rig.progress.fail = true
        vm.markEpisodeWatched("s1e1", true)
        advanceUntilIdle()
        assertNotNull(vm.state.value.message)
        vm.selectSeason(2)
        advanceUntilIdle()
        assertNull(vm.state.value.message)
    }
}
