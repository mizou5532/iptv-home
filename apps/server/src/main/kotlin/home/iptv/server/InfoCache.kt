package home.iptv.server

import home.iptv.shared.movies.MovieRepository
import home.iptv.shared.series.SeriesRepository
import home.iptv.shared.xtream.MovieInfo
import home.iptv.shared.xtream.SeriesInfo
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Levée lorsqu'une fiche appartient à une catégorie masquée pour tout le monde. */
class HiddenCategoryException : Exception("Catégorie masquée")

/**
 * Cache partagé des fiches films (`get_vod_info`) et séries (`get_series_info`) pour toute la maison :
 * réutilise le cache SQLite 6 h de [MovieRepository] / [SeriesRepository], limite les appels simultanés vers le
 * fournisseur à [MAX_CONCURRENT], appelle [beforeFetch] avant tout accès réseau (pour recharger les identifiants IPTV
 * en mémoire après un redémarrage), et refuse toute fiche appartenant à une catégorie masquée pour tout le monde.
 */
class InfoCache(
    private val movies: MovieRepository,
    private val series: SeriesRepository,
    private val hiddenMovieCats: () -> Set<String> = { emptySet() },
    private val hiddenSeriesCats: () -> Set<String> = { emptySet() },
    private val beforeFetch: suspend () -> Unit = {},
) {
    private val permits = Semaphore(MAX_CONCURRENT)

    suspend fun movieInfo(movieId: String): MovieInfo? {
        val hidden = hiddenMovieCats()
        val cachedCat = movies.cachedCategory(movieId)
        if (cachedCat != null && cachedCat in hidden) throw HiddenCategoryException()
        if (movies.isInfoFresh(movieId)) {
            val info = movies.cachedInfo(movieId)
            if (info != null && info.summary.categoryId in hidden) throw HiddenCategoryException()
            return info
        }
        val info = permits.withPermit {
            beforeFetch()
            movies.info(movieId)
        }
        if (info != null && info.summary.categoryId in hiddenMovieCats()) throw HiddenCategoryException()
        return info
    }

    suspend fun seriesInfo(seriesId: String): SeriesInfo? {
        val hidden = hiddenSeriesCats()
        val cachedCat = series.cachedCategory(seriesId)
        if (cachedCat != null && cachedCat in hidden) throw HiddenCategoryException()
        if (series.isInfoFresh(seriesId)) {
            val info = series.cachedInfo(seriesId)
            if (info != null && info.summary.categoryId in hidden) throw HiddenCategoryException()
            return info
        }
        val info = permits.withPermit {
            beforeFetch()
            series.info(seriesId)
        }
        if (info != null && info.summary.categoryId in hiddenSeriesCats()) throw HiddenCategoryException()
        return info
    }

    private companion object {
        const val MAX_CONCURRENT = 3
    }
}
