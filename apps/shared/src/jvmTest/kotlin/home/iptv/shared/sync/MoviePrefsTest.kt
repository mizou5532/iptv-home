package home.iptv.shared.sync

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MoviePrefsTest {
    private val base = """{"rev":1,"hidden":[],"favorites":[],"playlists":[],"order":null}"""
    @Test fun legacyPreferencesKeepDefaults() {
        val p = Json.decodeFromString<ProfilePrefs>(base)
        assertNull(p.movieOrder)
        assertEquals(emptyList(), p.movieList)
    }
    @Test fun filmPreferencesDecodeAlongsideSeries() {
        val raw = base.dropLast(1) + """, "series_order":["s"],"series_list":["e"],"movie_order":["v"],"movie_list":["42"]}"""
        val p = Json.decodeFromString<ProfilePrefs>(raw)
        assertEquals(listOf("v"), p.movieOrder)
        assertEquals(listOf("42"), p.movieList)
        assertEquals(listOf("s"), p.seriesOrder)
        assertEquals(listOf("e"), p.seriesList)
    }
}
