package home.iptv.server

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import home.iptv.shared.catalog.CatalogDatabase
import home.iptv.shared.home.MovieCategoryReply
import home.iptv.shared.home.SeriesCategoryReply
import home.iptv.shared.home.SnapMovieInfo
import home.iptv.shared.home.SnapSeriesInfo
import home.iptv.shared.home.VersionsReply
import home.iptv.shared.home.VersionsRequest
import home.iptv.shared.home.VodManifest
import home.iptv.shared.home.accountFingerprint
import home.iptv.shared.home.toVersionIndex
import home.iptv.shared.movies.MovieRepository
import home.iptv.shared.movies.MovieSource
import home.iptv.shared.series.SeriesRepository
import home.iptv.shared.series.SeriesSource
import home.iptv.shared.sync.HiddenItem
import home.iptv.shared.sync.HouseholdSettings
import home.iptv.shared.sync.PairingState
import home.iptv.shared.sync.Profile
import home.iptv.shared.sync.ProfilePrefs
import home.iptv.shared.sync.PublishedCategory
import home.iptv.shared.sync.PublishedChannel
import home.iptv.shared.sync.SyncClient
import home.iptv.shared.xtream.Episode
import home.iptv.shared.xtream.MediaTech
import home.iptv.shared.xtream.MovieCategory
import home.iptv.shared.xtream.MovieInfo
import home.iptv.shared.xtream.MovieSummary
import home.iptv.shared.xtream.Season
import home.iptv.shared.xtream.SeriesCategory
import home.iptv.shared.xtream.SeriesInfo
import home.iptv.shared.xtream.SeriesSummary
import home.iptv.shared.xtream.XtreamCreds
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class RouteVodSync(
    var hidden: List<HiddenItem> = listOf(HiddenItem("vod_category", "m3"), HiddenItem("series_category", "s3")),
    var splits: List<String> = emptyList(),
) : SyncClient {
    val creds = XtreamCreds("http://fournisseur.example", "alice", "secret")
    override suspend fun ensureSignedIn() {}
    override suspend fun currentHousehold(): String? = "h1"
    override suspend fun createPairingCode(deviceName: String): PairingState.Waiting = error("inutile")
    override suspend fun credentials(householdId: String): XtreamCreds? = creds
    override suspend fun profiles(householdId: String): List<Profile> = emptyList()
    override suspend fun globalHiddenCategories(householdId: String): List<HiddenItem> = hidden
    override suspend fun householdSettings(householdId: String): HouseholdSettings = HouseholdSettings(versionSplits = splits)
    override suspend fun publishLiveCategories(householdId: String, cats: List<PublishedCategory>): Map<String, String?> = emptyMap()
    override suspend fun publishLiveChannels(
        householdId: String, categoryId: String, fingerprint: String, chunk: Int, isLast: Boolean, channels: List<PublishedChannel>,
    ) {}
    override suspend fun profilePrefs(profileId: String): ProfilePrefs = error("inutile")
    override suspend fun prefsRevision(householdId: String): Long = 0
    override suspend fun publishSeriesCategories(householdId: String, cats: List<PublishedCategory>) {}
}

class VodRoutesTest {
    private val checker = TokenChecker(check = { t -> if (t == "bon") "tele" else null }, clockMs = { 0L })

    private fun setup(
        populate: Boolean = true,
        failInfo: Boolean = false,
    ): Triple<VodJob, InfoCache, RouteVodSync> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CatalogDatabase.Schema.create(driver)
        val db = CatalogDatabase(driver)
        val sync = RouteVodSync()
        val movieSource = object : MovieSource {
            override suspend fun movieCategories() = listOf(
                MovieCategory("m1", "FR - Action"),
                MovieCategory("m2", "QC - Action"),
                MovieCategory("m3", "ADULTE"),
            )
            override suspend fun movies(categoryId: String) = when (categoryId) {
                "m1" -> listOf(MovieSummary("101", "m1", "FR - Dune (2021)", "http://img.example/1.jpg", "2021", "SF", "Plot", "8.2", 1_700_000_000L, "mkv", 9300, null))
                "m2" -> listOf(MovieSummary("102", "m2", "QC - Dune (2021)", "http://img.example/2.jpg", "2021", "SF", "Plot", "8.2", 1_700_000_000L, "mkv", 9300, null))
                else -> emptyList()
            }
            override suspend fun movieInfo(movieId: String): MovieInfo? {
                if (failInfo) throw IOException("http://fournisseur.example/player_api.php?username=alice&password=secret")
                return when (movieId) {
                    "101" -> MovieInfo(
                        MovieSummary("101", "m1", "FR - Dune (2021)", "http://img.example/1.jpg", "2021", "SF", "Plot", "8.2", 1_700_000_000L, "mkv", 9300, null),
                        MediaTech(3840, 2160, "HDR10", "hevc", "eac3", 6),
                    )
                    "999" -> MovieInfo(
                        MovieSummary("999", "m3", "Interdit", null, null, null, null, null, null, "mkv", null, null),
                    )
                    else -> null
                }
            }
        }
        val seriesSource = object : SeriesSource {
            override suspend fun seriesCategories() = listOf(SeriesCategory("s1", "FRANCE SÉRIES"), SeriesCategory("s3", "ADULTE"))
            override suspend fun series(categoryId: String) = when (categoryId) {
                "s1" -> listOf(SeriesSummary("201", "s1", "FR - Severance", "http://img.example/s1.jpg", "2022", "SF", "Plot", "8.7", null))
                else -> emptyList()
            }
            override suspend fun seriesInfo(seriesId: String): SeriesInfo? {
                if (failInfo) throw IOException("http://fournisseur.example/player_api.php?username=alice&password=secret")
                return if (seriesId == "201") {
                    SeriesInfo(
                        SeriesSummary("201", "s1", "FR - Severance", "http://img.example/s1.jpg", "2022", "SF", "Plot", "8.7", null),
                        listOf(Season(1, "Saison 1", listOf(Episode("e1", 1, 1, "Good News", 3000, "mkv", null)))),
                    )
                } else null
            }
        }
        val movies = MovieRepository(db, movieSource)
        val series = SeriesRepository(db, seriesSource)
        val dir = createTempDirectory("vodroute").toFile().also { it.deleteOnExit() }
        val vod = VodJob(
            movies = movies,
            series = series,
            sync = sync,
            publishMovieCategories = { _, _ -> },
            householdId = "h1",
            account = { accountFingerprint(sync.creds.server, sync.creds.username) },
            serverHost = { sync.creds.server },
            clockMs = { 1_000_000L },
            manifestFile = File(dir, "vod-manifests.json"),
            titlesStateFile = File(dir, "titles-published.json"),
        )
        if (populate) runBlocking { vod.runOnce() }
        val info = InfoCache(
            movies = movies,
            series = series,
            hiddenMovieCats = { vod.hiddenMovieCategories.value },
            hiddenSeriesCats = { vod.hiddenSeriesCategories.value },
        )
        return Triple(vod, info, sync)
    }

    private fun app(
        populate: Boolean = true,
        failInfo: Boolean = false,
        block: suspend ApplicationTestBuilder.(VodJob, RouteVodSync) -> Unit,
    ) = testApplication {
        val (vod, info, sync) = setup(populate, failInfo)
        application { homeModule(checker, "0.2.0") { vodRoutes(vod, info) } }
        block(vod, sync)
    }

    private suspend fun ApplicationTestBuilder.get(path: String, gzip: Boolean = false) = client.get(path) {
        header("Authorization", "Bearer bon")
        header("X-Home-Protocol", "1")
        if (gzip) header(HttpHeaders.AcceptEncoding, "gzip")
    }

    private suspend fun ApplicationTestBuilder.postJson(path: String, body: String = "") = client.post(path) {
        header("Authorization", "Bearer bon")
        header("X-Home-Protocol", "1")
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    @Test
    fun manifestsReturn503WhenEmptyAnd304WhenUnchanged() = app(populate = false) { vod, _ ->
        assertEquals(HttpStatusCode.ServiceUnavailable, get("/v1/movies/manifest").status)
        assertEquals(HttpStatusCode.ServiceUnavailable, get("/v1/series/manifest").status)
        // Tant que les catégories masquées globalement ne sont pas chargées, /info répond 503.
        assertEquals(HttpStatusCode.ServiceUnavailable, get("/v1/movies/info/101").status)
        assertEquals(HttpStatusCode.ServiceUnavailable, get("/v1/series/info/201").status)

        vod.runOnce()

        val mr = get("/v1/movies/manifest", gzip = true)
        assertEquals(HttpStatusCode.OK, mr.status)
        assertEquals("gzip", mr.headers[HttpHeaders.ContentEncoding])
        val mBody = java.util.zip.GZIPInputStream(mr.bodyAsBytes().inputStream()).readBytes().decodeToString()
        val m = snapshotJson.decodeFromString(VodManifest.serializer(), mBody)
        assertEquals(listOf("m1", "m2"), m.categories.map { it.id })
        assertEquals(HttpStatusCode.NotModified, get("/v1/movies/manifest?since=${m.rev}").status)

        val sr = get("/v1/series/manifest")
        assertEquals(HttpStatusCode.OK, sr.status)
        val s = snapshotJson.decodeFromString(VodManifest.serializer(), sr.bodyAsText())
        assertEquals(HttpStatusCode.NotModified, get("/v1/series/manifest?since=${s.rev}").status)
    }

    @Test
    fun categoriesReturn200304404400AndNoCredentials() = app { _, _ ->
        val r = get("/v1/movies/category/m1")
        assertEquals(HttpStatusCode.OK, r.status)
        val body = r.bodyAsText()
        for (interdit in listOf("alice", "secret", "username", "password", "fournisseur.example", "player_api")) {
            assertFalse(body.contains(interdit), interdit)
        }
        val parsed = snapshotJson.decodeFromString(MovieCategoryReply.serializer(), body)
        assertEquals(listOf("101"), parsed.items.map { it.id })
        assertEquals(HttpStatusCode.NotModified, get("/v1/movies/category/m1?since=${parsed.rev}").status)
        assertEquals(HttpStatusCode.NotFound, get("/v1/movies/category/m3").status)
        assertEquals(HttpStatusCode.NotFound, get("/v1/movies/category/inconnue").status)
        assertEquals(HttpStatusCode.BadRequest, get("/v1/movies/category/a%20b").status)

        val sr = get("/v1/series/category/s1")
        assertEquals(HttpStatusCode.OK, sr.status)
        val sParsed = snapshotJson.decodeFromString(SeriesCategoryReply.serializer(), sr.bodyAsText())
        assertEquals(listOf("201"), sParsed.items.map { it.id })
        assertEquals(HttpStatusCode.NotModified, get("/v1/series/category/s1?since=${sParsed.rev}").status)
        assertEquals(HttpStatusCode.NotFound, get("/v1/series/category/s3").status)
    }

    @Test
    fun infoRoutesServeDetailsAndHideForbiddenOrErrors() = app { _, _ ->
        val mr = get("/v1/movies/info/101")
        assertEquals(HttpStatusCode.OK, mr.status)
        val mInfo = snapshotJson.decodeFromString(SnapMovieInfo.serializer(), mr.bodyAsText())
        assertEquals("FR - Dune (2021)", mInfo.summary.name)
        assertEquals("HDR10", mInfo.tech?.hdr)

        assertEquals(HttpStatusCode.NotFound, get("/v1/movies/info/999").status) // catégorie masquée m3
        assertEquals(HttpStatusCode.NotFound, get("/v1/movies/info/40404").status)
        assertEquals(HttpStatusCode.BadRequest, get("/v1/movies/info/bad.id").status)

        val sr = get("/v1/series/info/201")
        assertEquals(HttpStatusCode.OK, sr.status)
        val sInfo = snapshotJson.decodeFromString(SnapSeriesInfo.serializer(), sr.bodyAsText())
        assertEquals(1, sInfo.seasons.first().episodes.size)
    }

    @Test
    fun infoProviderFailureIs502WithoutLeak() = app(failInfo = true) { _, _ ->
        val r = get("/v1/movies/info/101")
        assertEquals(HttpStatusCode.BadGateway, r.status)
        val body = r.bodyAsText()
        for (interdit in listOf("alice", "secret", "fournisseur")) assertFalse(body.contains(interdit), interdit)
    }

    @Test
    fun versionsLookupAndSplitInvalidation() = app { _, sync ->
        val req = snapshotJson.encodeToString(VersionsRequest.serializer(), VersionsRequest(listOf("101")))
        val r1 = postJson("/v1/versions/movies", req)
        assertEquals(HttpStatusCode.OK, r1.status)
        val idx1 = snapshotJson.decodeFromString(VersionsReply.serializer(), r1.bodyAsText()).toVersionIndex()
        assertEquals(setOf("101", "102"), idx1.versionsOf("101").map { it.id }.toSet())

        // Séparation manuelle + invalidation immédiate (Review Focus 5).
        sync.splits = listOf("movie:102")
        assertEquals(HttpStatusCode.NoContent, postJson("/v1/versions/invalidate").status)

        val r2 = postJson("/v1/versions/movies", req)
        assertEquals(HttpStatusCode.OK, r2.status)
        val idx2 = snapshotJson.decodeFromString(VersionsReply.serializer(), r2.bodyAsText()).toVersionIndex()
        assertEquals(listOf("101"), idx2.versionsOf("101").map { it.id })

        // Requête invalide ou trop longue -> 400.
        assertEquals(HttpStatusCode.BadRequest, postJson("/v1/versions/movies", "{pas json").status)
        val tooMany = snapshotJson.encodeToString(VersionsRequest.serializer(), VersionsRequest((1..2001).map { it.toString() }))
        assertEquals(HttpStatusCode.BadRequest, postJson("/v1/versions/movies", tooMany).status)
    }

    @Test
    fun routesRequireToken() = app { _, _ ->
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/movies/manifest").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/versions/movies").status)
    }
}
