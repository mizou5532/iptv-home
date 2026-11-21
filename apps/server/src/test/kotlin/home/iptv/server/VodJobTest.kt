package home.iptv.server

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import home.iptv.shared.account.AccountProbe
import home.iptv.shared.account.ExtraAccountsCheck
import home.iptv.shared.catalog.CatalogDatabase
import home.iptv.shared.catalog.CatalogTitleRow
import home.iptv.shared.home.accountFingerprint
import home.iptv.shared.home.toVersionIndex
import home.iptv.shared.meta.MediaKind
import home.iptv.shared.movies.MovieRepository
import home.iptv.shared.movies.MovieSource
import home.iptv.shared.series.SeriesRepository
import home.iptv.shared.series.SeriesSource
import home.iptv.shared.sync.ExtraAccount
import home.iptv.shared.sync.HiddenItem
import home.iptv.shared.sync.HouseholdSettings
import home.iptv.shared.sync.PairingState
import home.iptv.shared.sync.Profile
import home.iptv.shared.sync.ProfilePrefs
import home.iptv.shared.sync.PublishedCategory
import home.iptv.shared.sync.PublishedChannel
import home.iptv.shared.sync.SyncClient
import home.iptv.shared.xtream.LiveCategory
import home.iptv.shared.xtream.LiveStream
import home.iptv.shared.xtream.MovieCategory
import home.iptv.shared.xtream.MovieInfo
import home.iptv.shared.xtream.MovieSummary
import home.iptv.shared.xtream.SeriesCategory
import home.iptv.shared.xtream.SeriesInfo
import home.iptv.shared.xtream.SeriesSummary
import home.iptv.shared.xtream.UserInfo
import home.iptv.shared.xtream.XtreamCreds
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

private class FakeVodSync(
    var hidden: List<HiddenItem> = emptyList(),
    var splits: Set<String> = emptySet(),
    var revision: Long = 0,
    var creds: XtreamCreds = XtreamCreds("http://fournisseur.example", "alice", "secret"),
    var extras: List<ExtraAccount> = emptyList(),
) : SyncClient {
    val publishedSeriesCats = mutableListOf<List<String>>()
    val publishedTitles = mutableListOf<Pair<String, List<CatalogTitleRow>>>()
    val reportedExtras = mutableListOf<Pair<String, Boolean>>()

    override suspend fun ensureSignedIn() {}
    override suspend fun currentHousehold(): String? = "h1"
    override suspend fun createPairingCode(deviceName: String): PairingState.Waiting = error("inutile")
    override suspend fun credentials(householdId: String): XtreamCreds? = creds
    override suspend fun profiles(householdId: String): List<Profile> = emptyList()
    override suspend fun globalHiddenCategories(householdId: String): List<HiddenItem> = hidden
    override suspend fun householdSettings(householdId: String): HouseholdSettings = HouseholdSettings(versionSplits = splits.toList())
    override suspend fun publishLiveCategories(householdId: String, cats: List<PublishedCategory>): Map<String, String?> = emptyMap()
    override suspend fun publishLiveChannels(
        householdId: String, categoryId: String, fingerprint: String, chunk: Int, isLast: Boolean, channels: List<PublishedChannel>,
    ) {}
    override suspend fun profilePrefs(profileId: String): ProfilePrefs = error("inutile")
    override suspend fun prefsRevision(householdId: String): Long = revision
    override suspend fun publishSeriesCategories(householdId: String, cats: List<PublishedCategory>) {
        publishedSeriesCats += cats.map { it.id }
    }
    override suspend fun publishCatalogTitles(householdId: String, kind: String, gen: Long, rows: List<CatalogTitleRow>, done: Boolean) {
        publishedTitles += kind to rows
    }
    override suspend fun extraAccounts(householdId: String): List<ExtraAccount> = extras
    override suspend fun reportExtraAccount(householdId: String, accountId: String, ok: Boolean, reason: String?, expiresAt: Long?) {
        reportedExtras += accountId to ok
    }
}

private class FakeMovieSource(
    var categories: List<MovieCategory> = listOf(
        MovieCategory("m1", "FR - Action"),
        MovieCategory("m2", "QC - Comédie"),
        MovieCategory("m3", "ADULTE"),
    ),
    var items: Map<String, List<MovieSummary>> = mapOf(
        "m1" to listOf(
            MovieSummary("101", "m1", "FR - Oppenheimer (2023)", "http://fournisseur.example/p1.jpg", "2023", "Drame", "Plot", "8.5", 1_700_000_000L, "mkv", 10800, null),
        ),
        "m2" to listOf(
            MovieSummary("102", "m2", "QC - Oppenheimer (2023)", "http://fournisseur.example/p2.jpg", "2023", "Drame", "Plot", "8.5", 1_700_000_000L, "mkv", 10800, null),
        ),
        "m3" to listOf(
            MovieSummary("103", "m3", "Interdit", null, null, null, null, null, null, "mkv", null, null),
        ),
    ),
) : MovieSource {
    val requestedCategories = mutableListOf<String>()
    override suspend fun movieCategories() = categories
    override suspend fun movies(categoryId: String): List<MovieSummary> {
        requestedCategories += categoryId
        return items[categoryId].orEmpty()
    }
    override suspend fun movieInfo(movieId: String): MovieInfo? = null
}

private class FakeSeriesSource(
    var categories: List<SeriesCategory> = listOf(
        SeriesCategory("s1", "FRANCE SÉRIES"),
        SeriesCategory("s2", "QUÉBEC SÉRIES"),
        SeriesCategory("s3", "ADULTE"),
    ),
    var items: Map<String, List<SeriesSummary>> = mapOf(
        "s1" to listOf(SeriesSummary("201", "s1", "FR - Breaking Bad", null, "2008", "Drame", "Plot", "9.5", null)),
        "s2" to listOf(SeriesSummary("202", "s2", "QC - Breaking Bad", null, "2008", "Drame", "Plot", "9.5", null)),
        "s3" to listOf(SeriesSummary("203", "s3", "Interdit Série", null, null, null, null, null, null)),
    ),
) : SeriesSource {
    val requestedCategories = mutableListOf<String>()
    override suspend fun seriesCategories() = categories
    override suspend fun series(categoryId: String): List<SeriesSummary> {
        requestedCategories += categoryId
        return items[categoryId].orEmpty()
    }
    override suspend fun seriesInfo(seriesId: String): SeriesInfo? = null
}

class VodJobTest {
    private var nowMs = 1_000_000_000L
    private val clock = object : Clock {
        override fun now() = Instant.fromEpochMilliseconds(nowMs)
    }

    private fun newDb(): CatalogDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CatalogDatabase.Schema.create(driver)
        return CatalogDatabase(driver)
    }

    private fun createJob(
        db: CatalogDatabase = newDb(),
        sync: FakeVodSync = FakeVodSync(),
        movieSource: FakeMovieSource = FakeMovieSource(),
        seriesSource: FakeSeriesSource = FakeSeriesSource(),
        dir: File = createTempDirectory("vodjob").toFile().also { it.deleteOnExit() },
        publishedMovieCats: MutableList<List<String>> = mutableListOf(),
        extraCheck: ExtraAccountsCheck? = null,
    ): VodJob {
        val movies = MovieRepository(db, movieSource, clock)
        val series = SeriesRepository(db, seriesSource, clock)
        return VodJob(
            movies = movies,
            series = series,
            sync = sync,
            publishMovieCategories = { _, cats -> publishedMovieCats += cats.map { it.id } },
            householdId = "h1",
            account = { accountFingerprint(sync.creds.server, sync.creds.username) },
            serverHost = { sync.creds.server },
            clockMs = { nowMs },
            manifestFile = File(dir, "vod-manifests.json"),
            titlesStateFile = File(dir, "titles-published.json"),
            extraAccountsCheck = extraCheck,
        )
    }

    @Test
    fun hiddenVodAndSeriesCategoriesNeverRequested() = runTest {
        val sync = FakeVodSync(hidden = listOf(HiddenItem("vod_category", "m3"), HiddenItem("series_category", "s3")))
        val ms = FakeMovieSource()
        val ss = FakeSeriesSource()
        val job = createJob(sync = sync, movieSource = ms, seriesSource = ss)

        job.runOnce()

        assertEquals(listOf("m1", "m2"), ms.requestedCategories.sorted())
        assertEquals(listOf("s1", "s2"), ss.requestedCategories.sorted())
        assertFalse("m3" in ms.requestedCategories)
        assertFalse("s3" in ss.requestedCategories)
        assertNull(job.movieCategoryReply("m3"))
        assertNull(job.seriesCategoryReply("s3"))
        assertEquals(listOf("m3"), job.movieManifest.value?.hidden)
        assertEquals(listOf("s3"), job.seriesManifest.value?.hidden)
        assertEquals(listOf("m1", "m2"), job.movieManifest.value?.categories?.map { it.id })
        assertEquals(listOf("s1", "s2"), job.seriesManifest.value?.categories?.map { it.id })
    }

    @Test
    fun accountChangeWipesVodCache() = runTest {
        val db = newDb()
        val sync = FakeVodSync()
        val ms = FakeMovieSource()
        val ss = FakeSeriesSource()
        val job = createJob(db = db, sync = sync, movieSource = ms, seriesSource = ss)

        job.runOnce()
        val firstAcct = assertNotNull(job.movieManifest.value).account
        assertEquals(listOf("101"), job.movieCategoryReply("m1")?.items?.map { it.id })

        // Changement de compte IPTV avec d'autres films : l'ancien cache doit disparaître.
        sync.creds = XtreamCreds("http://fournisseur.example", "bob", "secret2")
        ms.categories = listOf(MovieCategory("m9", "FR - Nouveautés"))
        ms.items = mapOf("m9" to listOf(MovieSummary("901", "m9", "FR - Nouveau (2025)", null, "2025", null, null, null, null, "mkv", null, null)))
        ss.categories = listOf(SeriesCategory("s9", "FRANCE SÉRIES"))
        ss.items = mapOf("s9" to listOf(SeriesSummary("951", "s9", "FR - Nouvelle Série", null, "2025", null, null, null, null)))

        job.runOnce()
        val secondAcct = assertNotNull(job.movieManifest.value).account
        assertNotEquals(firstAcct, secondAcct)
        assertNull(job.movieCategoryReply("m1"))
        assertEquals(listOf("901"), job.movieCategoryReply("m9")?.items?.map { it.id })
        assertNull(job.seriesCategoryReply("s1"))
        assertEquals(listOf("951"), job.seriesCategoryReply("s9")?.items?.map { it.id })
    }

    @Test
    fun publishesCategoriesAndTitlesForIphone() = runTest {
        val sync = FakeVodSync(hidden = listOf(HiddenItem("vod_category", "m3"), HiddenItem("series_category", "s3")))
        val publishedMovieCats = mutableListOf<List<String>>()
        val job = createJob(sync = sync, publishedMovieCats = publishedMovieCats)

        job.runOnce()

        // Noms de toutes les catégories (masquées comprises pour permettre le démasquage sur iPhone).
        assertEquals(listOf(listOf("m1", "m2", "m3")), publishedMovieCats)
        assertEquals(listOf(listOf("s1", "s2", "s3")), sync.publishedSeriesCats)

        // Titres publiés : jamais ceux des catégories masquées.
        val movieIds = sync.publishedTitles.filter { it.first == "movie" }.flatMap { it.second }.map { it.id }.toSet()
        val seriesIds = sync.publishedTitles.filter { it.first == "series" }.flatMap { it.second }.map { it.id }.toSet()
        assertEquals(setOf("101", "102"), movieIds)
        assertEquals(setOf("201", "202"), seriesIds)
    }

    @Test
    fun versionsBuiltAndUpdatedOnSplitChange() = runTest {
        val sync = FakeVodSync(hidden = listOf(HiddenItem("vod_category", "m3"), HiddenItem("series_category", "s3")))
        val job = createJob(sync = sync)

        job.runOnce()

        val grouped = job.lookupVersions(MediaKind.MOVIE, listOf("101")).toVersionIndex()
        assertEquals(setOf("101", "102"), grouped.versionsOf("101").map { it.id }.toSet())

        // L'utilisateur clique « Ce n'est pas le même titre » sur 102 : séparation immédiate.
        sync.splits = setOf("movie:102")
        job.reloadVersionSplits()

        val afterSplit = job.lookupVersions(MediaKind.MOVIE, listOf("101", "102")).toVersionIndex()
        assertEquals(listOf("101"), afterSplit.versionsOf("101").map { it.id })
        assertEquals(listOf("102"), afterSplit.versionsOf("102").map { it.id })
    }

    @Test
    fun extraAccountsCheckedByServer() = runTest {
        val sync = FakeVodSync(
            extras = listOf(ExtraAccount("ex1", 1, "charlie", "pw", "a_verifier", null)),
        )
        val probe = object : AccountProbe {
            override suspend fun userInfo(creds: XtreamCreds) = UserInfo(true, 1, listOf("ts"), expiresAt = 2_000_000_000L, status = "Active")
            override suspend fun liveCategories(creds: XtreamCreds) = listOf(LiveCategory("1", "Sports"))
            override suspend fun liveStreams(creds: XtreamCreds, categoryId: String) = listOf(LiveStream("10", "TSN", "1", null, null, 1))
            override suspend fun movieCategoryIds(creds: XtreamCreds) = setOf("m1")
            override suspend fun seriesCategoryIds(creds: XtreamCreds) = setOf("s1")
        }
        val check = ExtraAccountsCheck(
            probe = probe,
            main = { sync.creds },
            accounts = { sync.extraAccounts("h1") },
            hiddenForEveryone = { emptySet() },
            report = { id, v -> sync.reportExtraAccount("h1", id, v.ok, v.reason, v.expiresAt) },
            nowS = { nowMs / 1000L },
        )
        val job = createJob(sync = sync, extraCheck = check)

        job.runOnce()

        assertEquals(listOf("ex1" to true), sync.reportedExtras)
    }

    @Test
    fun reloadsManifestsFromDiskOnRestart() = runTest {
        val db = newDb()
        val dir = createTempDirectory("vodjob-restart").toFile().also { it.deleteOnExit() }
        val sync = FakeVodSync()
        val job1 = createJob(db = db, sync = sync, dir = dir)
        job1.runOnce()
        val savedMovieRev = assertNotNull(job1.movieManifest.value).rev
        val savedSeriesRev = assertNotNull(job1.seriesManifest.value).rev

        // Nouveau VodJob sur le même disque : les manifestes et catégories sont servis aussitôt sans appel réseau.
        val job2 = createJob(db = db, sync = sync, dir = dir)
        assertEquals(savedMovieRev, job2.movieManifest.value?.rev)
        assertEquals(savedSeriesRev, job2.seriesManifest.value?.rev)
        assertEquals(listOf("101"), job2.movieCategoryReply("m1")?.items?.map { it.id })
        assertTrue(job2.lookupVersions(MediaKind.MOVIE, listOf("101")).groups.isNotEmpty())
    }
}
