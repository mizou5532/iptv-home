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
        assertEquals("Épisode 2", s2[1].title)
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

    @Test
    fun extensionIsSanitised() = runBlocking {
        val body = """{"info":{"name":"Z"},"episodes":{"1":[
          {"id":"1","episode_num":1,"title":"a","container_extension":"mp4;rm -rf"},
          {"id":"2","episode_num":2,"title":"b","container_extension":"../x"},
          {"id":"3","episode_num":3,"title":"c","container_extension":"toolongext"},
          {"id":"4","episode_num":4,"title":"d","container_extension":".MP4"}]}}"""
        val eps = assertNotNull(api(body).seriesInfo("9")).seasons.single().episodes
        assertEquals(listOf("mkv", "mkv", "mkv", "MP4"), eps.map { it.extension })
    }

    @Test
    fun episodesSortedByNumberWithinSeason() = runBlocking {
        val body = """{"info":{"name":"Z"},"episodes":{"1":[
          {"id":"3","episode_num":3,"title":"c"},{"id":"1","episode_num":1,"title":"a"},{"id":"2","episode_num":"2","title":"b"}]}}"""
        val eps = assertNotNull(api(body).seriesInfo("9")).seasons.single().episodes
        assertEquals(listOf("1", "2", "3"), eps.map { it.id })
    }

    @Test
    fun decimalDurationStringAccepted() = runBlocking {
        val body = """{"info":{"name":"Z"},"episodes":{"1":[{"id":"1","episode_num":1,"title":"a","info":{"duration_secs":"2580.0"}}]}}"""
        assertEquals(2580, assertNotNull(api(body).seriesInfo("9")).seasons.single().episodes.single().durationS)
    }

    @Test
    fun seriesWithBlankNameKeptWithFallbackTitle() = runBlocking {
        // Une série sans nom reste ouvrable (son affiche suffit souvent) : elle n'est plus écartée de la rangée.
        val r = api("""[{"series_id":1,"name":"  "},{"series_id":2},{"series_id":3,"name":"Ok"}]""").series("5")
        assertEquals(listOf("1", "2", "3"), r.map { it.id })
        assertEquals(listOf(UNTITLED_SERIES, UNTITLED_SERIES, "Ok"), r.map { it.name })
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/xtream/$name")) { name }.readText()

    /** Fixture inventée, types mélangés comme sur les panneaux Xtream courants : aucune série ouvrable n'est perdue. */
    @Test
    fun mixedTypeSeriesListKeepsEveryOpenableSeries() = runBlocking {
        val r = api(fixture("series_list_mixed.json")).series("201")
        assertEquals(listOf("5001", "5002", "5003", "5004", "5005", "5006", "5007", "5010", "5011"), r.map { it.id })
        val byId = r.associateBy { it.id }
        assertEquals(SeriesSummary("5001", "201", "Série Exemple Un", "http://img.example.test/covers/5001.jpg", "2019", "Drame / Policier", "Un résumé inventé.", "7.5"), byId["5001"])
        assertEquals("201", byId["5002"]!!.categoryId) // category_id numérique
        assertNull(byId["5002"]!!.cover)
        assertEquals("8", byId["5002"]!!.rating) // note numérique
        assertEquals("Titre de secours", byId["5003"]!!.name) // name nul -> title
        assertEquals("201", byId["5003"]!!.categoryId) // category_id nul -> catégorie demandée
        assertEquals("1899", byId["5004"]!!.name) // nom numérique
        assertNull(byId["5004"]!!.cover) // "cover": false
        assertNull(byId["5004"]!!.rating) // note vide
        assertEquals("2017", byId["5004"]!!.year) // année numérique
        assertEquals("مسلسل تجريبي", byId["5005"]!!.name) // identifiant 5005.0 ramené à 5005
        assertEquals("6.25", byId["5005"]!!.rating)
        assertEquals(UNTITLED_SERIES, byId["5006"]!!.name)
        assertEquals("Espaces autour", byId["5007"]!!.name)
        assertNull(byId["5007"]!!.cover)
        assertNull(byId["5007"]!!.year)
        assertEquals(UNTITLED_SERIES, byId["5010"]!!.name) // "name": true
    }

    @Test
    fun mixedTypeCategoriesKeepOrderAndNeverAnEmptyId() = runBlocking {
        val r = api(fixture("series_categories_mixed.json")).seriesCategories()
        // Ordre du fournisseur conservé ; un identifiant vide ferait demander TOUT le catalogue (get_series sans catégorie).
        assertEquals(listOf("101", "102", "201", "202", "301", "302", "303", "304", "401"), r.map { it.id })
        assertEquals("AR | مسلسلات عربية", r[0].name)
        assertEquals("FR | Séries françaises", r[2].name)
        assertEquals(UNNAMED_CATEGORY, r[6].name) // nom nul : la catégorie reste une rangée
        assertEquals(UNNAMED_CATEGORY, r[7].name) // nom vide
    }

    @Test
    fun emptyObjectIsAnEmptyCategory() = runBlocking {
        assertEquals(emptyList(), api("{}").series("5"))
        assertEquals(emptyList(), api("  [ ]  ").series("5"))
    }

    @Test
    fun byteOrderMarkAndPhpNoticeBeforeJsonAreTolerated() = runBlocking {
        val list = """[{"series_id":1,"name":"A"},{"series_id":2,"name":"B"}]"""
        assertEquals(listOf("1", "2"), api("﻿" + list).series("5").map { it.id })
        val notice = "<br />\n<b>Notice</b>:  Undefined index: x in <b>/var/www/player_api.php</b> on line <b>12</b><br />\n"
        assertEquals(listOf("1", "2"), api(notice + list).series("5").map { it.id })
        assertEquals(listOf("7"), api(notice + """[{"category_id":"7","category_name":"C [HD]"}]""").seriesCategories().map { it.id })
    }

    @Test
    fun authErrorObjectStillUnexpectedForSeries() {
        assertFailsWith<XtreamError.Unexpected> { runBlocking { api("""{"user_info":{"auth":0}}""").series("5") } }
        assertFailsWith<XtreamError.Unexpected> { runBlocking { api("<html>Erreur</html>").series("5") } }
    }

    @Test
    fun largeCategoryParsedEntirely() = runBlocking {
        val body = (1..5000).joinToString(",", "[", "]") { """{"series_id":$it,"name":"S $it","rating":${it % 10},"backdrop_path":[],"category_id":$it}""" }
        assertEquals(5000, api(body).series("5").size)
    }
}
