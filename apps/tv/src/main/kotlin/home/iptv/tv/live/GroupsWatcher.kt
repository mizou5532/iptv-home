package home.iptv.tv.live

import home.iptv.shared.groups.GroupSnapshot
import home.iptv.shared.groups.GroupsClient
import home.iptv.tv.data.LocalStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** Reveille par le polling B1 des preferences ; le cache appartient au foyer, jamais au profil. */
class GroupsWatcher(
    private val client: GroupsClient,
    private val store: LocalStore,
    private val householdId: () -> String?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private var household = householdId()
    private var prefsRevision: Long? = null
    private val _snapshot = MutableStateFlow(household?.let(store::groupSnapshot)?.takeIf { it.rulesVersion == 1 })
    val snapshot: StateFlow<GroupSnapshot?> = _snapshot
    private val lock = Mutex()

    suspend fun refresh(revision: Long) = lock.withLock {
        val h = householdId()
        if (h != household) {
            household = h
            prefsRevision = null
            _snapshot.value = null
            val local = h?.let { withContext(io) { store.groupSnapshot(it) } }?.takeIf { it.rulesVersion == 1 }
            if (householdId() != h) { clear(); return@withLock }
            _snapshot.value = local
        }
        if (h == null || prefsRevision == revision) return@withLock
        try {
            val groupRevision = client.revision(h)
            if (householdId() != h) { clear(); return@withLock }
            if (_snapshot.value?.rev != groupRevision) {
                val fresh = client.snapshot(h)
                require(fresh.rulesVersion == 1)
                if (householdId() != h) { clear(); return@withLock }
                withContext(io) { store.saveGroupSnapshot(h, fresh) }
                if (householdId() != h) { clear(); return@withLock }
                _snapshot.value = fresh
            }
            prefsRevision = revision
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            if (householdId() != h) clear()
            // Hors ligne : conserver uniquement le dernier snapshot complet du foyer courant.
        }
    }

    fun clear() { household = null; prefsRevision = null; _snapshot.value = null }
}
