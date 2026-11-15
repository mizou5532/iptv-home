package home.iptv.shared.catalog

import home.iptv.shared.xtream.LiveCategory
import home.iptv.shared.xtream.LiveStream
import home.iptv.shared.xtream.XtreamApi
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

interface LiveSource {
    suspend fun liveCategories(): List<LiveCategory>
    suspend fun liveStreams(categoryId: String): List<LiveStream>

    /** Demandes de catégories en même temps chez le fournisseur (appels d'API, pas des flux vidéo). */
    val parallelism: Int get() = 1
}

/**
 * [parallelism] : 3 demandes d'API à la fois au plus. Le client HTTP n'en mène que 5 par serveur : il en reste pour
 * le guide (2) sans attente, et le fournisseur reçoit un rythme modéré.
 */
class XtreamLiveSource(private val api: XtreamApi, override val parallelism: Int = 3) : LiveSource {
    override suspend fun liveCategories() = api.liveCategories()
    override suspend fun liveStreams(categoryId: String) = api.liveStreams(categoryId)
}

data class RefreshReport(
    val categories: Int,
    val streams: Int,
    val failedCategories: List<String>,
    /** Toutes les catégories du fournisseur (masquées comprises), dans son ordre ; vide si leur liste a échoué. */
    val providerCategories: List<LiveCategory> = emptyList(),
)

class CatalogRepository(private val db: CatalogDatabase) {
    private val q get() = db.catalogQueries

    /**
     * Met le cache du direct à jour, au plus [LiveSource.parallelism] catégories demandées à la fois (des appels
     * d'API : la limite d'une connexion du fournisseur concerne les flux vidéo). Une par une, des centaines de
     * catégories faisaient durer la mise à jour des dizaines de minutes (essai TCL 0.4.0). Les écritures dans le
     * cache et [onProgress] restent l'une après l'autre ; chaque catégorie garde sa place chez le fournisseur.
     * Les catégories masquées pour tout le monde ne sont jamais demandées et sont retirées du cache.
     * Une catégorie en erreur garde ses chaînes d'avant ; une catégorie disparue est retirée avec ses chaînes.
     * Si la liste des catégories elle-même échoue, l'erreur est propagée et le cache reste intact.
     *
     * [onProgress] (terminées, total) est appelé après chaque catégorie, une fois sa transaction validée :
     * l'écran peut ainsi relire le cache pendant un long premier rafraîchissement. Une catégorie jamais
     * téléchargée avec succès n'est donc jamais visible (pas de ligne vide ou « cassée »).
     */
    suspend fun refreshLive(
        source: LiveSource,
        hiddenCategoryIds: Set<String>,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): RefreshReport {
        val remote = source.liveCategories().distinctBy { it.id }
        val wanted = remote.filter { it.id !in hiddenCategoryIds }
        val wantedIds = wanted.map { it.id }.toSet()

        // Retire ce qui a disparu ou est masqué (chaque catégorie avec ses chaînes).
        val stale = q.selectCategoryIds().executeAsList().filter { it !in wantedIds }
        if (stale.isNotEmpty()) {
            db.transaction {
                stale.forEach {
                    q.deleteStreamsOfCategory(it)
                    q.deleteCategory(it)
                }
            }
        }

        var streamCount = 0
        var done = 0
        val failed = mutableListOf<Pair<Int, String>>()
        val writes = Mutex()
        val permits = Semaphore(source.parallelism.coerceAtLeast(1))
        coroutineScope {
            val all = this
            wanted.mapIndexed { index, cat ->
                async {
                    val streams = permits.withPermit {
                        try {
                            source.liveStreams(cat.id)
                        } catch (e: CancellationException) {
                            // Une annulation arrête tout, avant que la catégorie suivante ne soit demandée.
                            all.cancel(e)
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                    }
                    writes.withLock {
                        if (streams == null) {
                            failed += index to cat.id
                            // La catégorie reste telle quelle (nom et chaînes), seule sa place est mise à jour si elle existe.
                            q.updateCategoryPosition(cat.name, index.toLong(), cat.id)
                            streamCount += q.countStreams(cat.id).executeAsOne().toInt()
                        } else {
                            db.transaction {
                                q.deleteStreamsOfCategory(cat.id)
                                q.upsertCategory(cat.id, cat.name, index.toLong())
                                streams.forEachIndexed { i, s ->
                                    q.insertStream(s.id, cat.id, s.name, s.icon, s.epgChannelId, s.number?.toLong(), i.toLong())
                                }
                            }
                            streamCount += streams.size
                        }
                        onProgress(++done, wanted.size)
                    }
                }
            }.awaitAll()
        }
        return RefreshReport(
            categories = q.countCategories().executeAsOne().toInt(),
            streams = streamCount,
            failedCategories = failed.sortedBy { it.first }.map { it.second },
            providerCategories = remote,
        )
    }

    /** Index global leger sans URL, ordre du fournisseur a travers les categories. */
    fun allGroupSources(): List<home.iptv.shared.groups.GroupSource> =
        q.selectGroupSources().executeAsList().mapIndexed { index, row ->
            home.iptv.shared.groups.GroupSource(row.id, row.category_id, row.name, index)
        }

    fun categories(): List<LiveCategory> =
        q.selectCategories { id, name -> LiveCategory(id, name) }.executeAsList()

    fun streams(categoryId: String): List<LiveStream> =
        q.selectStreams(categoryId) { id, cat, name, icon, epg, number ->
            LiveStream(id, name, cat, icon, epg, number?.toInt())
        }.executeAsList()

    /** Chaînes du cache dans l'ordre des [ids] ; les identifiants absents du cache sont ignorés. */
    fun streamsByIds(ids: List<String>): List<LiveStream> {
        val wanted = ids.distinct()
        // Par lots : SQLite limite le nombre de variables d'une requête (999 sur les anciens Android).
        val found = wanted.chunked(IDS_PER_QUERY).flatMap { chunk ->
            q.selectStreamsByIds(chunk) { id, cat, name, icon, epg, number ->
                LiveStream(id, name, cat, icon, epg, number?.toInt())
            }.executeAsList()
        }.associateBy { it.id }
        return wanted.mapNotNull { found[it] }
    }
}

private const val IDS_PER_QUERY = 500
