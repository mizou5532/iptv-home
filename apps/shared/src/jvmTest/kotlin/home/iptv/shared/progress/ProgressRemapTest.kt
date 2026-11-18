package home.iptv.shared.progress

import home.iptv.shared.search.SearchItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class ProgressRemapTest {
    private val at = Instant.parse("2026-11-01T00:00:00Z")
    private fun movie(id: String, name: String?, pos: Int = 600) = ProgressRow(itemId = id, itemType = "movie", positionS = pos, durationS = 7200, clientUpdatedAt = at, movieName = name)
    private fun ep(id: String, series: String, name: String, s: Int, e: Int) =
        ProgressRow(itemId = id, itemType = "episode", seriesId = series, season = s, episode = e, positionS = 300, durationS = 2600, clientUpdatedAt = at, seriesName = name)

    @Test fun films_retrouves_par_nom_et_annee_numero_reutilise_compris() {
        val catalog = listOf(
            SearchItem("900", "FR - Training Day (2001)", setOf("c")),
            SearchItem("10", "FR - Cars (2006)", setOf("c")),               // ancien numéro 10 = un autre film maintenant
            SearchItem("901", "EN - Dune (1984)", setOf("c")), SearchItem("902", "EN - Dune (2021)", setOf("c")),
        )
        val rows = listOf(movie("10", "FR - Training Day (2001)"), movie("11", "Dune (2021)"), movie("12", null), movie("900", "Training Day"))
        val moved = remapMovies(rows, catalog)
        assertEquals(listOf("900", "902"), moved.map { it.itemId })
        assertEquals(600, moved.first().positionS)
    }

    @Test fun series_puis_episodes_par_saison_et_numero() {
        val catalog = listOf(SearchItem("s-new", "FR - Breaking Bad", setOf("cat")))
        val rows = listOf(ep("e1", "s-old", "Breaking Bad", 2, 3), ep("e2", "s-old", "Breaking Bad", 9, 9))
        val series = remapSeries(rows, catalog)
        assertEquals(mapOf("s-old" to catalog[0]), series)
        val moved = remapEpisodes(rows, "s-old", catalog[0], mapOf((2 to 3) to "ep-new"))
        assertEquals(listOf("ep-new"), moved.map { it.itemId })
        assertEquals("s-new", moved.single().seriesId)
    }

    @Test fun meme_fournisseur_rien_a_deplacer() {
        val catalog = listOf(SearchItem("10", "FR - Training Day (2001)", setOf("c")))
        assertEquals(emptyList(), remapMovies(listOf(movie("10", "FR - Training Day (2001)")), catalog))
    }
}
