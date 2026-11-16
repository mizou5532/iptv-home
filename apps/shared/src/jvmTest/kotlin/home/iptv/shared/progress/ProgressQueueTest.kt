package home.iptv.shared.progress

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
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
    override suspend fun seriesProgress(p: String, seriesId: String): List<ProgressRow> = emptyList()
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
    fun implausibleStampsIgnoreTheFourteenDayExpiry() = runBlocking {
        // Horloge fausse au demarrage : estampille en 1970, ou plus d'un jour dans le futur. Jamais perdue a cause de l'age.
        store.saved = listOf(
            QueuedProgress("p1", entry("epoch", 10).copy(clientUpdatedAt = Instant.fromEpochSeconds(0))),
            QueuedProgress("p1", entry("before", 10).copy(clientUpdatedAt = Instant.parse("2025-12-31T23:59:59Z"))),
            QueuedProgress("p1", entry("future", 10).copy(clientUpdatedAt = now + 2.days)),
        )
        assertEquals(FlushResult(3, 0, false), queue().flush())
        assertEquals(listOf("epoch", "before", "future"), client.sent.map { it.second.itemId })
    }

    @Test
    fun plausibleStampsStillExpireAndTheLimitsAreExact() = runBlocking {
        store.saved = listOf(
            QueuedProgress("p1", entry("old", 10).copy(clientUpdatedAt = now - 14.days)),
            QueuedProgress("p1", entry("floor", 10).copy(clientUpdatedAt = Instant.parse("2026-01-01T00:00:00Z"))),
            QueuedProgress("p1", entry("soon", 10).copy(clientUpdatedAt = now + 1.days)),
        )
        // « floor » (2026-01-01) est plausible et vieux de plus de 14 jours : expire. « soon » (+1 j pile) est plausible.
        assertEquals(FlushResult(1, 2, false), queue().flush())
        assertEquals(listOf("soon"), client.sent.map { it.second.itemId })
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

    private fun movie(item: String, pos: Int) = ProgressEntry(
        itemId = item, seriesId = null, season = null, episode = null, positionS = pos, durationS = 6000,
        clientUpdatedAt = Instant.fromEpochSeconds(0), itemType = ITEM_MOVIE,
        movieName = "Film", movieCover = "https://images.example.test/film.jpg", movieCategory = "v1",
    )

    @Test
    fun movieAndEpisodeWithSameIdStayIndependent() = runBlocking {
        val q = queue()
        q.enqueue("p1", entry("7", 100))
        q.enqueue("p1", movie("7", 500))
        assertEquals(listOf(ITEM_EPISODE to 100, ITEM_MOVIE to 500), store.saved.map { it.entry.itemType to it.entry.positionS })
        // Remplacer le film ne touche pas l'épisode, et inversement.
        q.enqueue("p1", movie("7", 530))
        q.enqueue("p1", entry("7", 130))
        assertEquals(setOf(ITEM_MOVIE to 530, ITEM_EPISODE to 130), store.saved.map { it.entry.itemType to it.entry.positionS }.toSet())
        assertEquals(2, store.saved.size)
        q.flush()
        assertEquals(setOf(ITEM_MOVIE to 530, ITEM_EPISODE to 130), client.sent.map { it.second.itemType to it.second.positionS }.toSet())
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun replacementNeverMixesMovieAndEpisodeMetadata() {
        val q = queue()
        q.enqueue("p1", entry("7", 100))
        q.enqueue("p1", movie("7", 500))
        // Les deux reviennent sans métadonnées : chacun retrouve les siennes, jamais celles de l'autre type.
        q.enqueue("p1", movie("7", 530).copy(movieName = null, movieCover = null, movieCategory = null))
        q.enqueue("p1", entry("7", 130).copy(seriesId = null, season = null, episode = null, seriesName = null, seriesCategory = null))
        val film = store.saved.single { it.entry.itemType == ITEM_MOVIE }.entry
        assertEquals(listOf("Film", "https://images.example.test/film.jpg", "v1"), listOf(film.movieName, film.movieCover, film.movieCategory))
        assertEquals(listOf(null, null, null, null, null), listOf(film.seriesId, film.season, film.episode, film.seriesName, film.seriesCategory))
        val episode = store.saved.single { it.entry.itemType == ITEM_EPISODE }.entry
        assertEquals(listOf("s1", "Serie", "c1"), listOf(episode.seriesId, episode.seriesName, episode.seriesCategory))
        assertEquals(listOf(null, null, null), listOf(episode.movieName, episode.movieCover, episode.movieCategory))
    }

    @Test
    fun replacementTimestampAdvancesPerTypeOnly() {
        val q = queue()
        q.enqueue("p1", entry("7", 100))
        val first = store.saved.single().entry.clientUpdatedAt
        // Premier ajout du film : pas de « +1 ms » hérité de l'épisode de même identifiant.
        q.enqueue("p1", movie("7", 500))
        assertEquals(first, store.saved.single { it.entry.itemType == ITEM_MOVIE }.entry.clientUpdatedAt)
    }

    @Test
    fun movieCoverPersistsOnlySchemeHostAndPath() {
        queue().enqueue("p1", movie("7", 1).copy(movieCover = "https://demo:placeholder@images.example.test/film.jpg?key=placeholder#section"))
        assertEquals("https://images.example.test/film.jpg", store.saved.single().entry.movieCover)
    }

    @Test
    fun sendingOrRefusingOneTypeLeavesTheOtherQueued() = runBlocking {
        val q = queue()
        q.enqueue("p1", entry("7", 100))
        q.enqueue("p1", movie("7", 500))
        // Le film est refusé définitivement (5 essais) ; l'épisode de même identifiant attend la fin d'une coupure.
        val refusing = object : ProgressClient by client {
            override suspend fun record(p: String, entry: ProgressEntry): Boolean {
                if (entry.itemType == ITEM_MOVIE) throw IllegalStateException("refus")
                throw IOException("coupure")
            }
        }
        val blocked = ProgressQueue(refusing, store) { now }
        assertEquals(FlushResult(0, 0, true), blocked.flush())
        assertEquals(2, store.saved.size)
        // Seul le film part : l'épisode reste en file, intact.
        val onlyMovies = object : ProgressClient by client {
            override suspend fun record(p: String, entry: ProgressEntry): Boolean {
                if (entry.itemType == ITEM_EPISODE) throw IOException("coupure")
                return client.record(p, entry)
            }
        }
        store.saved = store.saved.sortedBy { it.entry.itemType != ITEM_MOVIE }
        ProgressQueue(onlyMovies, store) { now }.flush()
        assertEquals(listOf(ITEM_MOVIE), client.sent.map { it.second.itemType })
        assertEquals(listOf(ITEM_EPISODE to 100), store.saved.map { it.entry.itemType to it.entry.positionS })
    }

    @Test
    fun entryHasNoStreamAddressField() {
        val names = (0 until ProgressEntry.serializer().descriptor.elementsCount)
            .map { ProgressEntry.serializer().descriptor.getElementName(it).lowercase() }
        assertTrue(names.none { "url" in it || "stream" in it || "pass" in it || "user" in it || "server" in it }, "$names")
    }
}

class SeriesCoverServerTest {
    @Test
    fun coverOnTheProviderServerKeepsOnlyItsPathAndIsRecomposedForDisplay() {
        val server = "http://prov.example.test:8080"
        assertEquals("/img/a.jpg", coverWithoutServer("http://prov.example.test:8080/img/a.jpg?x=1", server))
        assertEquals("/img/a.jpg", coverWithoutServer("http://PROV.example.test:8080/img/a.jpg", server))
        // Autre hote : inchange. Chemin relatif : inchange. Sans serveur : inchange.
        assertEquals("https://images.example.test/a.jpg", coverWithoutServer("https://images.example.test/a.jpg", server))
        assertEquals("/img/a.jpg", coverWithoutServer("/img/a.jpg", server))
        assertEquals("http://prov.example.test:8080/a.jpg", coverWithoutServer("http://prov.example.test:8080/a.jpg", null))
        assertEquals(null, coverWithoutServer(null, server))
        assertEquals("http://prov.example.test:8080/img/a.jpg", coverForDisplay("/img/a.jpg", server))
        assertEquals("https://images.example.test/a.jpg", coverForDisplay("https://images.example.test/a.jpg", server))
        assertEquals("/img/a.jpg", coverForDisplay("/img/a.jpg", null))
        assertEquals(null, coverForDisplay(null, server))
    }
}
