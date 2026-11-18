package home.iptv.tv.progress

import home.iptv.shared.progress.ProgressRow
import home.iptv.shared.search.SearchItem
import home.iptv.shared.xtream.Episode
import home.iptv.shared.xtream.Season
import home.iptv.shared.xtream.SeriesInfo
import home.iptv.shared.xtream.SeriesSummary
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class ProgressRemapperTest {
    private val at = Instant.parse("2026-11-01T00:00:00Z")

    @Test fun nouveau_fournisseur_films_et_episodes_recopies() = runTest {
        val copied = mutableMapOf<String, List<ProgressRow>>()
        val rows = listOf(
            ProgressRow(itemId = "old-m", itemType = "movie", positionS = 600, durationS = 7200, clientUpdatedAt = at, movieName = "Training Day (2001)"),
            ProgressRow(itemId = "old-e", itemType = "episode", seriesId = "old-s", season = 1, episode = 2, positionS = 300, durationS = 2600, clientUpdatedAt = at, seriesName = "Breaking Bad"),
        )
        val info = SeriesInfo(SeriesSummary("new-s", "cat", "Breaking Bad", null, null, null, null, null), listOf(Season(1, "Saison 1", listOf(Episode("new-e", 1, 2, "Ep", null, "mkv", null)))))
        val r = ProgressRemapper({ listOf("p1") }, { rows }, { listOf(SearchItem("new-m", "FR - Training Day (2001)", setOf("c"))) },
            { listOf(SearchItem("new-s", "FR - Breaking Bad", setOf("cat"))) }, { _, _ -> info }, { p, list -> copied[p] = (copied[p] ?: emptyList()) + list; list.size }).run()
        assertEquals(RemapResult(1, 1), r)
        assertEquals(listOf("new-m", "new-e"), copied["p1"]!!.map { it.itemId })
    }

    @Test fun declencheur_seulement_apres_un_vrai_changement_de_compte() {
        val kv = mutableMapOf<String, String>()
        val t = RemapTrigger({ kv[it] }, { k, v -> if (v == null) kv.remove(k) else kv[k] = v })
        t.accountSeen("A"); assertFalse(t.pending)
        t.accountSeen("A"); assertFalse(t.pending)
        t.accountSeen("B"); assertTrue(t.pending)
        t.done(RemapResult(3, 12), "18 nov.")
        assertFalse(t.pending)
        assertEquals("Retrouvée le 18 nov. : 3 films, 12 épisodes", t.last)
    }
}
