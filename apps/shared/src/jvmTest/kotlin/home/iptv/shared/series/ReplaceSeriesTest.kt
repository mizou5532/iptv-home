package home.iptv.shared.series

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import home.iptv.shared.catalog.CatalogDatabase
import home.iptv.shared.xtream.Episode
import home.iptv.shared.xtream.Season
import home.iptv.shared.xtream.SeriesCategory
import home.iptv.shared.xtream.SeriesInfo
import home.iptv.shared.xtream.SeriesSummary
import home.iptv.shared.xtream.XtreamError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

private object NeverSeriesSource : SeriesSource {
    override suspend fun seriesCategories() = error("ne doit pas appeler le fournisseur")
    override suspend fun series(categoryId: String) = error("ne doit pas appeler le fournisseur")
    override suspend fun seriesInfo(seriesId: String) = error("ne doit pas appeler le fournisseur")
}

class ReplaceSeriesTest {
    private var nowMs = 1_000_000L
    private val clock = object : Clock {
        override fun now() = Instant.fromEpochMilliseconds(nowMs)
    }

    private fun newRepo(): SeriesRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CatalogDatabase.Schema.create(driver)
        return SeriesRepository(CatalogDatabase(driver), NeverSeriesSource, clock)
    }

    private fun series(id: String, cat: String, name: String = "Série $id") = SeriesSummary(
        id = id, categoryId = cat, name = name, cover = "http://img/$id.jpg", year = "2023",
        genre = "Drame", plot = "Histoire", rating = "8.2", backdrop = "http://img/$id-bg.jpg",
    )

    @Test
    fun replaceCategoriesAndItemsAndRemoveGone() {
        val repo = newRepo()
        repo.replaceCategories(listOf(SeriesCategory("1", "Drame"), SeriesCategory("2", "Humour")), nowMs)
        repo.replaceCategorySeries("1", listOf(series("s1", "1"), series("s2", "1")), nowMs)
        repo.replaceCategorySeries("2", listOf(series("s3", "2")), nowMs)

        assertTrue(repo.areCategoriesFresh())
        assertTrue(repo.isCategoryFresh("1"))
        assertEquals(listOf("s1", "s2"), repo.cachedSeriesOf("1").map { it.id })

        repo.replaceCategories(listOf(SeriesCategory("1", "Drames QC")), nowMs + 1000)
        assertEquals(listOf(SeriesCategory("1", "Drames QC")), repo.cachedCategories())
        assertTrue(repo.cachedSeriesOf("2").isEmpty())
        assertFalse(repo.isCategoryFresh("2"))
        assertEquals(listOf("s1", "s2"), repo.cachedSeriesOf("1").map { it.id })
    }

    @Test
    fun emptyVodResponseKeepsExistingCache() {
        val repo = newRepo()
        repo.replaceCategories(listOf(SeriesCategory("1", "Drame")), nowMs)
        repo.replaceCategorySeries("1", listOf(series("s1", "1")), nowMs)

        assertFailsWith<XtreamError.Unexpected> { repo.replaceCategories(emptyList(), nowMs + 1000) }
        assertFailsWith<XtreamError.Unexpected> { repo.replaceCategorySeries("1", emptyList(), nowMs + 1000) }

        assertEquals(listOf(SeriesCategory("1", "Drame")), repo.cachedCategories())
        assertEquals(listOf("s1"), repo.cachedSeriesOf("1").map { it.id })
    }

    @Test
    fun wipeSeriesClearsEverythingIncludingInfo() {
        val repo = newRepo()
        repo.replaceCategories(listOf(SeriesCategory("1", "Drame")), nowMs)
        repo.replaceCategorySeries("1", listOf(series("s1", "1")), nowMs)
        repo.saveInfo(
            SeriesInfo(
                series("s1", "1"),
                listOf(Season(1, "Saison 1", listOf(Episode("e1", 1, 1, "Ép 1", 2500, "mkv", null)))),
            ),
            fetchedAtMs = nowMs,
        )

        assertEquals(1, repo.cachedInfo("s1")?.seasons?.first()?.episodes?.size)

        repo.wipeSeries()
        assertTrue(repo.cachedCategories().isEmpty())
        assertTrue(repo.cachedSeriesOf("1").isEmpty())
        assertNull(repo.cachedInfo("s1"))
        assertFalse(repo.areCategoriesFresh())
        assertFalse(repo.isCategoryFresh("1"))
    }
}
