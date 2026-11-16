package home.iptv.tv.feature

import home.iptv.shared.xtream.MovieSummary
import home.iptv.shared.xtream.SeriesInfo
import home.iptv.shared.xtream.SeriesSummary
import home.iptv.tv.movies.MovieCard
import home.iptv.tv.movies.MovieHome
import home.iptv.tv.series.ResumeItem
import home.iptv.tv.series.ResumeState
import home.iptv.tv.series.SeriesCard
import home.iptv.tv.series.SeriesHome
import home.iptv.tv.series.remainingLabel
import home.iptv.tv.series.shortLabel

/** Bannière d'accueil : au plus [MAX_FEATURES] suggestions, une toutes les [FEATURE_ROTATE_MS]. */
const val MAX_FEATURES = 5
const val FEATURE_ROTATE_MS = 10_000L

/**
 * Une suggestion de la bannière. [backdrop] (paysage) est préférée ; sans elle, l'affiche [cover].
 * [resume] : la série en cours (bouton « Reprendre ») ; [startS] : position de reprise d'un film.
 */
data class Feature(
    val itemId: String,
    val categoryId: String?,
    val title: String,
    /** Petite ligne au-dessus du titre : pourquoi on le propose (« REPRENDRE », « PARCE QUE TU REGARDES … »). */
    val reason: String,
    val meta: String?,
    val plot: String?,
    val backdrop: String?,
    val cover: String?,
    val progress: Float? = null,
    val resume: ResumeItem? = null,
    val startS: Int = 0,
    val isMovie: Boolean = false,
)

/** Une suggestion tirée des catégories que le profil regarde : la série/le film et le titre qui l'a inspirée. */
data class Pick<T>(val item: T, val because: String?)

internal fun details(year: String?, genre: String?, rating: String?, extra: String? = null): String? =
    listOfNotNull(
        year?.takeIf { it.isNotBlank() },
        extra,
        genre?.takeIf { it.isNotBlank() }?.substringBefore(',')?.substringBefore('/')?.trim(),
        rating?.toDoubleOrNull()?.takeIf { it > 0 }?.let { "Note " + "%.1f".format(java.util.Locale.FRANCE, it) },
    ).joinToString(" · ").takeIf { it.isNotBlank() }

private fun becauseLabel(because: String?) = if (because != null) "PARCE QUE TU REGARDES « ${because.uppercase()} »" else "À DÉCOUVRIR"

/** Note d'un titre pour classer les suggestions (absente ou illisible = 0). */
internal fun ratingOf(r: String?): Double = r?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0

/**
 * Bannière Séries, dans cet ordre : la série en cours, les séries qui ont de nouveaux épisodes, les suggestions tirées
 * des catégories regardées ([picks]), puis Ma liste. Jamais deux fois la même série.
 */
fun buildSeriesFeatures(home: SeriesHome, picks: List<Pick<SeriesSummary>>, infoOf: (String) -> SeriesInfo?): List<Feature> {
    val out = LinkedHashMap<String, Feature>()
    fun add(f: Feature) { if (f.itemId !in out && out.size < MAX_FEATURES) out[f.itemId] = f }
    home.resume?.takeIf { it.state != ResumeState.UpToDate && it.episode != null }?.let { r ->
        val s = infoOf(r.seriesId)?.summary
        add(Feature(
            r.seriesId, r.categoryId, r.title,
            if (r.state == ResumeState.InProgress) "REPRENDRE" else "ÉPISODE SUIVANT",
            listOfNotNull(r.episode?.shortLabel(), r.remainingLabel()).joinToString(" · "),
            r.episode?.title?.takeIf { it.isNotBlank() }?.let { "« $it »" + (s?.plot?.let { p -> " — $p" } ?: "") } ?: s?.plot,
            s?.backdrop, r.cover ?: s?.cover,
            progress = if (r.state == ResumeState.InProgress && r.durationS > 0) (r.positionS.toFloat() / r.durationS).coerceIn(0f, 1f) else null,
            resume = r,
        ))
    }
    fun card(c: SeriesCard, reason: String) {
        val s = infoOf(c.seriesId)?.summary
        add(Feature(c.seriesId, c.categoryId, c.title, reason, s?.let { details(it.year, it.genre, it.rating) }, s?.plot, s?.backdrop, c.cover ?: s?.cover))
    }
    home.newEpisodes.forEach { card(it, "NOUVEAUX ÉPISODES") }
    picks.forEach { (s, because) ->
        add(Feature(s.id, s.categoryId, s.name, becauseLabel(because), details(s.year, s.genre, s.rating), s.plot, s.backdrop, s.cover))
    }
    home.myList.forEach { card(it, "DANS TA LISTE") }
    return out.values.toList()
}

/**
 * Bannière Films : le film en cours, les suggestions des catégories regardées, Ma liste, puis les ajouts récents.
 * [infoOf] : la fiche déjà lue d'un film (image de fond, résumé), si on l'a.
 */
fun buildMovieFeatures(home: MovieHome, picks: List<Pick<MovieSummary>>, infoOf: (String) -> MovieSummary?): List<Feature> {
    val out = LinkedHashMap<String, Feature>()
    fun add(f: Feature) { if (f.itemId !in out && out.size < MAX_FEATURES) out[f.itemId] = f }
    fun movie(c: MovieCard, reason: String, resume: Boolean) {
        val s = infoOf(c.movieId)
        val remaining = if (resume && c.durationS > 0) "${((c.durationS - c.positionS).coerceAtLeast(0) + 59) / 60} min restantes" else null
        add(Feature(
            c.movieId, c.categoryId, c.title, reason,
            if (remaining != null) listOfNotNull(remaining, s?.year).joinToString(" · ") else s?.let { details(it.year, it.genre, it.rating, it.durationS?.let(::hours)) },
            s?.plot, s?.backdrop, c.cover ?: s?.cover,
            progress = if (resume && c.durationS > 0) (c.positionS.toFloat() / c.durationS).coerceIn(0f, 1f) else null,
            startS = if (resume) c.positionS else 0, isMovie = true,
        ))
    }
    home.continueRow.firstOrNull()?.let { movie(it, "REPRENDRE", true) }
    picks.forEach { (m, because) ->
        val s = infoOf(m.id) ?: m
        add(Feature(m.id, m.categoryId, m.name, becauseLabel(because), details(s.year, s.genre, s.rating, s.durationS?.let(::hours)), s.plot, s.backdrop ?: m.backdrop, m.cover ?: s.cover, isMovie = true))
    }
    home.myList.forEach { movie(it, "DANS TA LISTE", false) }
    home.recent.forEach { movie(it, "NOUVEAU", false) }
    return out.values.toList()
}

internal fun hours(s: Int): String = if (s >= 3600) "${s / 3600} h ${"%02d".format((s % 3600) / 60)}" else "${s / 60} min"

/**
 * Suggestions « basées sur ce qu'on regarde » : les mieux notées des catégories des titres en cours ([watched] :
 * identifiant de catégorie et titre en cours), sans ce qui est déjà commencé ou dans [exclude]. Sans titre en cours,
 * on prend dans [fallback] (première catégorie de l'accueil). [count] au plus, au plus 2 par catégorie.
 */
fun <T> pickFrom(
    watched: List<Pair<String, String>>,
    itemsOf: (String) -> List<T>,
    idOf: (T) -> String,
    ratingOf: (T) -> Double,
    exclude: Set<String>,
    fallback: String?,
    count: Int = 3,
): List<Pick<T>> {
    val sources = watched.distinctBy { it.first }.take(3).ifEmpty { listOfNotNull(fallback?.let { it to "" }) }
    val out = ArrayList<Pick<T>>()
    val seen = HashSet(exclude)
    for ((cat, title) in sources) {
        itemsOf(cat).sortedByDescending(ratingOf).filter { seen.add(idOf(it)) }.take(2).forEach {
            if (out.size < count) out += Pick(it, title.takeIf { t -> t.isNotBlank() })
        }
    }
    return out
}
