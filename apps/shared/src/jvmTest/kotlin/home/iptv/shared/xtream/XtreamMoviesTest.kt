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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Films (VOD) : fixtures inventées, aucun identifiant ni adresse réels. */
class XtreamMoviesTest {
    private val creds = XtreamCreds("http://panel.test:8080", "alice-user", "s3cret-pass")

    private fun api(body: String, status: HttpStatusCode = HttpStatusCode.OK, seen: MutableList<String>? = null): XtreamApi {
        val engine = MockEngine { req ->
            seen?.add(req.url.toString())
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return XtreamApi(HttpClient(engine), creds)
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/xtream/$name")) { name }.readText()

    @Test
    fun movieCategoriesKeepOrderAndNeverAnEmptyId() = runBlocking {
        val seen = mutableListOf<String>()
        val r = api(fixture("vod_categories_mixed.json"), seen = seen).movieCategories()
        // Ordre du fournisseur conservé ; un identifiant vide ferait demander TOUT le catalogue (get_vod_streams sans catégorie).
        assertEquals(listOf("501", "502", "503", "504", "505"), r.map { it.id })
        assertEquals(MovieCategory("501", "FR | Films récents"), r[0])
        assertEquals("AR | أفلام عربية", r[1].name)
        assertEquals(UNNAMED_CATEGORY, r[2].name) // nom nul : la catégorie reste une rangée
        assertEquals(UNNAMED_CATEGORY, r[3].name) // nom blanc, identifiant 504.0 ramené à 504
        assertTrue(seen.single().contains("action=get_vod_categories"))
    }

    @Test
    fun moviesAlwaysCarryTheCategoryId() = runBlocking {
        val seen = mutableListOf<String>()
        api("""[{"stream_id":1,"name":"A"}]""", seen = seen).movies("501")
        assertTrue(seen.single().contains("action=get_vod_streams"))
        assertTrue(seen.single().contains("category_id=501"))
    }

    @Test
    fun blankCategoryNeverReachesTheProvider() = runBlocking {
        // get_vod_streams sans catégorie renvoie TOUT le catalogue (des centaines de milliers de titres) : jamais demandé.
        val seen = mutableListOf<String>()
        val a = api("""[{"stream_id":1,"name":"A"}]""", seen = seen)
        assertEquals(emptyList(), a.movies(""))
        assertEquals(emptyList(), a.movies("   "))
        assertTrue(seen.isEmpty())
    }

    /** Types mélangés comme sur les panneaux Xtream courants : aucun film lisible n'est perdu. */
    @Test
    fun mixedTypeMovieListKeepsEveryPlayableMovie() = runBlocking {
        val r = api(fixture("vod_streams_mixed.json")).movies("501")
        assertEquals(listOf("7001", "7002", "7003", "7004", "7005", "7009", "7010"), r.map { it.id })
        val byId = r.associateBy { it.id }
        assertEquals(
            MovieSummary(
                "7001", "501", "Film Exemple Un", "http://img.example.test/films/7001.jpg", "2021", "Drame / Policier",
                "Un résumé inventé.", "7.5", 1_700_000_000L, "mkv", 6300,
            ),
            byId["7001"],
        )
        val two = byId.getValue("7002")
        assertEquals("501", two.categoryId) // category_id numérique
        assertNull(two.cover) // affiche vide
        assertEquals("8", two.rating) // note numérique
        assertEquals(1_700_000_500L, two.addedAt) // date numérique
        assertEquals("mp4", two.extension)
        assertNull(two.durationS)
        val three = byId.getValue("7003")
        assertEquals("Titre de secours", three.name) // name nul -> title
        assertEquals("501", three.categoryId) // category_id nul -> catégorie demandée
        assertNull(three.addedAt) // date absente : jamais une fausse nouveauté
        assertEquals("mkv", three.extension) // extension absente
        val four = byId.getValue("7004")
        assertEquals("1917", four.name) // nom numérique, identifiant 7004.0 ramené à 7004
        assertNull(four.cover) // "stream_icon": false
        assertNull(four.rating)
        assertNull(four.addedAt) // "0" n'est pas une date
        assertEquals("AVI", four.extension) // ".AVI"
        assertEquals("2019", four.year)
        assertEquals(7140, four.durationS) // "01:59:00"
        val five = byId.getValue("7005")
        assertEquals("فيلم تجريبي", five.name)
        assertNull(five.cover)
        assertNull(five.addedAt) // texte
        assertEquals("mkv", five.extension) // extension dangereuse écartée
        assertEquals("2018", five.year) // année tirée de la date de sortie
        val nine = byId.getValue("7009")
        assertEquals(UNTITLED_MOVIE, nine.name) // "name": true
        assertEquals(1_700_009_999L, nine.addedAt) // millisecondes ramenées en secondes
        assertEquals("mkv", nine.extension)
        assertNull(nine.durationS) // durée 0 = inconnue
        val ten = byId.getValue("7010")
        assertNull(ten.addedAt) // date négative
        assertEquals(5400, ten.durationS) // "5400.0"
    }

    @Test
    fun emptyAnswersAreAnEmptyMovieList() = runBlocking {
        assertEquals(emptyList(), api("{}").movies("5"))
        assertEquals(emptyList(), api("  [ ]  ").movies("5"))
        assertEquals(emptyList(), api("{}").movieCategories())
    }

    @Test
    fun malformedAnswersAreErrorsNotEmptyLists() {
        // Une réponse inattendue doit échouer : prise pour une liste vide, elle pourrait passer pour « plus aucun film ».
        for (body in listOf("""{"user_info":{"auth":0}}""", "<html>Erreur</html>", """[{"stream_id":1""", "\"texte\"")) {
            assertFailsWith<XtreamError.Unexpected> { runBlocking { api(body).movies("5") } }
            assertFailsWith<XtreamError.Unexpected> { runBlocking { api(body).movieCategories() } }
            assertFailsWith<XtreamError.Unexpected> { runBlocking { api(body).movieInfo("7") } }
        }
        assertFailsWith<XtreamError.Unexpected> { runBlocking { api("[]", HttpStatusCode.InternalServerError).movies("5") } }
        assertFailsWith<XtreamError.Refused> { runBlocking { api("[]", HttpStatusCode.Forbidden).movies("5") } }
    }

    @Test
    fun byteOrderMarkAndPhpNoticeBeforeJsonAreTolerated() = runBlocking {
        val list = """[{"stream_id":1,"name":"A"},{"stream_id":2,"name":"B"}]"""
        assertEquals(listOf("1", "2"), api("﻿" + list).movies("5").map { it.id })
        val notice = "<br />\n<b>Notice</b>:  Undefined index: x in <b>/var/www/player_api.php</b> on line <b>12</b><br />\n"
        assertEquals(listOf("1", "2"), api(notice + list).movies("5").map { it.id })
    }

    @Test
    fun objectWithNumericKeysIsReadAsAList() = runBlocking {
        val r = api("""{"0":{"stream_id":1,"name":"A"},"1":{"stream_id":2,"name":"B"}}""").movies("5")
        assertEquals(listOf("1", "2"), r.map { it.id })
    }

    @Test
    fun largeCategoryParsedEntirely() = runBlocking {
        val body = (1..5000).joinToString(",", "[", "]") { """{"stream_id":$it,"name":"F $it","rating":${it % 10},"added":"${1_700_000_000 + it}","category_id":5}""" }
        assertEquals(5000, api(body).movies("5").size)
    }

    @Test
    fun movieInfoParsesInfoAndMovieData() = runBlocking {
        val seen = mutableListOf<String>()
        val info = assertNotNull(api(fixture("vod_info.json"), seen = seen).movieInfo("7001"))
        assertEquals(
            MovieSummary(
                "7001", "501", "Film Exemple Un", "http://img.example.test/films/7001-grand.jpg", "2021", "Drame / Policier",
                "Un résumé inventé, version longue.", "7.5", 1_700_000_000L, "mkv", 6300,
            ),
            info.summary,
        )
        assertTrue(seen.single().contains("action=get_vod_info"))
        assertTrue(seen.single().contains("vod_id=7001"))
    }

    @Test
    fun movieInfoToleratesMissingFields() = runBlocking {
        // Sans `info` : le nom de `movie_data` sert ; durée en texte ; identifiant = celui demandé.
        val a = assertNotNull(api("""{"info":[],"movie_data":{"stream_id":"9","name":"Seul","container_extension":"mp4"}}""").movieInfo("9"))
        assertEquals(MovieSummary("9", "", "Seul", null, null, null, null, null, null, "mp4", null), a.summary)
        // Sans `movie_data` : description en secours du résumé, grande affiche en secours, durée HH:MM:SS.
        val b = assertNotNull(
            api("""{"info":{"name":"Titre","cover_big":"http://img.example.test/b.jpg","description":"Secours","duration":"00:50:10","year":2020}}""").movieInfo("3"),
        )
        assertEquals(MovieSummary("3", "", "Titre", "http://img.example.test/b.jpg", "2020", null, "Secours", null, null, "mkv", 3010), b.summary)
        // Ni nom ni titre : la fiche reste ouvrable.
        val c = assertNotNull(api("""{"info":{"plot":"x"},"movie_data":{"stream_id":4}}""").movieInfo("4"))
        assertEquals(UNTITLED_MOVIE, c.summary.name)
    }

    @Test
    fun unknownMovieGivesNull() = runBlocking {
        assertNull(api("""{"info":[],"movie_data":[]}""").movieInfo("404"))
        assertNull(api("[]").movieInfo("404"))
        assertNull(api("{}").movieInfo("404"))
        assertNull(api("").movieInfo("404"))
    }

    @Test
    fun blankMovieIdNeverReachesTheProvider() = runBlocking {
        val seen = mutableListOf<String>()
        assertNull(api(fixture("vod_info.json"), seen = seen).movieInfo("  "))
        assertTrue(seen.isEmpty())
    }

    @Test
    fun streamUrlForMovieUsesMoviePathAndExtension() {
        assertEquals(
            "http://panel.test:8080/movie/alice-user/s3cret-pass/7001.mkv",
            streamUrl(creds, StreamKind.MOVIE, "7001", "mkv"),
        )
    }

    @Test
    fun errorsNeverContainTheAddressOrTheCredentials() {
        val e = assertFailsWith<XtreamError> { runBlocking { api("<html>", HttpStatusCode.BadGateway).movies("5") } }
        val text = e.toString() + e.message
        for (secret in listOf("panel.test", "alice-user", "s3cret-pass")) assertTrue(secret !in text)
    }
}
