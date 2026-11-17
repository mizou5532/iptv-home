package home.iptv.tv.search

import home.iptv.shared.catalog.CATALOG_TITLES_BATCH
import home.iptv.shared.catalog.CatalogTitleRow
import home.iptv.shared.catalog.CatalogTitleSource
import home.iptv.shared.catalog.catalogTitleRows
import home.iptv.shared.search.FillProgress
import home.iptv.shared.search.SearchSection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/** Heure de la dernière publication réussie, par foyer et type. */
interface CatalogTitlesState {
    fun publishedAt(household: String, kind: String): Long
    fun savePublished(household: String, kind: String, at: Long)
}

class AndroidCatalogTitlesState(context: android.content.Context) : CatalogTitlesState {
    private val prefs = context.applicationContext.getSharedPreferences("iptv_home_catalog_titles", android.content.Context.MODE_PRIVATE)
    override fun publishedAt(household: String, kind: String) = prefs.getLong("$household:$kind", 0L)
    override fun savePublished(household: String, kind: String, at: Long) { prefs.edit().putLong("$household:$kind", at).apply() }
}

/**
 * Publie, au plus une fois par [intervalMs] et par type, les titres du catalogue que la télé a déjà en cache (séries,
 * films), pour que la page iPhone puisse chercher et nommer les titres (« Ajouter à Ma liste », bannière). On attend que
 * le remplissage de la recherche soit complet pour ce type : une liste à moitié remplie effacerait des titres.
 * Seules les catégories téléchargées par la télé sont dans le cache (jamais une catégorie masquée pour tout le monde).
 * Lecture du disque et envoi hors du fil principal ; un échec est réessayé au prochain changement de progression.
 */
class CatalogTitlesPublisher(
    private val progress: StateFlow<Map<SearchSection, FillProgress>>,
    private val series: () -> List<CatalogTitleSource>,
    private val movies: () -> List<CatalogTitleSource>,
    private val server: () -> String?,
    private val household: () -> String?,
    private val publish: suspend (household: String, kind: String, gen: Long, rows: List<CatalogTitleRow>, done: Boolean) -> Unit,
    private val state: CatalogTitlesState,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    private val intervalMs: Long = 20L * 3_600_000,
) {
    private val running = Mutex()
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(io) { progress.collect { p -> runDue(p) } }
    }

    /** Publie chaque type complet dont la dernière publication est trop ancienne. Rend les types publiés. */
    internal suspend fun runDue(p: Map<SearchSection, FillProgress>): List<String> {
        if (!running.tryLock()) return emptyList()
        try {
            val h = household() ?: return emptyList()
            val done = ArrayList<String>()
            for ((section, kind) in listOf(SearchSection.SERIES to "series", SearchSection.MOVIES to "movie")) {
                val fill = p[section] ?: continue
                if (fill.total <= 0 || !fill.complete) continue
                if (now() - state.publishedAt(h, kind) < intervalMs) continue
                if (publishKind(h, kind)) done += kind
            }
            return done
        } finally {
            running.unlock()
        }
    }

    private suspend fun publishKind(h: String, kind: String): Boolean {
        return try {
            val rows = catalogTitleRows(if (kind == "series") series() else movies(), server()).take(MAX_TITLES)
            if (rows.isEmpty()) return false
            val gen = now()
            val batches = rows.chunked(CATALOG_TITLES_BATCH)
            batches.forEachIndexed { i, batch -> publish(h, kind, gen, batch, i == batches.lastIndex) }
            state.savePublished(h, kind, gen)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    private companion object {
        /** Garde-fou : au-delà, la page cherchera dans les premiers titres seulement. */
        const val MAX_TITLES = 300_000
    }
}
