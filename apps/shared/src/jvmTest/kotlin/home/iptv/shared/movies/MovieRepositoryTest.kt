package home.iptv.shared.movies

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import home.iptv.shared.catalog.CatalogDatabase
import home.iptv.shared.xtream.MovieCategory
import home.iptv.shared.xtream.MovieInfo
import home.iptv.shared.xtream.MovieSummary
import home.iptv.shared.xtream.UNTITLED_MOVIE
import home.iptv.shared.xtream.XtreamApi
import home.iptv.shared.xtream.XtreamCreds
import home.iptv.shared.xtream.XtreamError
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

private const val NOW_S = 1_800_000_000L

private class TestClock(var now: Instant = Instant.fromEpochSeconds(NOW_S)) : Clock {
    override fun now(): Instant = now
}

private class FakeMovies : MovieSource {
    var categories = listOf(MovieCategory("1", "Action"), MovieCategory("2", "Comedies"))
    var items = mapOf(
        "1" to listOf(movie("10", "1", "Alpha"), movie("11", "1", "Beta")),
        "2" to listOf(movie("20", "2", "Gamma")),
    )
    var info: MovieInfo? = MovieInfo(movie("10", "1", "Alpha"))
    var error: Exception? = null
    val calls = mutableListOf<String>()

    override suspend fun movieCategories(): List<MovieCategory> {
        calls += "cats"; error?.let { throw it }; return categories
    }

    override suspend fun movies(categoryId: String): List<MovieSummary> {
        // La liste globale ne doit jamais être demandée, quel que soit le chemin.
        check(categoryId.isNotBlank()) { "liste globale demandée" }
        calls += "cat:$categoryId"; error?.let { throw it }; return items[categoryId].orEmpty()
    }

    override suspend fun movieInfo(movieId: String): MovieInfo? {
        calls += "info:$movieId"; error?.let { throw it }; return info
    }
}

private fun movie(id: String, cat: String, name: String, addedAt: Long? = NOW_S - 1000, ext: String = "mkv") =
    MovieSummary(id, cat, name, "http://img.test/$id.jpg", "2020", "Action", "Resume", "7.5", addedAt, ext, 6000)

/** Pilote qui échoue à l'insertion d'un film une fois armé (test d'atomicité de l'enregistrement). */
private class StoreFailingDriver(private val inner: SqlDriver) : SqlDriver by inner {
    var armed = false

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        if (armed && sql.contains("INTO movie_item")) throw IllegalStateException("disque plein")
        return inner.execute(identifier, sql, parameters, binders)
    }
}

class MovieRepositoryTest {
    private val clock = TestClock()
    private val source = FakeMovies()

    private fun dbAndRepo(): Pair<CatalogDatabase, MovieRepository> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CatalogDatabase.Schema.create(driver)
        val db = CatalogDatabase(driver)
        return db to MovieRepository(db, source, clock)
    }

    private fun repo(): MovieRepository = dbAndRepo().second

    @Test
    fun featuredMovieUsesAllowedCacheWithoutRequiringAnAddedDateOrNetwork() = runTest {
        val r = repo()
        source.items = mapOf("1" to listOf(movie("dated-invalid", "1", "Invalide", addedAt = -1)), "2" to listOf(movie("undated", "2", "Sans date", addedAt = null)))
        assertNull(r.featuredMovie(emptySet()))
        r.moviesOf("1", emptySet()); r.moviesOf("2", emptySet())
        source.calls.clear()
        assertEquals("dated-invalid", r.featuredMovie(emptySet())?.id)
        assertEquals("undated", r.featuredMovie(setOf("1"))?.id)
        assertNull(r.featuredMovie(setOf("1", "2")))
        assertTrue(r.recentlyAdded(emptySet(), 20).isEmpty())
        assertTrue(source.calls.isEmpty())
    }

    @Test
    fun categoriesFreshForOneDay() = runTest {
        val r = repo()
        assertEquals(listOf(MovieCategory("1", "Action"), MovieCategory("2", "Comedies")), r.categories(emptySet()))
        clock.now += 23.hours
        r.categories(emptySet())
        assertEquals(listOf("cats"), source.calls)
        clock.now += 2.hours
        r.categories(emptySet())
        assertEquals(listOf("cats", "cats"), source.calls)
    }

    @Test
    fun hiddenCategoriesAreFilteredOut() = runTest {
        val r = repo()
        assertEquals(listOf("2"), r.categories(setOf("1")).map { it.id })
        // Le cache contient tout : la même liste revient avec un autre masquage, sans nouvelle demande.
        assertEquals(listOf("1"), r.categories(setOf("2")).map { it.id })
        assertEquals(1, source.calls.size)
    }

    @Test
    fun moviesOfFreshForThreeDaysAndKeepsOrderAndFields() = runTest {
        val r = repo()
        val first = r.moviesOf("1", emptySet())
        assertEquals(source.items["1"], first)
        clock.now += 71.hours
        r.moviesOf("1", emptySet())
        assertEquals(listOf("cat:1"), source.calls)
        clock.now += 2.hours
        r.moviesOf("1", emptySet())
        assertEquals(listOf("cat:1", "cat:1"), source.calls)
    }

    @Test
    fun moviesOfIsCachedPerCategory() = runTest {
        val r = repo()
        r.moviesOf("1", emptySet())
        assertEquals(listOf("Gamma"), r.moviesOf("2", emptySet()).map { it.name })
        assertEquals(listOf("cat:1", "cat:2"), source.calls)
    }

    @Test
    fun optionalFieldsRoundTripAsNull() = runTest {
        val bare = MovieSummary("30", "1", "Nu", null, null, null, null, null, null, "mp4", null)
        source.items = mapOf("1" to listOf(bare))
        assertEquals(listOf(bare), repo().moviesOf("1", emptySet()))
    }

    @Test
    fun hiddenCategoryIsNeverRequestedNorServedFromCache() = runTest {
        val r = repo()
        assertEquals(emptyList(), r.moviesOf("1", setOf("1")))
        assertEquals(emptyList(), source.calls)
        r.moviesOf("1", emptySet())
        assertEquals(emptyList(), r.moviesOf("1", setOf("1")))
        assertEquals(listOf("cat:1"), source.calls)
    }

    @Test
    fun blankCategoryIsNeverRequestedNorListed() = runTest {
        // get_vod_streams sans catégorie renvoie TOUT le catalogue : jamais demandé, même si le fournisseur liste "".
        source.categories = listOf(MovieCategory("", "Vide"), MovieCategory(" ", "Espace"), MovieCategory("1", "Action"))
        val r = repo()
        assertEquals(listOf("1"), r.categories(emptySet()).map { it.id })
        assertEquals(emptyList(), r.moviesOf("", emptySet()))
        assertEquals(emptyList(), r.moviesOf("  ", emptySet()))
        assertEquals(listOf("cats"), source.calls)
    }

    @Test
    fun moviesWithoutIdAndDuplicatesAreDropped() = runTest {
        source.items = mapOf("1" to listOf(movie("10", "1", "Alpha"), movie("", "1", "Sans id"), movie("10", "1", "Doublon"), movie("11", "1", "Beta")))
        assertEquals(listOf("Alpha", "Beta"), repo().moviesOf("1", emptySet()).map { it.name })
    }

    @Test
    fun manyCategoriesAndLargeCategoryKeptEntirelyInProviderOrder() = runTest {
        // Gros fournisseur : des centaines de catégories, des milliers de films dans une seule : rien n'est tronqué.
        source.categories = (1..400).map { MovieCategory("c$it", "Categorie $it") }
        source.items = mapOf("c7" to (1..5000).map { movie("m$it", "c7", "Film $it") })
        val r = repo()
        assertEquals((1..400).map { "c$it" }, r.categories(emptySet()).map { it.id })
        assertEquals((1..5000).map { "m$it" }, r.moviesOf("c7", emptySet()).map { it.id })
        // Relu depuis le cache (frais) : même contenu, aucune nouvelle demande, jamais de liste globale.
        assertEquals(5000, r.moviesOf("c7", emptySet()).size)
        assertEquals(listOf("cats", "cat:c7"), source.calls)
    }

    @Test
    fun infoFreshForSixHoursAndRoundTrips() = runTest {
        val r = repo()
        assertEquals(source.info, r.info("10"))
        clock.now += 5.hours
        assertEquals(source.info, r.info("10"))
        assertEquals(listOf("info:10"), source.calls)
        clock.now += 2.hours
        r.info("10")
        assertEquals(listOf("info:10", "info:10"), source.calls)
    }

    @Test
    fun staleInfoReplacedByNewFetch() = runTest {
        val r = repo()
        r.info("10")
        clock.now += 7.hours
        source.info = MovieInfo(movie("10", "1", "Alpha 2"))
        assertEquals("Alpha 2", r.info("10")?.summary?.name)
    }

    @Test
    fun blankMovieIdIsNeverRequested() = runTest {
        assertNull(repo().info("  "))
        assertEquals(emptyList(), source.calls)
    }

    @Test
    fun staleCacheServedWhenProviderFails() = runTest {
        val r = repo()
        val cats = r.categories(emptySet())
        val items = r.moviesOf("1", emptySet())
        val info = r.info("10")
        clock.now += 100.hours
        for (failure in listOf(XtreamError.Unreachable, XtreamError.Unexpected(200), XtreamError.Refused)) {
            source.error = failure
            assertEquals(cats, r.categories(emptySet()))
            assertEquals(items, r.moviesOf("1", emptySet()))
            assertEquals(info, r.info("10"))
        }
    }

    @Test
    fun errorWithoutCachePropagates() = runTest {
        val r = repo()
        source.error = XtreamError.Unreachable
        assertFailsWith<XtreamError.Unreachable> { r.categories(emptySet()) }
        assertFailsWith<XtreamError.Unreachable> { r.moviesOf("1", emptySet()) }
        assertFailsWith<XtreamError.Unreachable> { r.info("10") }
    }

    @Test
    fun cancellationIsRethrownNotSwallowedByStaleCache() = runTest {
        val r = repo()
        r.categories(emptySet())
        r.moviesOf("1", emptySet())
        r.info("10")
        clock.now += 100.hours
        source.error = CancellationException("annule")
        assertFailsWith<CancellationException> { r.categories(emptySet()) }
        assertFailsWith<CancellationException> { r.moviesOf("1", emptySet()) }
        assertFailsWith<CancellationException> { r.info("10") }
    }

    @Test
    fun unknownMovieGivesNull() = runTest {
        source.info = null
        assertNull(repo().info("404"))
    }

    @Test
    fun successfulRefreshReplacesPreviousItems() = runTest {
        val r = repo()
        r.moviesOf("1", emptySet())
        clock.now += 73.hours
        source.items = mapOf("1" to listOf(movie("12", "1", "Delta")))
        assertEquals(listOf("Delta"), r.moviesOf("1", emptySet()).map { it.name })
    }

    @Test
    fun categoryRemovedByProviderLosesItsMovies() = runTest {
        val r = repo()
        r.categories(emptySet())
        r.moviesOf("2", emptySet())
        clock.now += 73.hours
        source.categories = listOf(MovieCategory("1", "Action"))
        assertEquals(listOf("1"), r.categories(emptySet()).map { it.id })
        assertEquals(emptyList(), r.cachedMovies())
    }

    @Test
    fun emptyCategoriesAnswerKeepsGoodCache() = runTest {
        val (db, r) = dbAndRepo()
        val good = r.categories(emptySet())
        val stamp = db.moviesQueries.selectMovieFetchedAt("cats").executeAsOne()
        clock.now += 73.hours
        source.categories = emptyList()
        assertEquals(good, r.categories(emptySet()))
        assertEquals(stamp, db.moviesQueries.selectMovieFetchedAt("cats").executeAsOne())
    }

    @Test
    fun emptyMoviesAnswerKeepsGoodCache() = runTest {
        val (db, r) = dbAndRepo()
        val good = r.moviesOf("1", emptySet())
        val stamp = db.moviesQueries.selectMovieFetchedAt("cat:1").executeAsOne()
        clock.now += 73.hours
        source.items = mapOf("1" to emptyList())
        assertEquals(good, r.moviesOf("1", emptySet()))
        assertEquals(stamp, db.moviesQueries.selectMovieFetchedAt("cat:1").executeAsOne())
    }

    @Test
    fun nullInfoAnswerKeepsGoodCache() = runTest {
        val (db, r) = dbAndRepo()
        val good = r.info("10")
        val stamp = db.moviesQueries.selectMovieFetchedAt("info:10").executeAsOne()
        clock.now += 7.hours
        source.info = null
        assertEquals(good, r.info("10"))
        assertEquals(stamp, db.moviesQueries.selectMovieFetchedAt("info:10").executeAsOne())
    }

    @Test
    fun emptyAnswerIsAcceptedWhenNothingWasCached() = runTest {
        val r = repo()
        source.categories = emptyList()
        assertEquals(emptyList(), r.categories(emptySet()))
        source.items = mapOf("1" to emptyList())
        assertEquals(emptyList(), r.moviesOf("1", emptySet()))
    }

    /** De bout en bout avec le vrai client Xtream : aucune réponse vide ou mal formée ne remplace un cache valide. */
    @Test
    fun emptyOrMalformedProviderAnswersNeverOverwriteAValidCache() = runBlocking {
        // runBlocking : le délai d'attente du client Xtream ne doit pas courir en temps virtuel.
        var body = ""
        val seen = mutableListOf<String>()
        val engine = MockEngine { req ->
            seen += req.url.parameters["action"].orEmpty() + ":" + req.url.parameters["category_id"].orEmpty()
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val api = XtreamApi(HttpClient(engine), XtreamCreds("http://panel.test:8080", "alice-user", "s3cret-pass"))
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CatalogDatabase.Schema.create(driver)
        val r = MovieRepository(CatalogDatabase(driver), MovieSource.of(api), clock)

        body = """[{"category_id":"501","category_name":"Action"}]"""
        val cats = r.categories(emptySet())
        body = """[{"stream_id":7001,"name":"Film Exemple Un","added":"1700000000","container_extension":"mp4"}]"""
        val items = r.moviesOf("501", emptySet())
        body = """{"info":{"name":"Film Exemple Un","plot":"Texte"},"movie_data":{"stream_id":7001,"container_extension":"mp4"}}"""
        val info = r.info("7001")
        assertEquals(listOf("Film Exemple Un"), items.map { it.name })
        assertEquals("Texte", info?.summary?.plot)

        val bad = listOf("{}", "[]", "", "null", """{"user_info":{"auth":0}}""", "<html>Erreur</html>", """[{"stream_id":""", "[\"x\",3]", """{"info":[],"movie_data":[]}""")
        for (answer in bad) {
            clock.now += 73.hours
            body = answer
            assertEquals(cats, r.categories(emptySet()), answer)
            assertEquals(items, r.moviesOf("501", emptySet()), answer)
            assertEquals(info, r.info("7001"), answer)
        }
        // Chaque liste de films demandée portait sa catégorie.
        assertTrue(seen.filter { it.startsWith("get_vod_streams") }.all { it == "get_vod_streams:501" }, seen.toString())
        assertTrue(seen.count { it.startsWith("get_vod_streams") } > 1)
    }

    @Test
    fun nestedErrorDetailsPreserveWarmCacheAndDoNotRenewFreshness() = runBlocking {
        var body = """{"info":{"name":"Film valide","plot":"Résumé"}}"""
        var requests = 0
        val api = XtreamApi(HttpClient(MockEngine {
            requests++
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }), XtreamCreds("http://panel.test", "fixture", "fixture"))
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CatalogDatabase.Schema.create(driver)
        val db = CatalogDatabase(driver)
        val repo = MovieRepository(db, MovieSource.of(api), clock)
        val valid = repo.info("42")
        val stamp = db.moviesQueries.selectMovieFetchedAt("info:42").executeAsOne()
        clock.now += 7.hours
        for (invalid in listOf(
            """{"info":{"error":"temporarily unavailable"}}""",
            """{"movie_data":{"unknown":"value"}}""",
        )) {
            body = invalid
            assertEquals(valid, repo.info("42"), invalid)
            assertEquals(stamp, db.moviesQueries.selectMovieFetchedAt("info:42").executeAsOne(), invalid)
        }
        assertEquals(3, requests) // Les erreurs ne rendent pas le cache frais : le second appel réessaie.
        driver.close()
    }

    private fun withoutCategory(i: MovieInfo) = i.copy(summary = i.summary.copy(categoryId = ""))

    @Test
    fun infoFallsBackToCategoryOfCachedMovie() = runTest {
        val r = repo()
        r.moviesOf("1", emptySet())
        source.info = withoutCategory(source.info!!)
        assertEquals("1", r.info("10")?.summary?.categoryId)
    }

    @Test
    fun infoUsesCategoryGivenByCaller() = runTest {
        val r = repo()
        source.info = withoutCategory(source.info!!)
        assertEquals("2", r.info("10", categoryId = "2")?.summary?.categoryId)
    }

    @Test
    fun infoNeverOverwritesKnownCategoryWithEmpty() = runTest {
        val r = repo()
        source.info = withoutCategory(source.info!!)
        r.info("10", categoryId = "2")
        clock.now += 7.hours
        assertEquals("2", r.info("10")?.summary?.categoryId)
    }

    @Test
    fun infoIsCompletedByTheListedMovie() = runTest {
        // Fiche pauvre (ni nom, ni affiche, ni date, extension par défaut) : la liste déjà en cache complète.
        source.items = mapOf("1" to listOf(movie("10", "1", "Alpha", addedAt = NOW_S - 50, ext = "mp4")))
        val r = repo()
        r.moviesOf("1", emptySet())
        source.info = MovieInfo(MovieSummary("10", "", UNTITLED_MOVIE, null, null, null, "Long resume", null, null, "mkv", 7000))
        val s = r.info("10")!!.summary
        assertEquals("Alpha", s.name)
        assertEquals("http://img.test/10.jpg", s.cover)
        assertEquals("2020", s.year)
        assertEquals("Long resume", s.plot) // la fiche gagne quand elle sait
        assertEquals(7000, s.durationS)
        assertEquals(NOW_S - 50, s.addedAt)
        assertEquals("mp4", s.extension) // l'extension de la liste compose l'adresse de lecture
        assertEquals("1", s.categoryId)
    }

    @Test
    fun failingStoreKeepsOldRowsAndOldStamp() = runTest {
        val inner = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CatalogDatabase.Schema.create(inner)
        val driver = StoreFailingDriver(inner)
        val db = CatalogDatabase(driver)
        val r = MovieRepository(db, source, clock)
        val old = r.moviesOf("1", emptySet())
        val stamp = db.moviesQueries.selectMovieFetchedAt("cat:1").executeAsOne()
        clock.now += 73.hours
        source.items = mapOf("1" to listOf(movie("12", "1", "Delta")))
        driver.armed = true
        assertFailsWith<IllegalStateException> { r.moviesOf("1", emptySet()) }
        driver.armed = false
        assertEquals(stamp, db.moviesQueries.selectMovieFetchedAt("cat:1").executeAsOne())
        source.error = XtreamError.Unreachable
        assertEquals(old, r.moviesOf("1", emptySet()))
    }

    @Test
    fun detailCacheKeepsAtMost300NewestMovies() = runTest {
        val (db, r) = dbAndRepo()
        for (i in 1..305) {
            source.info = MovieInfo(movie("m$i", "1", "Film $i"))
            r.info("m$i")
            clock.now += 1.hours
        }
        assertEquals(300L, db.moviesQueries.countMovieDetails().executeAsOne())
        assertNull(db.moviesQueries.selectMovieFetchedAt("info:m1").executeAsOneOrNull())
        assertNotEquals(null, db.moviesQueries.selectMovieFetchedAt("info:m305").executeAsOneOrNull())
    }

    @Test
    fun cachedCategoryReadsTheCacheWithoutAnyNetworkCall() = runTest {
        val r = repo()
        assertNull(r.cachedCategory("10"))
        r.moviesOf("1", emptySet())
        source.calls.clear()
        assertEquals("1", r.cachedCategory("10"))
        assertNull(r.cachedCategory("999"))
        // Une fiche connue sans la liste : la catégorie de la fiche sert.
        val r2 = repo()
        source.info = withoutCategory(source.info!!)
        r2.info("10", "7")
        source.calls.clear()
        assertEquals("7", r2.cachedCategory("10"))
        assertTrue(source.calls.isEmpty())
    }

    @Test
    fun cachedMoviesReadsOnlyWhatWasAlreadyLoaded() = runTest {
        val r = repo()
        assertEquals(emptyList(), r.cachedMovies())
        r.moviesOf("1", emptySet())
        r.moviesOf("2", emptySet())
        source.calls.clear()
        assertEquals(setOf("10", "11", "20"), r.cachedMovies().map { it.id }.toSet())
        assertEquals(listOf("20"), r.cachedMovies(hidden = setOf("1")).map { it.id })
        assertTrue(source.calls.isEmpty())
    }

    @Test
    fun recentlyAddedSortsByValidDateOnlyWithinLoadedCategories() = runTest {
        source.items = mapOf(
            "1" to listOf(
                movie("a", "1", "Ancien", addedAt = NOW_S - 9000),
                movie("b", "1", "Sans date", addedAt = null),
                movie("c", "1", "Zero", addedAt = 0),
                movie("d", "1", "Futur lointain", addedAt = NOW_S + 400L * 86_400),
                movie("e", "1", "Neuf", addedAt = NOW_S - 10),
            ),
            "2" to listOf(movie("f", "2", "Milieu", addedAt = NOW_S - 500), movie("e", "2", "Neuf", addedAt = NOW_S - 10)),
            "3" to listOf(movie("g", "3", "Jamais chargee", addedAt = NOW_S - 1)),
        )
        val r = repo()
        r.moviesOf("1", emptySet())
        r.moviesOf("2", emptySet())
        source.calls.clear()
        // Date absente, nulle ou impossible (au-delà de demain) : pas une nouveauté. Un film listé deux fois ne compte qu'une fois.
        assertEquals(listOf("e", "f", "a"), r.recentlyAdded(emptySet(), 10).map { it.id })
        assertEquals(listOf("e", "f"), r.recentlyAdded(emptySet(), 2).map { it.id })
        // Masqué gagne ; la copie visible d'un film présent dans deux catégories reste.
        assertEquals(listOf("e" to "2", "f" to "2"), r.recentlyAdded(setOf("1"), 10).map { it.id to it.categoryId })
        assertEquals(emptyList(), r.recentlyAdded(emptySet(), 0))
        assertTrue(source.calls.isEmpty())
    }

    @Test
    fun recentlyAddedStaysBoundedOnALargeCacheWithManyHiddenRows() = runTest {
        source.items = mapOf(
            "1" to (1..3000).map { movie("h$it", "1", "Masque $it", addedAt = NOW_S - it) },
            "2" to (1..50).map { movie("v$it", "2", "Visible $it", addedAt = NOW_S - 100_000 - it) },
        )
        val r = repo()
        r.moviesOf("1", emptySet())
        r.moviesOf("2", emptySet())
        // Les 3000 plus récents sont masqués : on trouve quand même les 20 premiers visibles, dans l'ordre.
        assertEquals((1..20).map { "v$it" }, r.recentlyAdded(setOf("1"), 20).map { it.id })
    }

    private fun dump(d: SqlDriver): List<String> {
        val out = mutableListOf<String>()
        d.executeQuery(null, "SELECT type, name, tbl_name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name", { c ->
            while (c.next().value) {
                val sql = (c.getString(3) ?: "").replace(Regex("\\s*([(),])\\s*"), "$1").replace(Regex("\\s+"), " ").replace(" IF NOT EXISTS", "").trim()
                out += "${c.getString(0)}|${c.getString(1)}|${c.getString(2)}|$sql"
            }
            QueryResult.Unit
        }, 0)
        return out
    }

    /** Base telle que la 0.4.1 la laisse sur la télé : schéma version 2 (direct + séries), avec des données. */
    private fun version2Database(): SqlDriver {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "CREATE TABLE live_category (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, position INTEGER NOT NULL)", 0)
        driver.execute(null, "CREATE TABLE live_stream (id TEXT PRIMARY KEY NOT NULL, category_id TEXT NOT NULL, name TEXT NOT NULL, icon TEXT, epg_channel_id TEXT, number INTEGER, position INTEGER NOT NULL)", 0)
        driver.execute(null, "CREATE INDEX live_stream_category ON live_stream(category_id)", 0)
        CatalogDatabase.Schema.migrate(driver, 1, 2)
        driver.execute(null, "INSERT INTO live_category(id, name, position) VALUES ('1', 'Sport', 0)", 0)
        driver.execute(null, "INSERT INTO live_stream(id, category_id, name, icon, epg_channel_id, number, position) VALUES ('100', '1', 'Chaine Exemple', NULL, NULL, 4, 0)", 0)
        driver.execute(null, "INSERT INTO series_category(id, name, position) VALUES ('5', 'Drames', 0)", 0)
        driver.execute(null, "INSERT INTO series_item(id, category_id, name, cover, year, genre, plot, rating, position) VALUES ('55', '5', 'Serie Exemple', NULL, '2020', NULL, NULL, NULL, 0)", 0)
        driver.execute(null, "INSERT INTO series_detail(id, category_id, name, cover, year, genre, plot, rating) VALUES ('55', '5', 'Serie Exemple', NULL, '2020', NULL, NULL, NULL)", 0)
        driver.execute(null, "INSERT INTO series_season(series_id, number, name, position) VALUES ('55', 1, 'Saison 1', 0)", 0)
        driver.execute(null, "INSERT INTO series_episode(id, series_id, season, number, title, duration_s, extension, cover, position) VALUES ('901', '55', 1, 1, 'Pilote', 2580, 'mkv', NULL, 0)", 0)
        driver.execute(null, "INSERT INTO series_fetch(key, fetched_at) VALUES ('cats', 1700000000000), ('cat:5', 1700000000000), ('info:55', 1700000000000)", 0)
        return driver
    }

    @Test
    fun version2CacheUpgradesToMovieTablesAndKeepsLiveAndSeries() = runTest {
        val driver = version2Database()
        CatalogDatabase.Schema.migrate(driver, 2, CatalogDatabase.Schema.version)
        assertEquals(5L, CatalogDatabase.Schema.version)
        val db = CatalogDatabase(driver)
        // Direct et séries intacts, dates de fraîcheur comprises.
        assertEquals(listOf("Sport"), db.catalogQueries.selectCategories().executeAsList().map { it.name })
        assertEquals(1, db.catalogQueries.selectStreams("1").executeAsList().size)
        assertEquals(listOf("Drames"), db.seriesQueries.selectSeriesCategories().executeAsList().map { it.name })
        assertEquals(listOf("Serie Exemple"), db.seriesQueries.selectSeriesItems("5").executeAsList().map { it.name })
        assertEquals("Serie Exemple", db.seriesQueries.selectSeriesDetail("55").executeAsOne().name)
        assertEquals(listOf("Saison 1"), db.seriesQueries.selectSeriesSeasons("55").executeAsList().map { it.name })
        assertEquals(listOf("Pilote"), db.seriesQueries.selectSeriesEpisodes("55").executeAsList().map { it.title })
        assertEquals(1_700_000_000_000L, db.seriesQueries.selectFetchedAt("info:55").executeAsOne())
        // Le cache des films fonctionne sur la base migrée.
        val r = MovieRepository(db, source, clock)
        assertEquals(2, r.categories(emptySet()).size)
        assertEquals(listOf("Alpha", "Beta"), r.moviesOf("1", emptySet()).map { it.name })
        assertEquals("Alpha", r.info("10")?.summary?.name)
    }

    @Test
    fun fichesAlreadyCachedStayReadableOfflineAfterTheQualityUpgrade() = runTest {
        // Télé en schéma 4 (0.33) avec une fiche gardée ; mise à jour vers 5 puis fournisseur injoignable.
        val driver = version2Database()
        CatalogDatabase.Schema.migrate(driver, 2, 4)
        driver.execute(null, "INSERT INTO movie_detail(id, category_id, name, cover, year, genre, plot, rating, added_at, extension, duration_s, backdrop) VALUES ('10', '1', 'Alpha', NULL, NULL, NULL, NULL, NULL, NULL, 'mkv', NULL, NULL)", 0)
        driver.execute(null, "INSERT INTO movie_fetch(key, fetched_at) VALUES ('info:10', " + clock.now().toEpochMilliseconds() + ")", 0)
        CatalogDatabase.Schema.migrate(driver, 4, 5)
        val down = object : MovieSource by source { override suspend fun movieInfo(movieId: String): MovieInfo? = throw java.io.IOException("hors ligne") }
        assertEquals("Alpha", MovieRepository(CatalogDatabase(driver), down, clock).info("10")?.summary?.name)
        // En ligne : la fiche est relue aussitôt (date mise à 0), pour sa qualité.
        assertEquals("Alpha", MovieRepository(CatalogDatabase(driver), source, clock).info("10")?.summary?.name)
        assertTrue(CatalogDatabase(driver).moviesQueries.selectMovieFetchedAt("info:10").executeAsOne() > 0)
    }

    @Test
    fun migratedVersion2SchemaMatchesFreshSchema() {
        val fresh = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CatalogDatabase.Schema.create(fresh)
        val old = version2Database()
        CatalogDatabase.Schema.migrate(old, 2, CatalogDatabase.Schema.version)
        val a = dump(fresh)
        assertTrue(a.any { it.contains("movie_detail") })
        assertTrue(a.any { it.contains("movie_item_id") })
        assertTrue(a.any { it.contains("series_episode") })
        assertEquals(a, dump(old))
    }

    @Test
    fun migrationIsHarmlessWhenRunTwice() {
        // Mise à jour interrompue puis relancée : les tables déjà créées et leurs données restent.
        val driver = version2Database()
        CatalogDatabase.Schema.migrate(driver, 2, 3)
        driver.execute(null, "INSERT INTO movie_category(id, name, position) VALUES ('1', 'Action', 0)", 0)
        CatalogDatabase.Schema.migrate(driver, 2, 3)
        assertEquals(listOf("Action"), CatalogDatabase(driver).moviesQueries.selectMovieCategories().executeAsList().map { it.name })
    }
}
