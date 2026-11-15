package home.iptv.shared.progress

import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlin.time.Duration.Companion.milliseconds

@Serializable
data class QueuedProgress(val profileId: String, val entry: ProgressEntry)

/** Stockage durable de la file (SharedPreferences sur la télé, mémoire dans les tests). */
interface ProgressStore {
    fun loadProgressQueue(): List<QueuedProgress>
    fun saveProgressQueue(queue: List<QueuedProgress>)
    /** Atomic read/modify/write transaction, including concurrent enqueue and flush. */
    fun update(transform: (List<QueuedProgress>) -> List<QueuedProgress>)
}

/**
 * File locale des positions à envoyer. Une seule entrée par (profil, épisode) : la plus récente.
 *
 * `clientUpdatedAt` est posé à l'ajout dans la file (jamais à l'envoi), pour que « le plus récent gagne »
 * reste juste même si l'envoi a lieu bien plus tard, après une coupure.
 *
 * Les mutations passent par la transaction atomique du stockage ; un seul flush est actif à la fois.
 */
class ProgressQueue(
    private val client: ProgressClient,
    private val store: ProgressStore,
    private val clock: () -> Instant,
) {
    private val flushing = Mutex()

    fun enqueue(p: String, entry: ProgressEntry) = store.update { current ->
        val previous = current.firstOrNull { it.profileId == p && it.entry.itemId == entry.itemId }?.entry
        val stamped = QueuedProgress(p, entry.copy(
            clientUpdatedAt = previous?.let { maxOf(clock(), it.clientUpdatedAt + 1.milliseconds) } ?: clock(),
            seriesId = entry.seriesId ?: previous?.seriesId,
            season = entry.season ?: previous?.season,
            episode = entry.episode ?: previous?.episode,
            seriesName = entry.seriesName ?: previous?.seriesName,
            seriesCover = entry.seriesCover ?: previous?.seriesCover,
            seriesCategory = entry.seriesCategory ?: previous?.seriesCategory,
        ))
        val rest = current.filterNot { it.sameEpisode(stamped) }
        (rest + stamped).takeLast(300)
    }

    /**
     * Envoie dans l'ordre ; une entrée n'est retirée qu'après succès (y compris « le serveur garde une position
     * plus récente »). S'arrête à la première erreur : le reste attend le prochain passage.
     */
    suspend fun flush() = flushing.withLock {
        val skipped = mutableSetOf<QueuedProgress>()
        while (true) {
            val head = store.loadProgressQueue().firstOrNull { it !in skipped } ?: return@withLock
            try {
                client.record(head.profileId, head.entry)
            } catch (e: TimeoutCancellationException) {
                return@withLock
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isPermanentProgressFailure(e)) return@withLock
                skipped += head
                continue
            }
            // Relecture : une position plus récente du même épisode, ajoutée pendant l'envoi, reste en file.
            store.update { current -> current.filterNot { it == head } }
        }
    }

    private fun QueuedProgress.sameEpisode(o: QueuedProgress) = profileId == o.profileId && entry.itemId == o.entry.itemId
}

/** Typed SDK classification; unknown errors remain queued rather than losing progress. */
internal fun isPermanentProgressFailure(error: Exception): Boolean {
    if (error is PostgrestRestException && error.code in setOf("42501", "P0001")) return true
    val status = (error as? RestException)?.statusCode ?: return false
    return status in 400..499 && status !in setOf(401, 408, 429)
}
