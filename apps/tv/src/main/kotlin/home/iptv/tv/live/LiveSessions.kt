package home.iptv.tv.live

import home.iptv.shared.groups.GroupsClient
import home.iptv.shared.catalog.RefreshReport
import home.iptv.shared.catalog.CatalogRepository
import home.iptv.shared.sync.Profile
import home.iptv.shared.sync.SyncClient
import home.iptv.tv.data.LocalStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Un écran du direct pour un profil : son affichage, son observateur de préférences et sa portée de coroutines. */
class LiveSession internal constructor(
    val profile: Profile,
    val vm: LiveBrowseViewModel,
    internal val watcher: PrefsWatcher,
    private val scope: CoroutineScope,
    internal val groupsWatcher: GroupsWatcher?,
    private val stopGroups: () -> Unit,
) {
    val isActive: Boolean get() = scope.coroutineContext[Job]?.isActive == true
    internal fun stop() { watcher.stop(); stopGroups() }
    internal fun cancel() { stop(); scope.cancel(); groupsWatcher?.clear(); vm.releaseGroups() }
}

/**
 * Les écrans du direct, un à la fois. Ouvrir un profil ferme entièrement le précédent (portée annulée, observateur
 * arrêté) ; fermer une session déjà remplacée est sans effet. Le catalogue n'est rafraîchi et publié qu'une fois
 * par processus : un autre profil ouvert ensuite affiche le cache avec ses propres préférences.
 */
class LiveSessions(
    private val parentScope: CoroutineScope,
    private val sync: SyncClient,
    private val store: LocalStore,
    private val repo: CatalogRepository,
    private val householdId: () -> String?,
    private val refresh: suspend (onProgress: suspend (done: Int, total: Int) -> Unit) -> RefreshReport,
    private val publish: suspend (RefreshReport) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val periodMs: Long = 4_000,
    private val groupsClient: GroupsClient? = null,
) {
    private var current: LiveSession? = null
    @Volatile private var catalogDone = false

    fun open(profile: Profile): LiveSession {
        current?.cancel()
        val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
        val groupsWatcher = groupsClient?.let { GroupsWatcher(it, store, householdId, io) }
        var groupRefresh: Job? = null
        val watcher = PrefsWatcher(sync, store, householdId, profile.id, scope, periodMs, io,
            onRevision = { revision ->
                // Les masques B1 arrivent meme si le reseau des groupes est lent. Une seule requete en vol.
                if (groupsWatcher != null && groupRefresh?.isActive != true) {
                    groupRefresh = scope.launch { groupsWatcher.refresh(revision) }
                }
            })
        watcher.start()
        val vm = LiveBrowseViewModel(
            repo,
            if (catalogDone) null else refresh,
            scope, io,
            prefs = watcher.prefs,
            groupSnapshot = groupsWatcher?.snapshot ?: kotlinx.coroutines.flow.MutableStateFlow(null),
            store = store, profileId = profile.id,
            // Terminé seulement une fois la publication faite : une session annulée avant cela laisse la suivante réessayer.
            publish = { report -> publish(report); catalogDone = true },
        )
        return LiveSession(profile, vm, watcher, scope, groupsWatcher) { groupRefresh?.cancel(); groupRefresh = null }.also { current = it }
    }

    fun close(session: LiveSession) {
        if (current !== session) return
        session.cancel()
        current = null
    }

    /** onStop : pas d'interrogation du serveur en arrière-plan. */
    fun stop() { current?.stop() }

    /** onStart : l'écran est de retour. */
    fun resume() { current?.watcher?.start() }
}
