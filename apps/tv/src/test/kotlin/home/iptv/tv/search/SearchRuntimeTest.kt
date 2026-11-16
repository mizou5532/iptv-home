package home.iptv.tv.search

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import home.iptv.shared.catalog.CatalogDatabase
import home.iptv.shared.series.*
import home.iptv.shared.movies.*
import home.iptv.shared.xtream.*
import home.iptv.shared.search.*
import home.iptv.shared.sync.*
import home.iptv.tv.series.*
import home.iptv.tv.movies.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SearchRuntimeTest {
    private class Rig(val scope: CoroutineScope) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val db = CatalogDatabase(driver).also { CatalogDatabase.Schema.create(driver) }
        val calls = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        suspend fun call(name: String) { calls += name; gate?.await() }
        val series = SeriesRepository(db, object : SeriesSource {
            override suspend fun seriesCategories() = listOf(SeriesCategory("s", "Séries"))
            override suspend fun series(categoryId: String): List<SeriesSummary> { call("series"); return emptyList() }
            override suspend fun seriesInfo(seriesId: String): SeriesInfo? { call("seriesDetail"); return null }
        })
        val movies = MovieRepository(db, object : MovieSource {
            override suspend fun movieCategories() = listOf(MovieCategory("m", "Films"))
            override suspend fun movies(categoryId: String): List<MovieSummary> { call("movies"); return emptyList() }
            override suspend fun movieInfo(movieId: String): MovieInfo? { call("movieDetail"); return null }
        })
        val filler = CatalogFiller(series, movies, scope)
        val runtime = SearchRuntime(filler, scope, startupDelayMs = 3000)
        fun close() { runtime.stop(); driver.close() }
    }
    @Test fun cycle_de_vie_et_preferences_inconnues_ne_remplissent_pas() = runTest {
        val f = Rig(backgroundScope)
        try {
            val prefs = MutableStateFlow<ProfilePrefs?>(null)
            f.runtime.bind(prefs, SearchSection.SERIES); f.runtime.start(); advanceTimeBy(5000); runCurrent(); assertTrue(f.calls.isEmpty())
            prefs.value = ProfilePrefs(1, emptyList(), emptyList(), emptyList(), null)
            advanceTimeBy(1500); runCurrent(); assertTrue("series" in f.calls)
            f.runtime.stop(); val count = f.calls.size; advanceTimeBy(10000); runCurrent(); assertEquals(count, f.calls.size)
            val next = MutableStateFlow<ProfilePrefs?>(prefs.value!!.copy(hidden = listOf(HiddenItem("series_category", "s"), HiddenItem("vod_category", "m"))))
            f.runtime.bind(next, SearchSection.MOVIES); f.runtime.start(); advanceTimeBy(10000); runCurrent(); assertEquals(count, f.calls.size)
        } finally { f.close() }
    }
    @Test fun accueils_et_fiches_passent_par_le_meme_verrou() = runTest {
        val f = Rig(backgroundScope)
        try {
            val series = RepoSeriesCatalog(f.series, f.filler)
            val movies = RepoMovieCatalog(f.movies, f.filler)
            val release = CompletableDeferred<Unit>()
            val holder = launch { f.filler.screenFirst { release.await() } }; runCurrent()
            val jobs = listOf(launch { series.seriesOf("s", emptySet()) }, launch { series.info("id", "s") }, launch { movies.moviesOf("m", emptySet()) }, launch { movies.info("id", "m") })
            runCurrent(); assertTrue(f.calls.isEmpty())
            release.complete(Unit); holder.join(); jobs.joinAll()
            assertEquals(setOf("series", "seriesDetail", "movies", "movieDetail"), f.calls.toSet())
        } finally { f.close() }
    }
}
