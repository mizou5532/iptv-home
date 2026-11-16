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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/** Rangées de catégories gardées en mémoire au plus (les autres sont relues du cache local en y revenant). */
internal const val MAX_ROWS_IN_MEMORY = 24

data class SeriesHomeUi(
    val home: SeriesHome = SeriesHome.EMPTY,
    /** Séries déjà chargées de chaque rangée de catégorie (toute la catégorie). */
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
    /** Séries des rangées chargées, de la moins récemment vue à la plus récente (ordre d'accès) : mémoire bornée. */
    private val rowSeries = LinkedHashMap<String, List<SeriesCard>>(32, 0.75f, true)
    private val failedRows = HashSet<String>()
    /** Rangées composées par l'écran (à l'écran ou tout près), dans l'ordre où elles sont apparues. */
    private val onScreen = LinkedHashSet<String>()
    /** Rangées en échec : pas de nouvel essai automatique tant qu'elles ne reviennent pas à l'écran. */
    private val retryLater = HashSet<String>()
    /** Ordre des rangées de catégories du dernier accueil construit (pour savoir lesquelles sont juste en dessous). */
    private var rowOrder: List<String> = emptyList()
    private var pumpGeneration = 0
    private var pumpRunning = false
    private var pendingPump: Job? = null
    private var serverAnswered = false
    private var localSnapshot: List<ProgressRow> = emptyList()
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch(io) {
            saved = state.savedProgress(profileId)
            newIds = state.newEpisodeIds(profileId)
            refreshLocal()
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
        scope.launch(io) { refreshLocal(); loadServerProgress(); completeInfos() }
    }

    /**
     * La rangée d'une catégorie est composée par l'écran (elle est à l'écran ou tout près) : ses séries, puis celles des
     * [LOOKAHEAD_ROWS] rangées juste en dessous, sont chargées une à une après une courte attente (un défilement rapide
     * ne charge que les rangées où l'on s'arrête). Ainsi Bas trouve toujours des affiches prêtes, sans jamais demander
     * tout le catalogue. Rien tant que les préférences sont inconnues (tout part dès qu'elles arrivent), jamais pour une
     * catégorie masquée. Une rangée en échec est réessayée quand elle revient à l'écran.
     */
    fun loadCategory(categoryId: String) {
        synchronized(lock) {
            onScreen.add(categoryId)
            retryLater.remove(categoryId)
            rowSeries[categoryId] // marque la rangée comme récemment vue (mémoire bornée)
        }
        scheduleRows()
    }

    /**
     * La rangée a quitté l'écran : si sa demande n'est pas encore partie, elle est abandonnée. Une demande déjà partie va
     * au bout et son résultat est gardé (essai TCL : une rangée quittée pendant sa demande ne se chargeait jamais).
     */
    fun cancelCategory(categoryId: String) {
        synchronized(lock) { onScreen.remove(categoryId) }
        scheduleRows()
    }

    /** (Re)lance, après [ROW_DEBOUNCE_MS], le chargement des rangées voulues ; sans effet si un chargement est en cours. */
    private fun scheduleRows() {
        synchronized(lock) {
            // Le chargement en cours recalcule les rangées voulues après chaque réponse : rien à relancer.
            if (pumpRunning) return
            val generation = ++pumpGeneration
            pendingPump?.cancel()
            pendingPump = scope.launch(io) {
                delay(ROW_DEBOUNCE_MS)
                pumpRows(generation)
            }
        }
    }

    /** Charge, une par une, les rangées voulues ([nextRow]) jusqu'à ce qu'il n'y en ait plus. */
    private suspend fun pumpRows(generation: Int) {
        synchronized(lock) {
            if (generation != pumpGeneration || pumpRunning) return
            pumpRunning = true
        }
        try {
            while (true) {
                // Fin du chargement et choix de la suivante sous le même verrou : aucune demande d'écran n'est perdue.
                val next = synchronized(lock) { nextRow().also { if (it == null) pumpRunning = false } } ?: return
                fetchRow(next)
            }
        } finally {
            synchronized(lock) { pumpRunning = false }
        }
    }

    /**
     * Prochaine rangée à charger, sous le verrou : d'abord celles à l'écran (dans l'ordre des rangées), puis les
     * [LOOKAHEAD_ROWS] rangées sous la plus basse d'entre elles. Jamais une masquée, une déjà en mémoire ni une en échec.
     */
    private fun nextRow(): String? {
        if (prefs.value == null) return null
        return wantedRows().firstOrNull { it !in rowSeries && it !in retryLater }
    }

    /** Rangées voulues en mémoire, sous le verrou : celles à l'écran puis les [LOOKAHEAD_ROWS] suivantes, jamais une masquée. */
    private fun wantedRows(): List<String> {
        val hidden = hiddenCategories()
        val index = HashMap<String, Int>(rowOrder.size * 2).also { m -> rowOrder.forEachIndexed { i, id -> m[id] = i } }
        val shown = onScreen.filter { it.isNotBlank() && it !in hidden }.sortedBy { index[it] ?: Int.MAX_VALUE }
        val lowest = shown.mapNotNull { index[it] }.maxOrNull()
        val below = if (lowest == null) emptyList()
        else rowOrder.subList(lowest + 1, rowOrder.size).asSequence().filter { it !in hidden }.take(LOOKAHEAD_ROWS).toList()
        return shown + below
    }

    /** Toute la catégorie (la rangée est paresseuse). Une seule demande à la fois au fournisseur. */
    private suspend fun fetchRow(categoryId: String) {
        try {
            val list = provider.withLock {
                val hidden = hiddenCategories()
                if (categoryId in hidden) null else catalog.seriesOf(categoryId, hidden)
            }
            synchronized(lock) {
                if (list == null) {
                    retryLater.add(categoryId) // masquée entre-temps
                } else {
                    rowSeries[categoryId] = list.map { SeriesCard(it.id, categoryId, it.name, it.cover) }
                    failedRows.remove(categoryId)
                    trimRows()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            synchronized(lock) { failedRows.add(categoryId); retryLater.add(categoryId) }
        }
        rebuild()
    }

    /**
     * Mémoire bornée : au plus [MAX_ROWS_IN_MEMORY] rangées et [MAX_CARDS_IN_MEMORY] affiches, les moins récemment vues
     * partent d'abord ; une rangée voulue (à l'écran ou chargée d'avance, [wantedRows]) n'est jamais retirée, sinon elle
     * serait redemandée aussitôt, sans fin, quand les rangées voulues dépassent à elles seules la limite. Une rangée
     * retirée est relue du cache local (frais un jour) quand elle revient à l'écran.
     */
    private fun trimRows() {
        val wanted = wantedRows().toHashSet()
        var cards = rowSeries.values.sumOf { it.size }
        // Parcours par les entrées : un get() réordonnerait la table (ordre d'accès) pendant le parcours.
        val entries = rowSeries.entries.iterator()
        while ((rowSeries.size > MAX_ROWS_IN_MEMORY || cards > MAX_CARDS_IN_MEMORY) && entries.hasNext()) {
            val entry = entries.next()
            if (entry.key in onScreen || entry.key in wanted) continue
            cards -= entry.value.size
            entries.remove()
        }
    }

    /** Lecture disque hors du verrou et hors du fil principal ; le résultat est une copie gardée pour les reconstructions. */
    private fun refreshLocal() {
        val rows = localProgress()
        synchronized(lock) { localSnapshot = rows }
    }

    private suspend fun withIo(block: () -> Unit) = kotlinx.coroutines.withContext(io) { block() }

    private fun hiddenCategories(): Set<String> =
        prefs.value?.seriesHidden().orEmpty()

    private suspend fun loadServerProgress() {
        try {
            val rows = serverProgress()
            synchronized(lock) { server = rows; serverAnswered = true; progressFailed = false }
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
            // Publication à part : elle ne retarde jamais les fiches ni l'écran.
            scope.launch(io) { publishIfDue(all) }
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
        // Sans préférences on ne sait pas ce qui est masqué : on n'interroge rien.
        val p = prefs.filterNotNull().first()
        val hidden = hiddenCategories()
        // Masqué gagne : le masquage se juge sur la progression NON filtrée (une série de Ma liste dont la progression
        // porte une catégorie masquée ne doit jamais être demandée), puis sur le cache local.
        val all = merged().filter { it.seriesId != null }.sortedByDescending { it.clientUpdatedAt }
        val masked = all.filter { r -> r.seriesCategory?.let { it in hidden } == true }.mapTo(HashSet()) { it.seriesId!! }
        val rows = all.filter { it.seriesId !in masked }
        // Deux budgets distincts : les séries en cours (les plus récentes d'abord), puis Ma liste.
        val continuing = rows.map { it.seriesId!! }.distinct().take(MAX_INFOS)
        val wanted = continuing + p.seriesList.distinct().filter { it !in continuing && it !in masked }.take(MAX_INFOS)
        for (id in wanted) {
            if (synchronized(lock) { id in infos }) continue
            val category = all.firstOrNull { it.seriesId == id }?.seriesCategory ?: catalog.cachedCategory(id)
            if (category != null && category in hidden) continue
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
        // La vérification du jour n'est validée que si au moins une fiche a été obtenue (pas hors ligne).
        val learned = synchronized(lock) { wanted.any { it in infos } }
        val at = now()
        if (learned && at - state.lastNewCheck(profileId) >= DAY_MS) {
            val fresh = synchronized(lock) { buildNow(knownNew = null) }.newEpisodes.map { it.seriesId }.toSet()
            synchronized(lock) { newIds = fresh }
            state.saveNewCheck(profileId, at, fresh)
            rebuild()
        }
    }

    /** Progression locale (file d'envoi) + dernière copie du serveur : la ligne la plus récente de chaque série gagne. */
    private fun merged(): List<ProgressRow> {
        val (remote, local) = synchronized(lock) { (if (serverAnswered) server else saved) to localSnapshot }
        return (remote + local).filter { it.itemType == "episode" }.groupBy { it.seriesId ?: it.itemId }.values.map { rows ->
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
        // Rangées changées (catégories arrivées, préférences connues, masquage) : les rangées voulues aussi.
        if (rebuildLocked() && synchronized(lock) { onScreen.isNotEmpty() }) scheduleRows()
    }

    /** Vrai si l'ordre des rangées de catégories a changé. */
    private fun rebuildLocked(): Boolean {
        synchronized(lock) {
            if (prefs.value == null) {
                // Masqué gagne : sans préférences on ne montre rien (ni Reprendre ni catégories).
                _ui.value = SeriesHomeUi(
                    // « Mise à jour » seulement pendant la première lecture du catalogue : sans préférences (hors
                    // ligne), elle restait affichée sans fin ; le message dit quoi faire.
                    SeriesHome.EMPTY, emptyMap(), failedRows.toSet(), loading = categoriesPending,
                    message = if (categoriesPending) null else "Tes préférences ne sont pas encore connues : vérifie la connexion internet de la télé.",
                )
                val changed = rowOrder.isNotEmpty()
                rowOrder = emptyList()
                return changed
            }
            val home = buildNow(newIds)
            val order = home.categoryRows.map { it.id }
            val changed = order != rowOrder
            rowOrder = order
            val visible = order.toHashSet()
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
            return changed
        }
    }

    private companion object {
        const val MAX_INFOS = 20
        const val ROW_DEBOUNCE_MS = 300L
        /** Rangées chargées d'avance sous la plus basse rangée à l'écran. */
        const val LOOKAHEAD_ROWS = 2
        /** Affiches gardées en mémoire au plus, toutes rangées confondues (une catégorie peut en compter des milliers). */
        const val MAX_CARDS_IN_MEMORY = 40_000
        const val DAY_MS = 24L * 3_600_000
    }
}
