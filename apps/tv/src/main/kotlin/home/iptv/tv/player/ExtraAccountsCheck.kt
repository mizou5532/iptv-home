package home.iptv.tv.player

import home.iptv.shared.sync.ExtraAccount
import home.iptv.shared.xtream.LiveCategory
import home.iptv.shared.xtream.LiveStream
import home.iptv.shared.xtream.UserInfo
import home.iptv.shared.xtream.XtreamCreds
import home.iptv.shared.xtream.XtreamError
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/** Ce que la vérification demande au fournisseur, pour un compte donné. */
interface AccountProbe {
    suspend fun userInfo(creds: XtreamCreds): UserInfo
    suspend fun liveCategories(creds: XtreamCreds): List<LiveCategory>
    suspend fun liveStreams(creds: XtreamCreds, categoryId: String): List<LiveStream>
}

/** Résultat pour un compte : `ok`, ou une raison de refus ([ExtraAccount.REASONS]) ; `null` = à refaire (réseau). */
data class AccountVerdict(val ok: Boolean, val reason: String?, val expiresAt: Long?)

/**
 * Vérifie les comptes supplémentaires « à vérifier » : acceptés par le fournisseur, actifs, et donnant le même catalogue
 * que le compte principal (mêmes catégories du Direct et mêmes chaînes dans une catégorie visible). Le résultat est
 * envoyé au serveur (page iPhone) ; seul un compte « ok » est ensuite donné à une télé.
 *
 * Masqué gagne : la catégorie comparée n'est jamais une catégorie masquée pour tout le monde (rien n'en est téléchargé).
 */
class ExtraAccountsCheck(
    private val probe: AccountProbe,
    private val main: () -> XtreamCreds?,
    private val accounts: suspend () -> List<ExtraAccount>,
    /** Catégories du Direct masquées pour tout le monde (jamais téléchargées) ; lève une exception si inconnues. */
    private val hiddenForEveryone: suspend () -> Set<String>,
    private val report: suspend (accountId: String, verdict: AccountVerdict) -> Unit,
    private val nowS: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val running = Mutex()

    /** Vérifie les comptes en attente ; une seule vérification à la fois (les demandes pendant ce temps sont ignorées). */
    suspend fun runPending() {
        if (!running.tryLock()) return
        try {
            val m = main() ?: return
            val pending = accounts().filter { it.status == "a_verifier" }
            if (pending.isEmpty()) return
            for (a in pending) {
                val v = verdict(m, a) ?: continue
                try { report(a.id, v) } catch (e: CancellationException) { throw e } catch (e: Exception) { /* au prochain tour */ }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Serveur ou fournisseur injoignable : nouvel essai au prochain tour.
        } finally {
            running.unlock()
        }
    }

    /** `null` : impossible de conclure (réseau) ; le compte reste « à vérifier ». */
    internal suspend fun verdict(main: XtreamCreds, a: ExtraAccount): AccountVerdict? {
        if (a.username.trim() == main.username.trim()) return AccountVerdict(false, "meme_compte", null)
        val other = XtreamCreds(main.server, a.username, a.password)
        val info = try {
            probe.userInfo(other)
        } catch (e: CancellationException) {
            throw e
        } catch (e: XtreamError.Refused) {
            return AccountVerdict(false, "identifiants", null)
        } catch (e: Exception) {
            return null
        }
        val expired = info.status?.let { !it.equals("Active", ignoreCase = true) } == true ||
            info.expiresAt?.let { it <= nowS() } == true
        if (expired) return AccountVerdict(false, "expire", info.expiresAt)
        return try {
            val mainCats = probe.liveCategories(main)
            val mine = mainCats.map { it.id }.toSet()
            val theirs = probe.liveCategories(other).map { it.id }.toSet()
            if (mine.isEmpty() || mine != theirs) return AccountVerdict(false, "catalogue", info.expiresAt)
            val hidden = hiddenForEveryone()
            val sample = mainCats.firstOrNull { it.id !in hidden }?.id
            if (sample != null) {
                val a1 = probe.liveStreams(main, sample).map { it.id }.toSet()
                val a2 = probe.liveStreams(other, sample).map { it.id }.toSet()
                if (a1 != a2) return AccountVerdict(false, "catalogue", info.expiresAt)
            }
            AccountVerdict(true, null, info.expiresAt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}
