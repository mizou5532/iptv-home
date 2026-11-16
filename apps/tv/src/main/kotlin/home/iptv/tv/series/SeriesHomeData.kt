package home.iptv.tv.series

import android.content.Context
import home.iptv.shared.progress.ProgressRow
import home.iptv.shared.progress.QueuedProgress
import home.iptv.shared.series.SeriesRepository
import home.iptv.shared.xtream.SeriesCategory
import home.iptv.shared.xtream.SeriesInfo
import home.iptv.shared.xtream.SeriesSummary
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Ce que l'accueil lit du catalogue des séries (le dépôt en production, un faux dans les tests). */
interface SeriesCatalog {
    /** Toutes les catégories du fournisseur, masquées comprises (le filtre se fait à l'affichage). */
    suspend fun categories(): List<SeriesCategory>
    suspend fun seriesOf(categoryId: String, hidden: Set<String>): List<SeriesSummary>
    /** Catégorie connue d'une série d'après le cache local seul : aucune demande au fournisseur. */
    fun cachedCategory(seriesId: String): String?
    suspend fun info(seriesId: String, categoryId: String?): SeriesInfo?
}

class RepoSeriesCatalog(private val repo: SeriesRepository) : SeriesCatalog {
    override suspend fun categories() = repo.categories(emptySet())
    override suspend fun seriesOf(categoryId: String, hidden: Set<String>) = repo.seriesOf(categoryId, hidden)
    override fun cachedCategory(seriesId: String) = repo.cachedCategory(seriesId)
    override suspend fun info(seriesId: String, categoryId: String?) = repo.info(seriesId, categoryId)
}

/** Mémoire locale de l'accueil Séries : dernière progression connue, dernière vérification, dernière publication. */
interface SeriesHomeState {
    fun savedProgress(profileId: String): List<ProgressRow>
    fun saveProgress(profileId: String, rows: List<ProgressRow>)
    /** Heure (ms) de la dernière vérification « nouveaux épisodes » et séries trouvées ; 0 = jamais. */
    fun lastNewCheck(profileId: String): Long
    fun newEpisodeIds(profileId: String): Set<String>
    fun saveNewCheck(profileId: String, atMs: Long, ids: Set<String>)
    fun lastPublish(householdId: String): Long
    fun savePublished(householdId: String, atMs: Long)
}

/** Positions en attente d'envoi de ce profil, vues comme des lignes de progression (la plus récente par série gagne plus loin). */
fun queuedAsRows(queue: List<QueuedProgress>, profileId: String): List<ProgressRow> =
    queue.filter { it.profileId == profileId && it.entry.itemType == "episode" && it.entry.seriesId != null }.map { q ->
        val e = q.entry
        ProgressRow(
            itemId = e.itemId, seriesId = e.seriesId, season = e.season, episode = e.episode,
            positionS = e.positionS, durationS = e.durationS,
            // Règle du backend : « vu » à 90 %.
            watched = e.durationS > 0 && e.positionS.toLong() * 10 >= e.durationS.toLong() * 9,
            clientUpdatedAt = e.clientUpdatedAt,
            seriesName = e.seriesName, seriesCover = e.seriesCover, seriesCategory = e.seriesCategory,
        )
    }

/** Marquer un épisode non vu retire seulement ses positions, jamais le film de même identifiant. */
fun dropQueuedEpisodes(queue: List<QueuedProgress>, profileId: String, ids: Set<String>): List<QueuedProgress> =
    queue.filterNot { it.profileId == profileId && it.entry.itemType == "episode" && it.entry.itemId in ids }

private val json = Json { ignoreUnknownKeys = true }

class AndroidSeriesHomeState(context: Context) : SeriesHomeState {
    private val prefs = context.applicationContext.getSharedPreferences("iptv_home_series", Context.MODE_PRIVATE)

    override fun savedProgress(profileId: String): List<ProgressRow> = runCatching {
        json.decodeFromString(ListSerializer(ProgressRow.serializer()), prefs.getString("progress:$profileId", null) ?: return emptyList())
    }.getOrDefault(emptyList())

    override fun saveProgress(profileId: String, rows: List<ProgressRow>) {
        prefs.edit().putString("progress:$profileId", json.encodeToString(ListSerializer(ProgressRow.serializer()), rows)).apply()
    }

    override fun lastNewCheck(profileId: String): Long = prefs.getLong("new_check:$profileId", 0L)

    override fun newEpisodeIds(profileId: String): Set<String> = runCatching {
        json.decodeFromString(SetSerializer(String.serializer()), prefs.getString("new_ids:$profileId", null) ?: return emptySet())
    }.getOrDefault(emptySet())

    override fun saveNewCheck(profileId: String, atMs: Long, ids: Set<String>) {
        prefs.edit()
            .putLong("new_check:$profileId", atMs)
            .putString("new_ids:$profileId", json.encodeToString(SetSerializer(String.serializer()), ids))
            .apply()
    }

    override fun lastPublish(householdId: String): Long = prefs.getLong("published:$householdId", 0L)
    override fun savePublished(householdId: String, atMs: Long) { prefs.edit().putLong("published:$householdId", atMs).apply() }
}
