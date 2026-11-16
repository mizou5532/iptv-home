package home.iptv.tv.series

import home.iptv.shared.progress.ProgressClient
import home.iptv.shared.progress.ProgressRow
import home.iptv.shared.progress.WatchedItem
import home.iptv.shared.sync.ProfilePrefs
import home.iptv.shared.xtream.Episode
import home.iptv.shared.xtream.SeriesInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** Fabrique de la fiche : profil, préférences, identifiant de série, catégorie connue, portée du ViewModel. */
typealias SeriesDetailFactory = (
    home.iptv.shared.sync.Profile, StateFlow<ProfilePrefs?>, String, String?, CoroutineScope,
) -> SeriesDetailViewModel

/** État d'un épisode sur la fiche. */
sealed interface EpisodeStatus {
    data object Watched : EpisodeStatus
    data class InProgress(val positionS: Int, val durationS: Int) : EpisodeStatus
    data object Upcoming : EpisodeStatus
}

data class EpisodeUi(
    val id: String,
    val season: Int,
    val number: Int,
    val title: String,
    /** Absente chez certains fournisseurs : l'écran reste utilisable. */
    val durationS: Int?,
    val status: EpisodeStatus,
)

data class SeasonUi(val number: Int, val name: String, val watchedCount: Int, val total: Int) {
    val allWatched: Boolean get() = total > 0 && watchedCount >= total
}

data class SeriesDetailState(
    val loading: Boolean = true,
    val title: String = "",
    val cover: String? = null,
    val genre: String? = null,
    val year: String? = null,
    val plot: String? = null,
    val seasons: List<SeasonUi> = emptyList(),
    val selectedSeason: Int? = null,
    /** Épisodes de la saison choisie, dans l'ordre. */
    val episodes: List<EpisodeUi> = emptyList(),
    val inMyList: Boolean = false,
    /** Catégorie masquée (pour tout le monde ou ce profil) : rien n'est affiché ni lu. */
    val masked: Boolean = false,
    /** Jamais d'écran sans explication quand quelque chose manque. */
    val message: String? = null,
)

/**
 * Fiche d'une série. Tout le réseau et le disque passent par [io]. La fiche vient du dépôt (cache de 6 h, servi
 * même périmé si le fournisseur est injoignable) ; l'état des épisodes fusionne la progression du serveur et celle en
 * attente d'envoi sur la télé (règle « vu » à 90 %), puis les marques posées ici sont appliquées tout de suite
 * (optimistes) et annulées si l'envoi échoue. Masqué gagne : une série dont la catégorie est masquée n'est ni demandée
 * ni lisible.
 *
 * [serverProgress] : lignes de progression connues du serveur pour ce profil. [localProgress] : positions en attente d'envoi.
 */
class SeriesDetailViewModel(
    private val seriesId: String,
    private val categoryId: String?,
    private val catalog: SeriesCatalog,
    private val progress: ProgressClient,
    private val profileId: String,
    private val serverProgress: suspend () -> List<ProgressRow>,
    private val localProgress: () -> List<ProgressRow>,
    /** Retire de la file d'envoi les positions de ces épisodes (une position ancienne ne doit pas défaire un « vu » ou un « non vu »). */
    private val dropQueued: (Set<String>) -> Unit,
    private val prefs: StateFlow<ProfilePrefs?>,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Affiche telle qu'elle part vers le serveur de synchro (sans l'adresse du fournisseur). */
    private val coverFor: (String?) -> String? = { it },
) {
    private val _state = MutableStateFlow(SeriesDetailState())
    val state: StateFlow<SeriesDetailState> = _state

    private val lock = Any()
    @Volatile private var info: SeriesInfo? = null
    /** Catégorie connue du cache local (sans réseau), lue une fois au départ : sert au masquage avant toute demande. */
    @Volatile private var cachedCategory: String? = null
    private var infoFailed = false
    private var server: List<ProgressRow> = emptyList()
    private var serverFailed = false
    /** Vrai quand la lecture de la progression est terminée (réussie ou non) : la saison initiale et le focus en dépendent. */
    private var serverSettled = false
    private var local: List<ProgressRow> = emptyList()
    private var started = false
    /** Marques posées sur cet écran (id d'épisode -> vu ?), prioritaires sur la progression lue. */
    private val overrides = HashMap<String, Boolean>()
    private var listOverride: Boolean? = null
    private var chosenSeason: Int? = null
    private var actionMessage: String? = null

    fun start() {
        if (started) return
        started = true
        scope.launch(io) {
            // Masqué gagne : tant que les préférences sont inconnues, on ne demande rien au fournisseur.
            prefs.filterNotNull().first()
            refreshLocal()
            cachedCategory = catalog.cachedCategory(seriesId)?.takeIf { it.isNotBlank() }
            // Sans catégorie connue d'avance, la progression (qui la porte) est lue avant la fiche : jamais de demande pour une série masquée.
            if (categoryId == null) loadServer()
            if (!isMasked()) loadInfo()
            rebuild()
            if (categoryId != null) loadServer()
        }
        scope.launch { prefs.collect { withContext(io) { rebuild() } } }
    }

    /** « Réessayer » : relit la fiche et la progression. */
    fun reload() {
        scope.launch(io) {
            synchronized(lock) { infoFailed = false; actionMessage = null }
            refreshLocal()
            if (categoryId == null) loadServer()
            if (!isMasked()) loadInfo()
            rebuild()
            if (categoryId != null) loadServer()
        }
    }

    /** La lecture vient de se fermer : relit la file locale et le serveur, et efface la marque de l'épisode lu. */
    fun playbackClosed(episodeId: String) {
        scope.launch(io) {
            synchronized(lock) { overrides.remove(episodeId) }
            refreshLocal()
            rebuild()
            loadServer()
        }
    }

    fun selectSeason(number: Int) {
        synchronized(lock) { chosenSeason = number; actionMessage = null }
        scope.launch(io) { rebuild() }
    }

    fun toggleMyList() {
        val on = !_state.value.inMyList
        val previous = synchronized(lock) { listOverride.also { listOverride = on; actionMessage = null } }
        scope.launch(io) {
            rebuild()
            try {
                progress.setSeriesList(profileId, seriesId, on)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                synchronized(lock) { listOverride = previous; actionMessage = "Impossible de modifier Ma liste : vérifie la connexion internet de la télé." }
                rebuild()
            }
        }
    }

    /** Marque toute la saison choisie vue ou non vue. */
    fun markSeasonWatched(watched: Boolean) {
        val season = _state.value.selectedSeason ?: return
        // La saison sur laquelle on agit reste affichée, même si elle devient « entièrement vue ».
        synchronized(lock) { chosenSeason = season }
        mark(episodesOf(season), watched)
    }

    fun markEpisodeWatched(id: String, watched: Boolean) {
        val episode = info?.seasons.orEmpty().flatMap { it.episodes }.firstOrNull { it.id == id } ?: return
        synchronized(lock) { chosenSeason = episode.season }
        mark(listOf(episode), watched)
    }

    /**
     * Demande de lecture d'un épisode (réalisée par la tâche suivante), ou `null` si elle est refusée : série masquée,
     * épisode inconnu. Sans [fromStart], un épisode commencé reprend à sa position.
     */
    fun play(episodeId: String, fromStart: Boolean): PlayEpisodeRequest? {
        if (isMasked()) {
            synchronized(lock) { actionMessage = "Série masquée : lecture impossible." }
            scope.launch(io) { rebuild() }
            return null
        }
        val episode = info?.seasons.orEmpty().flatMap { it.episodes }.firstOrNull { it.id == episodeId } ?: return null
        val status = _state.value.episodes.firstOrNull { it.id == episodeId }?.status
        val start = if (!fromStart && status is EpisodeStatus.InProgress) status.positionS else 0
        return PlayEpisodeRequest(seriesId, knownCategory(), episode.id, episode.season, episode.number, start, fromStart)
    }

    private fun episodesOf(season: Int): List<Episode> =
        info?.seasons.orEmpty().firstOrNull { it.number == season }?.episodes.orEmpty()

    private fun mark(episodes: List<Episode>, watched: Boolean) {
        if (episodes.isEmpty() || isMasked()) return
        val summary = info?.summary
        val category = knownCategory()
        val items = episodes.map {
            WatchedItem(
                itemId = it.id, seriesId = seriesId, season = it.season, episode = it.number, durationS = it.durationS,
                seriesName = summary?.name, seriesCover = coverFor(summary?.cover), seriesCategory = category,
            )
        }
        val before = synchronized(lock) {
            val old = episodes.associate { it.id to overrides[it.id] }
            episodes.forEach { overrides[it.id] = watched }
            actionMessage = null
            old
        }
        scope.launch(io) {
            rebuild()
            try {
                progress.setWatched(profileId, items, watched)
                // Une position plus ancienne en attente d'envoi ne doit pas défaire cette marque (record_progress la remettrait « vu »).
                dropQueued(episodes.mapTo(HashSet()) { it.id })
                refreshLocal()
                rebuild()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                synchronized(lock) {
                    before.forEach { (id, old) -> if (old == null) overrides.remove(id) else overrides[id] = old }
                    actionMessage = "Impossible de mettre à jour « vu » : vérifie la connexion internet de la télé."
                }
                rebuild()
            }
        }
    }

    private suspend fun loadInfo() {
        try {
            val loaded = catalog.info(seriesId, categoryId)
            synchronized(lock) { info = loaded; infoFailed = loaded == null }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            synchronized(lock) { infoFailed = true }
        }
    }

    private suspend fun loadServer() {
        try {
            val rows = serverProgress()
            synchronized(lock) { server = rows; serverFailed = false; serverSettled = true }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            synchronized(lock) { serverFailed = true; serverSettled = true }
        }
        refreshLocal()
        rebuild()
    }

    /** Lecture disque hors du verrou ; le résultat est une copie. */
    private fun refreshLocal() {
        val rows = localProgress()
        synchronized(lock) { local = rows }
    }

    private fun hiddenCategories(): Set<String> =
        prefs.value?.seriesHidden().orEmpty()

    /** Catégorie connue : celle de l'appelant, sinon celle de la fiche, sinon celle gardée avec la progression. */
    private fun knownCategory(): String? = knownCategories().firstOrNull()

    private fun knownCategories(): List<String> {
        val (i, rows) = synchronized(lock) { info to (server + local) }
        return listOfNotNull(
            categoryId, i?.summary?.categoryId,
            rows.filter { it.seriesId == seriesId }.firstNotNullOfOrNull { it.seriesCategory },
            cachedCategory,
        ).filter { it.isNotBlank() }.distinct()
    }

    private fun isMasked(): Boolean {
        val hidden = hiddenCategories()
        return knownCategories().any { it in hidden }
    }

    private fun rebuild() = synchronized(lock) { rebuildLocked() }

    private fun rebuildLocked() {
        val p = prefs.value
        if (p == null) {
            _state.value = SeriesDetailState(loading = true)
            return
        }
        val masked = isMasked()
        val snapshot = Snapshot(info, infoFailed, server, serverFailed, local, HashMap(overrides), listOverride, chosenSeason, actionMessage)
        val inList = snapshot.listOverride ?: (seriesId in p.seriesList)
        // Les préférences ont rattrapé la marque posée ici : elle n'a plus lieu d'être.
        if (snapshot.listOverride != null && snapshot.listOverride == (seriesId in p.seriesList)) {
            listOverride = null
        }
        if (masked) {
            _state.value = SeriesDetailState(loading = false, masked = true, message = "Série masquée")
            return
        }
        val i = snapshot.info
        if (i == null) {
            _state.value = SeriesDetailState(
                loading = !snapshot.infoFailed,
                message = if (snapshot.infoFailed) "Fiche indisponible : vérifie la connexion internet de la télé." else null,
                inMyList = inList,
            )
            return
        }
        // Fiche et progression arrivent ensemble : sinon la saison initiale et le focus seraient décidés trop tôt.
        if (!serverSettled) {
            _state.value = SeriesDetailState(loading = true, inMyList = inList)
            return
        }
        val rows = (snapshot.server + snapshot.local).filter { it.itemType == "episode" && it.seriesId == seriesId }
            .groupBy { it.itemId }.mapValues { (_, r) -> r.maxByOrNull { it.clientUpdatedAt }!! }
        val statuses = HashMap<String, EpisodeStatus>()
        val seenEpisodes = HashSet<String>()
        // Saisons et épisodes en double (fiche incomplète) : un seul de chaque, les clés de la liste restent uniques.
        val seasonsEpisodes = i.seasons.distinctBy { it.number }.map { s -> s to s.episodes.filter { seenEpisodes.add(it.id) }.sortedBy { it.number } }
        seasonsEpisodes.forEach { (_, eps) ->
            eps.forEach { e -> statuses[e.id] = statusOf(rows[e.id], snapshot.overrides[e.id]) }
        }
        val seasons = seasonsEpisodes.map { (s, eps) ->
            SeasonUi(s.number, s.name.ifBlank { "Saison ${s.number}" }, eps.count { statuses[it.id] is EpisodeStatus.Watched }, eps.size)
        }
        val selected = snapshot.chosenSeason?.takeIf { c -> seasons.any { it.number == c } }
            ?: initialSeason(seasonsEpisodes.map { it.first.number to it.second }, seasons, statuses, rows)
        val episodes = seasonsEpisodes.firstOrNull { it.first.number == selected }?.second.orEmpty().map { e ->
            EpisodeUi(e.id, e.season, e.number, e.title, e.durationS, statuses.getValue(e.id))
        }
        val message = snapshot.actionMessage ?: when {
            seasons.isEmpty() -> "Aucun épisode connu pour cette série."
            episodes.isEmpty() -> "Aucun épisode dans cette saison."
            snapshot.serverFailed -> "Progression indisponible : les épisodes déjà vus peuvent être incomplets."
            else -> null
        }
        _state.value = SeriesDetailState(
            loading = false, title = i.summary.name, cover = i.summary.cover, genre = i.summary.genre?.takeIf { it.isNotBlank() },
            year = i.summary.year?.takeIf { it.isNotBlank() }, plot = i.summary.plot?.takeIf { it.isNotBlank() },
            seasons = seasons, selectedSeason = selected, episodes = episodes,
            inMyList = inList, message = message,
        )
    }

    private class Snapshot(
        val info: SeriesInfo?, val infoFailed: Boolean, val server: List<ProgressRow>, val serverFailed: Boolean,
        val local: List<ProgressRow>, val overrides: Map<String, Boolean>, val listOverride: Boolean?,
        val chosenSeason: Int?, val actionMessage: String?,
    )

    /** Saison de l'épisode en cours (le plus récent), sinon la première qui n'est pas entièrement vue, sinon la première. */
    private fun initialSeason(
        episodes: List<Pair<Int, List<Episode>>>,
        seasons: List<SeasonUi>,
        statuses: Map<String, EpisodeStatus>,
        rows: Map<String, ProgressRow>,
    ): Int? {
        val current = episodes.flatMap { (n, eps) -> eps.map { n to it } }
            .filter { statuses[it.second.id] is EpisodeStatus.InProgress }
            .maxByOrNull { rows[it.second.id]?.clientUpdatedAt ?: kotlin.time.Instant.DISTANT_PAST }
        if (current != null) return current.first
        return seasons.firstOrNull { it.total > 0 && !it.allWatched }?.number ?: seasons.firstOrNull()?.number
    }

    private fun statusOf(row: ProgressRow?, override: Boolean?): EpisodeStatus = when {
        override != null -> if (override) EpisodeStatus.Watched else EpisodeStatus.Upcoming
        row == null -> EpisodeStatus.Upcoming
        // Règle du backend : « vu » à 90 % (la file locale l'applique déjà, le serveur renvoie `watched`).
        row.watched || (row.durationS > 0 && row.positionS.toLong() * 10 >= row.durationS.toLong() * 9) -> EpisodeStatus.Watched
        row.positionS > 0 -> EpisodeStatus.InProgress(row.positionS, row.durationS)
        else -> EpisodeStatus.Upcoming
    }
}
