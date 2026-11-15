package home.iptv.shared.series

import home.iptv.shared.catalog.CatalogDatabase
import home.iptv.shared.xtream.Episode
import home.iptv.shared.xtream.Season
import home.iptv.shared.xtream.SeriesCategory
import home.iptv.shared.xtream.SeriesInfo
import home.iptv.shared.xtream.SeriesSummary
import home.iptv.shared.xtream.XtreamApi
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

interface SeriesSource {
    suspend fun seriesCategories(): List<SeriesCategory>
    suspend fun series(categoryId: String): List<SeriesSummary>
    suspend fun seriesInfo(seriesId: String): SeriesInfo?

    companion object {
        fun of(api: XtreamApi): SeriesSource = object : SeriesSource {
            override suspend fun seriesCategories() = api.seriesCategories()
            override suspend fun series(categoryId: String) = api.series(categoryId)
            override suspend fun seriesInfo(seriesId: String) = api.seriesInfo(seriesId)
        }
    }
}

/**
 * Cache local des séries : une entrée par catégorie (liste des séries) et par série (fiche).
 * Chaque lecture sert le cache s'il est frais (catégories et séries d'une catégorie : 1 jour, fiche : 6 h),
 * sinon interroge le fournisseur ; si celui-ci échoue, le cache même périmé est servi.
 * Sans cache, l'erreur du fournisseur est propagée. L'annulation est toujours relancée.
 */
class SeriesRepository(
    private val db: CatalogDatabase,
    private val api: SeriesSource,
    private val clock: Clock = Clock.System,
) {
    private val q get() = db.seriesQueries

    /** Catégories du fournisseur sans celles de [hidden] (le cache les contient toutes). */
    suspend fun categories(hidden: Set<String>): List<SeriesCategory> {
        refreshIfStale("cats", CATEGORIES_TTL_MS, { api.seriesCategories().distinctBy { it.id } }) { remote ->
            val gone = q.selectSeriesCategoryIds().executeAsList().toSet() - remote.map { it.id }.toSet()
            db.transaction {
                q.deleteAllSeriesCategories()
                remote.forEachIndexed { i, c -> q.insertSeriesCategory(c.id, c.name, i.toLong()) }
                gone.forEach {
                    q.deleteSeriesItemsOfCategory(it)
                    q.deleteFetch(categoryKey(it))
                }
            }
        }
        return q.selectSeriesCategories { id, name -> SeriesCategory(id, name) }.executeAsList()
            .filter { it.id !in hidden }
    }

    /**
     * Séries d'une catégorie. Une catégorie de [hidden] n'est jamais demandée au fournisseur
     * ni lue dans le cache : le résultat est vide.
     */
    suspend fun seriesOf(categoryId: String, hidden: Set<String> = emptySet()): List<SeriesSummary> {
        if (categoryId in hidden) return emptyList()
        refreshIfStale(categoryKey(categoryId), SERIES_TTL_MS, { api.series(categoryId).distinctBy { it.id } }) { remote ->
            db.transaction {
                q.deleteSeriesItemsOfCategory(categoryId)
                remote.forEachIndexed { i, s ->
                    q.insertSeriesItem(s.id, categoryId, s.name, s.cover, s.year, s.genre, s.plot, s.rating, i.toLong())
                }
            }
        }
        return q.selectSeriesItems(categoryId) { id, cat, name, cover, year, genre, plot, rating ->
            SeriesSummary(id, cat, name, cover, year, genre, plot, rating)
        }.executeAsList()
    }

    /** Fiche d'une série ; `null` si le fournisseur ne la connaît pas. */
    suspend fun info(seriesId: String): SeriesInfo? {
        refreshIfStale(infoKey(seriesId), INFO_TTL_MS, { Fetched(api.seriesInfo(seriesId)) }) { fetched ->
            val info = fetched.value
            db.transaction {
                deleteInfo(seriesId)
                if (info == null) return@transaction
                val s = info.summary
                q.insertSeriesDetail(seriesId, s.categoryId, s.name, s.cover, s.year, s.genre, s.plot, s.rating)
                info.seasons.forEachIndexed { i, season ->
                    q.insertSeriesSeason(seriesId, season.number.toLong(), season.name, i.toLong())
                }
                var position = 0L
                info.seasons.forEach { season ->
                    season.episodes.forEach { e ->
                        q.insertSeriesEpisode(
                            e.id, seriesId, season.number.toLong(), e.number.toLong(), e.title,
                            e.durationS?.toLong(), e.extension, e.cover, position++,
                        )
                    }
                }
            }
        }
        return readInfo(seriesId)
    }

    private fun readInfo(seriesId: String): SeriesInfo? {
        val summary = q.selectSeriesDetail(seriesId) { id, cat, name, cover, year, genre, plot, rating ->
            SeriesSummary(id, cat, name, cover, year, genre, plot, rating)
        }.executeAsOneOrNull() ?: return null
        val episodes = q.selectSeriesEpisodes(seriesId) { id, season, number, title, duration, ext, cover ->
            Episode(id, season.toInt(), number.toInt(), title, duration?.toInt(), ext, cover)
        }.executeAsList().groupBy { it.season }
        val seasons = q.selectSeriesSeasons(seriesId) { number, name ->
            Season(number.toInt(), name, episodes[number.toInt()].orEmpty())
        }.executeAsList()
        return SeriesInfo(summary, seasons)
    }

    private fun deleteInfo(seriesId: String) {
        q.deleteSeriesDetail(seriesId)
        q.deleteSeriesSeasons(seriesId)
        q.deleteSeriesEpisodes(seriesId)
    }

    /** Enveloppe qui permet de mémoriser aussi « le fournisseur ne connaît pas cette série » (valeur null). */
    private class Fetched<T>(val value: T)

    /**
     * Si l'entrée [key] n'est plus fraîche, la demande au fournisseur puis l'enregistre avec sa date
     * (dans la même transaction). En cas d'erreur : le cache périmé est conservé tel quel ; sans
     * cache du tout, l'erreur est propagée. L'annulation est toujours relancée.
     */
    private suspend fun <T> refreshIfStale(key: String, ttlMs: Long, fetch: suspend () -> T, store: (T) -> Unit) {
        if (isFresh(key, ttlMs)) return
        val remote = try {
            fetch()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (hasFetched(key)) return
            throw e
        }
        db.transaction {
            store(remote)
            q.upsertFetchedAt(key, now())
        }
    }

    private fun now(): Long = clock.now().toEpochMilliseconds()

    private fun hasFetched(key: String): Boolean = q.selectFetchedAt(key).executeAsOneOrNull() != null

    private fun isFresh(key: String, ttlMs: Long): Boolean {
        val at = q.selectFetchedAt(key).executeAsOneOrNull() ?: return false
        val age = now() - at
        return age in 0 until ttlMs
    }

    private fun categoryKey(id: String) = "cat:$id"
    private fun infoKey(id: String) = "info:$id"

    private companion object {
        const val CATEGORIES_TTL_MS = 24L * 3_600_000
        const val SERIES_TTL_MS = 24L * 3_600_000
        const val INFO_TTL_MS = 6L * 3_600_000
    }
}
