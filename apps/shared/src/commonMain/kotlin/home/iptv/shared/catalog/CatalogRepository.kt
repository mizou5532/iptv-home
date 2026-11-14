package home.iptv.shared.catalog

import home.iptv.shared.xtream.LiveCategory
import home.iptv.shared.xtream.LiveStream
import home.iptv.shared.xtream.XtreamApi
import kotlin.coroutines.cancellation.CancellationException

interface LiveSource {
    suspend fun liveCategories(): List<LiveCategory>
    suspend fun liveStreams(categoryId: String): List<LiveStream>
}

class XtreamLiveSource(private val api: XtreamApi) : LiveSource {
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
     * Met le cache du direct à jour, une catégorie après l'autre (une seule connexion chez le fournisseur).
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
        val failed = mutableListOf<String>()
        wanted.forEachIndexed { index, cat ->
            val streams = try {
                source.liveStreams(cat.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += cat.id
                // La catégorie reste telle quelle (nom et chaînes), seule sa place est mise à jour si elle existe.
                q.updateCategoryPosition(cat.name, index.toLong(), cat.id)
                streamCount += q.countStreams(cat.id).executeAsOne().toInt()
                onProgress(index + 1, wanted.size)
                return@forEachIndexed
            }
            db.transaction {
                q.deleteStreamsOfCategory(cat.id)
                q.upsertCategory(cat.id, cat.name, index.toLong())
                streams.forEachIndexed { i, s ->
                    q.insertStream(s.id, cat.id, s.name, s.icon, s.epgChannelId, s.number?.toLong(), i.toLong())
                }
            }
            streamCount += streams.size
            onProgress(index + 1, wanted.size)
        }
        return RefreshReport(
            categories = q.countCategories().executeAsOne().toInt(),
            streams = streamCount,
            failedCategories = failed,
            providerCategories = remote,
        )
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
