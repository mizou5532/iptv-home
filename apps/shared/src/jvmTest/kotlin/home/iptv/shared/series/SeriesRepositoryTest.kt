package home.iptv.shared.series

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import home.iptv.shared.catalog.CatalogDatabase
import home.iptv.shared.xtream.Episode
import home.iptv.shared.xtream.Season
import home.iptv.shared.xtream.SeriesCategory
import home.iptv.shared.xtream.SeriesInfo
import home.iptv.shared.xtream.SeriesSummary
import home.iptv.shared.xtream.XtreamError
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

private class TestClock(var now: Instant = Instant.fromEpochSeconds(1_800_000_000)) : Clock {
    override fun now(): Instant = now
}

private class FakeSeries : SeriesSource {
    var categories = listOf(SeriesCategory("1", "Drames"), SeriesCategory("2", "Comedies"))
    var items = mapOf(
        "1" to listOf(summary("10", "1", "Alpha"), summary("11", "1", "Beta")),
        "2" to listOf(summary("20", "2", "Gamma")),
    )
    var info: SeriesInfo? = infoOf("10", "Alpha")
    var error: Exception? = null
    val calls = mutableListOf<String>()

    override suspend fun seriesCategories(): List<SeriesCategory> {
        calls += "cats"; error?.let { throw it }; return categories
    }

    override suspend fun series(categoryId: String): List<SeriesSummary> {
        calls += "cat:$categoryId"; error?.let { throw it }; return items[categoryId].orEmpty()
    }

    override suspend fun seriesInfo(seriesId: String): SeriesInfo? {
        calls += "info:$seriesId"; error?.let { throw it }; return info
    }
}

private fun summary(id: String, cat: String, name: String) =
    SeriesSummary(id, cat, name, "http://img.test/$id.jpg", "2020", "Drame", "Resume", "7.5")

private fun infoOf(id: String, name: String) = SeriesInfo(
    summary(id, "1", name),
    listOf(
        Season(1, "Saison 1", listOf(Episode("900", 1, 1, "Pilote", 2580, "mp4", null), Episode("901", 1, 2, "Suite", null, "mkv", "http://img.test/e.jpg"))),
        Season(2, "Saison 2", emptyList()),
    ),
)

class SeriesRepositoryTest {
    private val clock = TestClock()
    private val source = FakeSeries()

    private fun repo(): SeriesRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CatalogDatabase.Schema.create(driver)
        return SeriesRepository(CatalogDatabase(driver), source, clock)
    }

    @Test
    fun categoriesFreshForOneDay() = runTest {
        val r = repo()
        assertEquals(listOf("1", "2"), r.categories(emptySet()).map { it.id })
        clock.now += 23.hours
        r.categories(emptySet())
        assertEquals(listOf("cats"), source.calls)
        clock.now += 2.hours
        r.categories(emptySet())
        assertEquals(listOf("cats", "cats"), source.calls)
    }

    @Test
    fun hiddenCategoriesAreFilteredOut() = runTest {
        val r = repo()
        assertEquals(listOf("2"), r.categories(setOf("1")).map { it.id })
        // Le cache contient tout : la même liste revient avec un autre masquage, sans nouvelle demande.
        assertEquals(listOf("1"), r.categories(setOf("2")).map { it.id })
        assertEquals(1, source.calls.size)
    }

    @Test
    fun seriesOfFreshForOneDayAndKeepsOrderAndFields() = runTest {
        val r = repo()
        val first = r.seriesOf("1")
        assertEquals(listOf("Alpha", "Beta"), first.map { it.name })
        assertEquals(source.items["1"], first)
        clock.now += 23.hours
        r.seriesOf("1")
        assertEquals(listOf("cat:1"), source.calls)
        clock.now += 2.hours
        r.seriesOf("1")
        assertEquals(listOf("cat:1", "cat:1"), source.calls)
    }

    @Test
    fun seriesOfIsCachedPerCategory() = runTest {
        val r = repo()
        r.seriesOf("1")
        assertEquals(listOf("Gamma"), r.seriesOf("2").map { it.name })
        assertEquals(listOf("cat:1", "cat:2"), source.calls)
    }

    @Test
    fun hiddenCategoryIsNeverRequested() = runTest {
        val r = repo()
        assertEquals(emptyList(), r.seriesOf("1", hidden = setOf("1")))
        assertEquals(emptyList(), source.calls)
    }

    @Test
    fun hiddenCategoryIsNotServedFromCacheEither() = runTest {
        val r = repo()
        r.seriesOf("1")
        assertEquals(emptyList(), r.seriesOf("1", hidden = setOf("1")))
    }

    @Test
    fun infoFreshForSixHoursAndRoundTrips() = runTest {
        val r = repo()
        val got = r.info("10")
        assertEquals(source.info, got)
        clock.now += 5.hours
        assertEquals(source.info, r.info("10"))
        assertEquals(listOf("info:10"), source.calls)
        clock.now += 2.hours
        r.info("10")
        assertEquals(listOf("info:10", "info:10"), source.calls)
    }

    @Test
    fun staleInfoReplacedByNewFetch() = runTest {
        val r = repo()
        r.info("10")
        clock.now += 7.hours
        source.info = SeriesInfo(summary("10", "1", "Alpha 2"), listOf(Season(1, "S1", emptyList())))
        assertEquals(source.info, r.info("10"))
    }

    @Test
    fun staleCacheServedWhenOffline() = runTest {
        val r = repo()
        val cats = r.categories(emptySet())
        val items = r.seriesOf("1")
        val info = r.info("10")
        clock.now += 100.hours
        source.error = XtreamError.Unreachable
        assertEquals(cats, r.categories(emptySet()))
        assertEquals(items, r.seriesOf("1"))
        assertEquals(info, r.info("10"))
    }

    @Test
    fun errorWithoutCachePropagates() = runTest {
        val r = repo()
        source.error = XtreamError.Unreachable
        assertFailsWith<XtreamError.Unreachable> { r.categories(emptySet()) }
        assertFailsWith<XtreamError.Unreachable> { r.seriesOf("1") }
        assertFailsWith<XtreamError.Unreachable> { r.info("10") }
    }

    @Test
    fun cancellationIsRethrownNotSwallowedByStaleCache() = runTest {
        val r = repo()
        r.categories(emptySet())
        r.seriesOf("1")
        r.info("10")
        clock.now += 100.hours
        source.error = CancellationException("annule")
        assertFailsWith<CancellationException> { r.categories(emptySet()) }
        assertFailsWith<CancellationException> { r.seriesOf("1") }
        assertFailsWith<CancellationException> { r.info("10") }
    }

    @Test
    fun unknownSeriesGivesNull() = runTest {
        val r = repo()
        source.info = null
        assertNull(r.info("404"))
    }

    @Test
    fun failedRefreshKeepsPreviousCacheIntact() = runTest {
        val r = repo()
        r.seriesOf("1")
        clock.now += 30.hours
        source.items = mapOf("1" to listOf(summary("12", "1", "Delta")))
        assertEquals(listOf("Delta"), r.seriesOf("1").map { it.name })
    }

    @Test
    fun existingVersion1CacheUpgradesToSeriesTables() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // Base de la version 0.3.0 : seulement le cache du direct, avec une ligne.
        driver.execute(null, "CREATE TABLE live_category (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, position INTEGER NOT NULL)", 0)
        driver.execute(null, "CREATE TABLE live_stream (id TEXT PRIMARY KEY NOT NULL, category_id TEXT NOT NULL, name TEXT NOT NULL, icon TEXT, epg_channel_id TEXT, number INTEGER, position INTEGER NOT NULL)", 0)
        driver.execute(null, "INSERT INTO live_category(id, name, position) VALUES ('1', 'Sport', 0)", 0)
        CatalogDatabase.Schema.migrate(driver, 1, CatalogDatabase.Schema.version)
        assertEquals(2L, CatalogDatabase.Schema.version)
        val db = CatalogDatabase(driver)
        assertEquals(1, db.catalogQueries.selectCategories().executeAsList().size)
        val r = SeriesRepository(db, source, clock)
        assertEquals(2, r.categories(emptySet()).size)
    }
}
