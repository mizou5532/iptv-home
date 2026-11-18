package home.iptv.shared.movies

import home.iptv.shared.catalog.CatalogDatabase
import home.iptv.shared.search.SearchItem
import home.iptv.shared.search.mergeSearchItems
import home.iptv.shared.xtream.MovieCategory
import home.iptv.shared.xtream.MovieInfo
import home.iptv.shared.xtream.MovieSummary
import home.iptv.shared.xtream.UNTITLED_MOVIE
import home.iptv.shared.xtream.XtreamApi
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

interface MovieSource {
    suspend fun movieCategories(): List<MovieCategory>
    suspend fun movies(categoryId: String): List<MovieSummary>
    suspend fun movieInfo(movieId: String): MovieInfo?

    companion object {
        fun of(api: XtreamApi): MovieSource = object : MovieSource {
            override suspend fun movieCategories() = api.movieCategories()
            override suspend fun movies(categoryId: String) = api.movies(categoryId)
            override suspend fun movieInfo(movieId: String) = api.movieInfo(movieId)
        }
    }
}

/**
 * Cache local des films, sur le modèle des séries : une entrée par catégorie (liste des films) et par film (fiche).
 * Chaque lecture sert le cache s'il est frais (catégories et films d'une catégorie : 1 jour, fiche : 6 h), sinon
 * interroge le fournisseur ; si celui-ci échoue ou répond vide, le cache même périmé est servi et reste intact.
 * Sans cache, l'erreur du fournisseur est propagée. L'annulation est toujours relancée.
 *
 * La liste globale des films n'est jamais demandée : seules les catégories parcourues sont connues.
 */
class MovieRepository(
    private val db: CatalogDatabase,
    private val api: MovieSource,
    private val clock: Clock = Clock.System,
) {
    /** Tous les titres du cache, listes et fiches, sans réseau ; toutes les catégories connues sont conservées. */
    fun searchItems(): List<SearchItem> = mergeSearchItems(
        q.selectAllMovieTitles { id, name, category, cover -> SearchItem(id, name, setOf(category), cover) }.executeAsList(),
    )

    /** Noms des catégories du cache (aucune demande au fournisseur) : langue des versions dans la recherche. */
    fun cachedCategoryNames(): Map<String, String> = q.selectMovieCategories { id, name -> id to name }.executeAsList().toMap()

    /** Lecture locale de la fraîcheur de la liste des catégories, sans demande fournisseur. */
    fun areCategoriesFresh(): Boolean = isFresh("cats", CATEGORIES_TTL_MS)

    /** Lecture locale de la fraîcheur des titres de [categoryId], y compris une liste vide valide. */
    fun isCategoryFresh(categoryId: String): Boolean =
        categoryId.isNotBlank() && isFresh(categoryKey(categoryId), MOVIES_TTL_MS)

    private val q get() = db.moviesQueries

    /** Catégories du fournisseur sans celles de [hidden] (le cache les contient toutes). */
    suspend fun categories(hidden: Set<String>): List<MovieCategory> {
        refreshIfStale(
            "cats", CATEGORIES_TTL_MS, { api.movieCategories().filter { it.id.isNotBlank() }.distinctBy { it.id } },
            isEmpty = { it.isEmpty() }, hasCache = { q.selectMovieCategoryIds().executeAsList().isNotEmpty() },
        ) { remote ->
            val gone = q.selectMovieCategoryIds().executeAsList().toSet() - remote.map { it.id }.toSet()
            q.deleteAllMovieCategories()
            remote.forEachIndexed { i, c -> q.insertMovieCategory(c.id, c.name, i.toLong()) }
            gone.forEach {
                q.deleteMovieItemsOfCategory(it)
                q.deleteMovieFetch(categoryKey(it))
            }
        }
        // Jamais de rangée pour un identifiant vide : elle demanderait tout le catalogue.
        return q.selectMovieCategories { id, name -> MovieCategory(id, name) }.executeAsList()
            .filter { it.id !in hidden && it.id.isNotBlank() }
    }

    /**
     * Films d'une catégorie, tous, dans l'ordre du fournisseur. Une catégorie de [hidden] n'est jamais demandée au
     * fournisseur ni lue dans le cache : le résultat est vide. Un identifiant vide non plus : `get_vod_streams` sans
     * catégorie renvoie tout le catalogue.
     */
    suspend fun moviesOf(categoryId: String, hidden: Set<String>): List<MovieSummary> {
        if (categoryId.isBlank() || categoryId in hidden) return emptyList()
        refreshIfStale(
            categoryKey(categoryId), MOVIES_TTL_MS,
            { api.movies(categoryId).filter { it.id.isNotBlank() }.distinctBy { it.id } },
            isEmpty = { it.isEmpty() }, hasCache = { q.countMovieItems(categoryId).executeAsOne() > 0 },
        ) { remote ->
            q.deleteMovieItemsOfCategory(categoryId)
            remote.forEachIndexed { i, m ->
                q.insertMovieItem(
                    m.id, categoryId, m.name, m.cover, m.year, m.genre, m.plot, m.rating, m.addedAt, m.extension,
                    m.durationS?.toLong(), i.toLong(), m.backdrop,
                )
            }
        }
        return q.selectMovieItems(categoryId, ::summary).executeAsList()
    }

    /** Catégorie connue d'un film d'après le cache seul (liste d'une catégorie, puis fiche) : aucune demande au fournisseur. */
    fun cachedCategory(movieId: String): String? =
        cachedItem(movieId)?.categoryId?.takeIf { it.isNotBlank() }
            ?: q.selectMovieDetailCategory(movieId).executeAsOneOrNull()?.takeIf { it.isNotBlank() }

    /** Un film déjà connu, sans condition de date : cache seul et résultat borné à une ligne. */
    fun featuredMovie(hidden: Set<String>): MovieSummary? =
        q.selectFeaturedMovie(hidden, ::summary).executeAsOneOrNull()

    /**
     * Tous les films des catégories déjà parcourues, sans ceux d'une catégorie de [hidden]. Lecture du cache seul.
     * Peut être long sur un gros cache : pour une rangée, préférer [recentlyAdded] ou [moviesOf].
     */
    fun cachedMovies(hidden: Set<String> = emptySet()): List<MovieSummary> =
        q.selectAllMovieItems(::summary).executeAsList().filter { it.categoryId.isNotBlank() && it.categoryId !in hidden }

    /**
     * Au plus [limit] films ajoutés le plus récemment, parmi les catégories déjà parcourues et non masquées. Cache seul.
     * Une date absente, nulle ou impossible (au-delà de demain) n'est jamais une nouveauté. Un film présent dans
     * plusieurs catégories ne compte qu'une fois (sa première copie visible). Lecture par pages : la mémoire reste
     * bornée même si le cache contient des centaines de milliers de films.
     */
    fun recentlyAdded(hidden: Set<String>, limit: Int): List<MovieSummary> {
        if (limit <= 0) return emptyList()
        val latest = now() / 1000 + 86_400
        val found = LinkedHashMap<String, MovieSummary>()
        var skip = 0L
        while (found.size < limit) {
            val page = q.selectRecentMovieItems(latest, RECENT_PAGE, skip, ::summary).executeAsList()
            for (m in page) {
                if (found.size >= limit) break
                if (m.categoryId.isBlank() || m.categoryId in hidden || m.id in found) continue
                found[m.id] = m
            }
            if (page.size < RECENT_PAGE) break
            skip += RECENT_PAGE
        }
        return found.values.toList()
    }

    /**
     * Fiche d'un film ; `null` si le fournisseur ne le connaît pas. Ce que la fiche ignore (nom, affiche, date
     * d'ajout…) est complété par le film déjà listé dans une catégorie ; l'extension de la liste sert à la lecture.
     */
    suspend fun info(movieId: String, categoryId: String? = null): MovieInfo? {
        if (movieId.isBlank()) return null
        refreshIfStale(
            infoKey(movieId), INFO_TTL_MS, { Fetched(api.movieInfo(movieId)) },
            isEmpty = { it.value == null }, hasCache = { q.selectMovieDetail(movieId).executeAsOneOrNull() != null },
        ) { fetched ->
            val s = fetched.value?.summary
            val listed = cachedItem(movieId)
            // Catégorie : celle du fournisseur, sinon celle qu'on connaît (appelant, film listé, ancienne fiche), jamais "".
            val known = s?.categoryId?.takeIf { it.isNotBlank() }
                ?: categoryId?.takeIf { it.isNotBlank() }
                ?: listed?.categoryId?.takeIf { it.isNotBlank() }
                ?: q.selectMovieDetailCategory(movieId).executeAsOneOrNull()?.takeIf { it.isNotBlank() }
                ?: ""
            q.deleteMovieDetail(movieId)
            if (s == null) return@refreshIfStale
            q.insertMovieDetail(
                movieId, known,
                s.name.takeIf { it.isNotBlank() && it != UNTITLED_MOVIE } ?: listed?.name ?: UNTITLED_MOVIE,
                s.cover ?: listed?.cover, s.year ?: listed?.year, s.genre ?: listed?.genre, s.plot ?: listed?.plot,
                s.rating ?: listed?.rating, s.addedAt ?: listed?.addedAt, listed?.extension ?: s.extension,
                (s.durationS ?: listed?.durationS)?.toLong(), s.backdrop ?: listed?.backdrop,
                fetched.value.tech?.encode(),
            )
        }
        evictOldDetails()
        val tech = home.iptv.shared.xtream.MediaTech.decode(q.selectMovieTech(movieId).executeAsOneOrNull()?.tech)
        return q.selectMovieDetail(movieId, ::summary).executeAsOneOrNull()?.let { MovieInfo(it, tech) }
    }

    private fun cachedItem(movieId: String): MovieSummary? =
        q.selectMovieItemById(movieId, ::summary).executeAsOneOrNull()

    /** Garde au plus [MAX_DETAILS] fiches : les plus anciennes (date de demande) sont retirées. */
    private fun evictOldDetails() {
        val old = q.selectOldestMovieInfoKeys(MAX_DETAILS.toLong()).executeAsList()
        if (old.isEmpty()) return
        db.transaction {
            old.forEach { key ->
                q.deleteMovieDetail(key.removePrefix("info:"))
                q.deleteMovieFetch(key)
            }
        }
    }

    @Suppress("LongParameterList")
    /** Films d'une catégorie d'après le cache local seul (aucune demande au fournisseur), vide si jamais chargée. */
    fun cachedMoviesOf(categoryId: String): List<MovieSummary> = q.selectMovieItems(categoryId, ::summary).executeAsList()

    private fun summary(
        id: String, categoryId: String, name: String, cover: String?, year: String?, genre: String?, plot: String?,
        rating: String?, addedAt: Long?, extension: String, durationS: Long?, backdrop: String?,
    ) = MovieSummary(id, categoryId, name, cover, year, genre, plot, rating, addedAt, extension, durationS?.toInt(), backdrop)

    /** Enveloppe qui permet de mémoriser aussi « le fournisseur ne connaît pas ce film » (valeur null). */
    private class Fetched<T>(val value: T)

    /**
     * Si l'entrée [key] n'est plus fraîche, la demande au fournisseur puis l'enregistre avec sa date (dans la même
     * transaction). En cas d'erreur (panne, réponse mal formée) : le cache périmé est conservé tel quel ; sans cache du
     * tout, l'erreur est propagée. Une réponse vide face à un cache non vide est ignorée (cache et date inchangés).
     * L'annulation est toujours relancée.
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
            q.upsertMovieFetchedAt(key, now())
        }
    }

    private fun now(): Long = clock.now().toEpochMilliseconds()

    private fun hasFetched(key: String): Boolean = q.selectMovieFetchedAt(key).executeAsOneOrNull() != null

    private fun isFresh(key: String, ttlMs: Long): Boolean {
        val at = q.selectMovieFetchedAt(key).executeAsOneOrNull() ?: return false
        val age = now() - at
        return age in 0 until ttlMs
    }

    private fun categoryKey(id: String) = "cat:$id"
    private fun infoKey(id: String) = "info:$id"

    private companion object {
        const val CATEGORIES_TTL_MS = 24L * 3_600_000
        const val MOVIES_TTL_MS = 24L * 3_600_000
        const val INFO_TTL_MS = 6L * 3_600_000
        const val MAX_DETAILS = 300
        const val RECENT_PAGE = 200L
    }
}
