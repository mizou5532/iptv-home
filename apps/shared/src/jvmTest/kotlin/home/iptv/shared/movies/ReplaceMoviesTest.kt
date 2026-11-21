package home.iptv.shared.movies

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import home.iptv.shared.catalog.CatalogDatabase
import home.iptv.shared.xtream.MediaTech
import home.iptv.shared.xtream.MovieCategory
import home.iptv.shared.xtream.MovieInfo
import home.iptv.shared.xtream.MovieSummary
import home.iptv.shared.xtream.XtreamError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

private object NeverMovieSource : MovieSource {
    override suspend fun movieCategories() = error("ne doit pas appeler le fournisseur")
    override suspend fun movies(categoryId: String) = error("ne doit pas appeler le fournisseur")
    override suspend fun movieInfo(movieId: String) = error("ne doit pas appeler le fournisseur")
}

class ReplaceMoviesTest {
    private var nowMs = 1_000_000L
    private val clock = object : Clock {
        override fun now() = Instant.fromEpochMilliseconds(nowMs)
    }

    private fun newRepo(): MovieRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CatalogDatabase.Schema.create(driver)
        return MovieRepository(CatalogDatabase(driver), NeverMovieSource, clock)
    }

    private fun movie(id: String, cat: String, name: String = "Film $id") = MovieSummary(
        id = id, categoryId = cat, name = name, cover = "http://img/$id.jpg", year = "2024",
        genre = "Action", plot = "Résumé", rating = "7.5", addedAt = 1_700_000_000L, extension = "mkv",
        durationS = 7000, backdrop = "http://img/$id-bg.jpg",
    )

    @Test
    fun replaceCategoriesAndItemsAndRemoveGone() {
        val repo = newRepo()
        repo.replaceCategories(listOf(MovieCategory("1", "Action"), MovieCategory("2", "Comédie")), nowMs)
        repo.replaceCategoryMovies("1", listOf(movie("m1", "1"), movie("m2", "1")), nowMs)
        repo.replaceCategoryMovies("2", listOf(movie("m3", "2")), nowMs)

        assertTrue(repo.areCategoriesFresh())
        assertTrue(repo.isCategoryFresh("1"))
        assertEquals(listOf("m1", "m2"), repo.cachedMoviesOf("1").map { it.id })
        assertEquals(listOf("m3"), repo.cachedMoviesOf("2").map { it.id })

        // La catégorie 2 disparaît : ses films et sa fraîcheur doivent être retirés.
        repo.replaceCategories(listOf(MovieCategory("1", "Action HD")), nowMs + 1000)
        assertEquals(listOf(MovieCategory("1", "Action HD")), repo.cachedCategories())
        assertTrue(repo.cachedMoviesOf("2").isEmpty())
        assertFalse(repo.isCategoryFresh("2"))
        assertEquals(listOf("m1", "m2"), repo.cachedMoviesOf("1").map { it.id })
    }

    @Test
    fun emptyVodResponseKeepsExistingCache() {
        val repo = newRepo()
        repo.replaceCategories(listOf(MovieCategory("1", "Action")), nowMs)
        repo.replaceCategoryMovies("1", listOf(movie("m1", "1")), nowMs)

        assertFailsWith<XtreamError.Unexpected> { repo.replaceCategories(emptyList(), nowMs + 1000) }
        assertFailsWith<XtreamError.Unexpected> { repo.replaceCategoryMovies("1", emptyList(), nowMs + 1000) }

        assertEquals(listOf(MovieCategory("1", "Action")), repo.cachedCategories())
        assertEquals(listOf("m1"), repo.cachedMoviesOf("1").map { it.id })
    }

    @Test
    fun emptyReplaceOnEmptyCacheIsHarmless() {
        val repo = newRepo()
        repo.replaceCategories(emptyList(), nowMs)
        repo.replaceCategoryMovies("1", emptyList(), nowMs)
        assertTrue(repo.cachedCategories().isEmpty())
        assertTrue(repo.cachedMoviesOf("1").isEmpty())
    }

    @Test
    fun wipeMoviesClearsEverything() {
        val repo = newRepo()
        repo.replaceCategories(listOf(MovieCategory("1", "Action")), nowMs)
        repo.replaceCategoryMovies("1", listOf(movie("m1", "1")), nowMs)
        repo.saveInfo(MovieInfo(movie("m1", "1"), MediaTech(1920, 1080, null, "h264", "aac", 2)), fetchedAtMs = nowMs)

        assertEquals("m1", repo.cachedInfo("m1")?.summary?.id)
        assertEquals("Full HD · H.264 · Stéréo AAC", repo.cachedInfo("m1")?.tech?.label())

        repo.wipeMovies()
        assertTrue(repo.cachedCategories().isEmpty())
        assertTrue(repo.cachedMoviesOf("1").isEmpty())
        assertNull(repo.cachedInfo("m1"))
        assertFalse(repo.areCategoriesFresh())
        assertFalse(repo.isCategoryFresh("1"))
    }

    @Test
    fun markFreshAndRemoveHiddenCategories() {
        val repo = newRepo()
        repo.replaceCategories(listOf(MovieCategory("1", "Action"), MovieCategory("2", "Masquée")), nowMs)
        repo.replaceCategoryMovies("1", listOf(movie("m1", "1")), nowMs)
        repo.replaceCategoryMovies("2", listOf(movie("m2", "2")), nowMs)

        nowMs += 80L * 3_600_000 // > 72 h
        assertFalse(repo.areCategoriesFresh())
        assertFalse(repo.isCategoryFresh("1"))

        repo.markCategoriesFresh(nowMs)
        repo.markCategoryFresh("1", nowMs)
        assertTrue(repo.areCategoriesFresh())
        assertTrue(repo.isCategoryFresh("1"))

        repo.removeCategoryItems(setOf("2"))
        assertTrue(repo.cachedMoviesOf("2").isEmpty())
        assertFalse(repo.isCategoryFresh("2"))
    }
}
