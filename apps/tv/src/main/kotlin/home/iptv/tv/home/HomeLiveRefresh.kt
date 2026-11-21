package home.iptv.tv.home

import home.iptv.shared.catalog.CatalogRepository
import home.iptv.shared.catalog.LiveRefresher
import home.iptv.shared.catalog.RefreshReport
import home.iptv.shared.xtream.EpgEntry
import home.iptv.shared.xtream.EpgSource
import home.iptv.tv.data.LocalStore

data class RefreshOutcome(val report: RefreshReport, val fromServer: Boolean)

private const val SERVER_ABSENT_FALLBACK_MS = 24 * 3_600_000L

/**
 * Mise à jour du Direct sur la télé : demande d'abord la liste prête au serveur maison ([home]), et ne retombe sur le
 * téléchargement chez le fournisseur ([local]) que si le cache est vide ou si le serveur maison n'a pas répondu depuis
 * plus de 24 h. Quand la liste vient du serveur, le serveur a déjà publié pour l'iPhone : [publish] ne fait rien.
 */
class HomeLiveRefresh(
    private val home: HomeServerClient,
    private val repo: CatalogRepository,
    private val local: LiveRefresher,
    private val store: LocalStore,
    private val account: () -> String?,
    private val clockMs: () -> Long,
) {
    /** Catégories masquées pour tout le monde lors de la dernière mise à jour réussie (serveur ou secours). */
    @Volatile var lastHidden: Set<String>? = null
        private set

    @Volatile private var lastFromServer: Boolean = false

    suspend fun run(onProgress: suspend (Int, Int) -> Unit = { _, _ -> }): RefreshOutcome {
        return when (val res = home.liveSnapshot(store.lastSnapshotRev())) {
            is LiveSnapshotResult.Fresh -> {
                val snap = res.snapshot
                val currentAccount = account()
                if (snap.account != currentAccount) {
                    // Le serveur sert un autre compte IPTV : on l'ignore et on suit la règle du secours.
                    fallbackOrKeepCache(onProgress)
                } else {
                    val report = repo.replaceLive(snap)
                    store.saveLastSnapshotRev(snap.rev)
                    store.saveLastServerContactMs(clockMs())
                    lastHidden = snap.hidden.toSet()
                    lastFromServer = true
                    RefreshOutcome(report, fromServer = true)
                }
            }
            LiveSnapshotResult.Unchanged -> {
                store.saveLastServerContactMs(clockMs())
                val cats = repo.categories()
                if (cats.isEmpty()) {
                    // Cache vidé alors que lastSnapshotRev restait présent : on relance sans révision.
                    store.saveLastSnapshotRev(null)
                    fallbackOrKeepCache(onProgress)
                } else {
                    lastFromServer = true
                    RefreshOutcome(
                        RefreshReport(categories = cats.size, streams = 0, failedCategories = emptyList(), providerCategories = cats),
                        fromServer = true,
                    )
                }
            }
            LiveSnapshotResult.Unavailable -> fallbackOrKeepCache(onProgress)
        }
    }

    private suspend fun fallbackOrKeepCache(onProgress: suspend (Int, Int) -> Unit): RefreshOutcome {
        val cats = repo.categories()
        val lastContact = store.lastServerContactMs()
        val absentTooLong = lastContact == null || clockMs() - lastContact > SERVER_ABSENT_FALLBACK_MS
        return if (cats.isEmpty() || absentTooLong) {
            val report = local.run(onProgress)
            // Le cache vient maintenant du fournisseur : au retour du serveur maison, il faudra recevoir sa liste complète.
            store.saveLastSnapshotRev(null)
            lastHidden = local.lastHidden
            lastFromServer = false
            RefreshOutcome(report, fromServer = false)
        } else {
            lastFromServer = true
            RefreshOutcome(
                RefreshReport(categories = cats.size, streams = 0, failedCategories = emptyList(), providerCategories = cats),
                fromServer = true,
            )
        }
    }

    /** Publie vers l'iPhone seulement si la dernière mise à jour a été faite par la télé elle-même ([fromServer] == false). */
    suspend fun publish(report: RefreshReport) {
        if (!lastFromServer) local.publish(report)
    }
}

/** Guide des programmes qui interroge d'abord le serveur maison, puis le fournisseur en secours si le résultat est nul. */
fun homeEpgSource(home: HomeServerClient, fallback: EpgSource): EpgSource = object : EpgSource {
    override suspend fun shortEpg(streamId: String, limit: Int): List<EpgEntry> =
        home.epg(streamId, limit, full = false) ?: fallback.shortEpg(streamId, limit)

    override suspend fun fullEpg(streamId: String): List<EpgEntry> =
        home.epg(streamId, limit = 8, full = true) ?: fallback.fullEpg(streamId)
}
