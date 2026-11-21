package home.iptv.server

import home.iptv.shared.xtream.EpgEntry
import home.iptv.shared.xtream.EpgSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class EpgCacheTest {
    private var now = 1_000_000L

    private fun entry(title: String, startMs: Long, endMs: Long) =
        EpgEntry(title, Instant.fromEpochMilliseconds(startMs), Instant.fromEpochMilliseconds(endMs))

    private class Fake(val answer: (String, Int) -> List<EpgEntry>) : EpgSource {
        val shortCalls = AtomicInteger()
        val limits = mutableListOf<Int>()
        override suspend fun shortEpg(streamId: String, limit: Int): List<EpgEntry> {
            shortCalls.incrementAndGet()
            synchronized(limits) { limits += limit }
            return answer(streamId, limit)
        }
    }

    @Test
    fun secondCallServedFromCache() = runBlocking {
        val src = Fake { _, _ -> listOf(entry("A", now - 1_000, now + 3_600_000)) }
        val cache = EpgCache(src, { now })
        cache.entries("1", 8, false)
        now += 60_000
        assertEquals(1, cache.entries("1", 8, false).size)
        assertEquals(1, src.shortCalls.get())
        // Au-delà de 5 min : nouvelle demande.
        now += 5 * 60_000
        cache.entries("1", 8, false)
        assertEquals(2, src.shortCalls.get())
    }

    @Test
    fun expiresAtEndOfCurrentProgram() = runBlocking {
        val src = Fake { _, _ -> listOf(entry("A", now - 1_000, now + 20_000), entry("B", now + 20_000, now + 90_000)) }
        val cache = EpgCache(src, { now })
        cache.entries("1", 8, false)
        now += 10_000
        cache.entries("1", 8, false)
        assertEquals(1, src.shortCalls.get())
        now += 15_000 // le programme A est fini
        cache.entries("1", 8, false)
        assertEquals(2, src.shortCalls.get())
    }

    @Test
    fun atMostTwoProviderCallsAtOnce() = runBlocking {
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val src = object : EpgSource {
            override suspend fun shortEpg(streamId: String, limit: Int): List<EpgEntry> {
                peak.accumulateAndGet(running.incrementAndGet()) { a, b -> maxOf(a, b) }
                gate.await()
                delay(10)
                running.decrementAndGet()
                return listOf(entry("A", now - 1_000, now + 60_000))
            }
        }
        val cache = EpgCache(src, { now })
        val jobs = (1..6).map { async(kotlinx.coroutines.Dispatchers.Default) { cache.entries("s$it", 8, false) } }
        delay(100)
        gate.complete(Unit)
        jobs.awaitAll()
        assertEquals(2, peak.get())
    }

    @Test
    fun limitCappedAt8() = runBlocking {
        val src = Fake { _, _ -> emptyList() }
        val cache = EpgCache(src, { now })
        cache.entries("1", 500, false)
        cache.entries("2", 3, false)
        assertEquals(listOf(8, 3), src.limits)
    }
}
