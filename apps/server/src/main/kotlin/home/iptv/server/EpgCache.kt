package home.iptv.server

import home.iptv.shared.xtream.EpgEntry
import home.iptv.shared.xtream.EpgSource
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.time.Instant

/**
 * Guide partagé par toute la maison : une seule demande au fournisseur, quel que soit le nombre de télés.
 * Mêmes règles que `EpgRepository` de la télé : fraîcheur de 5 min au plus, fin anticipée au terme du programme en
 * cours, 2 demandes simultanées au fournisseur, 8 entrées au plus par demande. Cache LRU de [maxEntries] entrées.
 * Une erreur du fournisseur n'est jamais mise en cache : elle remonte à l'appelant.
 */
class EpgCache(
    private val api: EpgSource,
    private val clockMs: () -> Long,
    private val maxEntries: Int = 2_000,
    /** Appelé avant une demande au fournisseur (assure que les identifiants IPTV sont chargés en mémoire après un redémarrage). */
    private val beforeFetch: suspend () -> Unit = {},
) {
    private class Cached(val entries: List<EpgEntry>, val expiresAtMs: Long)

    private val permits = Semaphore(MAX_CONCURRENT)
    private val cache = object : LinkedHashMap<String, Cached>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > maxEntries
    }

    suspend fun entries(streamId: String, limit: Int, full: Boolean): List<EpgEntry> {
        val n = limit.coerceIn(1, MAX_LIMIT)
        val key = "$streamId|$n|$full"
        val now = clockMs()
        synchronized(cache) { cache[key] }?.takeIf { now < it.expiresAtMs }?.let { return it.entries }
        val result = permits.withPermit {
            beforeFetch()
            if (full) api.fullEpg(streamId).sortedBy { it.start }.dropWhile { it.end.toEpochMilliseconds() <= now }.take(n)
            else api.shortEpg(streamId, n)
        }
        val after = clockMs()
        // Prochain changement : fin du programme en cours ou début du suivant ; sinon fraîcheur normale.
        val untilChange = result.flatMap { listOf(it.start, it.end) }
            .map(Instant::toEpochMilliseconds)
            .filter { it > after }
            .minOrNull()?.minus(after)
        val ttl = (untilChange ?: FRESH_MS).coerceIn(MIN_TTL_MS, FRESH_MS)
        synchronized(cache) { cache[key] = Cached(result, after + ttl) }
        return result
    }

    private companion object {
        const val MAX_CONCURRENT = 2
        const val MAX_LIMIT = 8
        const val FRESH_MS = 5 * 60_000L
        const val MIN_TTL_MS = 1_000L
    }
}
