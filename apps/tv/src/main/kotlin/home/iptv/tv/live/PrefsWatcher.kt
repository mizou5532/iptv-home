package home.iptv.tv.live

import home.iptv.shared.sync.ProfilePrefs
import home.iptv.shared.sync.SyncClient
import home.iptv.tv.data.LocalStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Suit l'organisation d'un profil : émet d'abord la dernière copie locale, puis celle du serveur.
 * Tant qu'il est démarré, interroge la révision du foyer toutes les [periodMs] et ne relit les préférences
 * que si elle a changé. Les erreurs réseau sont silencieuses : la dernière valeur est gardée.
 */
class PrefsWatcher(
    private val sync: SyncClient,
    private val store: LocalStore,
    private val householdId: () -> String?,
    private val profileId: String,
    private val scope: CoroutineScope,
    private val periodMs: Long = 4_000,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val onRevision: suspend (Long) -> Unit = {},
) {
    private val _prefs = MutableStateFlow(store.prefs(profileId))
    val prefs: StateFlow<ProfilePrefs?> = _prefs
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                poll()
                delay(periodMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun poll() {
        try {
            val household = householdId() ?: run { onRevision(0); return }
            val revision = sync.prefsRevision(household)
            onRevision(revision)
            if (householdId() != household) return
            val known = _prefs.value
            if (known != null && revision == known.rev) return
            val fresh = sync.profilePrefs(profileId)
            if (householdId() != household) return
            _prefs.value = fresh
            withContext(io) { store.savePrefs(profileId, fresh) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Hors ligne ou serveur capricieux : on garde la dernière organisation connue.
        }
    }
}
