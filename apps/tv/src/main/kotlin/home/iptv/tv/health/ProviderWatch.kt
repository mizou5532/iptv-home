package home.iptv.tv.health

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Cause d'une série d'échecs de lecture, vérifiée avant d'avertir. */
enum class Outage { PROVIDER_DOWN, NO_INTERNET }

/** Le lecteur signale ici chaque vidéo qui démarre (image reçue) ou qui échoue. */
object PlaybackSignals {
    @Volatile var watch: ProviderWatch? = null
    fun started() { watch?.success() }
    fun failed() { watch?.failure() }
}

const val OUTAGE_PROVIDER = "Le fournisseur IPTV ne répond pas en ce moment : ce n'est ni ta télé ni ta connexion. Réessaie dans quelques minutes."
const val OUTAGE_INTERNET = "La télé n'a plus accès à internet : vérifie le Wi-Fi ou le modem."

/**
 * Alerte « panne » (demande du propriétaire) : après [failures] échecs de lecture en [windowMs] sans aucune vidéo
 * réussie entre-temps, la télé vérifie qui est en cause ([check] : internet puis fournisseur). Fournisseur muet alors
 * qu'internet marche → message clair ; plus d'internet → message pour le Wi-Fi. Un problème d'une seule chaîne ne
 * déclenche rien. Au plus un message toutes les [quietMs] ; il s'efface seul après [showMs] ou à la vidéo suivante qui marche.
 */
class ProviderWatch(
    private val scope: CoroutineScope,
    private val check: suspend () -> Outage?,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val failures: Int = 3,
    private val windowMs: Long = 2 * 60_000,
    private val quietMs: Long = 5 * 60_000,
    private val showMs: Long = 15_000,
) {
    private val _alert = MutableStateFlow<String?>(null)
    val alert: StateFlow<String?> = _alert
    private val recent = ArrayDeque<Long>()
    private var lastAlertAt = Long.MIN_VALUE / 2
    private var checking: Job? = null
    private var hide: Job? = null

    /** Compte des vidéos réussies : une vérification lancée avant une réussite n'avertit plus. */
    private var successes = 0L

    @Synchronized fun success() {
        successes++
        recent.clear()
        checking?.cancel(); checking = null
        _alert.value = null
    }

    @Synchronized fun failure() {
        val now = clockMs()
        recent.addLast(now)
        while (recent.isNotEmpty() && now - recent.first() > windowMs) recent.removeFirst()
        if (recent.size < failures || now - lastAlertAt < quietMs || checking?.isActive == true) return
        val since = successes
        checking = scope.launch {
            val outage = try { check() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            val text = when (outage) { Outage.PROVIDER_DOWN -> OUTAGE_PROVIDER; Outage.NO_INTERNET -> OUTAGE_INTERNET; null -> null }
            synchronized(this@ProviderWatch) {
                // Une vidéo a démarré pendant la vérification : tout va bien, aucun message.
                if (successes != since) return@launch
                if (text == null) {
                    // Fournisseur et internet en marche (chaînes mortes) : pas de nouvelle vérification à chaque échec.
                    recent.clear()
                    return@launch
                }
                lastAlertAt = clockMs()
                recent.clear()
                _alert.value = text
                hide?.cancel()
                hide = scope.launch { delay(showMs); if (_alert.value == text) _alert.value = null }
            }
        }
    }
}
