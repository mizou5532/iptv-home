package home.iptv.shared.home

import home.iptv.shared.versions.Lang
import home.iptv.shared.versions.Version
import home.iptv.shared.versions.VersionTag
import home.iptv.shared.xtream.Episode
import home.iptv.shared.xtream.MediaTech
import home.iptv.shared.xtream.MovieInfo
import home.iptv.shared.xtream.MovieSummary
import home.iptv.shared.xtream.Season
import home.iptv.shared.xtream.SeriesInfo
import home.iptv.shared.xtream.SeriesSummary
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class HomeProtocolVodTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun vodManifestRoundTripAndRevStability() {
        val cats = listOf(
            SnapVodCategory("10", "FR - Action", 0, "aaa"),
            SnapVodCategory("20", "QC - Comédie", 1, "bbb"),
        )
        val hidden = listOf("99")
        val rev = vodManifestRev(cats, hidden)
        val manifest = VodManifest(rev, "acct1234", 5_000L, cats, hidden)

        val decoded = json.decodeFromString<VodManifest>(json.encodeToString(VodManifest.serializer(), manifest))
        assertEquals(manifest, decoded)
        assertEquals(64, rev.length)
        assertEquals(rev, vodManifestRev(cats, hidden))
        assertNotEquals(rev, vodManifestRev(cats.map { if (it.id == "10") it.copy(rev = "ccc") else it }, hidden))
        assertNotEquals(rev, vodManifestRev(cats, listOf("99", "100")))
    }

    @Test
    fun movieCategoryAndInfoRoundTrip() {
        val summary = MovieSummary(
            id = "501", categoryId = "10", name = "FR - Exemple (2024)", cover = "http://img/m.jpg",
            year = "2024", genre = "Action", plot = "Résumé", rating = "7.8",
            addedAt = 1_700_000_000L, extension = "mkv", durationS = 7200, backdrop = "http://img/bg.jpg",
        )
        val snap = summary.toSnapMovie(position = 3)
        assertEquals(summary, snap.toMovieSummary())

        val rev = movieCategoryRev(listOf(snap))
        assertEquals(64, rev.length)
        assertEquals(rev, movieCategoryRev(listOf(snap)))
        assertNotEquals(rev, movieCategoryRev(listOf(snap.copy(name = "Autre"))))

        val reply = MovieCategoryReply("10", rev, "acct1", listOf(snap))
        assertEquals(reply, json.decodeFromString<MovieCategoryReply>(json.encodeToString(MovieCategoryReply.serializer(), reply)))

        val info = MovieInfo(summary, MediaTech(3840, 2160, "HDR10", "hevc", "eac3", 6))
        val snapInfo = info.toSnapMovieInfo()
        val decodedInfo = json.decodeFromString<SnapMovieInfo>(json.encodeToString(SnapMovieInfo.serializer(), snapInfo))
        assertEquals(snapInfo, decodedInfo)
        assertEquals(info, decodedInfo.toMovieInfo())
    }

    @Test
    fun seriesCategoryAndInfoRoundTrip() {
        val summary = SeriesSummary(
            id = "801", categoryId = "20", name = "QC - Série Exemple", cover = "http://img/s.jpg",
            year = "2023", genre = "Drame", plot = "Histoire", rating = "8.5", backdrop = "http://img/sbg.jpg",
        )
        val snap = summary.toSnapSeries(position = 1)
        assertEquals(summary, snap.toSeriesSummary())

        val rev = seriesCategoryRev(listOf(snap))
        assertEquals(64, rev.length)
        assertEquals(rev, seriesCategoryRev(listOf(snap)))
        assertNotEquals(rev, seriesCategoryRev(listOf(snap.copy(year = "2024"))))

        val reply = SeriesCategoryReply("20", rev, "acct1", listOf(snap))
        assertEquals(reply, json.decodeFromString<SeriesCategoryReply>(json.encodeToString(SeriesCategoryReply.serializer(), reply)))

        val info = SeriesInfo(
            summary = summary,
            seasons = listOf(
                Season(1, "Saison 1", listOf(Episode("e1", 1, 1, "Pilote", 2700, "mp4", "http://img/e1.jpg"))),
                Season(2, "Saison 2", emptyList()),
            ),
        )
        val snapInfo = info.toSnapSeriesInfo()
        val decodedInfo = json.decodeFromString<SnapSeriesInfo>(json.encodeToString(SnapSeriesInfo.serializer(), snapInfo))
        assertEquals(snapInfo, decodedInfo)
        assertEquals(info, decodedInfo.toSeriesInfo())
    }

    @Test
    fun versionsRequestAndReplyRoundTrip() {
        val req = VersionsRequest(listOf("501", "502"))
        assertEquals(req, json.decodeFromString<VersionsRequest>(json.encodeToString(VersionsRequest.serializer(), req)))

        val v1 = Version("501", "10", "QC - Exemple (2024)", VersionTag(Lang.QC, uhd = true, hdr = true, source = null), "http://img/1.jpg", "2024")
        val v2 = Version("502", "11", "NF - Exemple (2024)", VersionTag(null, uhd = false, hdr = false, source = "Netflix"), null, "2024")
        val reply = VersionsReply(
            rev = "vrev1",
            groups = mapOf("exemple|2024" to listOf(v1.toSnapVersion(), v2.toSnapVersion())),
        )
        val decoded = json.decodeFromString<VersionsReply>(json.encodeToString(VersionsReply.serializer(), reply))
        assertEquals(reply, decoded)

        val index = decoded.toVersionIndex()
        assertEquals(listOf(v1, v2), index.versionsOf("501"))
        assertEquals("exemple|2024", index.keyOf("502"))
    }
}
