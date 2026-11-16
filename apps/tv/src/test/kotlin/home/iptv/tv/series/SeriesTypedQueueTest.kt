package home.iptv.tv.series

import home.iptv.shared.progress.ProgressEntry
import home.iptv.shared.progress.QueuedProgress
import kotlin.test.*
import kotlin.time.Instant

class SeriesTypedQueueTest {
    @Test fun moviesNeverBecomeEpisodeRowsEvenWithLegacySeriesMetadata() {
        val e = ProgressEntry("42", "s", 1, 1, 30, 100, Instant.parse("2026-11-15T10:00:00Z"))
        val queue = listOf(QueuedProgress("p", e), QueuedProgress("p", e.copy(itemType = "movie")))
        assertEquals(1, queuedAsRows(queue, "p").size)
        assertEquals(listOf(queue[1]), dropQueuedEpisodes(queue, "p", setOf("42")))
    }
}
