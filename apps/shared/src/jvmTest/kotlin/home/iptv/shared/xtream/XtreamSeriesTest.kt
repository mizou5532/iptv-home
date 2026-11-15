package home.iptv.shared.xtream

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XtreamSeriesTest {
    private val creds = XtreamCreds("http://panel.test:8080", "alice-user", "s3cret-pass")

    private fun api(body: String, status: HttpStatusCode = HttpStatusCode.OK, seen: MutableList<String>? = null): XtreamApi {
        val engine = MockEngine { req ->
            seen?.add(req.url.toString())
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return XtreamApi(HttpClient(engine), creds)
    }

    @Test
    fun categoriesParsedWithNumericIds() = runBlocking {
        val seen = mutableListOf<String>()
        val r = api("""[{"category_id":"5","category_name":"Drames"},{"category_id":7,"category_name":"Comédies"},{"category_name":"sans id"}]""", seen = seen)
            .seriesCategories()
        assertEquals(listOf(SeriesCategory("5", "Drames"), SeriesCategory("7", "Comédies")), r)
        assertTrue(seen.single().contains("action=get_series_categories"))
    }

    @Test
    fun seriesListParsedWithStringNumbersAndMissingFields() = runBlocking {
        val seen = mutableListOf<String>()
        val body = """[
          {"series_id":101,"name":"Alpha","cover":"http://img.test/a.jpg","releaseDate":"2019-03-04","genre":"Drame","plot":"Texte","rating":"7.5","category_id":"5"},
          {"series_id":"102","name":"Beta","cover":"","year":"2020","rating_5based":3.5},
          {"name":"sans id"}
        ]"""
        val r = api(body, seen = seen).series("5")
        assertEquals(2, r.size)
        assertEquals(SeriesSummary("101", "5", "Alpha", "http://img.test/a.jpg", "2019", "Drame", "Texte", "7.5"), r[0])
        assertEquals("102", r[1].id)
        assertEquals("5", r[1].categoryId)
        assertNull(r[1].cover)
        assertEquals("2020", r[1].year)
        assertTrue(seen.single().contains("action=get_series"))
        assertTrue(seen.single().contains("category_id=5"))
    }

    private val infoObject = """{
      "seasons":[{"season_number":1,"name":"Saison 1"},{"season_number":2,"name":"Saison 2"},{"season_number":3}],
      "info":{"name":"Alpha","cover":"http://img.test/a.jpg","plot":"Texte","genre":"Drame","releaseDate":"2019-03-04","rating":"7.5","category_id":"5"},
      "episodes":{
        "1":[
          {"id":"9001","episode_num":1,"title":"Pilote","container_extension":"mp4","info":{"duration_secs":2580,"movie_image":"http://img.test/e1.jpg"}},
          {"id":9002,"episode_num":"2","title":"Suite","info":{"duration":"00:41:30"}}
        ],
        "2":[
          {"id":"9003","episode_num":1,"title":"Retour","container_extension":"","info":{"duration_secs":"1800"}},
          {"id":"9004","episode_num":2,"info":[]}
        ]
      }
    }"""

    @Test
    fun infoParsesEpisodesIndexedBySeason() = runBlocking {
        val info = assertNotNull(api(infoObject).seriesInfo("101"))
        assertEquals("Alpha", info.summary.name)
        assertEquals("101", info.summary.id)
        assertEquals("5", info.summary.categoryId)
        assertEquals("2019", info.summary.year)
        assertEquals(listOf(1, 2, 3), info.seasons.map { it.number })
        assertEquals("Saison 1", info.seasons[0].name)
        val s1 = info.seasons[0].episodes
        assertEquals(Episode("9001", 1, 1, "Pilote", 2580, "mp4", "http://img.test/e1.jpg"), s1[0])
        // durée HH:MM:SS, extension absente -> mkv, affiche absente
        assertEquals(Episode("9002", 1, 2, "Suite", 41 * 60 + 30, "mkv", null), s1[1])
        val s2 = info.seasons[1].episodes
        assertEquals(1800, s2[0].durationS)
        assertEquals("mkv", s2[0].extension)
        // titre absent, durée absente : l'écran reste utilisable
        assertEquals("Episode 2", s2[1].title)
        assertNull(s2[1].durationS)
    }

    @Test
    fun seasonWithoutEpisodesKeptEmptyWithDefaultName() = runBlocking {
        val info = assertNotNull(api(infoObject).seriesInfo("101"))
        val s3 = info.seasons.single { it.number == 3 }
        assertTrue(s3.episodes.isEmpty())
        assertEquals("Saison 3", s3.name)
    }

    @Test
    fun seasonsMissingFromSeasonsArrayAreDerivedFromEpisodes() = runBlocking {
        val body = """{"info":{"name":"Z"},"episodes":{"2":[{"id":"1","episode_num":1,"title":"a"}],"1":[{"id":"2","episode_num":1,"title":"b"}]}}"""
        val info = assertNotNull(api(body).seriesInfo("9"))
        assertEquals(listOf(1, 2), info.seasons.map { it.number })
        assertEquals("", info.summary.categoryId)
    }

    @Test
    fun episodesGivenAsFlatArrayAreGroupedBySeasonField() = runBlocking {
        val body = """{"info":{"name":"Z"},"episodes":[
          {"id":"1","episode_num":1,"title":"a","season":1},
          {"id":"2","episode_num":1,"title":"b","season":2},
          {"id":"3","episode_num":2,"title":"c","season":2}]}"""
        val info = assertNotNull(api(body).seriesInfo("9"))
        assertEquals(listOf(1, 2), info.seasons.map { it.number })
        assertEquals(listOf("2", "3"), info.seasons[1].episodes.map { it.id })
    }

    @Test
    fun episodesGivenAsArrayOfSeasonArrays() = runBlocking {
        val body = """{"info":{"name":"Z"},"episodes":[
          [{"id":"1","episode_num":1,"title":"a"}],
          [{"id":"2","episode_num":1,"title":"b"}]]}"""
        val info = assertNotNull(api(body).seriesInfo("9"))
        assertEquals(listOf(1, 2), info.seasons.map { it.number })
        assertEquals("2", info.seasons[1].episodes.single().id)
    }

    @Test
    fun coverFallsBackToMovieImage() = runBlocking {
        val body = """{"info":{"name":"Z","movie_image":"http://img.test/m.jpg"},"episodes":{}}"""
        val info = assertNotNull(api(body).seriesInfo("9"))
        assertEquals("http://img.test/m.jpg", info.summary.cover)
        assertTrue(info.seasons.isEmpty())
    }

    @Test
    fun emptyInfoGivesNull() = runBlocking {
        assertNull(api("[]").seriesInfo("9"))
    }

    @Test
    fun refusedAndUnexpectedAreMappedWithoutCredentials() {
        val e = assertFailsWith<XtreamError.Refused> { runBlocking { api("{}", HttpStatusCode.Unauthorized).series("1") } }
        assertFalse((e.message ?: "").contains("s3cret"))
        assertFailsWith<XtreamError.Unexpected> { runBlocking { api("{}", HttpStatusCode.InternalServerError).seriesInfo("1") } }
        assertFailsWith<XtreamError.Unexpected> { runBlocking { api("\"oups\"").seriesCategories() } }
    }

    @Test
    fun streamUrlForEpisodeUsesSeriesPath() {
        val ep = Episode("9001", 1, 1, "Pilote", null, "mkv", null)
        assertEquals(
            "http://panel.test:8080/series/alice-user/s3cret-pass/9001.mkv",
            streamUrl(creds, StreamKind.SERIES, ep.id, ep.extension),
        )
    }
}
