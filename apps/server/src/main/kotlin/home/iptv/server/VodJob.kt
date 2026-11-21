package home.iptv.server

import home.iptv.shared.account.ExtraAccountsCheck
import home.iptv.shared.catalog.CatalogTitlesPublisher
import home.iptv.shared.catalog.CatalogTitlesState
import home.iptv.shared.catalog.Showcase
import home.iptv.shared.home.MovieCategoryReply
import home.iptv.shared.home.SeriesCategoryReply
import home.iptv.shared.home.SnapVodCategory
import home.iptv.shared.home.VersionsReply
import home.iptv.shared.home.VodManifest
import home.iptv.shared.home.accountFingerprint
import home.iptv.shared.home.movieCategoryRev
import home.iptv.shared.home.seriesCategoryRev
import home.iptv.shared.home.toSnapMovie
import home.iptv.shared.home.toSnapSeries
import home.iptv.shared.home.toSnapVersion
import home.iptv.shared.home.vodManifestRev
import home.iptv.shared.meta.MediaKind
import home.iptv.shared.movies.MovieRepository
import home.iptv.shared.search.FillProgress
import home.iptv.shared.search.SearchSection
import home.iptv.shared.series.SeriesRepository
import home.iptv.shared.sync.PublishedCategory
import home.iptv.shared.sync.SyncClient
import home.iptv.shared.versions.VersionIndexes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@Serializable
private data class PersistedVodState(
    val movies: VodManifest? = null,
    val series: VodManifest? = null,
)

/** Date de dernière publication des titres iPhone gardée sur disque (une entrée par foyer et type). */
internal class FileCatalogTitlesState(private val file: File) : CatalogTitlesState {
    private val mapSer = MapSerializer(String.serializer(), Long.serializer())
    private val lock = Any()
    private val values: MutableMap<String, Long> = load()

    private fun load(): MutableMap<String, Long> = try {
        if (file.isFile) snapshotJson.decodeFromString(mapSer, file.readText()).toMutableMap() else HashMap()
    } catch (_: Exception) {
        HashMap()
    }

    private fun save() {
        file.absoluteFile.parentFile?.mkdirs()
        val tmp = File(file.absolutePath + ".tmp")
        tmp.writeText(snapshotJson.encodeToString(mapSer, values))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    override fun publishedAt(household: String, kind: String): Long =
        synchronized(lock) { values["$household|$kind"] ?: 0L }

    override fun savePublished(household: String, kind: String, at: Long) = synchronized(lock) {
        values["$household|$kind"] = at
        runCatching { save() }
        Unit
    }
}

/**
 * Met à jour Films et Séries en tâche de fond (tous les 3 jours), respecte strictement les catégories masquées pour
 * tout le monde, maintient les manifestes différentiels par catégorie, garde l'index des versions en mémoire (avec
 * `version_splits`), publie catégories et titres pour l'iPhone, et vérifie les comptes IPTV supplémentaires.
 */
class VodJob(
    private val movies: MovieRepository,
    private val series: SeriesRepository,
    private val sync: SyncClient,
    private val publishMovieCategories: suspend (String, List<PublishedCategory>) -> Unit,
    private val householdId: String,
    private val account: () -> String?,
    private val serverHost: () -> String?,
    private val clockMs: () -> Long,
    private val manifestFile: File,
    private val titlesStateFile: File,
    private val extraAccountsCheck: ExtraAccountsCheck? = null,
    private val beforeRun: suspend () -> Unit = {},
    private val periodMs: Long = 72L * 3_600_000L,
    private val pollMs: Long = 60_000L,
    private val extraAccountsPeriodMs: Long = 6L * 3_600_000L,
) {
    private val persisted = readManifests()
    private val _movieManifest = MutableStateFlow(persisted.movies)
    private val _seriesManifest = MutableStateFlow(persisted.series)
    private val _hiddenMovies = MutableStateFlow(persisted.movies?.hidden.orEmpty().toSet())
    private val _hiddenSeries = MutableStateFlow(persisted.series?.hidden.orEmpty().toSet())

    val movieManifest: StateFlow<VodManifest?> = _movieManifest
    val seriesManifest: StateFlow<VodManifest?> = _seriesManifest
    val hiddenMovieCategories: StateFlow<Set<String>> = _hiddenMovies
    val hiddenSeriesCategories: StateFlow<Set<String>> = _hiddenSeries

    private val running = Mutex()
    private val splitsRef = AtomicReference<Set<String>>(emptySet())
    private val versionsRevNum = AtomicLong(1L)

    private val versions = VersionIndexes(
        items = { kind ->
            when (kind) {
                MediaKind.MOVIE -> movies.searchItems(movies.versionIds() - _hiddenMovies.value, inside = true)
                MediaKind.SERIES -> series.searchItems(series.versionIds() - _hiddenSeries.value, inside = true)
            }
        },
        categoryNames = { kind ->
            if (kind == MediaKind.MOVIE) movies.cachedCategoryNames() else series.cachedCategoryNames()
        },
        splits = { splitsRef.get() },
    )

    private val titlesState = FileCatalogTitlesState(titlesStateFile)
    private val dummyProgress = MutableStateFlow<Map<SearchSection, FillProgress>>(emptyMap())
    private val titlesPublisher = CatalogTitlesPublisher(
        progress = dummyProgress,
        series = { series.cachedSeries() },
        movies = { movies.cachedTitleRows() },
        server = serverHost,
        household = { householdId },
        publish = { h, kind, gen, rows, done -> sync.publishCatalogTitles(h, kind, gen, rows, done) },
        state = titlesState,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        now = clockMs,
    )

    private fun readManifests(): PersistedVodState = try {
        if (manifestFile.isFile) snapshotJson.decodeFromString(PersistedVodState.serializer(), manifestFile.readText())
        else PersistedVodState()
    } catch (_: Exception) {
        PersistedVodState()
    }

    private fun writeManifests() {
        manifestFile.absoluteFile.parentFile?.mkdirs()
        val tmp = File(manifestFile.absolutePath + ".tmp")
        val state = PersistedVodState(_movieManifest.value, _seriesManifest.value)
        tmp.writeText(snapshotJson.encodeToString(PersistedVodState.serializer(), state))
        Files.move(tmp.toPath(), manifestFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** Contenu d'une catégorie de films prête à servir, ou `null` si inconnue, masquée ou pas encore téléchargée. */
    fun movieCategoryReply(categoryId: String): MovieCategoryReply? {
        val manifest = _movieManifest.value ?: return null
        if (categoryId in _hiddenMovies.value) return null
        val cat = manifest.categories.firstOrNull { it.id == categoryId } ?: return null
        if (cat.rev.isEmpty()) return null
        val items = movies.cachedMoviesOf(categoryId).mapIndexed { i, m -> m.toSnapMovie(i) }
        return MovieCategoryReply(categoryId, cat.rev, manifest.account, items)
    }

    /** Contenu d'une catégorie de séries prête à servir, ou `null` si inconnue, masquée ou pas encore téléchargée. */
    fun seriesCategoryReply(categoryId: String): SeriesCategoryReply? {
        val manifest = _seriesManifest.value ?: return null
        if (categoryId in _hiddenSeries.value) return null
        val cat = manifest.categories.firstOrNull { it.id == categoryId } ?: return null
        if (cat.rev.isEmpty()) return null
        val items = series.cachedSeriesOf(categoryId).mapIndexed { i, s -> s.toSnapSeries(i) }
        return SeriesCategoryReply(categoryId, cat.rev, manifest.account, items)
    }

    /** Groupes de versions demandés par une télé (borné à [MAX_VERSION_IDS] identifiants). */
    fun lookupVersions(kind: MediaKind, ids: List<String>): VersionsReply {
        val idx = versions.index(kind)
        val out = LinkedHashMap<String, List<home.iptv.shared.home.SnapVersion>>()
        for (id in ids.take(MAX_VERSION_IDS)) {
            val key = idx.keyOf(id) ?: continue
            if (key in out) continue
            val group = idx.groups[key] ?: continue
            out[key] = group.map { it.toSnapVersion() }
        }
        val mRev = (if (kind == MediaKind.MOVIE) _movieManifest.value?.rev else _seriesManifest.value?.rev).orEmpty().take(12)
        return VersionsReply("${versionsRevNum.get()}-$mRev", out)
    }

    /** Recharge `version_splits` depuis Supabase et reconstruit aussitôt les index de versions en mémoire. */
    suspend fun reloadVersionSplits() {
        val splits = try {
            sync.householdSettings(householdId).versionSplits.toSet()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (splits != null) splitsRef.set(splits)
        rebuildVersions()
    }

    private fun rebuildVersions() {
        versions.invalidate()
        versionsRevNum.incrementAndGet()
        versions.index(MediaKind.MOVIE)
        versions.index(MediaKind.SERIES)
    }

    /** Une passe complète Films + Séries + Versions + Publication iPhone + Comptes supplémentaires. */
    suspend fun runOnce() = running.withLock {
        beforeRun()
        val acct = account()
        val prevAcct = _movieManifest.value?.account?.takeIf { it.isNotEmpty() }
            ?: _seriesManifest.value?.account?.takeIf { it.isNotEmpty() }
        if (prevAcct != null && acct != null && prevAcct != acct) {
            // Changement de compte IPTV : tout repart à zéro (jamais de mélange d'identifiants entre deux comptes).
            movies.wipeMovies()
            series.wipeSeries()
            _movieManifest.value = null
            _seriesManifest.value = null
            manifestFile.delete()
            versions.invalidate()
            versionsRevNum.incrementAndGet()
        }

        val globalHidden = sync.globalHiddenCategories(householdId)
        val hiddenMovies = globalHidden.filter { it.itemType == "vod_category" }.map { it.itemId }.toSet()
        val hiddenSeries = globalHidden.filter { it.itemType == "series_category" }.map { it.itemId }.toSet()
        _hiddenMovies.value = hiddenMovies
        _hiddenSeries.value = hiddenSeries
        movies.removeCategoryItems(hiddenMovies)
        series.removeCategoryItems(hiddenSeries)

        try {
            splitsRef.set(sync.householdSettings(householdId).versionSplits.toSet())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // On garde les séparations connues en mémoire.
        }

        val acctStr = acct.orEmpty()
        val moviesComplete = refreshMovies(acctStr, hiddenMovies)
        val seriesComplete = refreshSeries(acctStr, hiddenSeries)

        rebuildVersions()

        val fillProgress = HashMap<SearchSection, FillProgress>()
        if (moviesComplete > 0) fillProgress[SearchSection.MOVIES] = FillProgress(moviesComplete, moviesComplete)
        if (seriesComplete > 0) fillProgress[SearchSection.SERIES] = FillProgress(seriesComplete, seriesComplete)
        if (fillProgress.isNotEmpty()) titlesPublisher.runDue(fillProgress)

        extraAccountsCheck?.runPending()
    }

    /** Retourne le nombre de catégories visibles complètes si 100 % ont réussi, sinon 0. */
    private suspend fun refreshMovies(acct: String, hidden: Set<String>): Int {
        val allCats = movies.categories(emptySet())
        if (allCats.isEmpty()) return 0
        try {
            publishMovieCategories(householdId, allCats.mapIndexed { i, c -> PublishedCategory(c.id, c.name, i) })
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Non bloquant : réessayé à la prochaine passe.
        }
        val visible = allCats.filter { it.id !in hidden }
        if (visible.isEmpty()) {
            val manifest = VodManifest(vodManifestRev(emptyList(), hidden.sorted()), acct, clockMs(), emptyList(), hidden.sorted())
            _movieManifest.value = manifest
            writeManifests()
            return 0
        }
        val showcaseIds = Showcase.ids(Showcase.Kind.MOVIES, visible.associate { it.id to it.name })
        val (first, rest) = visible.partition { it.id in showcaseIds }
        var failures = 0
        for (cat in first) {
            try {
                movies.moviesOf(cat.id, hidden)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failures++
            }
        }
        if (rest.isNotEmpty()) {
            _movieManifest.value = buildMovieManifest(visible, acct, hidden)
            writeManifests()
        }
        for (cat in rest) {
            try {
                movies.moviesOf(cat.id, hidden)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failures++
            }
        }
        _movieManifest.value = buildMovieManifest(visible, acct, hidden)
        writeManifests()
        return if (failures == 0) visible.size else 0
    }

    private fun buildMovieManifest(
        visible: List<home.iptv.shared.xtream.MovieCategory>,
        acct: String,
        hidden: Set<String>,
    ): VodManifest {
        val snapCats = visible.mapIndexed { i, c ->
            val items = movies.cachedMoviesOf(c.id)
            val rev = if (items.isNotEmpty() || movies.isCategoryFresh(c.id)) {
                movieCategoryRev(items.mapIndexed { idx, m -> m.toSnapMovie(idx) })
            } else ""
            SnapVodCategory(c.id, c.name, i, rev)
        }
        val sortedHidden = hidden.sorted()
        return VodManifest(vodManifestRev(snapCats, sortedHidden), acct, clockMs(), snapCats, sortedHidden)
    }

    /** Retourne le nombre de catégories visibles complètes si 100 % ont réussi, sinon 0. */
    private suspend fun refreshSeries(acct: String, hidden: Set<String>): Int {
        val allCats = series.categories(emptySet())
        if (allCats.isEmpty()) return 0
        try {
            sync.publishSeriesCategories(householdId, allCats.mapIndexed { i, c -> PublishedCategory(c.id, c.name, i) })
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Non bloquant : réessayé à la prochaine passe.
        }
        val visible = allCats.filter { it.id !in hidden }
        if (visible.isEmpty()) {
            val manifest = VodManifest(vodManifestRev(emptyList(), hidden.sorted()), acct, clockMs(), emptyList(), hidden.sorted())
            _seriesManifest.value = manifest
            writeManifests()
            return 0
        }
        val showcaseIds = Showcase.ids(Showcase.Kind.SERIES, visible.associate { it.id to it.name })
        val (first, rest) = visible.partition { it.id in showcaseIds }
        var failures = 0
        for (cat in first) {
            try {
                series.seriesOf(cat.id, hidden)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failures++
            }
        }
        if (rest.isNotEmpty()) {
            _seriesManifest.value = buildSeriesManifest(visible, acct, hidden)
            writeManifests()
        }
        for (cat in rest) {
            try {
                series.seriesOf(cat.id, hidden)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failures++
            }
        }
        _seriesManifest.value = buildSeriesManifest(visible, acct, hidden)
        writeManifests()
        return if (failures == 0) visible.size else 0
    }

    private fun buildSeriesManifest(
        visible: List<home.iptv.shared.xtream.SeriesCategory>,
        acct: String,
        hidden: Set<String>,
    ): VodManifest {
        val snapCats = visible.mapIndexed { i, c ->
            val items = series.cachedSeriesOf(c.id)
            val rev = if (items.isNotEmpty() || series.isCategoryFresh(c.id)) {
                seriesCategoryRev(items.mapIndexed { idx, s -> s.toSnapSeries(idx) })
            } else ""
            SnapVodCategory(c.id, c.name, i, rev)
        }
        val sortedHidden = hidden.sorted()
        return VodManifest(vodManifestRev(snapCats, sortedHidden), acct, clockMs(), snapCats, sortedHidden)
    }

    private suspend fun runQuietly(): Boolean = try {
        runOnce()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        System.err.println("Mise à jour Films/Séries impossible (${e::class.simpleName}), le cache précédent reste servi.")
        false
    }

    private suspend fun prefsRevisionOrNull(): Long? = try {
        sync.prefsRevision(householdId)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private suspend fun currentAccountOrNull(): String? = try {
        sync.credentials(householdId)?.let { accountFingerprint(it.server, it.username) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /**
     * Boucle principale : met Films et Séries à jour au démarrage si le cache a plus de [periodMs] (72 h) ou manque,
     * réagit aux changements de catégories masquées / séparations / compte IPTV, et vérifie les comptes supplémentaires.
     */
    suspend fun loop() {
        var lastRev = prefsRevisionOrNull()
        var pendingRev: Long? = null
        var retryNotBefore = 0L

        val mBuilt = _movieManifest.value?.builtAt
        val sBuilt = _seriesManifest.value?.builtAt
        val oldest = if (mBuilt != null && sBuilt != null) minOf(mBuilt, sBuilt) else null
        var ok = true
        if (oldest == null || clockMs() - oldest >= periodMs) {
            ok = runQuietly()
        } else {
            // Cache disque récent : on recharge quand même version_splits, les index en mémoire et les comptes suppl.
            reloadVersionSplits()
            if (extraAccountsCheck != null) {
                try { beforeRun() } catch (e: CancellationException) { throw e } catch (_: Exception) {}
                extraAccountsCheck.runPending()
            }
        }
        if (!ok) retryNotBefore = clockMs() + RETRY_MS
        val curBuilt = listOfNotNull(_movieManifest.value?.builtAt, _seriesManifest.value?.builtAt).minOrNull() ?: clockMs()
        var nextAt = if (ok) curBuilt + periodMs else retryNotBefore
        var nextExtraAt = clockMs() + extraAccountsPeriodMs

        while (true) {
            delay(pollMs)
            val now = clockMs()
            var due = now >= nextAt
            val allowed = now >= retryNotBefore

            val rev = prefsRevisionOrNull()
            if (rev != null && rev != lastRev) pendingRev = rev
            if (pendingRev != null) {
                val hiddenAll = try {
                    sync.globalHiddenCategories(householdId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                val newSplits = try {
                    sync.householdSettings(householdId).versionSplits.toSet()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (newSplits != null && newSplits != splitsRef.get()) {
                    splitsRef.set(newSplits)
                    rebuildVersions()
                }
                extraAccountsCheck?.runPending()
                if (hiddenAll != null) {
                    val hm = hiddenAll.filter { it.itemType == "vod_category" }.map { it.itemId }.toSet()
                    val hs = hiddenAll.filter { it.itemType == "series_category" }.map { it.itemId }.toSet()
                    if (hm != _hiddenMovies.value || hs != _hiddenSeries.value) {
                        if (allowed) due = true
                    } else {
                        lastRev = pendingRev
                        pendingRev = null
                    }
                }
            }

            if (!due && allowed) {
                val keptAcct = _movieManifest.value?.account?.takeIf { it.isNotEmpty() }
                    ?: _seriesManifest.value?.account?.takeIf { it.isNotEmpty() }
                val cur = currentAccountOrNull()
                if (keptAcct != null && cur != null && cur != keptAcct) due = true
            }

            if (due) {
                ok = runQuietly()
                retryNotBefore = if (ok) 0L else clockMs() + RETRY_MS
                nextAt = if (ok) clockMs() + periodMs else retryNotBefore
                if (ok && pendingRev != null) {
                    lastRev = pendingRev
                    pendingRev = null
                }
            } else if (now >= nextExtraAt) {
                extraAccountsCheck?.runPending()
                nextExtraAt = clockMs() + extraAccountsPeriodMs
            }
        }
    }

    private companion object {
        const val RETRY_MS = 10L * 60_000L
        const val MAX_VERSION_IDS = 2_000
    }
}
