package home.iptv.server

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import home.iptv.shared.catalog.CatalogDatabase
import home.iptv.shared.home.StreamStartRequest
import home.iptv.shared.movies.MovieRepository
import home.iptv.shared.movies.MovieSource
import home.iptv.shared.series.SeriesRepository
import home.iptv.shared.series.SeriesSource
import home.iptv.shared.sync.AccountClaim
import home.iptv.shared.sync.Occupant
import home.iptv.shared.xtream.MovieCategory
import home.iptv.shared.xtream.MovieInfo
import home.iptv.shared.xtream.MovieSummary
import home.iptv.shared.xtream.SeriesCategory
import home.iptv.shared.xtream.SeriesInfo
import home.iptv.shared.xtream.SeriesSummary
import home.iptv.shared.xtream.XtreamCreds
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StreamRelayTest {
    private fun emptyInfoCache(): InfoCache {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CatalogDatabase.Schema.create(driver)
        val db = CatalogDatabase(driver)
        val movies = MovieRepository(db, object : MovieSource {
            override suspend fun movieCategories(): List<MovieCategory> = emptyList()
            override suspend fun movies(categoryId: String): List<MovieSummary> = emptyList()
            override suspend fun movieInfo(movieId: String): MovieInfo? = null
        })
        val series = SeriesRepository(db, object : SeriesSource {
            override suspend fun seriesCategories(): List<SeriesCategory> = emptyList()
            override suspend fun series(categoryId: String): List<SeriesSummary> = emptyList()
            override suspend fun seriesInfo(seriesId: String): SeriesInfo? = null
        })
        return InfoCache(movies, series, { emptySet() }, { emptySet() })
    }

    @Test
    fun refuse_parametres_invalides_et_renvoie_busy_quand_tous_les_comptes_sont_pris() = runBlocking {
        val dir = Files.createTempDirectory("stream-relay-test").toFile()
        try {
            val sync = object : FakeLiveSync() {
                override suspend fun claimAccount(
                    householdId: String, profileId: String?, deviceName: String, label: String, deviceKey: String, takeover: String?,
                ): AccountClaim = AccountClaim.Busy(listOf(Occupant("TV Salon", "Maha", "TVA Sports", "lease-1")))
            }
            val relay = StreamRelay(sync, "h1", { XtreamCreds("http://example.com", "u", "p") }, emptyInfoCache(), dir)

            // Id invalide
            val bad = relay.start(StreamStartRequest(kind = "live", id = "../bad", deviceKey = "iphone-123456"))
            assertEquals("error", bad.status)

            // Compte occupé -> status = busy avec l'occupant
            val busy = relay.start(StreamStartRequest(kind = "live", id = "101", deviceKey = "iphone-123456", label = "RDS"))
            assertEquals("busy", busy.status)
            assertEquals(1, busy.occupants.size)
            assertEquals("TV Salon", busy.occupants[0].deviceName)
            assertEquals("Maha", busy.occupants[0].profileName)

            // Protection contre traversée de dossier sur readFile
            assertNull(relay.readFile("../../etc", "index.m3u8"))
            assertNull(relay.readFile("0123456789abcdef0123456789abcdef", "../secret.txt"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
