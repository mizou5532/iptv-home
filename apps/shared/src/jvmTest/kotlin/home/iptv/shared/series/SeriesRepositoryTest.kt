package home.iptv.shared.series

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import home.iptv.shared.catalog.CatalogDatabase
import home.iptv.shared.xtream.Episode
import home.iptv.shared.xtream.Season
import home.iptv.shared.xtream.SeriesCategory
import home.iptv.shared.xtream.SeriesInfo
import home.iptv.shared.xtream.SeriesSummary
import home.iptv.shared.xtream.XtreamError
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals
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

/** Pilote qui échoue à l'insertion d'une série une fois armé (test d'atomicité de l'enregistrement). */
private class StoreFailingDriver(private val inner: SqlDriver) : SqlDriver by inner {
    var armed = false

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        if (armed && sql.contains("INTO series_item")) throw IllegalStateException("disque plein")
        return inner.execute(identifier, sql, parameters, binders)
    }
}

class SeriesRepositoryTest {
    private val clock = TestClock()
    private val source = FakeSeries()

    private fun repo(): SeriesRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CatalogDatabase.Schema.create(driver)
        return SeriesRepository(CatalogDatabase(driver), source, clock)
    }

    @Test
    fun backdropIsCachedAndInheritedByInfo() = runTest {
        source.items = mapOf("1" to listOf(summary("10", "1", "Alpha").copy(backdrop = "http://img.test/fond10.jpg")))
        val r = repo()
        assertEquals("http://img.test/fond10.jpg", r.seriesOf("1", emptySet()).single().backdrop)
        // La fiche du fournisseur sans image de fond reprend celle de la liste.
        assertEquals("http://img.test/fond10.jpg", r.info("10", "1")!!.summary.backdrop)
        source.info = infoOf("10", "Alpha").let { it.copy(summary = it.summary.copy(backdrop = "http://img.test/autre.jpg")) }
        clock.now += 30.hours
        assertEquals("http://img.test/autre.jpg", r.info("10", "1")!!.summary.backdrop)
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
        val first = r.seriesOf("1", emptySet())
        assertEquals(listOf("Alpha", "Beta"), first.map { it.name })
        assertEquals(source.items["1"], first)
        clock.now += 23.hours
        r.seriesOf("1", emptySet())
        assertEquals(listOf("cat:1"), source.calls)
        clock.now += 2.hours
        r.seriesOf("1", emptySet())
        assertEquals(listOf("cat:1", "cat:1"), source.calls)
    }

    @Test
    fun seriesOfIsCachedPerCategory() = runTest {
        val r = repo()
        r.seriesOf("1", emptySet())
        assertEquals(listOf("Gamma"), r.seriesOf("2", emptySet()).map { it.name })
        assertEquals(listOf("cat:1", "cat:2"), source.calls)
    }

    @Test
    fun hiddenCategoryIsNeverRequested() = runTest {
        val r = repo()
        assertEquals(emptyList(), r.seriesOf("1", setOf("1")))
        assertEquals(emptyList(), source.calls)
    }

    @Test
    fun blankCategoryIsNeverRequestedNorListed() = runTest {
        // get_series sans catégorie renvoie TOUT le catalogue : jamais demandé, même si un ancien cache contient "".
        source.categories = listOf(SeriesCategory("", "Vide"), SeriesCategory(" ", "Espace"), SeriesCategory("1", "Drames"))
        val r = repo()
        assertEquals(listOf("1"), r.categories(emptySet()).map { it.id })
        assertEquals(emptyList(), r.seriesOf("", emptySet()))
        assertEquals(emptyList(), r.seriesOf("  ", emptySet()))
        assertEquals(listOf("cats"), source.calls)
    }

    @Test
    fun manyCategoriesAndLargeCategoryKeptEntirelyInProviderOrder() = runTest {
        // Gros fournisseur : des centaines de catégories, des milliers de séries dans une seule : rien n'est tronqué.
        source.categories = (1..400).map { SeriesCategory("c$it", "Categorie $it") }
        source.items = mapOf("c7" to (1..5000).map { summary("s$it", "c7", "Serie $it") })
        val r = repo()
        assertEquals((1..400).map { "c$it" }, r.categories(emptySet()).map { it.id })
        val all = r.seriesOf("c7", emptySet())
        assertEquals(5000, all.size)
        assertEquals((1..5000).map { "s$it" }, all.map { it.id })
        // Relu depuis le cache (frais) : même contenu, aucune nouvelle demande.
        assertEquals(5000, r.seriesOf("c7", emptySet()).size)
        assertEquals(listOf("cats", "cat:c7"), source.calls)
    }

    @Test
    fun hiddenCategoryIsNotServedFromCacheEither() = runTest {
        val r = repo()
        r.seriesOf("1", emptySet())
        assertEquals(emptyList(), r.seriesOf("1", setOf("1")))
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
        val items = r.seriesOf("1", emptySet())
        val info = r.info("10")
        clock.now += 100.hours
        source.error = XtreamError.Unreachable
        assertEquals(cats, r.categories(emptySet()))
        assertEquals(items, r.seriesOf("1", emptySet()))
        assertEquals(info, r.info("10"))
    }

    @Test
    fun errorWithoutCachePropagates() = runTest {
        val r = repo()
        source.error = XtreamError.Unreachable
        assertFailsWith<XtreamError.Unreachable> { r.categories(emptySet()) }
        assertFailsWith<XtreamError.Unreachable> { r.seriesOf("1", emptySet()) }
        assertFailsWith<XtreamError.Unreachable> { r.info("10") }
    }

    @Test
    fun cancellationIsRethrownNotSwallowedByStaleCache() = runTest {
        val r = repo()
        r.categories(emptySet())
        r.seriesOf("1", emptySet())
        r.info("10")
        clock.now += 100.hours
        source.error = CancellationException("annule")
        assertFailsWith<CancellationException> { r.categories(emptySet()) }
        assertFailsWith<CancellationException> { r.seriesOf("1", emptySet()) }
        assertFailsWith<CancellationException> { r.info("10") }
    }

    @Test
    fun unknownSeriesGivesNull() = runTest {
        val r = repo()
        source.info = null
        assertNull(r.info("404"))
    }

    @Test
    fun successfulRefreshReplacesPreviousItems() = runTest {
        val r = repo()
        r.seriesOf("1", emptySet())
        clock.now += 30.hours
        source.items = mapOf("1" to listOf(summary("12", "1", "Delta")))
        assertEquals(listOf("Delta"), r.seriesOf("1", emptySet()).map { it.name })
    }

    @Test
    fun existingVersion1CacheUpgradesToSeriesTables() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // Base de la version 0.3.0 : seulement le cache du direct, avec une ligne.
        driver.execute(null, "CREATE TABLE live_category (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, position INTEGER NOT NULL)", 0)
        driver.execute(null, "CREATE TABLE live_stream (id TEXT PRIMARY KEY NOT NULL, category_id TEXT NOT NULL, name TEXT NOT NULL, icon TEXT, epg_channel_id TEXT, number INTEGER, position INTEGER NOT NULL)", 0)
        driver.execute(null, "INSERT INTO live_category(id, name, position) VALUES ('1', 'Sport', 0)", 0)
        CatalogDatabase.Schema.migrate(driver, 1, CatalogDatabase.Schema.version)
        // Version 4 depuis les images de fond (3.sqm) : la base 0.3.0 traverse les trois migrations.
        assertEquals(5L, CatalogDatabase.Schema.version)
        val db = CatalogDatabase(driver)
        assertEquals(1, db.catalogQueries.selectCategories().executeAsList().size)
        val r = SeriesRepository(db, source, clock)
        assertEquals(2, r.categories(emptySet()).size)
    }

    private fun dbAndRepo(): Pair<CatalogDatabase, SeriesRepository> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CatalogDatabase.Schema.create(driver)
        val db = CatalogDatabase(driver)
        return db to SeriesRepository(db, source, clock)
    }

    @Test
    fun emptyCategoriesAnswerKeepsGoodCache() = runTest {
        val (db, r) = dbAndRepo()
        val good = r.categories(emptySet())
        val stamp = db.seriesQueries.selectFetchedAt("cats").executeAsOne()
        clock.now += 30.hours
        source.categories = emptyList()
        assertEquals(good, r.categories(emptySet()))
        assertEquals(stamp, db.seriesQueries.selectFetchedAt("cats").executeAsOne())
    }

    @Test
    fun emptySeriesAnswerKeepsGoodCache() = runTest {
        val (db, r) = dbAndRepo()
        val good = r.seriesOf("1", emptySet())
        val stamp = db.seriesQueries.selectFetchedAt("cat:1").executeAsOne()
        clock.now += 30.hours
        source.items = mapOf("1" to emptyList())
        assertEquals(good, r.seriesOf("1", emptySet()))
        assertEquals(stamp, db.seriesQueries.selectFetchedAt("cat:1").executeAsOne())
    }

    @Test
    fun nullInfoAnswerKeepsGoodCache() = runTest {
        val (db, r) = dbAndRepo()
        val good = r.info("10")
        val stamp = db.seriesQueries.selectFetchedAt("info:10").executeAsOne()
        clock.now += 7.hours
        source.info = null
        assertEquals(good, r.info("10"))
        assertEquals(stamp, db.seriesQueries.selectFetchedAt("info:10").executeAsOne())
    }

    @Test
    fun emptyAnswerIsAcceptedWhenNothingWasCached() = runTest {
        val r = repo()
        source.categories = emptyList()
        assertEquals(emptyList(), r.categories(emptySet()))
        source.items = mapOf("1" to emptyList())
        assertEquals(emptyList(), r.seriesOf("1", emptySet()))
    }

    private fun withoutCategory(i: SeriesInfo) = i.copy(summary = i.summary.copy(categoryId = ""))

    @Test
    fun infoFallsBackToCategoryOfCachedSeriesItem() = runTest {
        val r = repo()
        r.seriesOf("1", emptySet())
        source.info = withoutCategory(infoOf("10", "Alpha"))
        assertEquals("1", r.info("10")?.summary?.categoryId)
    }

    @Test
    fun infoUsesCategoryGivenByCaller() = runTest {
        val r = repo()
        source.info = withoutCategory(infoOf("10", "Alpha"))
        assertEquals("2", r.info("10", categoryId = "2")?.summary?.categoryId)
    }

    @Test
    fun infoNeverOverwritesKnownCategoryWithEmpty() = runTest {
        val r = repo()
        source.info = withoutCategory(infoOf("10", "Alpha"))
        r.info("10", categoryId = "2")
        clock.now += 7.hours
        assertEquals("2", r.info("10")?.summary?.categoryId)
    }

    @Test
    fun failingStoreKeepsOldRowsAndOldStamp() = runTest {
        val inner = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CatalogDatabase.Schema.create(inner)
        val driver = StoreFailingDriver(inner)
        val db = CatalogDatabase(driver)
        val r = SeriesRepository(db, source, clock)
        val old = r.seriesOf("1", emptySet())
        val stamp = db.seriesQueries.selectFetchedAt("cat:1").executeAsOne()
        clock.now += 30.hours
        source.items = mapOf("1" to listOf(summary("12", "1", "Delta")))
        driver.armed = true
        assertFailsWith<IllegalStateException> { r.seriesOf("1", emptySet()) }
        driver.armed = false
        assertEquals(stamp, db.seriesQueries.selectFetchedAt("cat:1").executeAsOne())
        val rows = db.seriesQueries.selectSeriesItems("1") { id, cat, name, cover, year, genre, plot, rating, _ ->
            SeriesSummary(id, cat, name, cover, year, genre, plot, rating)
        }.executeAsList()
        assertEquals(old, rows)
    }

    @Test
    fun detailCacheKeepsAtMost300NewestSeries() = runTest {
        val (db, r) = dbAndRepo()
        for (i in 1..305) {
            source.info = infoOf("s$i", "Serie $i")
            r.info("s$i")
            clock.now += 1.hours
        }
        val kept = db.seriesQueries.countDetails().executeAsOne()
        assertEquals(300L, kept)
        assertNull(db.seriesQueries.selectFetchedAt("info:s1").executeAsOneOrNull())
        assertNotEquals(null, db.seriesQueries.selectFetchedAt("info:s305").executeAsOneOrNull())
        assertEquals(0L, db.seriesQueries.countEpisodesOf("s1").executeAsOne())
    }

    @Test
    fun migratedSchemaMatchesFreshSchema() {
        fun dump(d: SqlDriver): List<String> {
            val out = mutableListOf<String>()
            d.executeQuery(null, "SELECT type, name, tbl_name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name", { c ->
                while (c.next().value) {
                    val sql = (c.getString(3) ?: "").replace(Regex("\\s*([(),])\\s*"), "$1").replace(Regex("\\s+"), " ").replace(" IF NOT EXISTS", "").trim()
                    out += "${c.getString(0)}|${c.getString(1)}|${c.getString(2)}|$sql"
                }
                QueryResult.Unit
            }, 0)
            return out
        }
        val fresh = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CatalogDatabase.Schema.create(fresh)
        val old = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // DDL réel de Catalog.sq en version 1.
        old.execute(null, "CREATE TABLE live_category (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, position INTEGER NOT NULL)", 0)
        old.execute(null, "CREATE TABLE live_stream (id TEXT PRIMARY KEY NOT NULL, category_id TEXT NOT NULL, name TEXT NOT NULL, icon TEXT, epg_channel_id TEXT, number INTEGER, position INTEGER NOT NULL)", 0)
        old.execute(null, "CREATE INDEX live_stream_category ON live_stream(category_id)", 0)
        CatalogDatabase.Schema.migrate(old, 1, CatalogDatabase.Schema.version)
        val a = dump(fresh)
        assertTrue(a.any { it.contains("live_stream_category") })
        assertTrue(a.any { it.contains("series_episode") })
        assertEquals(a, dump(old))
    }

    @Test
    fun cachedCategoryReadsTheCacheWithoutAnyNetworkCall() = runTest {
        val r = repo()
        assertNull(r.cachedCategory("10"))
        r.seriesOf("1", emptySet())
        source.calls.clear()
        assertEquals("1", r.cachedCategory("10"))
        assertNull(r.cachedCategory("999"))
        // Une fiche connue sans la liste : la catégorie de la fiche sert.
        val r2 = repo()
        r2.info("10", "7")
        source.calls.clear()
        assertEquals("1", r2.cachedCategory("10"))
        assertTrue(source.calls.isEmpty())
    }

    @Test
    fun itemCategoryLookupByIdHasAnIndex() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CatalogDatabase.Schema.create(driver)
        val names = mutableListOf<String>()
        driver.executeQuery(null, "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'series_item'", { c ->
            while (c.next().value) names += c.getString(0)!!
            QueryResult.Unit
        }, 0)
        assertTrue("series_item_id" in names, "index manquant : $names")
    }
}
