package home.iptv.server

import home.iptv.shared.catalog.CatalogRepository
import home.iptv.shared.catalog.LiveRefresher
import home.iptv.shared.groups.cachedAutoKey
import home.iptv.shared.groups.cachedTitle
import home.iptv.shared.groups.categoryCountries
import home.iptv.shared.home.LiveSnapshot
import home.iptv.shared.home.SnapCategory
import home.iptv.shared.home.SnapStream
import home.iptv.shared.home.accountFingerprint
import home.iptv.shared.home.liveSnapshotRev
import home.iptv.shared.sync.SyncClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Format du fichier gardé et de la réponse `/v1/live` (les valeurs par défaut sont écrites aussi). */
internal val snapshotJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

/**
 * Met le Direct à jour pour la télé : télécharge chez le fournisseur (via [refresher]), publie pour la page iPhone,
 * puis construit la liste servie par `/v1/live` et la garde dans [file] (relue au démarrage).
 *
 * Les identifiants IPTV ne passent jamais ici : [beforeRun] les lit (Supabase) à chaque mise à jour pour la source,
 * en mémoire seulement. [account] donne l'empreinte du compte courant (jamais les identifiants eux-mêmes).
 */
class LiveJob(
    private val refresher: LiveRefresher,
    private val repo: CatalogRepository,
    private val sync: SyncClient,
    private val householdId: String,
    private val account: () -> String?,
    private val clockMs: () -> Long,
    private val periodMs: Long = 6 * 3_600_000L,
    private val file: File,
    /** Appelé au début de chaque mise à jour (lecture des identifiants du foyer). */
    private val beforeRun: suspend () -> Unit = {},
    private val pollMs: Long = 30_000L,
) {
    private val state = MutableStateFlow<LiveSnapshot?>(readFile())
    val snapshot: StateFlow<LiveSnapshot?> = state

    private val running = Mutex()

    private fun readFile(): LiveSnapshot? = try {
        if (file.isFile) snapshotJson.decodeFromString(LiveSnapshot.serializer(), file.readText()) else null
    } catch (e: Exception) {
        null // fichier abîmé : on repart d'une mise à jour complète
    }

    private fun writeFile(s: LiveSnapshot) {
        // Écriture atomique : un arrêt en cours d'écriture ne laisse jamais un fichier à moitié écrit.
        file.absoluteFile.parentFile?.mkdirs()
        val tmp = File(file.absolutePath + ".tmp")
        tmp.writeText(snapshotJson.encodeToString(LiveSnapshot.serializer(), s))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** Une mise à jour complète. Une erreur (fournisseur, réseau) remonte et la dernière liste reste servie. */
    suspend fun runOnce() = running.withLock {
        beforeRun()
        val acct = account()
        val kept = state.value
        if (kept != null && acct != null && kept.account != acct) {
            // Autre compte IPTV : rien de l'ancien ne doit rester (cache, liste servie, fichier).
            repo.wipeLive()
            state.value = null
            file.delete()
        }
        val report = refresher.run()
        refresher.publish(report)
        val categories = repo.categories()
        if (categories.isEmpty()) return@withLock // jamais de liste vide servie à la télé
        val countries = categoryCountries(categories)
        val snapCategories = categories.mapIndexed { i, c -> SnapCategory(c.id, c.name, i) }
        val snapStreams = categories.flatMap { c ->
            repo.streams(c.id).mapIndexed { i, s ->
                SnapStream(
                    id = s.id, categoryId = c.id, name = s.name, icon = s.icon, epgChannelId = s.epgChannelId,
                    number = s.number, position = i,
                    autoKey = cachedAutoKey(s.name, countries[c.id]), title = cachedTitle(s.name),
                )
            }
        }
        val hidden = refresher.lastHidden.orEmpty().sorted()
        val next = LiveSnapshot(
            rev = liveSnapshotRev(snapCategories, snapStreams, hidden),
            account = acct.orEmpty(),
            builtAt = clockMs(),
            categories = snapCategories,
            streams = snapStreams,
            hidden = hidden,
        )
        writeFile(next)
        state.value = next
    }

    /** `true` si la mise à jour a réussi. */
    private suspend fun runQuietly(): Boolean = try {
        runOnce()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Jamais de détail (une erreur réseau peut contenir l'adresse du fournisseur) : la liste gardée reste servie.
        System.err.println("Mise à jour du Direct impossible (${e::class.simpleName}), la liste précédente reste servie.")
        false
    }

    private suspend fun prefsRevisionOrNull(): Long? = try {
        sync.prefsRevision(householdId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** Empreinte du compte IPTV actuel dans Supabase (identifiants gardés nulle part), ou `null` si illisible. */
    private suspend fun currentAccountOrNull(): String? = try {
        sync.credentials(householdId)?.let { accountFingerprint(it.server, it.username) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun hiddenOrNull(): Set<String>? = try {
        sync.globalHiddenCategories(householdId).filter { it.itemType == "live_category" }.map { it.itemId }.toSet()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /**
     * Au démarrage si la liste gardée a plus de [periodMs] (ou n'existe pas), puis toutes les [periodMs] ; après un
     * échec, nouvel essai dans [RETRY_MS]. Toutes les [pollMs] : aussitôt si les catégories masquées pour tout le monde
     * changent (la révision n'est considérée comme traitée qu'après une mise à jour réussie) ou si le compte IPTV change.
     */
    suspend fun loop() {
        val kept = state.value
        var lastRev = prefsRevisionOrNull()
        var pendingRev: Long? = null
        var ok = true
        var retryNotBefore = 0L // après un échec, aucun déclencheur (masquage, compte) ne relance avant ce moment
        if (kept == null || clockMs() - kept.builtAt >= periodMs) ok = runQuietly()
        if (!ok) retryNotBefore = clockMs() + RETRY_MS
        var nextAt = if (ok) (state.value?.builtAt ?: clockMs()) + periodMs else retryNotBefore
        while (true) {
            delay(pollMs)
            var due = clockMs() >= nextAt
            val allowed = clockMs() >= retryNotBefore
            val rev = prefsRevisionOrNull()
            if (rev != null && rev != lastRev) pendingRev = rev
            if (pendingRev != null) {
                val hidden = hiddenOrNull()
                if (hidden == null) {
                    // Lecture impossible : la révision reste à traiter, nouvel essai au prochain passage.
                } else if (hidden != refresher.lastHidden) {
                    if (allowed) due = true
                } else {
                    lastRev = pendingRev
                    pendingRev = null
                }
            }
            if (!due && allowed) {
                val acct = state.value?.account
                val now = currentAccountOrNull()
                if (!acct.isNullOrEmpty() && now != null && now != acct) due = true
            }
            if (due) {
                ok = runQuietly()
                retryNotBefore = if (ok) 0L else clockMs() + RETRY_MS
                nextAt = if (ok) clockMs() + periodMs else retryNotBefore
                if (ok && pendingRev != null) {
                    lastRev = pendingRev
                    pendingRev = null
                }
            }
        }
    }

    private companion object {
        const val RETRY_MS = 5 * 60_000L
    }
}
