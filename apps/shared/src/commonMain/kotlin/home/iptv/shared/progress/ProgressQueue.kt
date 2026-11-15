package home.iptv.shared.progress

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class QueuedProgress(val profileId: String, val entry: ProgressEntry)

/** Stockage durable de la file (SharedPreferences sur la télé, mémoire dans les tests). */
interface ProgressStore {
    fun loadProgressQueue(): List<QueuedProgress>
    fun saveProgressQueue(queue: List<QueuedProgress>)
}

/**
 * File locale des positions à envoyer. Une seule entrée par (profil, épisode) : la plus récente.
 *
 * `clientUpdatedAt` est posé à l'ajout dans la file (jamais à l'envoi), pour que « le plus récent gagne »
 * reste juste même si l'envoi a lieu bien plus tard, après une coupure.
 *
 * À utiliser depuis un seul fil (le fil principal de la télé) : `enqueue` ne suspend pas, et `flush` relit
 * la file après chaque envoi sans suspension entre la lecture et l'écriture.
 */
class ProgressQueue(
    private val client: ProgressClient,
    private val store: ProgressStore,
    private val clock: () -> Instant,
) {
    private val flushing = Mutex()

    fun enqueue(p: String, entry: ProgressEntry) {
        val stamped = QueuedProgress(p, entry.copy(clientUpdatedAt = clock()))
        val rest = store.loadProgressQueue().filterNot { it.sameEpisode(stamped) }
        store.saveProgressQueue(rest + stamped)
    }

    /**
     * Envoie dans l'ordre ; une entrée n'est retirée qu'après succès (y compris « le serveur garde une position
     * plus récente »). S'arrête à la première erreur : le reste attend le prochain passage.
     */
    suspend fun flush() = flushing.withLock {
        while (true) {
            val head = store.loadProgressQueue().firstOrNull() ?: return@withLock
            try {
                client.record(head.profileId, head.entry)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withLock
            }
            // Relecture : une position plus récente du même épisode, ajoutée pendant l'envoi, reste en file.
            store.saveProgressQueue(store.loadProgressQueue().filterNot { it == head })
        }
    }

    private fun QueuedProgress.sameEpisode(o: QueuedProgress) = profileId == o.profileId && entry.itemId == o.entry.itemId
}
