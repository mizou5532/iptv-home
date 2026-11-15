package home.iptv.tv.series

import home.iptv.shared.progress.ProgressQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Envoie la file de progression : jamais sur le fil principal, un seul envoi à la fois, demandes rapprochées
 * regroupées ([debounceMs]). Un envoi bloqué (hors ligne) n'est pas réessayé en boucle : la file est reprise à la
 * prochaine demande (prochain enregistrement, retour du réseau, ouverture de Séries, prochain démarrage).
 */
class ProgressFlusher(
    private val queue: ProgressQueue,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val debounceMs: Long = 1_500,
) {
    private val inFlight = AtomicBoolean(false)
    private val again = AtomicBoolean(false)

    /** Sûr depuis n'importe quel fil. */
    fun request() {
        again.set(true)
        if (!inFlight.compareAndSet(false, true)) return
        scope.launch(io) {
            try {
                while (again.getAndSet(false)) {
                    delay(debounceMs)
                    try {
                        queue.flush()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // La file reste sur la télé : elle sera reprise à la prochaine demande.
                    }
                }
            } finally {
                inFlight.set(false)
                // Une demande arrivée entre la dernière vérification et la libération ne doit pas être perdue.
                if (again.get()) request()
            }
        }
    }
}
