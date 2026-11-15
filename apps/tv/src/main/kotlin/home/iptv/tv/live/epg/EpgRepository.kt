package home.iptv.tv.live.epg

import android.os.SystemClock
import home.iptv.shared.xtream.EpgEntry
import home.iptv.shared.xtream.XtreamApi
import home.iptv.tv.diag.Diagnostics
import kotlin.time.Instant
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

/** Source du guide ; permet de tester sans réseau. */
fun interface EpgSource {
    suspend fun shortEpg(streamId: String, limit: Int): List<EpgEntry>

    /** Tout le guide de la chaîne (secours, plus lourd) ; vide si la source ne sait pas le donner. */
    suspend fun fullEpg(streamId: String): List<EpgEntry> = emptyList()

    companion object {
        /** [api] est rappelé à chaque demande : les identifiants sont lus au moment de l'appel. */
        fun of(api: () -> XtreamApi): EpgSource = object : EpgSource {
            override suspend fun shortEpg(streamId: String, limit: Int) = api().shortEpg(streamId, limit)
            override suspend fun fullEpg(streamId: String) = api().fullEpg(streamId)
        }
    }
}

/**
 * Guide « maintenant / ensuite » de la source sélectionnée, indépendant du lecteur. Une seule source est observée à
 * la fois : en observer une autre annule la précédente et vide aussitôt l'état (jamais le guide d'une autre copie).
 *
 * Bornes : 300 ms de sélection stable avant toute demande réseau, cache LRU de 100 sources, fraîcheur de 5 min au
 * plus et fin anticipée au terme du programme actuel, 2 demandes simultanées, 8 entrées par demande.
 *
 * Essai TCL 0.4.0 (« Programme indisponible » presque partout) : pour une source, on essaie d'abord le guide court ;
 * s'il ne donne ni actuel ni suivant, le guide complet ; puis, au besoin, les autres copies de la même chaîne
 * ([guideIds] : le fournisseur ne relie souvent le guide qu'à l'une d'elles), 3 sources au plus.
 * [clock] donne l'heure réelle (affichage) ; [monotonicMs] le temps monotone des caches et délais (par défaut `SystemClock.elapsedRealtime`, qui continue
 * pendant la veille profonde, contrairement à `System.nanoTime`).
 */
class EpgRepository(
    private val api: EpgSource,
    scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    /** Sources à interroger pour le guide de cette source, dans l'ordre (elle-même et ses copies visibles). */
    private val guideIds: suspend (String) -> List<String> = { listOf(it) },
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
    private var paused = false

    @Synchronized
    fun observe(sourceId: String?): StateFlow<NowNext> {
        if (closed) return state
        if (paused) {
            // En arrière-plan : on retient la source, sans interroger le serveur.
            if (sourceId != observed) { generation.incrementAndGet(); observed = sourceId; state.value = NowNext.EMPTY }
            return state
        }
        if (job != null && sourceId == observed) return state
        val gen = generation.incrementAndGet()
        job?.cancel()
        observed = sourceId
        state.value = sourceId?.let { id -> fresh(id)?.let { nowNext(it.entries, clock.now()) } } ?: NowNext.EMPTY
        job = if (sourceId == null) null else ownScope.launch { follow(sourceId, gen) }
        return state
    }

    /** onStop : plus aucune interrogation du guide ; la source observée est conservée. */
    @Synchronized
    fun pause() {
        if (closed || paused) return
        paused = true
        job?.cancel(); job = null
    }

    /** onStart : reprend le suivi de la source observée. */
    @Synchronized
    fun resume() {
        if (closed || !paused) return
        paused = false
        val id = observed ?: return
        val gen = generation.incrementAndGet()
        state.value = fresh(id)?.let { nowNext(it.entries, clock.now()) } ?: NowNext.EMPTY
        job = ownScope.launch { follow(id, gen) }
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

    /** Une demande bornée : `null` si elle échoue (réseau, format), jamais une exception. */
    private suspend fun request(block: suspend () -> List<EpgEntry>): List<EpgEntry>? = try {
        permits.withPermit { block() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun useful(entries: List<EpgEntry>, now: Instant) =
        nowNext(entries, now).let { it.current != null || it.next != null }

    /**
     * Guide court, puis guide complet, pour chaque source candidate jusqu'à trouver un actuel ou un suivant. Toutes
     * les demandes en erreur : `null` (nouvel essai dans [RETRY_MS]) ; réponses vides : « indisponible » mis en cache.
     */
    private suspend fun load(id: String): Cached? {
        val candidates = try {
            guideIds(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }.ifEmpty { listOf(id) }.distinct().take(MAX_CANDIDATES)
        var answered = false
        var entries: List<EpgEntry> = emptyList()
        var how = ""
        search@ for ((index, candidate) in candidates.withIndex()) {
            val copy = if (index == 0) "" else ", copie ${index + 1}"
            val short = request { api.shortEpg(candidate, ENTRIES) }
            if (short != null) {
                answered = true
                if (useful(short, clock.now())) { entries = short; how = "guide court$copy"; break@search }
            }
            val full = request { api.fullEpg(candidate) }
            // Un guide complet vide ne compte pas comme réponse : une erreur du guide court reste réessayée dans 1 min.
            if (!full.isNullOrEmpty()) {
                answered = true
                if (useful(full, clock.now())) { entries = around(full, clock.now()); how = "guide complet$copy"; break@search }
            }
        }
        val now = clock.now()
        val projection = nowNext(entries, now)
        // Calque de diagnostic : un état seulement (jamais d'adresse ni d'identifiant).
        Diagnostics.guide = when {
            !answered -> "erreur, nouvel essai dans 1 min (${candidates.size} source(s))"
            projection.current == null && projection.next == null -> "aucun programme (${candidates.size} source(s))"
            projection.current != null && projection.progress == null -> "$how, drapeau « en cours »"
            else -> how
        }
        if (!answered) return null
        // Prochain changement à venir (fin de l'actuel ou début du suivant) ; aucun dans le futur : fraîcheur normale.
        val untilChangeMs = listOfNotNull(projection.current?.end, projection.next?.start)
            .filter { it > now }.minOrNull()?.minus(now)?.inWholeMilliseconds
            ?: FRESH_MS
        val ttl = untilChangeMs.coerceIn(MIN_TTL_MS, FRESH_MS)
        return Cached(entries, monotonicMs() + ttl).also { c ->
            synchronized(cache) { cache[id] = c }
        }
    }

    /** Guide complet réduit à ce qui sert : à partir du programme en cours (ou du suivant), 8 entrées. */
    private fun around(full: List<EpgEntry>, now: Instant): List<EpgEntry> {
        val sorted = full.filter { it.end > it.start }.sortedBy { it.start }
        val p = nowNext(sorted, now)
        val anchor = p.current ?: p.next
        val from = anchor?.let { a -> sorted.indexOfFirst { it === a } }?.takeIf { it >= 0 } ?: 0
        return sorted.drop(from).take(ENTRIES)
    }

    private companion object {
        const val MAX_CANDIDATES = 3
        const val SETTLE_MS = 300L
        const val FRESH_MS = 5 * 60_000L
        const val MIN_TTL_MS = 1_000L
        const val RETRY_MS = 60_000L
        const val CACHE_SIZE = 100
        const val MAX_CONCURRENT = 2
        const val ENTRIES = 8
    }
}
