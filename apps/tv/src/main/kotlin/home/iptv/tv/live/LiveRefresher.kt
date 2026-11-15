package home.iptv.tv.live

import home.iptv.shared.catalog.CatalogPublisher
import home.iptv.shared.catalog.CatalogRepository
import home.iptv.shared.catalog.LiveSource
import home.iptv.shared.catalog.RefreshReport
import home.iptv.shared.sync.SyncClient
import home.iptv.shared.xtream.UserInfo
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

private const val SYNC_TIMEOUT_MS = 30_000L

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

    /**
     * D'abord les catégories masquées pour tout le monde : si cet appel échoue, on ne touche pas au fournisseur
     * (sinon des catégories masquées seraient téléchargées) et l'erreur remonte, le cache reste affiché.
     */
    suspend fun run(onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }): RefreshReport {
        val household = checkNotNull(householdId()) { "foyer inconnu" }
        // Sans réponse de la synchro, on abandonne (erreur ordinaire, le cache reste affiché) : jamais d'attente sans fin
        // (essai TCL 0.4.0 : « Mise à jour » permanent), et jamais le fournisseur sans la liste des masquées.
        val hidden = try {
            withTimeout(SYNC_TIMEOUT_MS) { sync.globalHiddenCategories(household) }
        } catch (e: TimeoutCancellationException) {
            throw java.io.IOException("liste des catégories masquées sans réponse")
        }.filter { it.itemType == "live_category" }.map { it.itemId }.toSet()
        try {
            format = chooseFormat(userInfo().allowedOutputFormats)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Format inconnu (hors ligne, panneau capricieux) : on garde `ts`.
        }
        return repo.refreshLive(source(), hidden, onProgress)
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
