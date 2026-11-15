package home.iptv.tv.series

import home.iptv.shared.progress.ProgressRow
import home.iptv.shared.sync.ProfilePrefs
import home.iptv.shared.sync.PublishedCategory
import home.iptv.shared.xtream.SeriesCategory
import home.iptv.shared.xtream.SeriesInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

data class SeriesHomeUi(
    val home: SeriesHome = SeriesHome.EMPTY,
    /** Séries déjà chargées de chaque rangée de catégorie (les premières seulement). */
    val rowSeries: Map<String, List<SeriesCard>> = emptyMap(),
    val failedRows: Set<String> = emptySet(),
    /** Vrai tant que la première lecture des catégories n'est pas finie. */
    val loading: Boolean = true,
    /** Jamais un écran vide sans explication : renseigné quand quelque chose manque ou est vide. */
    val message: String? = null,
)

/**
 * Accueil Séries d'un profil. Tout le réseau et le disque passent par [io]. Le bloc « Reprendre » apparaît dès que la
 * progression locale (file d'envoi + dernière copie du serveur) est lue ; le serveur, les catégories et les fiches
 * complètent ensuite. Une seule demande à la fois au fournisseur (une seule connexion).
 */
class SeriesHomeViewModel(
    private val catalog: SeriesCatalog,
    private val serverProgress: suspend () -> List<ProgressRow>,
    private val localProgress: () -> List<ProgressRow>,
    private val publishCategories: suspend (List<PublishedCategory>) -> Unit,
    private val state: SeriesHomeState,
    private val profileId: String,
    private val householdId: () -> String?,
    private val prefs: StateFlow<ProfilePrefs?>,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _ui = MutableStateFlow(SeriesHomeUi())
    val ui: StateFlow<SeriesHomeUi> = _ui

    private val lock = Any()
    private val provider = Mutex()
    private var saved: List<ProgressRow> = emptyList()
    private var server: List<ProgressRow> = emptyList()
    private var categories: List<SeriesCategory> = emptyList()
    private var categoriesFailed = false
    private var categoriesPending = true
    private var progressFailed = false
    private var newIds: Set<String>? = null
    private val infos = HashMap<String, SeriesInfo>()
    private val rowSeries = HashMap<String, List<SeriesCard>>()
    private val failedRows = HashSet<String>()
    private val rowsAsked = HashSet<String>()
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch(io) {
            saved = state.savedProgress(profileId)
            newIds = state.newEpisodeIds(profileId)
            rebuild()
            // Hors ligne ou serveur capricieux : on garde ce qu'on a (cache local), avec un message.
            loadServerProgress()
            loadCategories()
            completeInfos()
        }
        scope.launch { prefs.collect { withIo { rebuild() } } }
    }

    /** Relit la progression (retour d'une lecture). */
    fun reloadProgress() {
        scope.launch(io) { loadServerProgress(); completeInfos() }
    }

    /** Charge les premières séries d'une catégorie quand sa rangée approche de l'écran. Jamais pour une catégorie masquée. */
    fun loadCategory(categoryId: String) {
        val hidden = hiddenCategories()
        if (categoryId in hidden) return
        synchronized(lock) {
            if (categoryId in rowSeries || !rowsAsked.add(categoryId)) return
        }
        scope.launch(io) {
            try {
                val list = provider.withLock { catalog.seriesOf(categoryId, hiddenCategories()) }
                val cards = list.take(ROW_SIZE).map { SeriesCard(it.id, categoryId, it.name, it.cover) }
                synchronized(lock) { rowSeries[categoryId] = cards; failedRows.remove(categoryId) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                synchronized(lock) { failedRows.add(categoryId); rowsAsked.remove(categoryId) }
            }
            rebuild()
        }
    }

    private suspend fun withIo(block: () -> Unit) = kotlinx.coroutines.withContext(io) { block() }

    private fun hiddenCategories(): Set<String> =
        prefs.value?.hidden.orEmpty().filter { it.itemType == "series_category" }.mapTo(HashSet()) { it.itemId }

    private suspend fun loadServerProgress() {
        try {
            val rows = serverProgress()
            synchronized(lock) { server = rows; progressFailed = false }
            state.saveProgress(profileId, rows)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            synchronized(lock) { progressFailed = true }
        }
        rebuild()
    }

    private suspend fun loadCategories() {
        try {
            val all = provider.withLock { catalog.categories() }
            synchronized(lock) { categories = all; categoriesFailed = false; categoriesPending = false }
            rebuild()
            publishIfDue(all)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            synchronized(lock) { categoriesFailed = true; categoriesPending = false }
            rebuild()
        }
    }

    /** Copie légère (noms seulement) de TOUTES les catégories, masquées comprises, pour l'organisation depuis le téléphone. */
    private suspend fun publishIfDue(all: List<SeriesCategory>) {
        val household = householdId() ?: return
        if (all.isEmpty() || now() - state.lastPublish(household) < DAY_MS) return
        try {
            publishCategories(all.mapIndexed { i, c -> PublishedCategory(c.id, c.name, i) })
            state.savePublished(household, now())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Jamais bloquant pour l'écran : réessayé à la prochaine ouverture.
        }
    }

    /**
     * Fiches des séries qui en ont besoin : épisode suivant des séries vues, noms de Ma liste. Au plus [MAX_INFOS]
     * séries par ouverture (cache de 6 h du dépôt). La détection « nouveaux épisodes » n'est refaite qu'une fois par jour.
     */
    private suspend fun completeInfos() {
        val hidden = hiddenCategories()
        val watched = merged().filter { it.watched && it.seriesId != null }.sortedByDescending { it.clientUpdatedAt }
            .filter { r -> r.seriesCategory?.let { it !in hidden } ?: true }
        val wanted = (watched.map { it.seriesId!! } + (prefs.value?.seriesList.orEmpty())).distinct().take(MAX_INFOS)
        for (id in wanted) {
            if (synchronized(lock) { id in infos }) continue
            val category = watched.firstOrNull { it.seriesId == id }?.seriesCategory
            try {
                val info = provider.withLock { catalog.info(id, category) }
                if (info != null) synchronized(lock) { infos[id] = info }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue
            }
            rebuild()
        }
        val at = now()
        if (at - state.lastNewCheck(profileId) >= DAY_MS) {
            val fresh = synchronized(lock) { buildNow(knownNew = null) }.newEpisodes.map { it.seriesId }.toSet()
            synchronized(lock) { newIds = fresh }
            state.saveNewCheck(profileId, at, fresh)
            rebuild()
        }
    }

    /** Progression locale (file d'envoi) + dernière copie du serveur : la ligne la plus récente de chaque série gagne. */
    private fun merged(): List<ProgressRow> {
        val remote = synchronized(lock) { (server.ifEmpty { saved }) }
        val local = localProgress()
        return (remote + local).groupBy { it.seriesId ?: it.itemId }.values.map { rows ->
            val best = rows.maxByOrNull { it.clientUpdatedAt }!!
            // La file locale ne connaît pas toujours le nom, l'affiche ni la catégorie : on les prend de l'autre ligne.
            best.copy(
                seriesName = best.seriesName ?: rows.firstNotNullOfOrNull { it.seriesName },
                seriesCover = best.seriesCover ?: rows.firstNotNullOfOrNull { it.seriesCover },
                seriesCategory = best.seriesCategory ?: rows.firstNotNullOfOrNull { it.seriesCategory },
            )
        }
    }

    private fun buildNow(knownNew: Set<String>?): SeriesHome {
        val p = prefs.value
        return buildSeriesHome(merged(), p?.seriesList.orEmpty(), categories, p, knownNew) { infos[it] }
    }

    private fun rebuild() {
        synchronized(lock) {
            val home = buildNow(newIds)
            val visible = home.categoryRows.mapTo(HashSet()) { it.id }
            val rows = rowSeries.filterKeys { it in visible }
            val empty = home.resume == null && home.myList.isEmpty() && home.categoryRows.isEmpty()
            val message = when {
                categoriesFailed && home.categoryRows.isEmpty() ->
                    if (home.resume != null) "Catalogue indisponible : vérifie la connexion internet de la télé."
                    else "Impossible de charger les séries : vérifie la connexion internet de la télé."
                empty && progressFailed -> "Impossible de charger tes séries : vérifie la connexion internet de la télé."
                empty && categories.isNotEmpty() -> "Toutes les catégories de séries sont masquées pour ce profil."
                empty && !categoriesPending -> "Aucune série pour l'instant."
                else -> null
            }
            _ui.value = SeriesHomeUi(home, rows, failedRows.toSet(), categoriesPending, if (categoriesPending) null else message)
        }
    }

    private companion object {
        const val ROW_SIZE = 20
        const val MAX_INFOS = 20
        const val DAY_MS = 24L * 3_600_000
    }
}
