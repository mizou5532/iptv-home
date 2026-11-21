package home.iptv.server

import home.iptv.shared.catalog.CatalogRepository
import home.iptv.shared.groups.GroupSnapshot
import home.iptv.shared.groups.GroupsClient
import home.iptv.shared.groups.PrecomputedKey
import home.iptv.shared.groups.categoryCountries
import home.iptv.shared.groups.groupSourceIndex
import home.iptv.shared.home.LiveSnapshot
import home.iptv.shared.home.SearchReply
import home.iptv.shared.home.SearchRequest
import home.iptv.shared.home.VodManifest
import home.iptv.shared.home.toSnapSearchResult
import home.iptv.shared.movies.MovieRepository
import home.iptv.shared.search.PersonCredit
import home.iptv.shared.search.SEARCH_LIMIT
import home.iptv.shared.search.SearchIndex
import home.iptv.shared.search.SearchItem
import home.iptv.shared.search.SearchResult
import home.iptv.shared.search.groupSearchItems
import home.iptv.shared.search.liveSearchItems
import home.iptv.shared.search.matchPersonCredits
import home.iptv.shared.search.visibleSearchItems
import home.iptv.shared.series.SeriesRepository
import home.iptv.shared.versions.LanguagePrefs
import home.iptv.shared.versions.groupVersions
import home.iptv.shared.versions.versionCore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Suit en arrière-plan les regroupements manuels de chaînes (créés sur l'iPhone) pour que la recherche du Direct et
 * le Sport côté serveur donnent exactement les mêmes groupes que la télé.
 */
class ServerGroupsCache(
    private val client: GroupsClient,
    private val householdId: String,
    private val pollMs: Long = 30_000L,
) {
    private val _snapshot = MutableStateFlow(GroupSnapshot(0, 1, emptyList()))
    val snapshot: StateFlow<GroupSnapshot> = _snapshot.asStateFlow()

    suspend fun refreshIfChanged() {
        try {
            val rev = client.revision(householdId)
            if (rev != _snapshot.value.rev) {
                _snapshot.value = client.snapshot(householdId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Dernier snapshot gardé en mémoire ; nouvel essai au prochain sondage.
        }
    }

    suspend fun loop() {
        refreshIfChanged()
        while (true) {
            delay(pollMs)
            refreshIfChanged()
        }
    }
}

/** Levée par [SearchService] lorsque le catalogue de la section demandée n'est pas encore prêt (→ HTTP 503). */
class SearchNotReadyException : Exception("Catalogue en cours de préparation")

/**
 * Moteur de recherche sur le serveur maison (étape 3) : exécute les mêmes règles que la télé (accents, fautes,
 * surnoms, « masqué gagne », vitrine d'abord puis hors vitrine sans doublon, versions regroupées avec `version_splits`
 * et `LanguagePrefs`, groupes manuels du Direct, recherche par acteur/réalisateur) en gardant un petit cache LRU
 * d'index par profil pour répondre en quelques millisecondes sans charger la mémoire de la télé.
 */
class SearchService(
    private val liveRepo: CatalogRepository,
    private val moviesRepo: MovieRepository,
    private val seriesRepo: SeriesRepository,
    private val liveSnapshot: () -> LiveSnapshot?,
    private val movieManifest: () -> VodManifest?,
    private val seriesManifest: () -> VodManifest?,
    private val hiddenMovies: () -> Set<String>,
    private val hiddenSeries: () -> Set<String>,
    private val hiddenLoaded: () -> Boolean,
    private val splits: () -> Set<String>,
    private val splitsVersion: () -> Long,
    private val manualGroups: () -> GroupSnapshot = { GroupSnapshot(0, 1, emptyList()) },
    private val maxCachedIndexes: Int = 6,
) {
    private class BuiltVod(
        val showcaseIndex: SearchIndex,
        val showcaseItems: List<SearchItem>,
        val showcaseResults: Map<String, SearchResult>,
        val otherIndex: SearchIndex,
        val otherItems: List<SearchItem>,
        val otherResults: Map<String, SearchResult>,
    )

    private class BuiltLive(
        val index: SearchIndex,
        val results: Map<String, SearchResult>,
    )

    private data class VodCacheKey(
        val section: String,
        val manifestRev: String,
        val splitsRev: Long,
        val hidden: List<String>,
        val languages: LanguagePrefs,
    )

    private data class LiveCacheKey(
        val liveRev: String,
        val groupsRev: Long,
        val hiddenCats: List<String>,
        val hiddenChannels: List<String>,
        val groupCopies: Boolean,
    )

    private val vodLock = Any()
    private val vodCache = object : LinkedHashMap<VodCacheKey, BuiltVod>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<VodCacheKey, BuiltVod>?) = size > maxCachedIndexes
    }

    private val liveLock = Any()
    private val liveCache = object : LinkedHashMap<LiveCacheKey, BuiltLive>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LiveCacheKey, BuiltLive>?) = size > maxCachedIndexes
    }

    fun search(req: SearchRequest): SearchReply {
        val limit = req.limit.coerceIn(1, SEARCH_LIMIT)
        return when (req.section) {
            "live" -> searchLive(req, limit)
            "movies" -> searchVod("movies", req, limit)
            "series" -> searchVod("series", req, limit)
            else -> throw IllegalArgumentException("Section inconnue")
        }
    }

    private fun searchLive(req: SearchRequest, limit: Int): SearchReply {
        val snap = liveSnapshot() ?: throw SearchNotReadyException()
        val query = req.query.trim()
        if (query.isEmpty()) return SearchReply(snap.rev, snap.account, emptyList())
        val groups = manualGroups()
        // Union avec les catégories masquées globalement (ceinture et bretelles).
        val allHiddenCats = (req.hiddenCategories + snap.hidden).toSet()
        val allHiddenChans = req.hiddenChannels.toSet()
        val key = LiveCacheKey(snap.rev, groups.rev, allHiddenCats.sorted(), allHiddenChans.sorted(), req.groupCopies)
        val built = synchronized(liveLock) { liveCache[key] } ?: buildLive(snap, groups, allHiddenCats, allHiddenChans, req.groupCopies).also {
            synchronized(liveLock) { liveCache[key] = it }
        }
        val found = built.index.search(query, limit).mapNotNull { item ->
            built.results[item.id]?.takeIf { it.categoryId.isNotBlank() && it.categoryId !in allHiddenCats }
        }
        return SearchReply(snap.rev, snap.account, found.map { it.toSnapSearchResult() })
    }

    private fun buildLive(
        snap: LiveSnapshot,
        groups: GroupSnapshot,
        hiddenCats: Set<String>,
        hiddenChans: Set<String>,
        groupCopies: Boolean,
    ): BuiltLive {
        val visible = liveRepo.allGroupSources().filter { it.id !in hiddenChans && it.categoryId !in hiddenCats }
        val cats = liveRepo.categories()
        val precomputed: (String) -> PrecomputedKey? = liveRepo.liveKeys()::get
        val groupIdx = groupSourceIndex(visible, groups, groupCopies, categoryCountries(cats), precomputed = precomputed)
        val byId = groupIdx.values.associateBy { it.id }
        val visibleById = visible.associateBy { it.id }
        val results = byId.values.mapNotNull { group ->
            val anchor = group.sources.firstOrNull { it.id in visibleById } ?: return@mapNotNull null
            group.id to SearchResult(group.id, group.title, null, anchor.categoryId, anchor.id)
        }.toMap()
        val items = liveSearchItems(visible, byId)
        return BuiltLive(SearchIndex(items), results)
    }

    private fun searchVod(section: String, req: SearchRequest, limit: Int): SearchReply {
        if (!hiddenLoaded()) throw SearchNotReadyException()
        val isMovie = section == "movies"
        val manifest = (if (isMovie) movieManifest() else seriesManifest()) ?: throw SearchNotReadyException()
        val splitsRev = splitsVersion()
        val rev = "${manifest.rev.take(12)}-$splitsRev"
        val personCredits = req.personCredits
        val query = req.query.trim()
        if (personCredits == null && query.isEmpty()) return SearchReply(rev, manifest.account, emptyList())

        val globalHidden = if (isMovie) hiddenMovies() else hiddenSeries()
        // Union stricte des masquages globaux et du profil (« masqué gagne »).
        val allHidden = (req.hiddenCategories + globalHidden + manifest.hidden).toSet()
        val key = VodCacheKey(section, manifest.rev, splitsRev, allHidden.sorted(), req.languages)
        val built = synchronized(vodLock) { vodCache[key] } ?: buildVod(isMovie, allHidden, req.languages).also {
            synchronized(vodLock) { vodCache[key] = it }
        }

        if (personCredits != null) {
            val name = personCredits.person?.name ?: return SearchReply(rev, manifest.account, emptyList())
            val kind = if (isMovie) "movie" else "series"
            val roles = personCredits.credits.filter { it.kind == kind }
            if (roles.isEmpty()) return SearchReply(rev, manifest.account, emptyList())
            val combined = ArrayList<SearchResult>()
            val seenIds = HashSet<String>()
            fun collect(pool: List<SearchItem>, resultsMap: Map<String, SearchResult>) {
                matchPersonCredits(pool, roles, kind).forEach { item ->
                    if (combined.size >= limit) return@forEach
                    val r = resultsMap[item.id] ?: return@forEach
                    if (r.categoryId.isBlank() || r.categoryId in allHidden) return@forEach
                    if (r.id in seenIds || r.versions.any { it.id in seenIds }) return@forEach
                    val directed = roles.any { it.role == "realisateur" && sameTitle(it, item) } &&
                        roles.none { it.role == "acteur" && sameTitle(it, item) }
                    seenIds += r.id
                    r.versions.forEach { seenIds += it.id }
                    combined += r.copy(note = if (directed) "Réalisé par $name" else "Avec $name")
                }
            }
            collect(built.showcaseItems, built.showcaseResults)
            collect(built.otherItems, built.otherResults)
            return SearchReply(rev, manifest.account, combined.map { it.toSnapSearchResult() })
        }

        val found = built.showcaseIndex.search(query, limit).mapNotNull { item ->
            built.showcaseResults[item.id]?.takeIf { it.id == item.id && it.categoryId.isNotBlank() && it.categoryId !in allHidden }
        }
        var shown = found
        // Hors vitrine (0.41) : ajoutés sous la vitrine dès 2 caractères alphanumériques, sans doublon.
        if (query.count { it.isLetterOrDigit() } >= 2 && found.size < limit) {
            val already = found.flatMapTo(HashSet()) { r -> listOf(r.id) + r.versions.map { it.id } }
            val extra = built.otherIndex.search(query, limit).mapNotNull { item ->
                built.otherResults[item.id]?.takeIf { r ->
                    r.id !in already && r.versions.none { it.id in already } && r.categoryId.isNotBlank() && r.categoryId !in allHidden
                }
            }.take(limit - found.size)
            shown = found + extra
        }
        return SearchReply(rev, manifest.account, shown.map { it.toSnapSearchResult() })
    }

    private fun buildVod(isMovie: Boolean, hidden: Set<String>, languages: LanguagePrefs): BuiltVod {
        val names = if (isMovie) moviesRepo.cachedCategoryNames() else seriesRepo.cachedCategoryNames()
        val versionIds = if (isMovie) moviesRepo.versionIds() else seriesRepo.versionIds()
        val rawShowcase = if (isMovie) moviesRepo.searchItems(versionIds, inside = true) else seriesRepo.searchItems(versionIds, inside = true)
        val rawOther = if (isMovie) moviesRepo.searchItems(versionIds, inside = false) else seriesRepo.searchItems(versionIds, inside = false)
        val prefix = if (isMovie) "movie:" else "series:"
        val splitIds = splits().filter { it.startsWith(prefix) }.mapTo(HashSet()) { it.removePrefix(prefix) }

        val (showcaseItems, showcaseResults) = groupSearchItems(
            visible = visibleSearchItems(rawShowcase, hidden),
            categoryNames = names,
            prefs = languages,
            grouper = { inputs -> groupVersions(inputs, splitIds) },
        )
        val (otherItems, otherResults) = groupSearchItems(
            visible = visibleSearchItems(rawOther, hidden),
            categoryNames = names,
            prefs = languages,
            grouper = { inputs -> groupVersions(inputs, splitIds) },
        )
        return BuiltVod(
            showcaseIndex = SearchIndex(showcaseItems),
            showcaseItems = showcaseItems,
            showcaseResults = showcaseResults,
            otherIndex = SearchIndex(otherItems),
            otherItems = otherItems,
            otherResults = otherResults,
        )
    }
}

private fun sameTitle(c: PersonCredit, item: SearchItem): Boolean {
    val core = versionCore(item.title)
    return listOfNotNull(c.title, c.originalTitle).any { versionCore(it) == core }
}
