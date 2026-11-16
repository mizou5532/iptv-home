package home.iptv.shared.progress

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class ProgressQueueCodecTest {
    private val entry = QueuedProgress("p1", ProgressEntry("e1", null, null, null, 20, 100,
        Instant.parse("2026-11-15T10:00:00.123Z")), attempts = 2)

    @Test
    fun legacyOversizedQueueKeepsOnlyNewestThreeHundred() {
        val entries = (0..301).map { entry.copy(entry = entry.entry.copy(itemId = "e$it")) }
        val legacy = Json.encodeToString(ListSerializer(QueuedProgress.serializer()), entries)
        assertEquals(entries.takeLast(300), ProgressQueueCodec.decode(legacy).entries)
    }

    @Test
    fun encodedQueueNeverExceedsThreeHundred() {
        val entries = (0..301).map { entry.copy(entry = entry.entry.copy(itemId = "e$it")) }
        val encoded = ProgressQueueCodec.encode(entries)
        assertEquals(300, Json.decodeFromString(ListSerializer(QueuedProgress.serializer()), encoded).size)
    }

    @Test
    fun unreadableDocumentIsReturnedForBackup() {
        for (raw in listOf("[broken", "{}", "null")) {
            val decoded = ProgressQueueCodec.decode(raw)
            assertEquals(emptyList(), decoded.entries)
            assertEquals(raw, decoded.unreadable)
        }
    }

    @Test
    fun unknownKeysAndAbsentOptionalFieldsAreAccepted() {
        val raw = """[{"profileId":"p1","unknown":42,"entry":{"itemId":"e1","seriesId":null,"season":null,"episode":null,"positionS":20,"durationS":100,"clientUpdatedAt":"2026-11-15T10:00:00.123Z","future":"ignored"}}]"""
        assertEquals(listOf(entry.copy(attempts = 0)), ProgressQueueCodec.decode(raw).entries)
    }

    @Test
    fun corruptEntryDoesNotDiscardValidNeighbors() {
        val valid = ProgressQueueCodec.encode(listOf(entry)).removePrefix("[").removeSuffix("]")
        assertEquals(listOf(entry, entry), ProgressQueueCodec.decode("[$valid,{\"broken\":true},$valid]").entries)
    }

    /** Document exactement tel que la 0.4.1 l'écrit (aucun type, aucun champ film). */
    @Test
    fun legacyEntriesWithoutTypeDecodeAsEpisodesAndLoseNothing() {
        val raw = """[{"profileId":"p1","entry":{"itemId":"901","seriesId":"55","season":2,"episode":3,"positionS":600,"durationS":2400,"clientUpdatedAt":"2026-11-15T10:00:00.123Z","seriesName":"Serie","seriesCover":"/c.jpg","seriesCategory":"9"},"attempts":3},
            {"profileId":"p2","entry":{"itemId":"902","seriesId":null,"season":null,"episode":null,"positionS":1,"durationS":0,"clientUpdatedAt":"2026-11-14T08:00:00Z"}}]"""
        val expected = listOf(
            QueuedProgress("p1", ProgressEntry("901", "55", 2, 3, 600, 2400, Instant.parse("2026-11-15T10:00:00.123Z"), "Serie", "/c.jpg", "9"), attempts = 3),
            QueuedProgress("p2", ProgressEntry("902", null, null, null, 1, 0, Instant.parse("2026-11-14T08:00:00Z"))),
        )
        val decoded = ProgressQueueCodec.decode(raw)
        assertEquals(expected, decoded.entries)
        assertEquals(null, decoded.unreadable)
        assertEquals(listOf(ITEM_EPISODE, ITEM_EPISODE), decoded.entries.map { it.entry.itemType })
        // Réécrite puis relue par cette version : toujours des épisodes, toujours complets.
        assertEquals(expected, ProgressQueueCodec.decode(ProgressQueueCodec.encode(decoded.entries)).entries)
    }

    @Test
    fun movieAndEpisodeWithSameIdBothSurviveARoundTrip() {
        val film = QueuedProgress("p1", ProgressEntry("901", null, null, null, 700, 6000, Instant.parse("2026-11-15T11:00:00Z"),
            itemType = ITEM_MOVIE, movieName = "Film", movieCover = "/f.jpg", movieCategory = "v1"), attempts = 1)
        val episode = QueuedProgress("p1", ProgressEntry("901", "55", 2, 3, 600, 2400, Instant.parse("2026-11-15T10:00:00Z"), "Serie", "/c.jpg", "9"))
        val encoded = ProgressQueueCodec.encode(listOf(episode, film))
        assertEquals(listOf(episode, film), ProgressQueueCodec.decode(encoded).entries)
        assertTrue("\"itemType\":\"movie\"" in encoded)
    }

    @Test
    fun roundTripPreservesInstantNullsAndAttempts() {
        assertEquals(listOf(entry), ProgressQueueCodec.decode(ProgressQueueCodec.encode(listOf(entry))).entries)
    }
}
