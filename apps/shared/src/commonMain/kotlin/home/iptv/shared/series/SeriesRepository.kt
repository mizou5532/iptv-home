package home.iptv.shared.series

import home.iptv.shared.catalog.CatalogDatabase
import home.iptv.shared.search.SearchItem
import home.iptv.shared.search.mergeSearchItems
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
    /** Tous les titres du cache, listes et fiches, sans réseau ; toutes les catégories connues sont conservées. */
    fun searchItems(): List<SearchItem> = mergeSearchItems(
        q.selectAllSeriesTitles { id, name, category, cover -> SearchItem(id, name, setOf(category), cover) }.executeAsList(),
    )

    /** Noms des catégories du cache (aucune demande au fournisseur) : langue des versions dans la recherche. */
    fun cachedCategoryNames(): Map<String, String> = q.selectSeriesCategories { id, name -> id to name }.executeAsList().toMap()

    /** Lecture locale de la fraîcheur de la liste des catégories, sans demande fournisseur. */
    fun areCategoriesFresh(): Boolean = isFresh("cats", CATEGORIES_TTL_MS)

    /** Catégories dont les titres sont frais, en une seule lecture (remplissage de la recherche). */
    fun freshCategoryIds(): Set<String> {
        val t = now()
        return q.selectFetchesLike("cat:%").executeAsList()
            .filter { (t - it.fetched_at) in 0 until SERIES_TTL_MS }.mapTo(HashSet()) { it.key.removePrefix("cat:") }
    }

    /** Lecture locale de la fraîcheur des titres de [categoryId], y compris une liste vide valide. */
    fun isCategoryFresh(categoryId: String): Boolean =
        categoryId.isNotBlank() && isFresh(categoryKey(categoryId), SERIES_TTL_MS)

    private val q get() = db.seriesQueries

    /** Catégories du fournisseur sans celles de [hidden] (le cache les contient toutes). */
    suspend fun categories(hidden: Set<String>): List<SeriesCategory> {
        refreshIfStale(
            "cats", CATEGORIES_TTL_MS, { api.seriesCategories().filter { it.id.isNotBlank() }.distinctBy { it.id } },
            isEmpty = { it.isEmpty() }, hasCache = { q.selectSeriesCategoryIds().executeAsList().isNotEmpty() },
        ) { remote ->
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
        // Un ancien cache peut contenir un identifiant vide : jamais une rangée (elle demanderait tout le catalogue).
        return q.selectSeriesCategories { id, name -> SeriesCategory(id, name) }.executeAsList()
            .filter { it.id !in hidden && it.id.isNotBlank() }
    }

    /**
     * Séries d'une catégorie, toutes, dans l'ordre du fournisseur. Une catégorie de [hidden] n'est jamais demandée au
     * fournisseur ni lue dans le cache : le résultat est vide. Un identifiant vide non plus : `get_series` sans
     * catégorie renvoie tout le catalogue.
     */
    suspend fun seriesOf(categoryId: String, hidden: Set<String>): List<SeriesSummary> {
        if (categoryId.isBlank() || categoryId in hidden) return emptyList()
        refreshIfStale(
            categoryKey(categoryId), SERIES_TTL_MS, { api.series(categoryId).distinctBy { it.id } },
            isEmpty = { it.isEmpty() }, hasCache = { q.selectSeriesItems(categoryId).executeAsList().isNotEmpty() },
        ) { remote ->
            db.transaction {
                q.deleteSeriesItemsOfCategory(categoryId)
                remote.forEachIndexed { i, s ->
                    q.insertSeriesItem(s.id, categoryId, s.name, s.cover, s.year, s.genre, s.plot, s.rating, i.toLong(), s.backdrop)
                }
            }
        }
        return q.selectSeriesItems(categoryId) { id, cat, name, cover, year, genre, plot, rating, backdrop ->
            SeriesSummary(id, cat, name, cover, year, genre, plot, rating, backdrop)
        }.executeAsList()
    }

    /** Séries d'une catégorie d'après le cache local seul (aucune demande au fournisseur), vide si jamais chargée. */
    fun cachedSeriesOf(categoryId: String): List<SeriesSummary> =
        q.selectSeriesItems(categoryId) { id, cat, name, cover, year, genre, plot, rating, backdrop ->
            SeriesSummary(id, cat, name, cover, year, genre, plot, rating, backdrop)
        }.executeAsList()

    /** Toutes les séries des catégories déjà parcourues (cache seul, aucune demande au fournisseur). */
    fun cachedSeries(): List<home.iptv.shared.catalog.CatalogTitleSource> =
        q.selectAllSeriesItems { id, cat, name, cover, year, rating -> home.iptv.shared.catalog.CatalogTitleSource(id, name, cover, cat, year, rating) }
            .executeAsList()

    /** Catégorie connue d'une série d'après le cache seul (liste d'une catégorie, puis fiche) : aucune demande au fournisseur. */
    fun cachedCategory(seriesId: String): String? =
        q.selectItemCategory(seriesId).executeAsOneOrNull()?.takeIf { it.isNotBlank() }
            ?: q.selectDetailCategory(seriesId).executeAsOneOrNull()?.takeIf { it.isNotBlank() }

    /** Fiche d'une série ; `null` si le fournisseur ne la connaît pas. */
    suspend fun info(seriesId: String, categoryId: String? = null): SeriesInfo? {
        refreshIfStale(
            infoKey(seriesId), INFO_TTL_MS, { Fetched(api.seriesInfo(seriesId)) },
            isEmpty = { it.value == null }, hasCache = { q.selectSeriesDetail(seriesId).executeAsOneOrNull() != null },
        ) { fetched ->
            val info = fetched.value
            // Catégorie : celle du fournisseur, sinon celle qu'on connaît (appelant, série listée, ancienne fiche), jamais "".
            val known = info?.summary?.categoryId?.takeIf { it.isNotBlank() }
                ?: categoryId?.takeIf { it.isNotBlank() }
                ?: q.selectItemCategory(seriesId).executeAsOneOrNull()?.takeIf { it.isNotBlank() }
                ?: q.selectDetailCategory(seriesId).executeAsOneOrNull()?.takeIf { it.isNotBlank() }
                ?: ""
            db.transaction {
                deleteInfo(seriesId)
                if (info == null) return@transaction
                val s = info.summary
                q.insertSeriesDetail(seriesId, known, s.name, s.cover, s.year, s.genre, s.plot, s.rating, s.backdrop ?: q.selectItemBackdrop(seriesId).executeAsOneOrNull())
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
        evictOldDetails()
        return readInfo(seriesId)
    }

    /** Garde au plus [MAX_DETAILS] fiches : les plus anciennes (date de demande) sont retirées. */
    private fun evictOldDetails() {
        val old = q.selectOldestInfoKeys(MAX_DETAILS.toLong()).executeAsList()
        if (old.isEmpty()) return
        db.transaction {
            old.forEach { key ->
                val id = key.removePrefix("info:")
                deleteInfo(id)
                q.deleteFetch(key)
            }
        }
    }

    private fun readInfo(seriesId: String): SeriesInfo? {
        val summary = q.selectSeriesDetail(seriesId) { id, cat, name, cover, year, genre, plot, rating, backdrop ->
            SeriesSummary(id, cat, name, cover, year, genre, plot, rating, backdrop)
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
     * cache du tout, l'erreur est propagée. Une réponse vide face à un cache non vide est ignorée (cache et date inchangés). L'annulation est toujours relancée.
     */
    private suspend fun <T> refreshIfStale(
        key: String,
        ttlMs: Long,
        fetch: suspend () -> T,
        isEmpty: (T) -> Boolean,
        hasCache: () -> Boolean,
        store: (T) -> Unit,
    ) {
        if (isFresh(key, ttlMs)) return
        val remote = try {
            fetch()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (hasFetched(key)) return
            throw e
        }
        // Une réponse vide ne remplace jamais un cache non vide : on la traite comme une panne.
        if (isEmpty(remote) && hasCache()) return
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
        // 0.40 : 3 jours (avant 24 h) : chaque télé retéléchargeait toutes les séries chaque jour.
        const val SERIES_TTL_MS = 72L * 3_600_000
        const val INFO_TTL_MS = 6L * 3_600_000
        const val MAX_DETAILS = 300
    }
}
