package home.iptv.tv.player

import home.iptv.shared.sync.AccountClaim
import home.iptv.shared.sync.Occupant
import home.iptv.shared.xtream.XtreamCreds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/** Compte à utiliser pour une vidéo. */
sealed interface LeaseResult {
    /** [creds] : compte réservé ; `null` = compte principal (adresse inchangée). */
    data class Use(val creds: XtreamCreds?) : LeaseResult
    /** Tous les comptes sont pris : rien ne doit être lu. */
    data class Busy(val occupants: List<Occupant>) : LeaseResult
}

/**
 * Comptes IPTV du foyer partagés entre les télés : une télé qui lance une vidéo réserve le premier compte libre auprès
 * du serveur (1 = principal), le garde tant qu'elle regarde (changements de chaîne compris : il n'est rendu que
 * [releaseGraceMs] après la dernière vidéo), confirme toutes les [heartbeatMs] qu'elle regarde toujours, puis le rend.
 *
 * Serveur injoignable : le compte principal est utilisé comme avant (jamais d'écran bloqué faute de réseau).
 */
class AccountLeases(
    private val scope: CoroutineScope,
    /** Demande au serveur avec ce qui sera regardé et, pour « Prendre la main », la réservation à reprendre ; lève une exception si injoignable. */
    private val claim: suspend (label: String, takeover: String?) -> AccountClaim,
    /** Signe de vie avec ce qui est regardé en ce moment. */
    private val heartbeat: suspend (streamId: String, label: String) -> Boolean,
    private val release: suspend (streamId: String) -> Unit,
    /** Identifiants du compte n° [position] (2, 3…) ; `null` s'il est inconnu (retiré entre-temps). */
    private val account: suspend (position: Int) -> XtreamCreds?,
    /** Réservation perdue : qui a pris la main (`null` : personne). */
    private val takenOver: suspend (streamId: String) -> home.iptv.shared.sync.TakenBy? = { null },
    /** Après « Prendre la main » : l'autre télé a-t-elle arrêté sa vidéo ? */
    private val takeoverDone: suspend (streamId: String) -> Boolean = { true },
    /** Attente au plus de l'arrêt de l'autre télé (deux vidéos sur un compte : le fournisseur couperait). */
    private val takeoverWaitMs: Long = 8_000,
    private val claimTimeoutMs: Long = 4_000,
    private val heartbeatMs: Long = 15_000,
    private val releaseGraceMs: Long = 5_000,
    /** Après « tout est pris », pas de nouvelle demande au serveur pendant ce temps (une chaîne qui échoue en essaie une autre). */
    private val busyMemoryMs: Long = 8_000,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private data class Lease(val streamId: String?, val position: Int, val creds: XtreamCreds?)

    private val lock = Mutex()
    @Volatile private var lease: Lease? = null
    private var heartbeatJob: Job? = null
    private var releaseJob: Job? = null
    private var busyUntil = 0L
    private var lastBusy: List<Occupant> = emptyList()

    private val _position = MutableStateFlow<Int?>(null)
    /** Compte réservé en ce moment (1 = principal), pour le calque de diagnostic ; `null` : aucun. */
    val position: StateFlow<Int?> = _position

    /** Ce qui est regardé (envoyé avec chaque signe de vie : l'autre télé voit le changement de chaîne). */
    @Volatile private var label: String = ""

    /** Compte déjà réservé (changement de chaîne) : sans attendre le serveur. `null` si aucun. */
    fun current(label: String = ""): LeaseResult.Use? = lease?.let { uses.incrementAndGet(); this.label = label; LeaseResult.Use(it.creds) }

    /** Compte de vidéos démarrées : un rendu prévu n'a lieu que si aucune n'a démarré depuis. */
    private val uses = java.util.concurrent.atomic.AtomicLong()

    /** Appelé (hors du verrou) quand la réservation est perdue pendant une vidéo et qu'aucun compte ne la remplace. */
    @Volatile var onLost: (String) -> Unit = {}

    /**
     * Une vidéo démarre : compte gardé s'il y en a un, sinon demande au serveur. [takeover] : réservation d'une autre
     * télé à reprendre (« Prendre la main », après un [LeaseResult.Busy]).
     */
    suspend fun acquire(label: String = "", takeover: String? = null): LeaseResult = lock.withLock {
        uses.incrementAndGet()
        this.label = label
        releaseJob?.cancel(); releaseJob = null
        lease?.let { return@withLock LeaseResult.Use(it.creds) }
        if (takeover == null && clockMs() < busyUntil) return@withLock LeaseResult.Busy(lastBusy)
        val answer = try {
            withTimeoutOrNull(claimTimeoutMs) { claim(label, takeover) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return@withLock LeaseResult.Use(null)
        when (answer) {
            is AccountClaim.Busy -> {
                busyUntil = clockMs() + busyMemoryMs
                lastBusy = answer.occupants
                LeaseResult.Busy(answer.occupants)
            }
            is AccountClaim.Granted -> {
                val creds = if (answer.position <= 1) null else try { account(answer.position) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                if (answer.position > 1 && creds == null) {
                    // Compte inconnu de cette télé (retiré à l'instant, ou illisible) : le principal est pris par une
                    // autre télé, on ne peut donc pas lire ; la réservation est rendue.
                    answer.streamId?.let { id -> scope.launch { quietly { release(id) } } }
                    return@withLock LeaseResult.Busy(emptyList())
                }
                busyUntil = 0L
                // Place reprise à une autre télé : on attend qu'elle ait arrêté sa vidéo (quelques secondes au plus).
                answer.replaced?.let { waitStopped(it) }
                val l = Lease(answer.streamId, answer.position, creds)
                lease = l
                _position.value = l.position
                startHeartbeat(l)
                LeaseResult.Use(creds)
            }
        }
    }

    private suspend fun waitStopped(replaced: String) {
        withTimeoutOrNull(takeoverWaitMs) {
            while (true) {
                val done = try { takeoverDone(replaced) } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
                if (done) break
                delay(TAKEOVER_POLL_MS)
            }
        }
    }

    /** La vidéo s'est arrêtée : le compte est rendu si aucune autre ne démarre dans [releaseGraceMs]. */
    fun stopped() {
        // Relevé tout de suite (pas dans la tâche) : une vidéo démarrée juste après la fermeture doit garder le compte.
        val since = uses.get()
        scope.launch {
            lock.withLock {
                if (lease == null || uses.get() != since) return@withLock
                releaseJob?.cancel()
                releaseJob = scope.launch {
                    delay(releaseGraceMs)
                    // Une vidéo démarrée pendant l'attente (changement de chaîne) garde le compte.
                    lock.withLock { if (uses.get() == since) drop() }
                }
            }
        }
    }

    /** L'app passe en arrière-plan : compte rendu tout de suite. */
    fun releaseNow() {
        scope.launch { lock.withLock { releaseJob?.cancel(); releaseJob = null; drop() } }
    }

    /** Sous [lock]. */
    private fun drop() {
        val l = lease ?: return
        lease = null
        _position.value = null
        heartbeatJob?.cancel(); heartbeatJob = null
        l.streamId?.let { id -> scope.launch { quietly { release(id) } } }
    }

    private fun startHeartbeat(l: Lease) {
        heartbeatJob?.cancel()
        val id = l.streamId ?: return
        heartbeatJob = scope.launch {
            while (true) {
                delay(heartbeatMs)
                val alive = try { heartbeat(id, label) } catch (e: CancellationException) { throw e } catch (e: Exception) { true }
                if (!alive) {
                    // Une autre télé a pris la main : on s'arrête tout de suite, sans redemander de compte.
                    val by = try { takenOver(id) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                    if (by != null) {
                        val dropped = lock.withLock { if (lease === l) { lease = null; _position.value = null; true } else false }
                        if (dropped) onLost(takenOverMessage(by))
                        return@launch
                    }
                    // Réservation perdue (coupure réseau de plus de 45 s) : on redemande aussitôt. Même compte rendu :
                    // la vidéo continue ; sinon (compte pris entre-temps, ou un autre) elle s'arrête avec un message.
                    val again = try { withTimeoutOrNull(claimTimeoutMs) { claim(label, null) } } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                    val kept = lock.withLock {
                        if (lease !== l) return@launch
                        if (again is AccountClaim.Granted && again.position == l.position) {
                            val renewed = Lease(again.streamId, l.position, l.creds)
                            lease = renewed
                            heartbeatJob = null
                            startHeartbeat(renewed)
                            true
                        } else {
                            lease = null; _position.value = null
                            if (again is AccountClaim.Granted) again.streamId?.let { id -> scope.launch { quietly { release(id) } } }
                            false
                        }
                    }
                    if (!kept) onLost(if (again == null) LOST_OFFLINE else busyMessage((again as? AccountClaim.Busy)?.occupants.orEmpty()))
                    return@launch
                }
            }
        }
    }

    private suspend fun quietly(block: suspend () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { /* réservation libérée seule après 45 s */ }
    }
}

private const val TAKEOVER_POLL_MS = 1_000L

/** « Hamza a pris la main depuis Télé de la chambre : ta vidéo est arrêtée. » */
fun takenOverMessage(by: home.iptv.shared.sync.TakenBy): String {
    val who = by.profileName?.takeIf { it.isNotBlank() }
    val where = by.deviceName.takeIf { it.isNotBlank() } ?: "une autre télé"
    return if (who != null) "$who a pris la main depuis $where : ta vidéo est arrêtée."
    else "Quelqu’un a pris la main depuis $where : ta vidéo est arrêtée."
}

/** Réservation perdue et serveur injoignable. */
const val LOST_OFFLINE = "Connexion perdue avec le serveur de la maison : vidéo arrêtée pour ne pas couper l’autre télé."

/** « Les comptes sont tous utilisés : Maha sur Télé du salon. » */
fun busyMessage(occupants: List<Occupant>): String {
    val who = occupants.joinToString(", ") { o -> listOfNotNull(o.profileName?.takeIf { it.isNotBlank() }, o.deviceName.takeIf { it.isNotBlank() }).joinToString(" sur ") }
        .takeIf { it.isNotBlank() }
    val n = occupants.size
    val head = if (n <= 1) "Le compte IPTV est déjà utilisé" else "Les $n comptes IPTV sont déjà utilisés"
    return if (who == null) "$head." else "$head : $who."
}
