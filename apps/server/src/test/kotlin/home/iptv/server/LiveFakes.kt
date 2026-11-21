package home.iptv.server

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import home.iptv.shared.catalog.CatalogDatabase
import home.iptv.shared.catalog.CatalogRepository
import home.iptv.shared.catalog.LiveRefresher
import home.iptv.shared.catalog.LiveSource
import home.iptv.shared.sync.HiddenItem
import home.iptv.shared.sync.PairingState
import home.iptv.shared.sync.Profile
import home.iptv.shared.sync.ProfilePrefs
import home.iptv.shared.sync.PublishedCategory
import home.iptv.shared.sync.PublishedChannel
import home.iptv.shared.sync.SyncClient
import home.iptv.shared.xtream.LiveCategory
import home.iptv.shared.xtream.LiveStream
import home.iptv.shared.xtream.UserInfo
import home.iptv.shared.xtream.XtreamCreds
import java.io.IOException

/** Fausse source du fournisseur : jamais de réseau. */
class FakeLiveSource(
    var categories: List<LiveCategory> = listOf(
        LiveCategory("1", "FR| SPORT"),
        LiveCategory("2", "FR| CINEMA"),
        LiveCategory("3", "FR| ADULTE"),
    ),
    var fail: Boolean = false,
) : LiveSource {
    var categoryCalls = 0
    val streamCalls = mutableListOf<String>()
    override suspend fun liveCategories(): List<LiveCategory> {
        categoryCalls++
        if (fail) throw IOException("fournisseur coupé")
        return categories
    }
    override suspend fun liveStreams(categoryId: String): List<LiveStream> {
        streamCalls += categoryId
        return listOf(
            LiveStream("${categoryId}0", "FR: Chaine $categoryId HD", categoryId, "http://icones.example/a.png", null, 1),
            LiveStream("${categoryId}1", "Chaine $categoryId FHD", categoryId, null, "epg$categoryId", null),
        )
    }
}

/** Faux SyncClient : le Direct ne lit que le masquage, la révision et les publications. */
class FakeLiveSync(
    var hidden: List<HiddenItem> = emptyList(),
    var revision: Long = 0,
    var creds: XtreamCreds = XtreamCreds("http://fournisseur.example", "alice", "secret"),
) : SyncClient {
    val publishedCategories = mutableListOf<List<String>>()
    override suspend fun ensureSignedIn() {}
    override suspend fun currentHousehold(): String? = "h1"
    override suspend fun createPairingCode(deviceName: String): PairingState.Waiting = error("inutile")
    override suspend fun credentials(householdId: String): XtreamCreds? = creds
    override suspend fun profiles(householdId: String): List<Profile> = emptyList()
    override suspend fun globalHiddenCategories(householdId: String): List<HiddenItem> = hidden
    override suspend fun publishLiveCategories(householdId: String, cats: List<PublishedCategory>): Map<String, String?> {
        publishedCategories += cats.map { it.id }
        return emptyMap()
    }
    override suspend fun publishLiveChannels(
        householdId: String, categoryId: String, fingerprint: String, chunk: Int, isLast: Boolean, channels: List<PublishedChannel>,
    ) {}
    override suspend fun profilePrefs(profileId: String): ProfilePrefs = error("inutile")
    override suspend fun prefsRevision(householdId: String): Long = revision
    override suspend fun publishSeriesCategories(householdId: String, cats: List<PublishedCategory>) {}
}

fun memoryRepo(): CatalogRepository {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    CatalogDatabase.Schema.create(driver)
    return CatalogRepository(CatalogDatabase(driver))
}

fun fakeRefresher(sync: SyncClient, repo: CatalogRepository, source: LiveSource) =
    LiveRefresher(sync, repo, { "h1" }, { UserInfo(true, 1, listOf("ts")) }, { source })
