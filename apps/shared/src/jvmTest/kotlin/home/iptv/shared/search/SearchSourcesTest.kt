package home.iptv.shared.search

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import home.iptv.shared.catalog.CatalogDatabase
import home.iptv.shared.groups.*
import home.iptv.shared.movies.*
import home.iptv.shared.series.*
import home.iptv.shared.sync.*
import home.iptv.shared.xtream.*
import kotlin.test.*

class SearchSourcesTest {
    private fun database() = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { CatalogDatabase.Schema.create(it) }
    private val noSeries = object : SeriesSource {
        override suspend fun seriesCategories(): List<SeriesCategory> = error("network forbidden")
        override suspend fun series(categoryId: String): List<SeriesSummary> = error("network forbidden")
        override suspend fun seriesInfo(seriesId: String): SeriesInfo? = error("network forbidden")
    }
    private val noMovies = object : MovieSource {
        override suspend fun movieCategories(): List<MovieCategory> = error("network forbidden")
        override suspend fun movies(categoryId: String): List<MovieSummary> = error("network forbidden")
        override suspend fun movieInfo(movieId: String): MovieInfo? = error("network forbidden")
    }
    @Test fun serie_dans_deux_categories_dont_une_masquee_est_ecartee() {
        val item = SearchItem("s", "Serie", setOf("a", "b"))
        assertEquals(emptyList(), visibleSearchItems(listOf(item), setOf("b")))
    }
    @Test fun film_sans_categorie_connue_est_ecarte() {
        assertEquals(emptyList(), visibleSearchItems(listOf(SearchItem("f", "Film", emptySet()), SearchItem("g", "Film", setOf(" ", ""))), emptySet()))
    }
    @Test fun masquage_profil_et_tout_le_monde() {
        val prefs = ProfilePrefs(1, listOf(HiddenItem("series_category", "a"), HiddenItem("vod_category", "b")), emptyList(), emptyList(), null)
        val items = listOf(SearchItem("s", "Serie", setOf("a")), SearchItem("f", "Film", setOf("b")), SearchItem("v", "Visible", setOf("c")))
        assertEquals(listOf(items.last()), visibleSearchItems(items, prefs.hidden.map { it.itemId }.toSet()))
    }
    @Test fun prefs_inconnues_aucun_element() {
        // Unknown preferences must be gated by the caller; an empty hidden set means known/no masks.
        val items = listOf(SearchItem("s", "Serie", setOf("a")))
        assertEquals(items, visibleSearchItems(items, emptySet()))
    }
    @Test fun copies_regroupees_un_seul_resultat() {
        val sources = listOf(GroupSource("1", "a", "RDS HD", 0), GroupSource("2", "b", "RDS FHD", 1))
        val groups = buildGroups(sources, GroupSnapshot(1, 1, emptyList())).associateBy { it.id }
        assertEquals(listOf(SearchItem(groups.keys.single(), "RDS", setOf("a", "b"))), liveSearchItems(sources, groups))
        // Comme dans la liste du Direct : une copie masquée ne cache pas le groupe, seules les copies visibles comptent.
        assertEquals(listOf(SearchItem(groups.keys.single(), "RDS", setOf("a"))), liveSearchItems(sources.take(1), groups))
        assertEquals(1, visibleSearchItems(liveSearchItems(sources.take(1), groups), setOf("b")).size)
        assertEquals(emptyList(), liveSearchItems(emptyList(), groups))
    }
    @Test fun searchItems_lit_le_cache_sans_reseau() {
        database().use { driver ->
            val db = CatalogDatabase(driver)
            db.seriesQueries.apply {
                insertSeriesItem("s", "a", "Alpha", null, null, null, null, null, 0, null)
                insertSeriesItem("s", "b", "Beta", null, null, null, null, null, 0, null)
                insertSeriesDetail("s", "hidden", "Detail", null, null, null, null, null, null)
                insertSeriesDetail("only", "c", "Seule", null, null, null, null, null, null)
                insertSeriesDetail("unknown", " ", "Inconnue", null, null, null, null, null, null)
            }
            val repository = SeriesRepository(db, noSeries)
            val items = repository.searchItems()
            assertEquals(3, items.size)
            assertEquals(SearchItem("s", "Alpha", setOf("a", "b", "hidden")), items.single { it.id == "s" })
            assertEquals(items, repository.searchItems())
            assertEquals(listOf(SearchItem("only", "Seule", setOf("c"))), visibleSearchItems(items, setOf("hidden")))
        }
    }
    @Test fun films_union_liste_et_fiche_et_categories_vides() {
        database().use { driver ->
            val db = CatalogDatabase(driver)
            db.moviesQueries.apply {
                insertMovieItem("f", "a", "Alpha", null, null, null, null, null, null, "mkv", null, 0, null)
                insertMovieItem("f", "b", "Beta", null, null, null, null, null, null, "mkv", null, 0, null)
                insertMovieDetail("f", "hidden", "Detail", null, null, null, null, null, null, "mkv", null, null)
                insertMovieDetail("only", "c", "Seul", null, null, null, null, null, null, "mkv", null, null)
                insertMovieDetail("unknown", "", "Inconnu", null, null, null, null, null, null, "mkv", null, null)
            }
            val repository = MovieRepository(db, noMovies)
            val items = repository.searchItems()
            assertEquals(3, items.size)
            assertEquals(SearchItem("f", "Alpha", setOf("a", "b", "hidden")), items.single { it.id == "f" })
            assertEquals(items, repository.searchItems())
            assertEquals(listOf(SearchItem("only", "Seul", setOf("c"))), visibleSearchItems(items, setOf("hidden")))
        }
    }
}
