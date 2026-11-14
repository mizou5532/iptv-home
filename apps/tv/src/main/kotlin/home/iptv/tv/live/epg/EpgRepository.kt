package home.iptv.tv.live.epg

import android.os.SystemClock
import home.iptv.shared.xtream.EpgEntry
import home.iptv.shared.xtream.XtreamApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Clock

/** Source du guide court ; permet de tester sans réseau. */
fun interface EpgSource {
    suspend fun shortEpg(streamId: String, limit: Int): List<EpgEntry>

    companion object {
        fun of(api: XtreamApi): EpgSource = EpgSource { id, n -> api.shortEpg(id, n) }
    }
}

/**
 * Guide « maintenant / ensuite » de la source sélectionnée, indépendant du lecteur. Une seule source est observée à
 * la fois : en observer une autre annule la précédente et vide aussitôt l'état (jamais le guide d'une autre copie).
 *
 * Bornes : 300 ms de sélection stable avant toute demande réseau, cache LRU de 100 sources, fraîcheur de 5 min au
 * plus et fin anticipée au terme du programme actuel, 2 demandes simultanées, 8 entrées par demande.
 * [clock] donne l'heure réelle (affichage) ; [monotonicMs] le temps monotone des caches et délais (par défaut `SystemClock.elapsedRealtime`, qui continue
 * pendant la veille profonde, contrairement à `System.nanoTime`).
 */
class EpgRepository(
    private val api: EpgSource,
    scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val monotonicMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private class Cached(val entries: List<EpgEntry>, val expiresAtMs: Long)

    private val ownScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val state = MutableStateFlow(NowNext.EMPTY)
    private val permits = Semaphore(MAX_CONCURRENT)
    private val generation = AtomicLong(0)
    private val cache = object : LinkedHashMap<String, Cached>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > CACHE_SIZE
    }
    private var job: Job? = null
    private var observed: String? = null
    private var closed = false

    @Synchronized
    fun observe(sourceId: String?): StateFlow<NowNext> {
        if (closed || (job != null && sourceId == observed)) return state
        val gen = generation.incrementAndGet()
        job?.cancel()
        observed = sourceId
        state.value = sourceId?.let { id -> fresh(id)?.let { nowNext(it.entries, clock.now()) } } ?: NowNext.EMPTY
        job = if (sourceId == null) null else ownScope.launch { follow(sourceId, gen) }
        return state
    }

    @Synchronized
    fun close() {
        closed = true
        generation.incrementAndGet()
        job?.cancel(); job = null
        ownScope.cancel()
        state.value = NowNext.EMPTY
        synchronized(cache) { cache.clear() }
    }

    internal fun cacheSize(): Int = synchronized(cache) { cache.size }

    @Synchronized
    private fun publish(gen: Long, value: NowNext) {
        if (generation.get() == gen) state.value = value
    }

    private fun fresh(id: String): Cached? = synchronized(cache) { cache[id] }?.takeIf { monotonicMs() < it.expiresAtMs }

    private suspend fun follow(id: String, gen: Long) {
        var cached = fresh(id)
        if (cached == null) delay(SETTLE_MS)
        while (true) {
            if (cached == null) cached = fresh(id) ?: load(id)
            if (cached == null) {
                publish(gen, NowNext.EMPTY)
                delay(RETRY_MS)
                continue
            }
            publish(gen, nowNext(cached.entries, clock.now()))
            val wait = cached.expiresAtMs - monotonicMs()
            if (wait > 0) delay(wait)
            cached = null
        }
    }

    /** Une demande bornée ; toute erreur (réseau, format) donne « indisponible », jamais une exception. */
    private suspend fun load(id: String): Cached? {
        val entries = try {
            permits.withPermit { api.shortEpg(id, ENTRIES) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        val now = clock.now()
        val projection = nowNext(entries, now)
        val untilChangeMs = projection.current?.end?.minus(now)?.inWholeMilliseconds
            ?: projection.next?.start?.minus(now)?.inWholeMilliseconds
            ?: FRESH_MS
        val ttl = untilChangeMs.coerceIn(MIN_TTL_MS, FRESH_MS)
        return Cached(entries, monotonicMs() + ttl).also { c ->
            synchronized(cache) { cache[id] = c }
        }
    }

    private companion object {
        const val SETTLE_MS = 300L
        const val FRESH_MS = 5 * 60_000L
        const val MIN_TTL_MS = 1_000L
        const val RETRY_MS = 60_000L
        const val CACHE_SIZE = 100
        const val MAX_CONCURRENT = 2
        const val ENTRIES = 8
    }
}
