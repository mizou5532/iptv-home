package home.iptv.tv.series

import home.iptv.shared.progress.ProgressRow
import home.iptv.shared.sync.HiddenItem
import home.iptv.shared.sync.ProfilePrefs
import home.iptv.shared.xtream.Episode
import home.iptv.shared.xtream.Season
import home.iptv.shared.xtream.SeriesCategory
import home.iptv.shared.xtream.SeriesInfo
import home.iptv.shared.xtream.SeriesSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

private fun at(minute: Int) = Instant.parse("2026-11-15T10:00:00Z") + kotlin.time.Duration.parse("${minute}m")

private fun row(
    series: String, season: Int, ep: Int, watched: Boolean = false, minute: Int = 0,
    category: String? = "1", pos: Int = 600, dur: Int = 2400,
) = ProgressRow(
    itemId = "$series-s${season}e$ep", seriesId = series, season = season, episode = ep,
    positionS = pos, durationS = dur, watched = watched, clientUpdatedAt = at(minute),
    seriesName = "Serie $series", seriesCover = null, seriesCategory = category,
)

/** Fiche : [seasons] = saison -> nombre d'épisodes ; l'identifiant d'un épisode est « série-sXeY ». */
private fun info(series: String, category: String = "1", vararg seasons: Pair<Int, Int>) = SeriesInfo(
    SeriesSummary(series, category, "Serie $series", null, null, null, null, null),
    seasons.map { (s, n) -> Season(s, "Saison $s", (1..n).map { Episode("$series-s${s}e$it", s, it, "Titre $it", 2400, "mkv", null) }) },
)

private fun prefs(hidden: List<String> = emptyList(), order: List<String>? = null) = ProfilePrefs(
    rev = 1, hidden = hidden.map { HiddenItem("series_category", it) }, favorites = emptyList(),
    playlists = emptyList(), order = null, seriesOrder = order,
)

private val cats = listOf("1", "2", "3", "4").map { SeriesCategory(it, "Cat $it") }

class SeriesRowsTest {
    @Test
    fun reprendre_est_la_serie_la_plus_recente() {
        val home = buildSeriesHome(
            listOf(row("a", 1, 1, minute = 5), row("b", 1, 2, minute = 30), row("c", 2, 1, minute = 10)),
            emptyList(), cats, null,
        ) { null }
        assertEquals(listOf("b", "c", "a"), home.continueRow.map { it.seriesId })
        assertEquals("b", home.resume?.seriesId)
        assertEquals(home.continueRow.first(), home.resume)
        assertEquals(ResumeState.InProgress, home.resume?.state)
        assertEquals(600, home.resume?.positionS)
        assertEquals(2, home.resume?.episode?.number)
    }

    @Test
    fun episode_vu_propose_le_suivant() {
        val infos = mapOf("a" to info("a", "1", 1 to 2, 2 to 3), "b" to info("b", "1", 1 to 5))
        val home = buildSeriesHome(
            listOf(row("a", 1, 2, watched = true, minute = 20), row("b", 1, 2, watched = true, minute = 10), row("z", 1, 1, watched = true, minute = 5)),
            emptyList(), cats, null,
        ) { infos[it] }
        val a = home.continueRow.first { it.seriesId == "a" }
        assertEquals(ResumeState.NextEpisode, a.state)
        assertEquals(2, a.episode?.season) // passe à la saison suivante
        assertEquals(1, a.episode?.number)
        assertEquals(0, a.positionS)
        assertEquals(2, a.then?.number)
        val b = home.continueRow.first { it.seriesId == "b" }
        assertEquals(3, b.episode?.number)
        // Fiche inconnue : la série reste, « à jour ».
        val z = home.continueRow.first { it.seriesId == "z" }
        assertEquals(ResumeState.UpToDate, z.state)
        assertNull(z.episode)
    }

    @Test
    fun serie_terminee_disparait() {
        val infos = mapOf("a" to info("a", "1", 1 to 2, 2 to 2))
        val home = buildSeriesHome(
            listOf(row("a", 2, 2, watched = true, minute = 20), row("b", 1, 1, minute = 10)),
            emptyList(), cats, null,
        ) { infos[it] }
        assertEquals(listOf("b"), home.continueRow.map { it.seriesId })
        assertEquals("b", home.resume?.seriesId)
    }

    @Test
    fun categorie_masquee_retire_la_serie_partout() {
        val infos = mapOf(
            "a" to info("a", "9", 1 to 3), "m" to info("m", "9", 1 to 3), "n" to info("n", "1", 1 to 3),
            "l" to info("l", "9", 1 to 3),
        )
        val home = buildSeriesHome(
            listOf(
                row("a", 1, 1, watched = true, minute = 30, category = "9"), // catégorie sur la ligne
                row("m", 1, 1, watched = true, minute = 20, category = null), // catégorie lue sur la fiche
                row("n", 1, 1, watched = true, minute = 10, category = "1"),
            ),
            listOf("l", "n"), cats + SeriesCategory("9", "Masquée"), prefs(hidden = listOf("9")),
        ) { infos[it] }
        assertEquals(listOf("n"), home.continueRow.map { it.seriesId })
        assertEquals(listOf("n"), home.myList.map { it.seriesId })
        assertEquals(listOf("n"), home.newEpisodes.map { it.seriesId })
        assertEquals(listOf("1", "2", "3", "4"), home.categoryRows.map { it.id })
        assertEquals("n", home.resume?.seriesId)
    }

    @Test
    fun ordre_categories_profil_puis_fournisseur() {
        val home = buildSeriesHome(emptyList(), emptyList(), cats, prefs(order = listOf("3", "inconnue", "1"))) { null }
        assertEquals(listOf("3", "1", "2", "4"), home.categoryRows.map { it.id })
        val sansOrdre = buildSeriesHome(emptyList(), emptyList(), cats, prefs()) { null }
        assertEquals(listOf("1", "2", "3", "4"), sansOrdre.categoryRows.map { it.id })
        val masque = buildSeriesHome(emptyList(), emptyList(), cats, prefs(hidden = listOf("3"), order = listOf("3", "1"))) { null }
        assertEquals(listOf("1", "2", "4"), masque.categoryRows.map { it.id })
    }

    @Test
    fun nouveaux_episodes_limites_a_20() {
        val ids = (1..25).map { "s$it" }
        val infos = ids.associateWith { info(it, "1", 1 to 4) }
        val home = buildSeriesHome(
            ids.mapIndexed { i, id -> row(id, 1, 1, watched = true, minute = i) },
            emptyList(), cats, null,
        ) { infos[it] }
        assertEquals(MAX_NEW_EPISODES, home.newEpisodes.size)
        assertNotNull(home.newEpisodes.firstOrNull())
    }
}
