package home.iptv.tv.search

import home.iptv.shared.catalog.CatalogTitleSource
import home.iptv.shared.catalog.CatalogTitlesPublisher
import home.iptv.shared.catalog.CatalogTitlesState
import home.iptv.shared.search.FillProgress
import home.iptv.shared.search.SearchSection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogTitlesPublisherTest {
    private class MemState : CatalogTitlesState {
        val at = HashMap<String, Long>()
        override fun publishedAt(household: String, kind: String) = at["$household:$kind"] ?: 0L
        override fun savePublished(household: String, kind: String, at: Long) { this.at["$household:$kind"] = at }
    }
    private data class Call(val kind: String, val gen: Long, val size: Int, val done: Boolean)

    @Test fun publie_quand_le_remplissage_est_complet_par_lots_puis_attend_le_lendemain() = runBlocking {
        val calls = mutableListOf<Call>()
        var clock = 100_000_000L
        val state = MemState()
        val series = (1..4_500).map { CatalogTitleSource("s$it", "Série $it", null, "c", null, null) }
        val pub = CatalogTitlesPublisher(
            MutableStateFlow(emptyMap()), { series }, { listOf(CatalogTitleSource("m1", "Film", null, "v", null, null)) }, { null }, { "h1" },
            { _, kind, gen, rows, done -> calls += Call(kind, gen, rows.size, done) }, state, CoroutineScope(Dispatchers.Unconfined), now = { clock },
        )
        // Pas complet : rien.
        assertEquals(emptyList(), pub.runDue(mapOf(SearchSection.SERIES to FillProgress(3, 10), SearchSection.MOVIES to FillProgress(0, 0))))
        assertTrue(calls.isEmpty())
        // Séries complètes : 3 lots, le dernier marqué fini, même génération.
        assertEquals(listOf("series"), pub.runDue(mapOf(SearchSection.SERIES to FillProgress(10, 10))))
        assertEquals(listOf(Call("series", clock, 2000, false), Call("series", clock, 2000, false), Call("series", clock, 500, true)), calls)
        // Une heure plus tard : rien ; le lendemain : de nouveau.
        clock += 3_600_000
        assertEquals(emptyList(), pub.runDue(mapOf(SearchSection.SERIES to FillProgress(10, 10))))
        clock += 20 * 3_600_000
        assertEquals(listOf("series", "movie"), pub.runDue(mapOf(SearchSection.SERIES to FillProgress(10, 10), SearchSection.MOVIES to FillProgress(2, 2))))
    }

    @Test fun un_echec_sera_reessaye() = runBlocking {
        val state = MemState()
        var fail = true
        val pub = CatalogTitlesPublisher(
            MutableStateFlow(emptyMap()), { listOf(CatalogTitleSource("s1", "Série", null, "c", null, null)) }, { emptyList() }, { null }, { "h1" },
            { _, _, _, _, _ -> if (fail) throw java.io.IOException("hors ligne") }, state, CoroutineScope(Dispatchers.Unconfined), now = { 100_000_000_000L },
        )
        val p = mapOf(SearchSection.SERIES to FillProgress(1, 1))
        assertEquals(emptyList(), pub.runDue(p))
        fail = false
        assertEquals(listOf("series"), pub.runDue(p))
    }
}
