package home.iptv.shared.search

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import home.iptv.shared.catalog.CatalogDatabase
import home.iptv.shared.series.*
import home.iptv.shared.movies.*
import home.iptv.shared.xtream.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.Duration.Companion.hours

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogFillerTest {
    private class Fixture(val scope: CoroutineScope) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val db = CatalogDatabase(driver).also { CatalogDatabase.Schema.create(driver) }
        var now = Instant.fromEpochSeconds(1_800_000_000)
        val clock = object : Clock { override fun now() = this@Fixture.now }
        val calls = mutableListOf<String>()
        var active = 0
        var maxActive = 0
        var gate: CompletableDeferred<Unit>? = null
        var fail = false
        var failLists = false
        var seriesHidden: Set<String>? = emptySet()
        var movieHidden: Set<String>? = emptySet()
        var priority = SearchSection.SERIES
        suspend fun <T> request(key: String, value: T): T {
            calls += key
            active++
            maxActive = maxOf(active, maxActive)
            try {
                gate?.await()
                if ((fail && key.contains(':')) || (failLists && key.endsWith("Cats"))) error("indisponible")
                return value
            } finally { active-- }
        }
        val series = SeriesRepository(db, object : SeriesSource {
            override suspend fun seriesCategories() = request("seriesCats", listOf(SeriesCategory("1", "Un"), SeriesCategory("2", "Deux")))
            override suspend fun series(categoryId: String) = request("s:$categoryId", listOf(SeriesSummary(categoryId, categoryId, "Serie", null, null, null, null, null)))
            override suspend fun seriesInfo(seriesId: String): SeriesInfo? = null
        }, clock)
        val movies = MovieRepository(db, object : MovieSource {
            override suspend fun movieCategories() = request("movieCats", listOf(MovieCategory("1", "Un"), MovieCategory("2", "Deux")))
            override suspend fun movies(categoryId: String) = request("m:$categoryId", listOf(MovieSummary(categoryId, categoryId, "Film", null, null, null, null, null, null, "mkv", null)))
            override suspend fun movieInfo(movieId: String): MovieInfo? = null
        }, clock)
        val filler = CatalogFiller(series, movies, scope)
        fun start() = filler.start({ seriesHidden }, { movieHidden }, { priority })
        fun close() { filler.stop(); driver.close() }
    }

    @Test fun ne_demande_jamais_une_categorie_masquee() = runTest {
        val f = Fixture(backgroundScope)
        try {
            f.seriesHidden = setOf("2"); f.movieHidden = setOf("2")
            f.start(); runCurrent()
            f.seriesHidden = setOf("1", "2"); f.movieHidden = setOf("1", "2")
            advanceTimeBy(5_000); runCurrent()
            assertFalse(f.calls.contains("s:2")); assertFalse(f.calls.any { it.startsWith("m:") })
            assertEquals(FillProgress(0, 0), f.filler.progress.value[SearchSection.SERIES])
        } finally { f.close() }
    }

    @Test fun une_seule_demande_a_la_fois() = runTest {
        val f = Fixture(backgroundScope)
        try {
            f.gate = CompletableDeferred(); f.start(); f.start(); runCurrent()
            val screen = launch { f.filler.screenFirst { f.request("screen", Unit) } }
            runCurrent(); assertEquals(1, f.active)
            f.gate!!.complete(Unit); advanceTimeBy(5_000); runCurrent(); screen.join()
            assertEquals(1, f.maxActive)
            assertEquals(4, f.calls.count { it.contains(':') })
        } finally { f.close() }
    }

    @Test fun priorite_a_la_section_affichee() = runTest {
        val f = Fixture(backgroundScope)
        try {
            f.priority = SearchSection.MOVIES; f.start(); advanceTimeBy(5_000); runCurrent()
            assertEquals("movieCats", f.calls.first())
            assertEquals("m:1", f.calls.first { it.contains(':') })
        } finally { f.close() }
    }

    @Test fun demande_de_l_ecran_passe_devant() = runTest {
        val f = Fixture(backgroundScope)
        try {
            f.start(); runCurrent()
            val release = CompletableDeferred<Unit>()
            val screen = launch { f.filler.screenFirst { f.request("screen", Unit); release.await() } }
            runCurrent(); val before = f.calls.toList()
            advanceTimeBy(5_000); runCurrent(); assertEquals(before, f.calls)
            release.complete(Unit); screen.join(); advanceTimeBy(5_000); runCurrent()
            assertTrue(f.calls.size > before.size)
        } finally { f.close() }
    }

    @Test fun echec_reessaye_au_prochain_passage_pas_en_boucle() = runTest {
        val f = Fixture(backgroundScope)
        try {
            f.fail = true; f.start(); advanceTimeBy(10_000); runCurrent()
            assertEquals(1, f.calls.count { it == "s:1" })
            assertEquals(FillProgress(0, 2), f.filler.progress.value[SearchSection.SERIES])
            f.filler.stop(); f.fail = false; f.start(); advanceTimeBy(5_000); runCurrent()
            assertEquals(2, f.calls.count { it == "s:1" })
            assertTrue(f.filler.progress.value.getValue(SearchSection.SERIES).complete)
        } finally { f.close() }
    }

    @Test fun stop_puis_start_reprend_sans_redemander_le_frais() = runTest {
        val f = Fixture(backgroundScope)
        try {
            f.start(); advanceTimeBy(5_000); runCurrent(); val calls = f.calls.toList()
            f.filler.stop(); advanceTimeBy(5_000); runCurrent(); assertEquals(calls, f.calls)
            f.start(); advanceTimeBy(5_000); runCurrent(); assertEquals(calls, f.calls)
        } finally { f.close() }
    }

    @Test fun prefs_inconnues_aucune_demande() = runTest {
        val f = Fixture(backgroundScope)
        try {
            f.seriesHidden = null; f.movieHidden = null; f.start(); advanceTimeBy(5_000); runCurrent()
            assertTrue(f.calls.isEmpty())
            f.seriesHidden = emptySet(); advanceTimeBy(5_000); runCurrent()
            assertTrue(f.calls.contains("s:1")); assertFalse(f.calls.contains("movieCats"))
        } finally { f.close() }
    }

    @Test fun changement_de_profil_recalcule_l_avancement() = runTest {
        val f = Fixture(backgroundScope)
        try {
            f.movieHidden = null; f.start(); runCurrent()
            f.seriesHidden = setOf("2"); advanceTimeBy(5_000); runCurrent()
            assertFalse(f.calls.contains("s:2"))
            assertEquals(FillProgress(1, 1), f.filler.progress.value[SearchSection.SERIES])
            f.seriesHidden = null; advanceTimeBy(1_000); runCurrent()
            assertEquals(FillProgress(0, 0), f.filler.progress.value[SearchSection.SERIES])
        } finally { f.close() }
    }

    @Test fun avancement_et_changed_emis() = runTest {
        val f = Fixture(backgroundScope)
        try {
            val changed = mutableListOf<SearchSection>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.filler.changed.collect { changed += it } }
            f.start(); advanceTimeBy(5_000); runCurrent()
            assertEquals(FillProgress(2, 2), f.filler.progress.value[SearchSection.SERIES])
            assertEquals(FillProgress(2, 2), f.filler.progress.value[SearchSection.MOVIES])
            assertEquals(2, changed.count { it == SearchSection.SERIES })
            assertEquals(2, changed.count { it == SearchSection.MOVIES })
        } finally { f.close() }
    }

    @Test fun cache_perime_servi_apres_echec_ne_compte_pas_comme_frais() = runTest {
        val f = Fixture(backgroundScope)
        try {
            f.start(); advanceTimeBy(5_000); runCurrent(); f.filler.stop()
            f.now += 73.hours; f.fail = true
            val changed = mutableListOf<SearchSection>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.filler.changed.collect { changed += it } }
            f.start(); advanceTimeBy(10_000); runCurrent()
            assertEquals(FillProgress(0, 2), f.filler.progress.value[SearchSection.SERIES])
            assertEquals(FillProgress(0, 2), f.filler.progress.value[SearchSection.MOVIES])
            assertEquals(2, f.calls.count { it == "s:1" }); assertTrue(changed.isEmpty())
        } finally { f.close() }
    }
    @Test fun echec_liste_categories_perimee_ne_boucle_pas() = runTest {
        val f = Fixture(backgroundScope)
        try {
            f.start(); advanceTimeBy(5_000); runCurrent(); f.filler.stop()
            f.now += 73.hours; f.failLists = true
            f.start(); advanceTimeBy(10_000); runCurrent()
            assertEquals(2, f.calls.count { it == "seriesCats" })
            assertEquals(2, f.calls.count { it == "movieCats" })
            assertEquals(FillProgress(0, 2), f.filler.progress.value[SearchSection.SERIES])
        } finally { f.close() }
    }

    @Test fun ecran_en_attente_passe_avant_prochaine_demande_de_fond() = runTest {
        val f = Fixture(backgroundScope)
        try {
            f.start(); runCurrent()
            val inFlight = CompletableDeferred<Unit>(); f.gate = inFlight
            advanceTimeBy(500); runCurrent(); assertEquals("s:1", f.calls.last())
            val releaseScreen = CompletableDeferred<Unit>()
            val screen = launch { f.filler.screenFirst { f.request("screen", Unit); releaseScreen.await() } }
            runCurrent(); inFlight.complete(Unit); runCurrent()
            assertEquals("screen", f.calls.last()); val before = f.calls.toList()
            advanceTimeBy(5_000); runCurrent(); assertEquals(before, f.calls)
            f.seriesHidden = null; f.movieHidden = null
            releaseScreen.complete(Unit); screen.join(); advanceTimeBy(2_000); runCurrent()
            assertEquals(before, f.calls)
            assertEquals(FillProgress(0, 0), f.filler.progress.value[SearchSection.SERIES])
        } finally { f.close() }
    }

    @Test fun stop_annule_demande_en_vol_et_start_reprend() = runTest {
        val f = Fixture(backgroundScope)
        try {
            f.start(); runCurrent(); f.gate = CompletableDeferred()
            advanceTimeBy(500); runCurrent(); assertEquals(1, f.active)
            f.filler.stop(); runCurrent(); assertEquals(0, f.active)
            f.gate = null; f.start(); advanceTimeBy(5_000); runCurrent()
            assertEquals(2, f.calls.count { it == "s:1" })
            assertEquals(FillProgress(2, 2), f.filler.progress.value[SearchSection.SERIES])
            assertEquals(1, f.maxActive)
        } finally { f.close() }
    }

    @Test fun profil_change_pendant_requete_recalcule_sans_attendre_sa_fin() = runTest {
        val f = Fixture(backgroundScope)
        try {
            f.movieHidden = null; f.seriesHidden = setOf("2"); f.start(); runCurrent()
            f.gate = CompletableDeferred(); advanceTimeBy(500); runCurrent()
            assertEquals("s:1", f.calls.last())
            f.seriesHidden = setOf("1")
            advanceTimeBy(1_000); runCurrent()
            assertEquals(FillProgress(0, 1), f.filler.progress.value[SearchSection.SERIES])
            f.gate!!.complete(Unit); advanceTimeBy(5_000); runCurrent()
            assertEquals(FillProgress(1, 1), f.filler.progress.value[SearchSection.SERIES])
        } finally { f.close() }
    }

    @Test fun expiration_apres_succes_rafraichit_sans_redemarrer() = runTest {
        val f = Fixture(backgroundScope)
        try {
            f.start(); advanceTimeBy(5_000); runCurrent()
            assertEquals(FillProgress(2, 2), f.filler.progress.value[SearchSection.SERIES])
            assertEquals(FillProgress(2, 2), f.filler.progress.value[SearchSection.MOVIES])
            f.now += 73.hours
            advanceTimeBy(5_000); runCurrent()
            for (key in listOf("seriesCats", "movieCats", "s:1", "s:2", "m:1", "m:2")) {
                assertEquals(2, f.calls.count { it == key }, key)
            }
            assertEquals(FillProgress(2, 2), f.filler.progress.value[SearchSection.SERIES])
            assertEquals(FillProgress(2, 2), f.filler.progress.value[SearchSection.MOVIES])
            assertEquals(1, f.maxActive)
        } finally { f.close() }
    }

}
