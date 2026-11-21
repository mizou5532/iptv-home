package home.iptv.shared.catalog

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import home.iptv.shared.groups.PrecomputedKey
import home.iptv.shared.home.LiveSnapshot
import home.iptv.shared.home.SnapCategory
import home.iptv.shared.home.SnapStream
import home.iptv.shared.xtream.LiveCategory
import home.iptv.shared.xtream.LiveStream
import home.iptv.shared.xtream.XtreamError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class StaticSource(val cats: List<LiveCategory>, val streams: Map<String, List<LiveStream>>) : LiveSource {
    override suspend fun liveCategories() = cats
    override suspend fun liveStreams(categoryId: String) = streams[categoryId].orEmpty()
}

class ReplaceLiveTest {
    private fun newDb(): CatalogDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CatalogDatabase.Schema.create(driver)
        return CatalogDatabase(driver)
    }

    private fun snap(
        categories: List<SnapCategory>,
        streams: List<SnapStream>,
    ) = LiveSnapshot("rev1", "acc", 1L, categories, streams, emptyList())

    private fun ss(id: String, cat: String, pos: Int, key: String?, title: String) =
        SnapStream(id, cat, "Nom $id", "http://i/$id", "epg$id", pos + 10, pos, key, title)

    private val twoCats = listOf(SnapCategory("1", "Sport", 0), SnapCategory("2", "Films", 1))

    @Test
    fun replaceLiveWritesStreamsAndKeys() {
        val r = CatalogRepository(newDb())
        val report = r.replaceLive(
            snap(twoCats, listOf(ss("a", "1", 0, "k-a", "Titre A"), ss("b", "1", 1, null, "Titre B"), ss("c", "2", 0, "k-c", "Titre C"))),
        )
        assertEquals(2, report.categories)
        assertEquals(3, report.streams)
        assertEquals(emptyList(), report.failedCategories)
        assertEquals(listOf(LiveCategory("1", "Sport"), LiveCategory("2", "Films")), report.providerCategories)
        assertEquals(listOf("1", "2"), r.categories().map { it.id })
        assertEquals(listOf("a", "b"), r.streams("1").map { it.id })
        assertEquals(LiveStream("a", "Nom a", "1", "http://i/a", "epga", 10), r.streams("1").first())
        assertEquals(
            mapOf("a" to PrecomputedKey("k-a", "Titre A"), "b" to PrecomputedKey(null, "Titre B"), "c" to PrecomputedKey("k-c", "Titre C")),
            r.liveKeys(),
        )
        // Un second envoi remplace tout (catégorie 2 et chaîne b disparues).
        r.replaceLive(snap(listOf(SnapCategory("1", "Sport", 0)), listOf(ss("a", "1", 0, "k2", "Autre"))))
        assertEquals(listOf("1"), r.categories().map { it.id })
        assertEquals(listOf("a"), r.streams("1").map { it.id })
        assertTrue(r.streams("2").isEmpty())
        assertEquals(mapOf("a" to PrecomputedKey("k2", "Autre")), r.liveKeys())
    }

    @Test
    fun replaceLiveKeepsCacheWhenSnapshotEmpty() {
        val r = CatalogRepository(newDb())
        r.replaceLive(snap(twoCats, listOf(ss("a", "1", 0, "k-a", "Titre A"))))
        assertFailsWith<XtreamError.Unexpected> { r.replaceLive(snap(emptyList(), emptyList())) }
        assertEquals(listOf("1", "2"), r.categories().map { it.id })
        assertEquals(listOf("a"), r.streams("1").map { it.id })
        assertEquals(mapOf("a" to PrecomputedKey("k-a", "Titre A")), r.liveKeys())
    }

    @Test
    fun replaceLiveOnEmptyCacheWithEmptySnapshotIsHarmless() {
        val r = CatalogRepository(newDb())
        val report = r.replaceLive(snap(emptyList(), emptyList()))
        assertEquals(0, report.categories)
        assertEquals(0, report.streams)
    }

    @Test
    fun refreshLiveClearsKeys() = runTest {
        val r = CatalogRepository(newDb())
        r.replaceLive(snap(twoCats, listOf(ss("a", "1", 0, "k-a", "Titre A"))))
        assertEquals(1, r.liveKeys().size)
        val src = StaticSource(
            listOf(LiveCategory("1", "Sport")),
            mapOf("1" to listOf(LiveStream("a", "Nom a", "1", null, null, null))),
        )
        r.refreshLive(src, emptySet())
        assertEquals(listOf("a"), r.streams("1").map { it.id })
        assertTrue(r.liveKeys().isEmpty())
    }

    @Test
    fun migration5KeepsExistingRows() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // Base telle que la télé la laisse en schéma 5 : direct (sans colonnes de clés) + séries, avec des données.
        driver.execute(null, "CREATE TABLE live_category (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, position INTEGER NOT NULL)", 0)
        driver.execute(null, "CREATE TABLE live_stream (id TEXT PRIMARY KEY NOT NULL, category_id TEXT NOT NULL, name TEXT NOT NULL, icon TEXT, epg_channel_id TEXT, number INTEGER, position INTEGER NOT NULL)", 0)
        driver.execute(null, "CREATE INDEX live_stream_category ON live_stream(category_id)", 0)
        CatalogDatabase.Schema.migrate(driver, 1, 5)
        driver.execute(null, "INSERT INTO live_category(id, name, position) VALUES ('1', 'Sport', 0)", 0)
        driver.execute(null, "INSERT INTO live_stream(id, category_id, name, icon, epg_channel_id, number, position) VALUES ('100', '1', 'Chaine Exemple', NULL, NULL, 4, 0), ('101', '1', 'Autre', NULL, NULL, NULL, 1)", 0)

        CatalogDatabase.Schema.migrate(driver, 5, CatalogDatabase.Schema.version)

        assertEquals(6L, CatalogDatabase.Schema.version)
        val r = CatalogRepository(CatalogDatabase(driver))
        assertEquals(listOf("Sport"), r.categories().map { it.name })
        assertEquals(listOf("100", "101"), r.streams("1").map { it.id })
        assertEquals(4, r.streams("1").first().number)
        // Les chaînes d'avant n'ont pas de clés gardées.
        assertTrue(r.liveKeys().isEmpty())
        // Et la base migrée accepte l'écriture avec clés.
        r.replaceLive(snap(listOf(SnapCategory("1", "Sport", 0)), listOf(ss("100", "1", 0, "k", "T"))))
        assertEquals(mapOf("100" to PrecomputedKey("k", "T")), r.liveKeys())
    }
}
