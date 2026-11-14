package home.iptv.shared.catalog

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import home.iptv.shared.xtream.LiveCategory
import home.iptv.shared.xtream.LiveStream
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.measureTime

private class FakeSource(
    var categories: List<LiveCategory>,
    var streams: Map<String, List<LiveStream>>,
    var failing: Set<String> = emptySet(),
) : LiveSource {
    val streamCalls = mutableListOf<String>()
    var inFlight = 0
    var maxInFlight = 0

    override suspend fun liveCategories() = categories

    override suspend fun liveStreams(categoryId: String): List<LiveStream> {
        inFlight++
        maxInFlight = maxOf(maxInFlight, inFlight)
        try {
            streamCalls += categoryId
            if (categoryId in failing) throw RuntimeException("expire")
            return streams[categoryId].orEmpty()
        } finally {
            inFlight--
        }
    }
}

private fun stream(id: String, cat: String, name: String = "Chaine $id", n: Int? = null) =
    LiveStream(id, name, cat, null, null, n)

class CatalogRepositoryTest {
    private fun repo(): CatalogRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CatalogDatabase.Schema.create(driver)
        return CatalogRepository(CatalogDatabase(driver))
    }

    private val cats = listOf(LiveCategory("1", "Sport"), LiveCategory("2", "Films"), LiveCategory("3", "Adulte"))

    @Test
    fun premier_rafraichissement_ne_demande_jamais_la_categorie_masquee() = runTest {
        val r = repo()
        val src = FakeSource(
            cats,
            mapOf("1" to listOf(stream("a", "1"), stream("b", "1")), "2" to listOf(stream("c", "2")), "3" to listOf(stream("x", "3"))),
        )
        val report = r.refreshLive(src, setOf("3"))
        assertEquals(listOf("1", "2"), src.streamCalls)
        assertEquals(1, src.maxInFlight)
        assertEquals(listOf("1", "2"), r.categories().map { it.id })
        assertEquals(RefreshReport(2, 3, emptyList()), report)
        assertEquals(listOf("a", "b"), r.streams("1").map { it.id })
        assertTrue(r.streams("3").isEmpty())
    }

    @Test
    fun categorie_en_erreur_garde_ses_chaines_en_cache() = runTest {
        val r = repo()
        val src = FakeSource(
            cats.take(2),
            mapOf("1" to listOf(stream("a", "1"), stream("b", "1")), "2" to listOf(stream("c", "2"))),
        )
        r.refreshLive(src, emptySet())
        src.failing = setOf("1")
        src.streams = mapOf("2" to listOf(stream("d", "2")))
        val report = r.refreshLive(src, emptySet())
        assertEquals(listOf("1"), report.failedCategories)
        assertEquals(listOf("a", "b"), r.streams("1").map { it.id })
        assertEquals(listOf("d"), r.streams("2").map { it.id })
        assertEquals(listOf("1", "2"), r.categories().map { it.id })
    }

    @Test
    fun categorie_disparue_ou_masquee_est_retiree_avec_ses_chaines() = runTest {
        val r = repo()
        val src = FakeSource(
            cats,
            mapOf("1" to listOf(stream("a", "1")), "2" to listOf(stream("c", "2")), "3" to listOf(stream("x", "3"))),
        )
        r.refreshLive(src, emptySet())
        assertEquals(3, r.categories().size)
        // "1" disparait chez le fournisseur, "3" est masquee pour tout le monde.
        src.categories = listOf(LiveCategory("2", "Films"), LiveCategory("3", "Adulte"))
        val calls = src.streamCalls.size
        r.refreshLive(src, setOf("3"))
        assertEquals(listOf("2"), src.streamCalls.drop(calls))
        assertEquals(listOf("2"), r.categories().map { it.id })
        assertTrue(r.streams("1").isEmpty())
        assertTrue(r.streams("3").isEmpty())
        assertFalse(r.streams("2").isEmpty())
    }

    @Test
    fun vingt_mille_chaines_sont_toutes_presentes_dans_l_ordre() = runTest {
        val r = repo()
        val big = (0 until 20_000).map { stream("s$it", "1", "Chaine $it", it) }
        val src = FakeSource(listOf(LiveCategory("1", "Tout")), mapOf("1" to big))
        val elapsed = measureTime { r.refreshLive(src, emptySet()) }
        println("20k insert: $elapsed")
        val out = r.streams("1")
        assertEquals(20_000, out.size)
        assertEquals(big.map { it.id }, out.map { it.id })
        assertEquals(19_999, out.last().number)
        assertTrue(elapsed.inWholeSeconds < 5, "trop lent: $elapsed")
    }
}
