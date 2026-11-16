package home.iptv.tv.movies

import home.iptv.shared.progress.*
import home.iptv.shared.sync.*
import home.iptv.shared.xtream.*
import kotlin.test.*
import kotlin.time.Instant

class MovieRowsTest {
    private val prefs = ProfilePrefs(1, emptyList(), emptyList(), emptyList(), null)
    private fun movie(id: String, category: String = "c", added: Long? = 100) = MovieSummary(id, category, id, null, null, null, null, null, added, "mkv", null)
    private fun row(id: String, category: String? = "c", watched: Boolean = false) = ProgressRow(itemId = id, itemType = "movie", positionS = 60, durationS = 1000, watched = watched, clientUpdatedAt = Instant.parse("2026-11-15T10:00:00Z"), movieName = id, movieCategory = category)
    @Test fun unknown_preferences_hide_everything() {
        assertEquals(MovieHome.EMPTY, buildMovieHome(listOf(row("a")), emptyList(), listOf(MovieCategory("c", "C")), null, listOf(movie("a"))))
    }
    @Test fun watched_and_unknown_category_are_not_continued() {
        val h = buildMovieHome(listOf(row("a"), row("b", watched = true), row("old", null)), emptyList(), emptyList(), prefs, emptyList())
        assertEquals(listOf("a"), h.continueRow.map { it.movieId })
    }
    @Test fun recent_is_bounded_valid_cache_only_and_hidden_wins() {
        val hidden = prefs.copy(hidden = listOf(HiddenItem("vod_category", "x")))
        val cached = (1..100).map { movie("$it", added = it.toLong()) } + movie("bad", added = null) + movie("future", added = Long.MAX_VALUE) + movie("hidden", "x", 200)
        val h = buildMovieHome(emptyList(), listOf(MovieListItem("hidden", "Secret", movieCategory = "x")), emptyList(), hidden, cached, nowS = 1000)
        assertEquals(20, h.recent.size)
        assertEquals("100", h.recent.first().movieId)
        assertTrue(h.myList.isEmpty())
    }
    @Test fun all_categories_remain_reachable_and_ordered() {
        val cats = (1..1000).map { MovieCategory("$it", "$it") }
        val h = buildMovieHome(emptyList(), emptyList(), cats, prefs.copy(movieOrder = listOf("999")), emptyList())
        assertEquals(1000, h.categoryRows.size)
        assertEquals("999", h.categoryRows.first().id)
    }
}
