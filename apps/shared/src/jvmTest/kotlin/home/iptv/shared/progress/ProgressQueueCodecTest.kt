package home.iptv.shared.progress

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun roundTripPreservesInstantNullsAndAttempts() {
        assertEquals(listOf(entry), ProgressQueueCodec.decode(ProgressQueueCodec.encode(listOf(entry))).entries)
    }
}
