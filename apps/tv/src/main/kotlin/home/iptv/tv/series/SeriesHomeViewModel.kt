package home.iptv.tv.series

import home.iptv.shared.versions.collapseVersions
import home.iptv.tv.feature.FEATURE_ROTATE_MS
import home.iptv.tv.feature.Feature
import home.iptv.tv.feature.Pick
import home.iptv.tv.feature.pickFrom
import home.iptv.tv.feature.ratingOf
import home.iptv.tv.feature.buildSeriesFeatures
import home.iptv.tv.feature.pinnedSeriesFeature
import home.iptv.shared.progress.ProgressRow
import home.iptv.shared.sync.HomeRows
import home.iptv.shared.sync.MediaKindView
import home.iptv.shared.sync.visibleRows
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
    /** Bannière d'accueil : suggestions qui défilent (voir [buildSeriesFeatures]). */
    val features: List<Feature> = emptyList(),
    /** Image de fond connue de chaque série (fiches lues), pour les cartes paysage de « Reprendre ». */
    val backdrops: Map<String, String> = emptyMap(),
    /** Rangées de l'accueil à afficher, dans l'ordre (réglage du profil) ; voir [HomeRows.SERIES]. */
    val rows: List<String> = HomeRows.SERIES,
    /** Délai entre deux titres de la bannière (réglage du profil). */
    val rotateMs: Long = FEATURE_ROTATE_MS,
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
    /** Série épinglée du profil (identifiant, catégorie), ancien réglage : seule dans la bannière. */
    pinned: Pair<String, String?>? = null,
    /** Réglages de l'accueil Séries du profil connus à l'ouverture (bannière, rangées). */
    initialView: MediaKindView = MediaKindView(),
    /** Relit les réglages du profil sur le serveur (changés depuis l'iPhone) ; null = pas de réponse. */
    private val loadView: suspend () -> MediaKindView? = { null },
    /** Retire une série de « Reprendre » sur le serveur (`dismiss_continue`). */
    private val dismissOnServer: suspend (String) -> Unit = {},
    /** Versions des titres (langues, 4K), construites au besoin hors du fil principal. */
    private val versionIndex: () -> home.iptv.shared.versions.VersionIndex = { home.iptv.shared.versions.VersionIndex.EMPTY },
    private val languages: home.iptv.shared.versions.LanguagePrefs = home.iptv.shared.versions.LanguagePrefs(),
    /** Index des versions déjà construit en mémoire à l'ouverture (évite d'afficher puis regrouper les cartes). */
    initialVersions: home.iptv.shared.versions.VersionIndex = home.iptv.shared.versions.VersionIndex.EMPTY,
) {
    @Volatile private var versions = initialVersions
    private fun ensureVersions() {
        if (versions !== home.iptv.shared.versions.VersionIndex.EMPTY) return
        val v = try { versionIndex() } catch (e: CancellationException) { throw e } catch (_: Exception) { return }
        if (v !== versions) versions = v
    }
    /** Lit (ou construit) l'index des versions puis redessine : une carte par titre dans les rangées. */
    private fun loadVersions() { scope.launch(io) {
        val v = try { versionIndex() } catch (e: CancellationException) { throw e } catch (_: Exception) { return@launch }
        if (v !== versions) { versions = v; rebuild() }
    } }
    @Volatile private var view: MediaKindView = initialView.let { v ->
        if (v.bannerPinned.isEmpty() && pinned != null) v.copy(bannerPinned = listOf(home.iptv.shared.sync.PinnedTitle(pinned.first, pinned.second))) else v
    }
    /** Séries retirées de « Reprendre » sur cette télé : les lignes jusqu'à cette heure sont écartées. */
    private val dismissed = HashMap<String, kotlin.time.Instant>()
    private val _ui = MutableStateFlow(SeriesHomeUi())
    val ui: StateFlow<SeriesHomeUi> = _ui

    private val lock = Any()
    /**
     * Les demandes de l'écran (rangée qui apparaît ou disparaît) passent par cette file, une à la fois et dans l'ordre :
     * le fil de l'écran ne prend jamais [lock], donc n'attend jamais un recalcul de l'accueil (à-coups en défilant).
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val screenEvents = io.limitedParallelism(1)
    /** Rangées déjà regroupées par versions : refaites seulement si leurs affiches ou l'index des versions changent. */
    private val collapsed = HashMap<String, Triple<List<SeriesCard>, home.iptv.shared.versions.VersionIndex, List<SeriesCard>>>()
    private val provider = Mutex()
    private var saved: List<ProgressRow> = emptyList()
    private var server: List<ProgressRow> = emptyList()
    private var categories: List<SeriesCategory> = emptyList()
    private var categoriesFailed = false
    private var categoriesPending = true
    private var progressFailed = false
    private var newIds: Set<String>? = null
    private val infos = HashMap<String, SeriesInfo>()
    private val fetchedInfos = HashSet<String>()
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
            loadCachedInfos()
            rebuild()
            loadVersions()
            // Hors ligne ou serveur capricieux : on garde ce qu'on a (cache local), avec un message.
            // Progression (Supabase) et catégories (fournisseur) en même temps : l'une n'attend jamais l'autre.
            kotlinx.coroutines.coroutineScope {
                launch { loadServerProgress() }
                launch { loadCategories() }
            }
            completeInfos()
            refreshPicks()
            rebuild()
        }
        // Réglages changés depuis l'iPhone : relus à chaque ouverture de l'onglet, sans redémarrer la télé.
        scope.launch(io) {
            val fresh = try { loadView() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (fresh != null && fresh != view) {
                view = fresh
                rebuild()
                completeInfos()
            }
        }
        scope.launch { prefs.collect { withIo { loadCachedInfos(); rebuild() } } }
    }

    /**
     * Retire une série de « Reprendre » (appui long sur sa carte) : tout de suite sur cette télé, puis sur le serveur.
     * La progression est gardée ; relancer la série la fait revenir. En cas d'échec, la série réapparaît.
     */
    fun dismiss(seriesId: String) {
        scope.launch(io) {
            // La dernière lecture connue compte aussi : une horloge de télé en retard ne fait pas revenir la série.
            val last = merged().filter { (it.seriesId ?: it.itemId) == seriesId }.maxOfOrNull { it.clientUpdatedAt }
            val at = kotlin.time.Instant.fromEpochMilliseconds(now()).let { n -> if (last != null && last > n) last else n }
            synchronized(lock) { dismissed[seriesId] = at }
            rebuild()
            try {
                dismissOnServer(seriesId)
                val kept = synchronized(lock) { server.filterNot { it.seriesId == seriesId }.also { server = it; saved = saved.filterNot { r -> r.seriesId == seriesId } } }
                state.saveProgress(profileId, kept)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                synchronized(lock) { dismissed.remove(seriesId) }
                rebuild()
            }
        }
    }

    /** Relit la progression (retour d'une lecture). */
    fun reloadProgress() {
        scope.launch(io) { refreshLocal(); loadServerProgress(); completeInfos() }
        loadVersions()
    }

    /**
     * La rangée d'une catégorie est composée par l'écran (elle est à l'écran ou tout près) : ses séries, puis celles des
     * [LOOKAHEAD_ROWS] rangées juste en dessous, sont chargées une à une après une courte attente (un défilement rapide
     * ne charge que les rangées où l'on s'arrête). Ainsi Bas trouve toujours des affiches prêtes, sans jamais demander
     * tout le catalogue. Rien tant que les préférences sont inconnues (tout part dès qu'elles arrivent), jamais pour une
     * catégorie masquée. Une rangée en échec est réessayée quand elle revient à l'écran.
     */
    fun loadCategory(categoryId: String) {
        scope.launch(screenEvents) {
            synchronized(lock) {
                onScreen.add(categoryId)
                retryLater.remove(categoryId)
                rowSeries[categoryId] // marque la rangée comme récemment vue (mémoire bornée)
            }
            scheduleRows()
        }
    }

    /**
     * La rangée a quitté l'écran : si sa demande n'est pas encore partie, elle est abandonnée. Une demande déjà partie va
     * au bout et son résultat est gardé (essai TCL : une rangée quittée pendant sa demande ne se chargeait jamais).
     */
    fun cancelCategory(categoryId: String) {
        scope.launch(screenEvents) {
            synchronized(lock) { onScreen.remove(categoryId) }
            scheduleRows()
        }
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
            if (list != null) ensureVersions()
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
        refreshPicks()
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

    /** Suggestions de la bannière : les séries les mieux notées des catégories des séries en cours (une fois). */
    private var picks: List<Pick<home.iptv.shared.xtream.SeriesSummary>> = emptyList()

    /**
     * Suggestions tirées du cache local seul (jamais de demande au fournisseur en plus des rangées) : refaites au
     * départ, puis à chaque rangée chargée tant qu'il en manque.
     */
    private fun refreshPicks() {
        val p = prefs.value ?: return
        val home = synchronized(lock) { if (picks.size >= 3) return; buildNow(newIds) }
        val hidden = p.seriesHidden()
        val watched = home.continueRow.mapNotNull { r -> r.categoryId?.takeIf { it.isNotBlank() && it !in hidden }?.let { it to r.title } }
        val fallback = home.categoryRows.firstOrNull()?.id
        val exclude = (home.continueRow.map { it.seriesId } + home.myList.map { it.seriesId }).toSet()
        val chosen = runCatching {
            pickFrom(watched, { catalog.cachedSeriesOf(it) }, { it.id }, { ratingOf(it.rating) }, exclude, fallback)
        }.getOrDefault(emptyList())
        synchronized(lock) { picks = chosen }
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
        loadCachedInfos()
        rebuild()
    }

    private suspend fun loadCategories() {
        try {
            val all = provider.withLock { catalog.categories() }
            // Rangées : la vitrine seulement (0.41) ; la publication pour l'iPhone garde toutes les catégories.
            val shown = catalog.showcase(all)
            synchronized(lock) { categories = shown; categoriesFailed = false; categoriesPending = false }
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

    /** Fiches déjà présentes dans le cache local (sans réseau) : image de fond et épisode suivant dès la 1re image. */
    private fun loadCachedInfos() {
        val p = prefs.value ?: return
        val hidden = hiddenCategories()
        val all = merged().filter { it.seriesId != null }.sortedByDescending { it.clientUpdatedAt }
        val masked = all.filter { r -> r.seriesCategory?.let { it in hidden } == true }.mapTo(HashSet()) { it.seriesId!! }
        val continuing = all.filter { it.seriesId !in masked }.map { it.seriesId!! }.distinct().take(MAX_INFOS)
        val pins = view.bannerPinned.filter { it.id !in masked && (it.category == null || it.category !in hidden) }.map { it.id }
        val wanted = (pins + continuing + p.seriesList.distinct().filter { it !in continuing && it !in masked }.take(MAX_INFOS)).distinct()
        for (id in wanted) {
            if (synchronized(lock) { id in infos }) continue
            val category = all.firstOrNull { it.seriesId == id }?.seriesCategory ?: runCatching { catalog.cachedCategory(id) }.getOrNull()
            if (category != null && category in hidden) continue
            val cached = runCatching { catalog.cachedInfo(id) }.getOrNull() ?: continue
            if (cached.summary.categoryId.isNotBlank() && cached.summary.categoryId in hidden) continue
            synchronized(lock) { infos[id] = cached }
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
        // Les séries épinglées d'abord : leur fiche donne le résumé et l'image de la bannière.
        val pins = view.bannerPinned.filter { it.id !in masked && (it.category == null || it.category !in hidden) }.map { it.id }
        val wanted = (pins + continuing + p.seriesList.distinct().filter { it !in continuing && it !in masked }.take(MAX_INFOS)).distinct()
        for (id in wanted) {
            if (synchronized(lock) { id in fetchedInfos }) continue
            val category = all.firstOrNull { it.seriesId == id }?.seriesCategory ?: catalog.cachedCategory(id)
            if (category != null && category in hidden) continue
            try {
                val info = provider.withLock { catalog.info(id, category) }
                synchronized(lock) {
                    fetchedInfos.add(id)
                    if (info != null) infos[id] = info
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue
            }
            rebuild()
        }
        // La vérification du jour n'est validée que si au moins une fiche a été obtenue (pas hors ligne).
        val learned = synchronized(lock) { wanted.any { it in fetchedInfos && it in infos } }
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
        val gone = synchronized(lock) { HashMap(dismissed) }
        return (remote + local).filter { it.itemType == "episode" }
            .filter { r -> gone[r.seriesId ?: r.itemId]?.let { r.clientUpdatedAt > it } ?: true }
            .groupBy { it.seriesId ?: it.itemId }.values.map { rows ->
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
        return buildSeriesHome(merged(), p?.seriesList.orEmpty(), categories, p, knownNew) { infos[it] }.groupVersions(versions, languages)
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
            val vs = versions
            val rows = rowSeries.filterKeys { it in visible }.mapValues { (id, cards) ->
                collapsed[id]?.takeIf { it.first === cards && it.second === vs }?.third
                    ?: collapseVersions(cards, vs, languages) { it.seriesId }.also { collapsed[id] = Triple(cards, vs, it) }
            }
            collapsed.keys.retainAll(rows.keys)
            val empty = home.resume == null && home.myList.isEmpty() && home.categoryRows.isEmpty()
            // Progression du serveur pas encore arrivée (lue en même temps que les catégories) : pas de conclusion hâtive.
            val progressKnown = serverAnswered || progressFailed
            val message = when {
                !progressKnown && home.resume == null -> null
                categoriesFailed && home.categoryRows.isEmpty() ->
                    if (home.resume != null) "Catalogue indisponible : vérifie la connexion internet de la télé."
                    else "Impossible de charger les séries : vérifie la connexion internet de la télé."
                empty && progressFailed -> "Impossible de charger tes séries : vérifie la connexion internet de la télé."
                empty && categories.isNotEmpty() -> "Toutes les catégories de séries sont masquées pour ce profil."
                empty && !categoriesPending -> "Aucune série pour l'instant."
                else -> null
            }
            val hidden = hiddenCategories()
            val v = view
            // Titres épinglés : seuls dans la bannière (ceux qu'on connaît déjà) ; sinon la bannière automatique.
            val pinnedFeatures = v.bannerPinned.filter { it.category == null || it.category !in hidden }
                .mapNotNull { pinnedSeriesFeature(home, it.id, it.category, infos[it.id]) }
            val auto = pinnedFeatures.ifEmpty { buildSeriesFeatures(home, picks.filter { it.item.categoryId !in hidden }, v) { infos[it] } }
            val features = if (v.fixed) auto.take(1) else auto
            val backdrops = infos.mapNotNull { (id, i) -> i.summary.backdrop?.let { id to it } }.toMap()
            _ui.value = SeriesHomeUi(home, rows, failedRows.toSet(), categoriesPending, if (categoriesPending) null else message, features, backdrops,
                v.visibleRows(HomeRows.SERIES), v.rotateMs)
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
