package home.iptv.tv.live

import home.iptv.shared.catalog.CatalogPublisher
import home.iptv.shared.catalog.CatalogRepository
import home.iptv.shared.catalog.LiveSource
import home.iptv.shared.catalog.RefreshReport
import home.iptv.shared.sync.SyncClient
import home.iptv.shared.xtream.UserInfo
import kotlin.coroutines.cancellation.CancellationException

/** `ts` sauf si le fournisseur liste des formats sans `ts` : alors `m3u8`. */
fun chooseFormat(allowed: List<String>): String =
    if (allowed.isEmpty() || allowed.any { it.equals("ts", ignoreCase = true) }) "ts" else "m3u8"

class LiveRefresher(
    private val sync: SyncClient,
    private val repo: CatalogRepository,
    private val householdId: () -> String?,
    private val userInfo: suspend () -> UserInfo,
    private val source: () -> LiveSource,
) {
    @Volatile var format: String = "ts"
        private set

    /** Catégories masquées pour tout le monde lors de la dernière mise à jour réussie ; `null` avant la première. */
    @Volatile var lastHidden: Set<String>? = null
        private set

    /**
     * D'abord les catégories masquées pour tout le monde : si cet appel échoue, on ne touche pas au fournisseur
     * (sinon des catégories masquées seraient téléchargées) et l'erreur remonte, le cache reste affiché.
     */
    suspend fun run(onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }): RefreshReport {
        val household = checkNotNull(householdId()) { "foyer inconnu" }
        val hidden = sync.globalHiddenCategories(household)
            .filter { it.itemType == "live_category" }.map { it.itemId }.toSet()
        try {
            format = chooseFormat(userInfo().allowedOutputFormats)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Format inconnu (hors ligne, panneau capricieux) : on garde `ts`.
        }
        return repo.refreshLive(source(), hidden, onProgress).also { lastHidden = hidden }
    }


    private val publisher = CatalogPublisher(sync, repo)

    /**
     * Publie la copie du catalogue vers le téléphone, après le rafraîchissement local. N'échoue jamais :
     * une publication interrompue (réseau coupé) est simplement ignorée, la prochaine reprendra.
     */
    suspend fun publish(report: RefreshReport) {
        try {
            val household = householdId() ?: return
            publisher.publish(household, report)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Les catégories non publiées gardent leur empreinte côté serveur : republiées au prochain rafraîchissement.
        }
    }
}
