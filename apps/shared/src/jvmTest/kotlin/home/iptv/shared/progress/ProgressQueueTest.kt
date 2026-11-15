package home.iptv.shared.progress

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

private class MemoryStore : ProgressStore {
    var saved: List<QueuedProgress> = emptyList()
    @Synchronized override fun loadProgressQueue() = saved
    @Synchronized override fun saveProgressQueue(queue: List<QueuedProgress>) { saved = queue }
    @Synchronized override fun update(transform: (List<QueuedProgress>) -> List<QueuedProgress>) { saved = transform(saved) }
}

private class FakeProgressClient : ProgressClient {
    val sent = mutableListOf<Pair<String, ProgressEntry>>()
    var failure: Throwable? = null
    var result = true
    override suspend fun record(p: String, entry: ProgressEntry): Boolean {
        failure?.let { throw it }
        sent += p to entry
        return result
    }
    override suspend fun continueWatching(p: String, max: Int): List<ProgressRow> = emptyList()
    override suspend fun setWatched(p: String, items: List<WatchedItem>, watched: Boolean) {}
    override suspend fun setSeriesList(p: String, seriesId: String, on: Boolean) {}
}

class ProgressQueueTest {
    private var now = Instant.parse("2026-11-15T10:00:00Z")
    private val client = FakeProgressClient()
    private val store = MemoryStore()
    private fun queue(s: ProgressStore = store) = ProgressQueue(client, s) { now }

    private fun entry(item: String, pos: Int, series: String? = "s1") = ProgressEntry(
        itemId = item, seriesId = series, season = 1, episode = 2, positionS = pos, durationS = 2400,
        clientUpdatedAt = Instant.fromEpochSeconds(0), seriesName = "Serie", seriesCover = null, seriesCategory = "c1",
    )

    @Test
    fun timeoutBlocksWithoutConsumingPermanentAttempts() = runBlocking {
        val timeoutClient = object : ProgressClient by client {
            override suspend fun record(p: String, entry: ProgressEntry): Boolean = kotlinx.coroutines.withTimeout(1) {
                kotlinx.coroutines.delay(Long.MAX_VALUE)
                true
            }
        }
        val q = ProgressQueue(timeoutClient, store) { now }
        q.enqueue("p1", entry("e1", 1))
        assertEquals(FlushResult(0, 0, true), q.flush())
        assertEquals(0, store.saved.single().attempts)
    }

    @Test
    fun coverPersistsOnlySchemeHostAndPath() {
        queue().enqueue("p1", entry("e1", 1).copy(seriesCover = "https://demo:placeholder@images.example.test/poster.jpg?key=placeholder#section"))
        assertEquals("https://images.example.test/poster.jpg", store.saved.single().entry.seriesCover)
    }

    @Test
    fun emptyFlushReturnsSummaryWithoutSending() = runBlocking {
        assertEquals(FlushResult(0, 0, false), queue().flush())
        assertTrue(client.sent.isEmpty())
    }

    @Test
    fun concurrentEnqueuesDoNotLoseEntries() {
        val concurrentStore = object : ProgressStore {
            private var entries = emptyList<QueuedProgress>()
            @Synchronized override fun loadProgressQueue(): List<QueuedProgress> = entries
            @Synchronized override fun saveProgressQueue(queue: List<QueuedProgress>) { entries = queue }
            @Synchronized override fun update(transform: (List<QueuedProgress>) -> List<QueuedProgress>) {
                entries = transform(entries)
            }
        }
        val q = queue(concurrentStore)
        val start = java.util.concurrent.CountDownLatch(1)
        val threads = (1..100).map { n -> Thread { start.await(); q.enqueue("p1", entry("e$n", n)) }.apply { start() } }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(100, concurrentStore.loadProgressQueue().size)
    }

    @Test
    fun queueDropsOldestBeyondThreeHundred() {
        val q = queue()
        repeat(302) { q.enqueue("p1", entry("e$it", it)) }
        assertEquals(300, store.saved.size)
        assertEquals("e2", store.saved.first().entry.itemId)
        assertEquals("e301", store.saved.last().entry.itemId)
    }

    @Test
    fun replacementTimestampAdvancesEvenWhenClockRegresses() {
        val q = queue()
        q.enqueue("p1", entry("e1", 1))
        val first = store.saved.single().entry.clientUpdatedAt
        q.enqueue("p1", entry("e1", 2))
        assertEquals(first + kotlin.time.Duration.parse("1ms"), store.saved.single().entry.clientUpdatedAt)
        now -= kotlin.time.Duration.parse("1h")
        q.enqueue("p1", entry("e1", 3))
        assertEquals(first + kotlin.time.Duration.parse("2ms"), store.saved.single().entry.clientUpdatedAt)
    }

    @Test
    fun replacementRetainsMissingMetadata() {
        val q = queue()
        q.enqueue("p1", entry("e1", 100).copy(seriesCover = "https://images.example.test/poster.jpg"))
        q.enqueue("p1", entry("e1", 130).copy(seriesId = null, season = null, episode = null,
            seriesName = null, seriesCover = null, seriesCategory = null))
        val actual = store.saved.single().entry
        assertEquals("s1", actual.seriesId)
        assertEquals(1, actual.season)
        assertEquals(2, actual.episode)
        assertEquals("Serie", actual.seriesName)
        assertEquals("https://images.example.test/poster.jpg", actual.seriesCover)
        assertEquals("c1", actual.seriesCategory)
        assertEquals(130, actual.positionS)
    }

    @Test
    fun queueKeptOnNetworkErrorThenEmptiedOnReturn() = runBlocking {
        val q = queue()
        client.failure = IOException("coupure")
        q.enqueue("p1", entry("e1", 100))
        assertEquals(FlushResult(0, 0, true), q.flush())
        assertEquals(1, store.saved.size)
        assertEquals(0, store.saved.single().attempts)
        assertTrue(client.sent.isEmpty())
        client.failure = null
        q.flush()
        assertTrue(store.saved.isEmpty())
        assertEquals(listOf("p1" to 100), client.sent.map { it.first to it.second.positionS })
    }

    @Test
    fun sameEpisodeKeepsOnlyLatestEntry() = runBlocking {
        val q = queue()
        q.enqueue("p1", entry("e1", 100))
        now += kotlin.time.Duration.parse("30s")
        q.enqueue("p1", entry("e1", 130))
        assertEquals(1, store.saved.size)
        q.flush()
        assertEquals(listOf(130), client.sent.map { it.second.positionS })
    }

    @Test
    fun sameEpisodeForTwoProfilesKeepsBoth() = runBlocking {
        val q = queue()
        q.enqueue("p1", entry("e1", 100))
        q.enqueue("p2", entry("e1", 50))
        q.flush()
        assertEquals(listOf("p1", "p2"), client.sent.map { it.first })
    }

    @Test
    fun clientUpdatedAtIsStampedAtEnqueueNotAtSend() = runBlocking {
        val q = queue()
        val enqueuedAt = now
        q.enqueue("p1", entry("e1", 100))
        now += kotlin.time.Duration.parse("2h")
        q.flush()
        assertEquals(enqueuedAt, client.sent.single().second.clientUpdatedAt)
    }

    @Test
    fun flushSendsInOrderAndStopsAtFirstError() = runBlocking {
        val q = queue()
        q.enqueue("p1", entry("e1", 1))
        q.enqueue("p1", entry("e2", 2))
        q.enqueue("p1", entry("e3", 3))
        val failOnSecond = object : ProgressClient by client {
            override suspend fun record(p: String, entry: ProgressEntry): Boolean {
                if (entry.itemId == "e2") throw IOException("coupure")
                return client.record(p, entry)
            }
        }
        val q2 = ProgressQueue(failOnSecond, store) { now }
        q2.flush()
        assertEquals(listOf("e1"), client.sent.map { it.second.itemId })
        assertEquals(listOf("e2", "e3"), store.saved.map { it.entry.itemId })
    }

    @Test
    fun serverKeepingNewerPositionStillRemovesEntry() = runBlocking {
        val q = queue()
        client.result = false
        q.enqueue("p1", entry("e1", 100))
        q.flush()
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun cancellationIsRethrownAndEntryKept() {
        val q = queue()
        q.enqueue("p1", entry("e1", 100))
        client.failure = CancellationException("annule")
        assertFailsWith<CancellationException> { runBlocking { q.flush() } }
        assertEquals(1, store.saved.size)
    }

    @Test
    fun queueSurvivesRestart() = runBlocking {
        queue().enqueue("p1", entry("e1", 100))
        queue().flush()
        assertEquals(listOf("e1"), client.sent.map { it.second.itemId })
    }

    @Test
    fun entryEnqueuedDuringSendIsNotLost() = runBlocking {
        val q = queue()
        q.enqueue("p1", entry("e1", 100))
        lateinit var qq: ProgressQueue
        val slow = object : ProgressClient by client {
            override suspend fun record(p: String, entry: ProgressEntry): Boolean {
                if (entry.positionS == 100) {
                    now += kotlin.time.Duration.parse("5s")
                    qq.enqueue("p1", entry("e1", 105))
                }
                return client.record(p, entry)
            }
        }
        qq = ProgressQueue(slow, store) { now }
        qq.flush()
        // La position 105, ajoutée pendant l'envoi de 100, n'est pas perdue : elle part ensuite.
        assertEquals(listOf(100, 105), client.sent.map { it.second.positionS })
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun entryHasNoStreamAddressField() {
        val names = (0 until ProgressEntry.serializer().descriptor.elementsCount)
            .map { ProgressEntry.serializer().descriptor.getElementName(it).lowercase() }
        assertTrue(names.none { "url" in it || "stream" in it || "pass" in it || "user" in it || "server" in it }, "$names")
    }
}
