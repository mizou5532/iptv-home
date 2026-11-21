package home.iptv.tv.home

import home.iptv.shared.catalog.Showcase
import home.iptv.shared.home.VodManifest
import home.iptv.shared.home.toMovieSummary
import home.iptv.shared.home.toSeriesSummary
import home.iptv.shared.home.toVersion
import home.iptv.shared.meta.MediaKind
import home.iptv.shared.movies.MovieRepository
import home.iptv.shared.movies.MovieSource
import home.iptv.shared.series.SeriesRepository
import home.iptv.shared.series.SeriesSource
import home.iptv.shared.versions.LanguagePrefs
import home.iptv.shared.versions.Version
import home.iptv.shared.versions.VersionIndex
import home.iptv.shared.versions.VersionIndexes
import home.iptv.shared.xtream.MovieCategory
import home.iptv.shared.xtream.MovieInfo
import home.iptv.shared.xtream.MovieSummary
import home.iptv.shared.xtream.SeriesCategory
import home.iptv.shared.xtream.SeriesInfo
import home.iptv.shared.xtream.SeriesSummary
import home.iptv.tv.data.LocalStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val VOD_ABSENT_FALLBACK_MS = 24L * 3_600_000L

/**
 * Vrai si le serveur maison assure le travail (présent maintenant ou vu il y a moins de 24 h).
 * Tant que c'est vrai, la télé ne publie pas pour l'iPhone et ne relance pas les vérifications redondantes.
 */
fun isHomeServerActive(home: HomeVodClient, store: LocalStore, clockMs: () -> Long): Boolean {
    if (home.state.value is HomeState.Present) return true
    val last = store.lastServerContactMs() ?: return false
    return clockMs() - last < VOD_ABSENT_FALLBACK_MS
}

private fun canFallbackToProvider(hasCache: Boolean, store: LocalStore, clockMs: () -> Long): Boolean {
    if (!hasCache) return true
    val last = store.lastServerContactMs() ?: return true
    return clockMs() - last >= VOD_ABSENT_FALLBACK_MS
}

/**
 * Source Films branchée sur le serveur maison avec secours transparent chez le fournisseur :
 * - Fiches (`movieInfo`) : serveur maison (1 500 ms), puis fournisseur si indisponible ou inconnu.
 * - Catégories et listes : serveur maison si le compte IPTV correspond ; secours fournisseur seulement si cache vide
 *   ou serveur absent depuis plus de 24 h.
 */
fun homeMovieSource(
    home: HomeVodClient,
    fallback: MovieSource,
    repo: () -> MovieRepository,
    store: LocalStore,
    account: () -> String?,
    clockMs: () -> Long,
): MovieSource = object : MovieSource {
    @Volatile private var lastManifest: VodManifest? = null

    override suspend fun movieCategories(): List<MovieCategory> {
        val cached = repo().cachedCategories()
        return when (val res = home.movieManifest(store.lastVodManifestRev("movies"))) {
            is VodManifestResult.Fresh -> {
                val m = res.manifest
                if (m.account != account()) {
                    // Manifeste d'un autre compte IPTV : ignoré.
                    if (canFallbackToProvider(cached.isNotEmpty(), store, clockMs)) fallback.movieCategories() else cached
                } else {
                    lastManifest = m
                    store.saveLastVodManifestRev("movies", m.rev)
                    m.categories.map { MovieCategory(it.id, it.name) }
                }
            }
            VodManifestResult.Unchanged -> {
                if (cached.isNotEmpty()) cached
                else {
                    store.saveLastVodManifestRev("movies", null)
                    when (val retry = home.movieManifest(null)) {
                        is VodManifestResult.Fresh -> if (retry.manifest.account == account()) {
                            lastManifest = retry.manifest
                            store.saveLastVodManifestRev("movies", retry.manifest.rev)
                            retry.manifest.categories.map { MovieCategory(it.id, it.name) }
                        } else fallback.movieCategories()
                        else -> fallback.movieCategories()
                    }
                }
            }
            VodManifestResult.Unavailable -> {
                if (canFallbackToProvider(cached.isNotEmpty(), store, clockMs)) fallback.movieCategories() else cached
            }
        }
    }

    override suspend fun movies(categoryId: String): List<MovieSummary> {
        val cached = repo().cachedMoviesOf(categoryId)
        val revs = store.vodCategoryRevs("movies")
        val localRev = revs[categoryId]
        val knownServerRev = lastManifest?.takeIf { it.account == account() }?.categories?.firstOrNull { it.id == categoryId }?.rev
        if (!knownServerRev.isNullOrEmpty() && knownServerRev == localRev && cached.isNotEmpty()) {
            return cached
        }
        return when (val res = home.movieCategory(categoryId, if (cached.isNotEmpty()) localRev else null)) {
            is MovieCategoryResult.Fresh -> {
                if (res.reply.account != account()) {
                    if (canFallbackToProvider(cached.isNotEmpty(), store, clockMs)) fallback.movies(categoryId) else cached
                } else {
                    store.saveVodCategoryRevs("movies", revs + (categoryId to res.reply.rev))
                    res.reply.items.map { it.toMovieSummary() }
                }
            }
            MovieCategoryResult.Unchanged -> {
                if (cached.isNotEmpty()) cached
                else when (val retry = home.movieCategory(categoryId, null)) {
                    is MovieCategoryResult.Fresh -> if (retry.reply.account == account()) {
                        store.saveVodCategoryRevs("movies", revs + (categoryId to retry.reply.rev))
                        retry.reply.items.map { it.toMovieSummary() }
                    } else fallback.movies(categoryId)
                    else -> fallback.movies(categoryId)
                }
            }
            MovieCategoryResult.NotFound -> {
                if (cached.isNotEmpty()) cached
                else if (canFallbackToProvider(false, store, clockMs)) fallback.movies(categoryId) else emptyList()
            }
            MovieCategoryResult.Unavailable -> {
                if (canFallbackToProvider(cached.isNotEmpty(), store, clockMs)) fallback.movies(categoryId) else cached
            }
        }
    }

    override suspend fun movieInfo(movieId: String): MovieInfo? =
        home.movieInfo(movieId) ?: fallback.movieInfo(movieId)
}

/**
 * Source Séries branchée sur le serveur maison avec secours transparent chez le fournisseur (symétrique à [homeMovieSource]).
 */
fun homeSeriesSource(
    home: HomeVodClient,
    fallback: SeriesSource,
    repo: () -> SeriesRepository,
    store: LocalStore,
    account: () -> String?,
    clockMs: () -> Long,
): SeriesSource = object : SeriesSource {
    @Volatile private var lastManifest: VodManifest? = null

    override suspend fun seriesCategories(): List<SeriesCategory> {
        val cached = repo().cachedCategories()
        return when (val res = home.seriesManifest(store.lastVodManifestRev("series"))) {
            is VodManifestResult.Fresh -> {
                val m = res.manifest
                if (m.account != account()) {
                    if (canFallbackToProvider(cached.isNotEmpty(), store, clockMs)) fallback.seriesCategories() else cached
                } else {
                    lastManifest = m
                    store.saveLastVodManifestRev("series", m.rev)
                    m.categories.map { SeriesCategory(it.id, it.name) }
                }
            }
            VodManifestResult.Unchanged -> {
                if (cached.isNotEmpty()) cached
                else {
                    store.saveLastVodManifestRev("series", null)
                    when (val retry = home.seriesManifest(null)) {
                        is VodManifestResult.Fresh -> if (retry.manifest.account == account()) {
                            lastManifest = retry.manifest
                            store.saveLastVodManifestRev("series", retry.manifest.rev)
                            retry.manifest.categories.map { SeriesCategory(it.id, it.name) }
                        } else fallback.seriesCategories()
                        else -> fallback.seriesCategories()
                    }
                }
            }
            VodManifestResult.Unavailable -> {
                if (canFallbackToProvider(cached.isNotEmpty(), store, clockMs)) fallback.seriesCategories() else cached
            }
        }
    }

    override suspend fun series(categoryId: String): List<SeriesSummary> {
        val cached = repo().cachedSeriesOf(categoryId)
        val revs = store.vodCategoryRevs("series")
        val localRev = revs[categoryId]
        val knownServerRev = lastManifest?.takeIf { it.account == account() }?.categories?.firstOrNull { it.id == categoryId }?.rev
        if (!knownServerRev.isNullOrEmpty() && knownServerRev == localRev && cached.isNotEmpty()) {
            return cached
        }
        return when (val res = home.seriesCategory(categoryId, if (cached.isNotEmpty()) localRev else null)) {
            is SeriesCategoryResult.Fresh -> {
                if (res.reply.account != account()) {
                    if (canFallbackToProvider(cached.isNotEmpty(), store, clockMs)) fallback.series(categoryId) else cached
                } else {
                    store.saveVodCategoryRevs("series", revs + (categoryId to res.reply.rev))
                    res.reply.items.map { it.toSeriesSummary() }
                }
            }
            SeriesCategoryResult.Unchanged -> {
                if (cached.isNotEmpty()) cached
                else when (val retry = home.seriesCategory(categoryId, null)) {
                    is SeriesCategoryResult.Fresh -> if (retry.reply.account == account()) {
                        store.saveVodCategoryRevs("series", revs + (categoryId to retry.reply.rev))
                        retry.reply.items.map { it.toSeriesSummary() }
                    } else fallback.series(categoryId)
                    else -> fallback.series(categoryId)
                }
            }
            SeriesCategoryResult.NotFound -> {
                if (cached.isNotEmpty()) cached
                else if (canFallbackToProvider(false, store, clockMs)) fallback.series(categoryId) else emptyList()
            }
            SeriesCategoryResult.Unavailable -> {
                if (canFallbackToProvider(cached.isNotEmpty(), store, clockMs)) fallback.series(categoryId) else cached
            }
        }
    }

    override suspend fun seriesInfo(seriesId: String): SeriesInfo? =
        home.seriesInfo(seriesId) ?: fallback.seriesInfo(seriesId)
}

/** Résultat d'une passe différentielle sur le serveur maison. */
data class VodSyncOutcome(val fromServer: Boolean, val downloadedCategories: Int)

/**
 * Synchronisation différentielle Films & Séries depuis le serveur maison :
 * lit le manifeste (304 = terminé aussitôt), met à jour la liste des catégories, puis ne télécharge que les
 * catégories dont la révision `rev` a changé (vitrine en premier). Ignore tout manifeste d'un autre compte IPTV.
 */
class HomeVodSync(
    private val home: HomeVodClient,
    private val movies: MovieRepository,
    private val series: SeriesRepository,
    private val store: LocalStore,
    private val account: () -> String?,
    private val clockMs: () -> Long,
    private val beforeCategory: suspend () -> Unit = {},
) {
    private val lock = Mutex()

    suspend fun syncMovies(): VodSyncOutcome = lock.withLock {
        val since = store.lastVodManifestRev("movies").takeIf { movies.cachedCategories().isNotEmpty() }
        val manifest = when (val res = home.movieManifest(since)) {
            VodManifestResult.Unchanged -> {
                movies.markCategoriesFresh(clockMs())
                return@withLock VodSyncOutcome(fromServer = true, downloadedCategories = 0)
            }
            is VodManifestResult.Fresh -> res.manifest
            VodManifestResult.Unavailable -> return@withLock VodSyncOutcome(fromServer = false, downloadedCategories = 0)
        }
        val acct = account()
        if (manifest.account != acct) return@withLock VodSyncOutcome(fromServer = false, downloadedCategories = 0)

        val cats = manifest.categories.map { MovieCategory(it.id, it.name) }
        if (cats.isNotEmpty()) movies.replaceCategories(cats, clockMs())
        if (manifest.hidden.isNotEmpty()) movies.removeCategoryItems(manifest.hidden.toSet())

        val existingRevs = store.vodCategoryRevs("movies").toMutableMap()
        val validIds = cats.map { it.id }.toSet()
        existingRevs.keys.retainAll(validIds)

        val showcaseIds = Showcase.ids(Showcase.Kind.MOVIES, cats.associate { it.id to it.name })
        val ordered = manifest.categories.sortedBy { if (it.id in showcaseIds) 0 else 1 }

        var downloaded = 0
        var complete = true
        for (cat in ordered) {
            if (cat.rev.isEmpty()) { complete = false; continue }
            val hasLocal = movies.cachedMoviesOf(cat.id).isNotEmpty()
            if (existingRevs[cat.id] == cat.rev && hasLocal) {
                movies.markCategoryFresh(cat.id, clockMs())
                continue
            }
            beforeCategory()
            when (val cr = home.movieCategory(cat.id, if (hasLocal) existingRevs[cat.id] else null)) {
                is MovieCategoryResult.Fresh -> {
                    // Relit account() à chaque catégorie : si un changement de compte vient de vider le cache, on s'arrête.
                    if (cr.reply.account != account()) { complete = false; break }
                    if (cr.reply.items.isEmpty() && hasLocal) {
                        complete = false
                        continue
                    }
                    movies.replaceCategoryMovies(cat.id, cr.reply.items.map { it.toMovieSummary() }, clockMs())
                    existingRevs[cat.id] = cr.reply.rev
                    downloaded++
                }
                MovieCategoryResult.Unchanged -> {
                    movies.markCategoryFresh(cat.id, clockMs())
                    existingRevs[cat.id] = cat.rev
                }
                MovieCategoryResult.NotFound -> complete = false
                MovieCategoryResult.Unavailable -> { complete = false; break }
            }
        }
        store.saveVodCategoryRevs("movies", existingRevs)
        if (complete) store.saveLastVodManifestRev("movies", manifest.rev)
        VodSyncOutcome(fromServer = true, downloadedCategories = downloaded)
    }

    suspend fun syncSeries(): VodSyncOutcome = lock.withLock {
        val since = store.lastVodManifestRev("series").takeIf { series.cachedCategories().isNotEmpty() }
        val manifest = when (val res = home.seriesManifest(since)) {
            VodManifestResult.Unchanged -> {
                series.markCategoriesFresh(clockMs())
                return@withLock VodSyncOutcome(fromServer = true, downloadedCategories = 0)
            }
            is VodManifestResult.Fresh -> res.manifest
            VodManifestResult.Unavailable -> return@withLock VodSyncOutcome(fromServer = false, downloadedCategories = 0)
        }
        val acct = account()
        if (manifest.account != acct) return@withLock VodSyncOutcome(fromServer = false, downloadedCategories = 0)

        val cats = manifest.categories.map { SeriesCategory(it.id, it.name) }
        if (cats.isNotEmpty()) series.replaceCategories(cats, clockMs())
        if (manifest.hidden.isNotEmpty()) series.removeCategoryItems(manifest.hidden.toSet())

        val existingRevs = store.vodCategoryRevs("series").toMutableMap()
        val validIds = cats.map { it.id }.toSet()
        existingRevs.keys.retainAll(validIds)

        val showcaseIds = Showcase.ids(Showcase.Kind.SERIES, cats.associate { it.id to it.name })
        val ordered = manifest.categories.sortedBy { if (it.id in showcaseIds) 0 else 1 }

        var downloaded = 0
        var complete = true
        for (cat in ordered) {
            if (cat.rev.isEmpty()) { complete = false; continue }
            val hasLocal = series.cachedSeriesOf(cat.id).isNotEmpty()
            if (existingRevs[cat.id] == cat.rev && hasLocal) {
                series.markCategoryFresh(cat.id, clockMs())
                continue
            }
            beforeCategory()
            when (val cr = home.seriesCategory(cat.id, if (hasLocal) existingRevs[cat.id] else null)) {
                is SeriesCategoryResult.Fresh -> {
                    if (cr.reply.account != account()) { complete = false; break }
                    if (cr.reply.items.isEmpty() && hasLocal) {
                        complete = false
                        continue
                    }
                    series.replaceCategorySeries(cat.id, cr.reply.items.map { it.toSeriesSummary() }, clockMs())
                    existingRevs[cat.id] = cr.reply.rev
                    downloaded++
                }
                SeriesCategoryResult.Unchanged -> {
                    series.markCategoryFresh(cat.id, clockMs())
                    existingRevs[cat.id] = cat.rev
                }
                SeriesCategoryResult.NotFound -> complete = false
                SeriesCategoryResult.Unavailable -> { complete = false; break }
            }
        }
        store.saveVodCategoryRevs("series", existingRevs)
        if (complete) store.saveLastVodManifestRev("series", manifest.rev)
        VodSyncOutcome(fromServer = true, downloadedCategories = downloaded)
    }
}

/**
 * Comptoir de versions côté télé : interroge `/v1/versions/{movies|series}` pour les identifiants affichés et garde
 * un petit cache LRU borné ([maxGroups]) en mémoire — évite le calcul lourd de 47 000 films au démarrage.
 * Si le serveur maison est absent ou répond `null`, bascule automatiquement sur [fallbackLocal].
 */
class HomeVersionStore(
    private val home: HomeVodClient,
    val fallbackLocal: VersionIndexes,
    private val maxGroups: Int = 2_000,
    private val fallbackDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private class KindCache(private val max: Int) {
        var serverRev: String? = null
        var generation: Long = 0L
        val groups = object : LinkedHashMap<String, List<Version>>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Version>>?): Boolean = size > max
        }
        val knownIds = object : LinkedHashMap<String, String?>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>?): Boolean = size > max * 4
        }
        var snapshot: VersionIndex = VersionIndex.EMPTY
        fun clear() {
            generation++
            serverRev = null
            groups.clear()
            knownIds.clear()
            snapshot = VersionIndex.EMPTY
        }
        fun rebuildSnapshot() {
            snapshot = if (groups.isEmpty()) VersionIndex.EMPTY else VersionIndex(LinkedHashMap(groups))
        }
    }

    private val lock = Any()
    private val caches = mapOf(MediaKind.MOVIE to KindCache(maxGroups), MediaKind.SERIES to KindCache(maxGroups))

    var languages: LanguagePrefs
        get() = fallbackLocal.languages
        set(value) { fallbackLocal.languages = value }

    /** Index déjà en mémoire (cache LRU du serveur, sinon secours local s'il a déjà été construit). */
    fun current(kind: MediaKind): VersionIndex {
        val local = synchronized(lock) { caches.getValue(kind).snapshot }
        return if (local.groups.isNotEmpty()) local else fallbackLocal.current(kind)
    }

    /**
     * Résout les versions pour [ids] : lit le cache LRU, interroge le serveur maison par lots de 2 000 pour les
     * identifiants manquants ou évincés, et ne construit l'index local ([fallbackLocal], hors du fil principal) que si
     * le serveur maison ne répond pas.
     */
    suspend fun resolve(kind: MediaKind, ids: Collection<String>): VersionIndex {
        val clean = ids.filter { it.isNotBlank() }.distinct()
        if (clean.isEmpty()) return current(kind)

        val (gen, missing) = synchronized(lock) {
            val c = caches.getValue(kind)
            c.generation to clean.filter { id ->
                id !in c.knownIds || c.knownIds[id]?.let { key -> key !in c.groups } == true
            }
        }
        if (missing.isEmpty()) return current(kind)

        for (batch in missing.chunked(BATCH_SIZE)) {
            val reply = try {
                home.versions(kind, batch)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (reply == null) {
                // Serveur maison absent : secours local transparent, toujours hors du fil principal.
                return withContext(fallbackDispatcher) { fallbackLocal.index(kind) }
            }
            synchronized(lock) {
                val c = caches.getValue(kind)
                // Si split()/invalidate() est passé pendant la requête, on n'écrit pas cette réponse périmée.
                if (c.generation != gen) return@synchronized
                if (c.serverRev != null && reply.rev.isNotEmpty() && c.serverRev != reply.rev) {
                    c.serverRev = null
                    c.groups.clear()
                    c.knownIds.clear()
                }
                if (reply.rev.isNotEmpty()) c.serverRev = reply.rev
                val keyById = HashMap<String, String>()
                reply.groups.forEach { (key, snapVersions) ->
                    val list = snapVersions.map { it.toVersion() }
                    c.groups[key] = list
                    list.forEach { v -> keyById[v.id] = key }
                }
                batch.forEach { id -> c.knownIds[id] = keyById[id] }
                keyById.forEach { (id, key) -> c.knownIds[id] = key }
                c.rebuildSnapshot()
            }
        }
        return current(kind)
    }

    /** Versions d'un seul titre (fiche ouverte depuis l'accueil ou Ma liste). */
    suspend fun versionsOf(kind: MediaKind, id: String): List<Version> =
        resolve(kind, listOf(id)).versionsOf(id)

    /** « Ce n'est pas le même titre » : enregistre dans Supabase, vide le cache local et prévient le serveur maison. */
    suspend fun split(kind: MediaKind, id: String) {
        fallbackLocal.split(kind, id)
        synchronized(lock) { caches.getValue(kind).clear() }
        home.invalidateVersions()
    }

    fun invalidate() {
        synchronized(lock) { caches.values.forEach { it.clear() } }
        fallbackLocal.invalidate()
    }

    private companion object {
        const val BATCH_SIZE = 2_000
    }
}
