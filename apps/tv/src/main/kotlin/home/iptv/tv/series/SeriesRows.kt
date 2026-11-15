package home.iptv.tv.series

import home.iptv.shared.progress.ProgressRow
import home.iptv.shared.sync.ProfilePrefs
import home.iptv.shared.xtream.SeriesCategory
import home.iptv.shared.xtream.SeriesInfo


/** Une affiche de série. [progress] (0..1) et [caption] ne servent qu'à « Continuer à regarder ». */
data class SeriesCard(
    val seriesId: String,
    val categoryId: String?,
    val title: String,
    val cover: String?,
    val progress: Float? = null,
    val caption: String? = null,
)

data class EpisodeRef(val id: String, val season: Int, val number: Int, val title: String)

enum class ResumeState {
    /** Un épisode commencé : on reprend à la position enregistrée. */
    InProgress,
    /** L'épisode vu est terminé : on propose le suivant (depuis le début). */
    NextEpisode,
    /** Épisode terminé et la fiche ne dit rien de plus : la série est « à jour ». */
    UpToDate,
}

data class ResumeItem(
    val seriesId: String,
    val categoryId: String?,
    val title: String,
    val cover: String?,
    val state: ResumeState,
    /** Épisode à lire (null seulement pour [ResumeState.UpToDate]). */
    val episode: EpisodeRef?,
    val positionS: Int,
    val durationS: Int,
    /** « Ensuite : … » (l'épisode d'après celui qui est affiché). */
    val then: EpisodeRef?,
)

data class CategoryRow(val id: String, val name: String)

data class SeriesHome(
    val resume: ResumeItem?,
    val continueRow: List<ResumeItem>,
    val myList: List<SeriesCard>,
    val newEpisodes: List<SeriesCard>,
    val categoryRows: List<CategoryRow>,
) {
    companion object { val EMPTY = SeriesHome(null, emptyList(), emptyList(), emptyList(), emptyList()) }
}

const val MAX_NEW_EPISODES = 20

/** « 18 min restantes », « début » ou null (rien à dire). */
fun ResumeItem.remainingLabel(): String? = when {
    state != ResumeState.InProgress -> if (state == ResumeState.NextEpisode) "prochain épisode" else "à jour"
    durationS <= 0 -> null
    positionS < 30 -> "début"
    else -> {
        val min = ((durationS - positionS).coerceAtLeast(0) + 59) / 60
        if (min <= 0) "presque fini" else "$min min restantes"
    }
}

/** Part déjà vue de l'épisode proposé (0..1), nulle quand on repart du début. */
fun ResumeItem.progressFraction(): Float =
    if (state == ResumeState.InProgress && durationS > 0) (positionS.toFloat() / durationS).coerceIn(0f, 1f) else 0f

fun EpisodeRef.shortLabel(): String = "S$season · É$number"

/**
 * Compose l'accueil Séries. Le masqué gagne : une série dont la catégorie (celle de la ligne de progression, sinon
 * celle de sa fiche) est masquée n'apparaît nulle part. Une catégorie masquée ne fait pas non plus de rangée.
 *
 * [knownNew] : séries déjà reconnues « nouveaux épisodes » par la vérification quotidienne ; absent, on les déduit
 * des séries dont le dernier épisode vu est terminé et dont la fiche connaît la suite.
 */
fun buildSeriesHome(
    progress: List<ProgressRow>,
    list: List<String>,
    categories: List<SeriesCategory>,
    prefs: ProfilePrefs?,
    knownNew: Set<String>? = null,
    infoOf: (String) -> SeriesInfo?,
): SeriesHome {
    val hidden = prefs?.hidden.orEmpty().filter { it.itemType == "series_category" }.mapTo(HashSet()) { it.itemId }

    val latest = progress
        .filter { it.itemType == "episode" && it.seriesId != null }
        .groupBy { it.seriesId!! }
        .mapNotNull { (_, rows) -> rows.maxByOrNull { it.clientUpdatedAt } }
        .sortedByDescending { it.clientUpdatedAt }

    // Catégorie connue par la progression, même pour une série masquée : sert aussi à écarter Ma liste.
    val progressCategory = latest.mapNotNull { r -> r.seriesCategory?.takeIf { it.isNotBlank() }?.let { r.seriesId!! to it } }.toMap()
    // Masqué si UNE des catégories connues (ligne de progression ou fiche) l'est. Aucune catégorie connue et fiche
    // pas encore chargée : on attend (la série reste cachée) ; fiche chargée sans catégorie : la série reste visible.
    fun visible(seriesId: String, rowCategory: String?, info: SeriesInfo?): Boolean {
        val known = listOfNotNull(rowCategory?.takeIf { it.isNotBlank() }, info?.summary?.categoryId?.takeIf { it.isNotBlank() })
        return if (known.isEmpty()) info != null else known.none { it in hidden }
    }
    val continueRow = latest.mapNotNull { row ->
        val info = infoOf(row.seriesId!!)
        val category = row.seriesCategory?.takeIf { it.isNotBlank() } ?: info?.summary?.categoryId?.takeIf { it.isNotBlank() }
        if (!visible(row.seriesId!!, row.seriesCategory, info)) null else resumeItem(row, info, category)
    }

    val progressCards = continueRow.associate { it.seriesId to SeriesCard(it.seriesId, it.categoryId, it.title, it.cover) }
    val myList = list.distinct().mapNotNull { id ->
        val info = infoOf(id)
        val known = progressCards[id]
        val category = known?.categoryId ?: progressCategory[id] ?: info?.summary?.categoryId?.takeIf { it.isNotBlank() }
        val title = known?.title ?: info?.summary?.name
        if (title == null || !visible(id, progressCategory[id], info)) null
        else SeriesCard(id, category, title, known?.cover ?: info?.summary?.cover)
    }

    val newIds = if (knownNew == null) {
        continueRow.filter { it.state == ResumeState.NextEpisode }.map { it.seriesId }
    } else {
        (continueRow.map { it.seriesId } + myList.map { it.seriesId }).filter { it in knownNew }
    }.distinct().take(MAX_NEW_EPISODES)
    val allCards = progressCards + myList.associateBy { it.seriesId }
    val newEpisodes = newIds.mapNotNull { allCards[it] }

    val byId = categories.distinctBy { it.id }.filter { it.id !in hidden }.associateBy { it.id }
    val ordered = prefs?.seriesOrder.orEmpty().mapNotNull { byId[it] }.distinctBy { it.id }
    val orderedIds = ordered.mapTo(HashSet()) { it.id }
    val categoryRows = (ordered + byId.values.filter { it.id !in orderedIds }).map { CategoryRow(it.id, it.name) }

    return SeriesHome(continueRow.firstOrNull(), continueRow, myList, newEpisodes, categoryRows)
}

private fun resumeItem(row: ProgressRow, info: SeriesInfo?, category: String?): ResumeItem? {
    val seriesId = row.seriesId ?: return null
    val episodes = info?.seasons.orEmpty().flatMap { it.episodes }.sortedWith(compareBy({ it.season }, { it.number }))
    val index = episodes.indexOfFirst { it.id == row.itemId }.takeIf { it >= 0 }
        ?: episodes.indexOfFirst { it.season == row.season && it.number == row.episode }.takeIf { it >= 0 }
    fun ref(i: Int) = episodes.getOrNull(i)?.let { EpisodeRef(it.id, it.season, it.number, it.title) }
    val title = row.seriesName?.takeIf { it.isNotBlank() } ?: info?.summary?.name ?: "Série"
    val cover = row.seriesCover ?: info?.summary?.cover
    fun item(state: ResumeState, episode: EpisodeRef?, pos: Int, dur: Int, then: EpisodeRef?) =
        ResumeItem(seriesId, category, title, cover, state, episode, pos, dur, then)

    if (!row.watched) {
        val current = ref(index ?: -1) ?: EpisodeRef(row.itemId, row.season ?: 0, row.episode ?: 0, "")
        return item(ResumeState.InProgress, current, row.positionS, row.durationS, index?.let { ref(it + 1) })
    }
    if (index == null) return item(ResumeState.UpToDate, null, 0, 0, null)
    val next = ref(index + 1) ?: return null // dernier épisode connu vu : série terminée
    return item(ResumeState.NextEpisode, next, 0, 0, ref(index + 2))
}
