package home.iptv.tv.live

import home.iptv.shared.groups.GroupsClient
import home.iptv.shared.catalog.RefreshReport
import home.iptv.shared.catalog.CatalogRepository
import home.iptv.shared.sync.Profile
import home.iptv.shared.sync.SyncClient
import home.iptv.tv.data.LocalStore
import home.iptv.tv.live.epg.EpgRepository
import home.iptv.tv.live.epg.EpgSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.Deferred
import home.iptv.tv.diag.Diagnostics
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.cancellation.CancellationException

/** Un écran du direct pour un profil : son affichage, son observateur de préférences et sa portée de coroutines. */
class LiveSession internal constructor(
    val profile: Profile,
    val vm: LiveBrowseViewModel,
    internal val watcher: PrefsWatcher,
    private val scope: CoroutineScope,
    internal val groupsWatcher: GroupsWatcher?,
    private val stopGroups: () -> Unit,
    /** Guide maintenant / ensuite de cette session ; fermé avec elle. */
    val epg: EpgRepository? = null,
) {
    /** Vrai quand la liste du Direct est à l'écran (pas en plein écran) : réglages vérifiés au rythme rapide. */
    @Volatile var fastPoll: Boolean = false
        private set

    /** La liste du Direct apparaît (vérification immédiate puis rapide) ou disparaît (rythme lent). */
    fun setFastPoll(on: Boolean) {
        if (fastPoll == on) return
        fastPoll = on
        if (on) watcher.pokeNow()
    }

    val isActive: Boolean get() = scope.coroutineContext[Job]?.isActive == true
    internal fun stop() { watcher.stop(); stopGroups(); epg?.pause() }
    internal fun resume() { watcher.start(); epg?.resume() }
    internal fun cancel() { stop(); epg?.close(); scope.cancel(); groupsWatcher?.clear(); vm.releaseGroups() }
}

/**
 * Les écrans du direct, un à la fois. Ouvrir un profil ferme entièrement le précédent (portée annulée, observateur
 * arrêté) ; fermer une session déjà remplacée est sans effet. Le catalogue n'est rafraîchi et publié qu'une fois
 * par processus : un autre profil ouvert ensuite affiche le cache avec ses propres préférences.
 */
/** Rythme des réglages hors de la liste du Direct. */
const val SLOW_PREFS_MS = 30_000L

/** Âge au-delà duquel la liste des chaînes est remise à jour au retour de veille. */
const val CATALOG_STALE_MS = 6 * 60 * 60_000L

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
    private val epgSource: EpgSource? = null,
    private val refreshDeadlineMs: Long = REFRESH_DEADLINE_MS,
    /** Rythme lent des réglages, hors de la liste du Direct (pendant un film, dans Séries…). */
    private val slowPeriodMs: Long = SLOW_PREFS_MS,
    /** Temps monotone (ms), qui continue pendant la veille. */
    private val clockMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    /** Catégories masquées pour tout le monde au moment de la dernière mise à jour réussie (`null` si inconnues). */
    private val hiddenAtRefresh: () -> Set<String>? = { null },
    /** Au-delà, une mise à jour est relancée au retour de veille. */
    private val staleAfterMs: Long = CATALOG_STALE_MS,
) {
    private var current: LiveSession? = null
    @Volatile private var catalogDone = false
    @Volatile private var lastRefreshDoneMs: Long? = null
    /** Dernière révision des réglages vue (pour le foyer) : une nouvelle révision fait vérifier les catégories réaffichées. */
    @Volatile private var lastRevision: Long? = null
    @Volatile private var hiddenCheck: Job? = null
    /** Vérification des catégories réaffichées à refaire (sautée, ou ratée faute de réseau) au prochain tour. */
    @Volatile private var recheckHidden = false
    /** App en arrière-plan (onStop) : aucune mise à jour ne doit démarrer. */
    @Volatile private var stopped = false

    private val refreshLock = Any()
    private var sharedRefresh: Deferred<Result<RefreshReport>>? = null
    private val progressListeners = CopyOnWriteArrayList<suspend (Int, Int) -> Unit>()

    /**
     * Un seul rafraîchissement du catalogue par processus, qui n'appartient à aucun profil (portée parente) : une
     * session qui s'ouvre pendant qu'il tourne le suit, avancement compris, au lieu de tout relancer depuis la première
     * catégorie (essai TCL 0.4.0 : « Mise à jour » revenait à chaque changement de profil et ne finissait jamais).
     * Borné à [refreshDeadlineMs] ; un échec ou un abandon laisse la session suivante réessayer.
     */
    private suspend fun joinRefresh(onProgress: suspend (done: Int, total: Int) -> Unit): RefreshReport = joinRefresh(onProgress, again = false)

    /** [again] : nouvelle mise à jour voulue (liste ancienne, catégorie réaffichée), sauf si une est déjà en cours. */
    private suspend fun joinRefresh(onProgress: suspend (done: Int, total: Int) -> Unit, again: Boolean): RefreshReport {
        progressListeners += onProgress
        try {
            val job = synchronized(refreshLock) {
                // En cours : on le suit ; déjà réussi (session ouverte pendant, affichée après) : son résultat, sans relance.
                sharedRefresh?.takeIf { it.isActive || (catalogDone && !again) } ?: parentScope.async(io) { runRefresh() }.also { sharedRefresh = it }
            }
            return job.await().getOrThrow()
        } finally {
            progressListeners -= onProgress
        }
    }

    private suspend fun runRefresh(): Result<RefreshReport> = try {
        Diagnostics.refresh = "en cours"
        val report = withTimeout(refreshDeadlineMs) {
            refresh { done, total ->
                Diagnostics.refresh = "en cours : $done / $total catégories"
                for (listener in progressListeners) {
                    try { listener(done, total) } catch (e: CancellationException) { throw e } catch (e: Exception) {}
                }
            }
        }
        // Le catalogue est à jour dès maintenant : un changement de profil pendant la publication ne relance rien.
        catalogDone = true
        lastRefreshDoneMs = clockMs()
        Diagnostics.refresh = "terminée, ${report.categories} catégories" +
            if (report.failedCategories.isEmpty()) "" else ", ${report.failedCategories.size} sans réponse"
        // Publication vers le téléphone, une seule fois, elle aussi indépendante du profil ; elle n'échoue jamais.
        parentScope.launch(io) {
            try { publish(report) } catch (e: CancellationException) { throw e } catch (e: Exception) {}
        }
        Result.success(report)
    } catch (e: TimeoutCancellationException) {
        Diagnostics.refresh = "arrêtée après ${refreshDeadlineMs / 60_000} min (trop longue)"
        Result.failure(RefreshTooLong())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Le nom de l'erreur seulement : son message pourrait contenir une adresse.
        Diagnostics.refresh = "échec (${e::class.simpleName})"
        Result.failure(e)
    }

    fun open(profile: Profile): LiveSession {
        current?.cancel()
        hiddenCheck?.cancel(); hiddenCheck = null
        val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
        val groupsWatcher = groupsClient?.let { GroupsWatcher(it, store, householdId, io) }
        var groupRefresh: Job? = null
        var session: LiveSession? = null
        val watcher = PrefsWatcher(sync, store, householdId, profile.id, scope, periodMs, io,
            onRevision = { revision ->
                // Les masques B1 arrivent meme si le reseau des groupes est lent. Une seule requete en vol.
                if (groupsWatcher != null && groupRefresh?.isActive != true) {
                    groupRefresh = scope.launch { groupsWatcher.refresh(revision) }
                }
                onHouseholdRevision(revision)
            },
            period = { if (session?.fastPoll == true) periodMs else slowPeriodMs })
        watcher.start()
        // Liste déjà faite mais vieille (veille longue) : ce Direct la remettra à jour à son ouverture.
        val stale = catalogDone && lastRefreshDoneMs?.let { clockMs() - it >= staleAfterMs } == true
        val vm = LiveBrowseViewModel(
            repo,
            when {
                !catalogDone -> ::joinRefresh
                stale -> { p -> joinRefresh(p, again = true) }
                else -> null
            },
            scope, io,
            prefs = watcher.prefs,
            groupSnapshot = groupsWatcher?.snapshot ?: kotlinx.coroutines.flow.MutableStateFlow(null),
            store = store, profileId = profile.id,
            // La publication est lancée par le rafraîchissement partagé (voir runRefresh), pas par la session.
        )
        val epg = epgSource?.let { EpgRepository(it, scope, guideIds = vm::guideCandidates) }
        return LiveSession(profile, vm, watcher, scope, groupsWatcher, { groupRefresh?.cancel(); groupRefresh = null }, epg)
            .also { current = it; session = it }
    }

    /**
     * Les réglages du foyer ont changé (révision nouvelle) : si une catégorie masquée pour tout le monde lors de la
     * dernière mise à jour ne l'est plus, ses chaînes ne sont pas dans le cache (jamais téléchargées) : on relance la
     * mise à jour, qui respecte toujours la liste actuelle des masquées. Une vérification à la fois.
     */
    private fun onHouseholdRevision(revision: Long) {
        val previous = lastRevision
        lastRevision = revision
        val changed = previous != null && previous != revision
        if (!changed && !recheckHidden) return
        // Pas encore de liste, ou vérification déjà en cours : on retient qu'il faudra vérifier au prochain tour.
        if (stopped || !catalogDone || hiddenCheck?.isActive == true) { recheckHidden = true; return }
        val before = hiddenAtRefresh() ?: run { recheckHidden = false; return }
        recheckHidden = false
        hiddenCheck = parentScope.launch(io) {
            try {
                val h = householdId() ?: return@launch
                val now = sync.globalHiddenCategories(h).filter { it.itemType == "live_category" }.mapTo(HashSet()) { it.itemId }
                if (before.any { it !in now }) refreshAgain()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Hors ligne : nouvel essai au prochain tour du suivi des réglages.
                recheckHidden = true
            }
        }
    }

    /** Nouvelle mise à jour en arrière-plan pour la session affichée (« Mise à jour » en haut, cache toujours affiché). */
    private fun refreshAgain() {
        if (stopped) return
        val s = current ?: return
        s.vm.refreshNow { onProgress -> joinRefresh(onProgress, again = true) }
    }

    fun close(session: LiveSession) {
        if (current !== session) return
        hiddenCheck?.cancel(); hiddenCheck = null
        session.cancel()
        current = null
    }

    /** onStop : pas d'interrogation du serveur en arrière-plan. */
    fun stop() {
        stopped = true
        hiddenCheck?.cancel(); hiddenCheck = null
        current?.stop()
    }

    /** onStart : l'écran est de retour ; une liste vieille de plus de [staleAfterMs] (veille d'une nuit) est remise à jour. */
    fun resume() {
        stopped = false
        current?.resume()
        val last = lastRefreshDoneMs ?: return
        if (catalogDone && clockMs() - last >= staleAfterMs) refreshAgain()
    }
}
