package home.iptv.tv.progress

import home.iptv.shared.progress.ProgressRow
import home.iptv.shared.progress.remapEpisodes
import home.iptv.shared.progress.remapMovies
import home.iptv.shared.progress.remapSeries
import home.iptv.shared.search.SearchItem
import home.iptv.shared.xtream.SeriesInfo
import kotlinx.coroutines.CancellationException

/** Ce qui a été retrouvé : films et épisodes recopiés sous leurs nouveaux numéros. */
data class RemapResult(val movies: Int, val episodes: Int)

/**
 * Retrouve la progression de chaque profil sur le nouveau catalogue (après un changement de fournisseur) et la recopie
 * sous les nouveaux numéros. L'ancienne progression n'est jamais effacée ; une position plus récente gagne toujours
 * (serveur). Une série dont la fiche ne répond pas est simplement sautée.
 */
class ProgressRemapper(
    private val profiles: () -> List<String>,
    private val rows: suspend (profileId: String) -> List<ProgressRow>,
    private val movies: () -> List<SearchItem>,
    private val series: () -> List<SearchItem>,
    private val seriesInfo: suspend (seriesId: String, categoryId: String?) -> SeriesInfo?,
    private val copy: suspend (profileId: String, rows: List<ProgressRow>) -> Int,
) {
    suspend fun run(): RemapResult {
        val movieItems = movies()
        val seriesItems = series()
        var m = 0; var e = 0
        val episodesOf = HashMap<String, Map<Pair<Int, Int>, String>?>()
        for (p in profiles()) {
            val all = rows(p)
            val movedMovies = remapMovies(all, movieItems)
            val movedEpisodes = mutableListOf<ProgressRow>()
            for ((old, target) in remapSeries(all, seriesItems)) {
                val map = episodesOf.getOrPut(target.id) {
                    try {
                        seriesInfo(target.id, target.categoryIds.minOrNull())?.seasons
                            ?.flatMap { s -> s.episodes.map { (s.number to it.number) to it.id } }?.toMap()
                    } catch (ex: CancellationException) { throw ex } catch (ex: Exception) { null }
                } ?: continue
                movedEpisodes += remapEpisodes(all, old, target, map)
            }
            (movedMovies + movedEpisodes).chunked(500).forEach { copy(p, it) }
            m += movedMovies.size; e += movedEpisodes.size
        }
        return RemapResult(m, e)
    }
}

/**
 * Quand lancer [ProgressRemapper] : le compte IPTV (serveur + nom d'utilisateur) a changé depuis la dernière fois, et le
 * nouveau catalogue de films et de séries est entièrement lu. [load]/[save] : état gardé sur la télé.
 */
class RemapTrigger(private val load: (String) -> String?, private val save: (String, String?) -> Unit) {
    /**
     * À appeler à chaque démarrage avec l'empreinte du compte actuel. Compte changé : [onChange] (vider l'ancien
     * catalogue) d'abord ; le nouveau compte n'est retenu que s'il a réussi (sinon nouvel essai au démarrage suivant).
     */
    fun accountSeen(fingerprint: String, onChange: () -> Unit = {}): Boolean {
        val last = load("account")
        if (last == null) { save("account", fingerprint); return false }
        if (last == fingerprint) return false
        onChange()
        save("account", fingerprint); save("pending", "1")
        return true
    }
    val pending: Boolean get() = load("pending") == "1"
    fun done(result: RemapResult, atText: String) {
        save("pending", null)
        save("last", "Retrouvée le $atText : ${result.movies} film${if (result.movies > 1) "s" else ""}, ${result.episodes} épisode${if (result.episodes > 1) "s" else ""}")
    }
    /** Dernier résultat, pour l'écran « État de l'app » ; `null` si jamais fait. */
    val last: String? get() = load("last")
}
