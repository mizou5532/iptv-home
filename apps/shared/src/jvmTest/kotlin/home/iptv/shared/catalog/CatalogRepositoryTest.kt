package home.iptv.shared.catalog

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import home.iptv.shared.xtream.LiveCategory
import home.iptv.shared.xtream.LiveStream
import home.iptv.shared.xtream.XtreamError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.measureTime

private class FakeSource(
    var categories: List<LiveCategory>,
    var streams: Map<String, List<LiveStream>>,
    var failing: Set<String> = emptySet(),
    var cancelling: Set<String> = emptySet(),
    var categoriesError: Exception? = null,
) : LiveSource {
    val streamCalls = mutableListOf<String>()
    var inFlight = 0
    var maxInFlight = 0

    override suspend fun liveCategories(): List<LiveCategory> {
        categoriesError?.let { throw it }
        return categories
    }

    override suspend fun liveStreams(categoryId: String): List<LiveStream> {
        inFlight++
        maxInFlight = maxOf(maxInFlight, inFlight)
        try {
            streamCalls += categoryId
            if (categoryId in cancelling) throw CancellationException("annule")
            if (categoryId in failing) throw RuntimeException("expire")
            return streams[categoryId].orEmpty()
        } finally {
            inFlight--
        }
    }
}

/** Pilote qui échoue à la N-ième insertion de chaîne une fois armé (test d'atomicité). */
private class FailingDriver(private val inner: SqlDriver) : SqlDriver by inner {
    var failAfterInserts = -1
    private var inserts = 0

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        if (failAfterInserts >= 0 && sql.contains("INTO live_stream") && inserts++ >= failAfterInserts) {
            throw IllegalStateException("disque plein")
        }
        return inner.execute(identifier, sql, parameters, binders)
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
    fun progression_apres_chaque_categorie_sans_montrer_une_categorie_jamais_telechargee() = runTest {
        val r = repo()
        val src = FakeSource(
            cats,
            mapOf("1" to listOf(stream("a", "1")), "3" to listOf(stream("x", "3"))),
            failing = setOf("2"),
        )
        val seen = mutableListOf<Triple<Int, Int, List<String>>>()
        r.refreshLive(src, emptySet()) { done, total -> seen += Triple(done, total, r.categories().map { it.id }) }
        // La categorie 2 echoue sans cache : jamais visible, meme pendant la progression.
        assertEquals(
            listOf(Triple(1, 3, listOf("1")), Triple(2, 3, listOf("1")), Triple(3, 3, listOf("1", "3"))),
            seen,
        )
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
        assertEquals(3, report.streams) // a, b (cache) + d
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
        assertTrue(elapsed.inWholeSeconds < 10, "trop lent: $elapsed")
    }

    @Test
    fun annulation_est_relancee_sans_toucher_au_cache_ni_aux_categories_suivantes() = runTest {
        val r = repo()
        val src = FakeSource(
            cats,
            mapOf("1" to listOf(stream("a", "1")), "2" to listOf(stream("b", "2"), stream("c", "2")), "3" to listOf(stream("x", "3"))),
        )
        r.refreshLive(src, emptySet())
        src.cancelling = setOf("2")
        src.streams = mapOf("1" to listOf(stream("a2", "1")), "3" to listOf(stream("x2", "3")))
        val calls = src.streamCalls.size
        assertFailsWith<CancellationException> { r.refreshLive(src, emptySet()) }
        assertEquals(listOf("1", "2"), src.streamCalls.drop(calls)) // "3" jamais demandee
        assertEquals(listOf("b", "c"), r.streams("2").map { it.id })
        assertEquals(listOf("x"), r.streams("3").map { it.id })
    }

    @Test
    fun ecriture_interrompue_en_cours_de_categorie_laisse_les_anciennes_chaines() = runTest {
        val driver = FailingDriver(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        CatalogDatabase.Schema.create(driver)
        val r = CatalogRepository(CatalogDatabase(driver))
        val old = (0 until 5).map { stream("o$it", "1") }
        val src = FakeSource(listOf(LiveCategory("1", "Sport")), mapOf("1" to old))
        r.refreshLive(src, emptySet())
        // Comportement actuel : l'erreur d'ecriture est propagee (pas rangee dans failedCategories).
        src.streams = mapOf("1" to (0 until 10).map { stream("n$it", "1") })
        driver.failAfterInserts = 3 // apres le delete et 3 insertions
        assertFailsWith<IllegalStateException> { r.refreshLive(src, emptySet()) }
        driver.failAfterInserts = -1
        assertEquals(old.map { it.id }, r.streams("1").map { it.id })
    }

    @Test
    fun liste_des_categories_en_erreur_laisse_le_cache_intact_et_propage() = runTest {
        val r = repo()
        val src = FakeSource(cats.take(2), mapOf("1" to listOf(stream("a", "1")), "2" to listOf(stream("c", "2"))))
        r.refreshLive(src, emptySet())
        src.categoriesError = XtreamError.Unreachable
        val calls = src.streamCalls.size
        assertFailsWith<XtreamError.Unreachable> { r.refreshLive(src, emptySet()) }
        assertEquals(calls, src.streamCalls.size)
        assertEquals(listOf("1", "2"), r.categories().map { it.id })
        assertEquals(listOf("a"), r.streams("1").map { it.id })
        assertEquals(listOf("c"), r.streams("2").map { it.id })
    }
}
