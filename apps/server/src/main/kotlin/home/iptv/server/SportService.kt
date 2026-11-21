package home.iptv.server

import home.iptv.shared.catalog.CatalogRepository
import home.iptv.shared.groups.GroupSnapshot
import home.iptv.shared.home.LiveSnapshot
import home.iptv.shared.home.SportBoardReply
import home.iptv.shared.home.SportBoardRequest
import home.iptv.shared.home.toSnapMatch
import home.iptv.shared.home.toSnapSportCandidates
import home.iptv.shared.sport.DEFAULT_TEAMS
import home.iptv.shared.sport.League
import home.iptv.shared.sport.Match
import home.iptv.shared.sport.MatchState
import home.iptv.shared.sport.SportChannel
import home.iptv.shared.sport.SportChannelIndex
import home.iptv.shared.sport.SportExtraIndex
import home.iptv.shared.sport.buildBoard
import home.iptv.shared.sport.confirmedBy
import home.iptv.shared.sport.fullWeekFor
import home.iptv.shared.sport.guideProbes
import home.iptv.shared.sport.involves
import home.iptv.shared.sport.isDefaultSportCategory
import home.iptv.shared.sport.isOver
import home.iptv.shared.sport.localDay
import home.iptv.shared.sport.matchVotes
import home.iptv.shared.sport.parseEspnScoreboard
import home.iptv.shared.sport.parseEspnStandings
import home.iptv.shared.sport.parseNhlSchedule
import home.iptv.shared.sport.parseNhlStandings
import home.iptv.shared.sport.preferenceKeys
import home.iptv.shared.sport.regionOrder
import home.iptv.shared.sport.scheduleSources
import home.iptv.shared.sport.sportCandidates
import home.iptv.shared.sport.sportLeagues
import home.iptv.shared.sport.standingsUrl
import home.iptv.shared.sport.visibleSportChannels
import home.iptv.shared.sync.HiddenItem
import home.iptv.shared.sync.ProfilePrefs
import home.iptv.shared.sync.SportMemory
import home.iptv.shared.sync.SportVote
import home.iptv.shared.sync.SyncClient
import home.iptv.shared.xtream.EpgEntry
import home.iptv.shared.xtream.LiveCategory
import home.iptv.shared.xtream.LiveStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Levée par [SportService] lorsque le Direct n'est pas encore prêt (→ HTTP 503). */
class SportNotReadyException : Exception("Sport en cours de préparation")

/**
 * Service Sport partagé par toute la maison (étape 4) :
 * - Télécharge les horaires LNH/ESPN et les classements une seule fois pour toute la maison.
 * - Télécharge les chaînes Sport dédiées (`sport_categories` ou catégories Sport par défaut) toutes les 6 h.
 * - Lit les votes de la maison et la mémoire Sport partagée par profil (`sport_memory_get`).
 * - Sonde le guide partagé (`EpgCache`) pour confirmer les chaînes des matchs imminents/en cours.
 * - Calcule la grille et les chaînes candidates pour un profil donné en quelques millisecondes.
 */
class SportService(
    private val liveRepo: CatalogRepository,
    private val sync: SyncClient,
    private val householdId: String,
    private val liveSnapshot: () -> LiveSnapshot?,
    private val manualGroups: () -> GroupSnapshot = { GroupSnapshot(0, 1, emptyList()) },
    private val fetchUrl: suspend (String) -> String,
    private val liveCategories: suspend () -> List<LiveCategory>,
    private val liveStreams: suspend (String) -> List<LiveStream>,
    private val guideFor: suspend (String) -> List<EpgEntry> = { emptyList() },
    private val beforeProviderFetch: suspend () -> Unit = {},
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val defaultOffsetSeconds: () -> Int = { java.util.TimeZone.getDefault().getOffset(clockMs()) / 1000 },
) {
    private class CachedBody(val text: String, val atMs: Long)
    private class CachedExtra(val channels: List<SportChannel>, val index: SportExtraIndex, val atMs: Long, val account: String)
    private class CachedVotes(val list: List<SportVote>, val atMs: Long)
    private class CachedMemory(val memory: SportMemory, val atMs: Long)

    private val urlCache = ConcurrentHashMap<String, CachedBody>()
    private val urlGate = Semaphore(6)
    private val extraLock = Mutex()
    private val extraRef = AtomicReference<CachedExtra?>(null)
    private val votesLock = Mutex()
    private val votesRef = AtomicReference<CachedVotes?>(null)
    private val memoryCache = ConcurrentHashMap<String, CachedMemory>()
    private val confirmedByMatch = ConcurrentHashMap<String, Set<String>>()
    private val confirmedAccount = AtomicReference<String?>(null)
    private val revCounter = AtomicLong(1L)

    /** Invalide le cache de mémoire/votes (après étoile, apprentissage ou « Pas le bon match »). */
    fun invalidate(profileId: String? = null) {
        if (profileId != null) memoryCache.remove(profileId) else memoryCache.clear()
        votesRef.set(null)
        revCounter.incrementAndGet()
    }

    /**
     * Prépare en tâche de fond horaires, classements et chaînes Sport pour que les télés reçoivent une réponse
     * immédiate. Renvoie vrai si au moins un match est en cours ou imminent (< 15 min).
     */
    suspend fun warmOnce(): Boolean {
        val snap = liveSnapshot() ?: return false
        if (confirmedAccount.getAndSet(snap.account) != snap.account) {
            confirmedByMatch.clear()
        }
        val profiles = try {
            sync.profiles(householdId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        val teams = profiles.flatMap { it.settings.sportTeams ?: DEFAULT_TEAMS }.ifEmpty { DEFAULT_TEAMS }
        val leagues = (profiles.flatMap { p -> sportLeagues(p.settings.sportTeams ?: DEFAULT_TEAMS, p.settings.sportView) }
            .ifEmpty { sportLeagues(DEFAULT_TEAMS, null) }).distinct()
        val offset = defaultOffsetSeconds()
        val (matches, _) = loadSchedules(offset, leagues, fullWeekFor(teams), forceToday = false)
        loadStandings(leagues.filter(fullWeekFor(teams)) + League.NHL)
        ensureExtraChannels(snap.account)
        ensureVotes()
        // Sonde le guide pour les 3 premiers matchs en cours ou imminents (< 3 h).
        val now = Instant.fromEpochMilliseconds(clockMs())
        val imminent = matches.filter { m -> !isOver(m, now) && (m.state == MatchState.LIVE || m.start - now <= 3.hours) }
            .sortedBy { it.start }.take(3)
        val imminentIds = imminent.mapTo(HashSet()) { it.id }
        confirmedByMatch.keys.retainAll(imminentIds)
        if (imminent.isNotEmpty()) {
            val allVisible = visibleSportChannels(
                liveRepo,
                ProfilePrefs(0, snap.hidden.map { HiddenItem("live_category", it) }, emptyList(), emptyList(), null),
                manualGroups(),
            )
            val idx = SportChannelIndex(allVisible)
            val extra = extraRef.get()?.takeIf { it.account == snap.account }
            val votes = votesRef.get()?.list.orEmpty()
            for (m in imminent) {
                val c = sportCandidates(
                    match = m, learned = emptyList(), idx = idx,
                    extra = extra?.channels.orEmpty(), extraIdx = extra?.index ?: SportExtraIndex(emptyList()),
                    votes = matchVotes(m, votes), offsetSeconds = offset,
                )
                val hits = HashSet<String>()
                for (ch in guideProbes(c, 8)) {
                    val entries = try {
                        guideFor(ch.anchorSourceId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                    if (confirmedBy(entries, m)) hits += ch.groupId
                }
                if (hits.isNotEmpty()) confirmedByMatch[m.id] = hits else confirmedByMatch.remove(m.id)
            }
        }
        revCounter.incrementAndGet()
        return matches.any { m -> !isOver(m, now) && (m.state == MatchState.LIVE || m.start - now <= 15.minutes) }
    }

    /** Boucle de fond : rafraîchit toutes les 3 min si un match est en cours ou imminent, sinon toutes les 15 min. */
    suspend fun loop() {
        while (true) {
            val hasLive = try {
                warmOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                System.err.println("Mise à jour Sport impossible (${e::class.simpleName}).")
                false
            }
            delay(if (hasLive) LIVE_REFRESH_MS else IDLE_REFRESH_MS)
        }
    }

    /** Réponse complète prête à afficher pour un profil (ne bloque jamais sur Xtream). */
    suspend fun board(req: SportBoardRequest): SportBoardReply = coroutineScope {
        val snap = liveSnapshot() ?: throw SportNotReadyException()
        val nowMs = clockMs()
        val now = Instant.fromEpochMilliseconds(nowMs)
        val teams = req.teams.ifEmpty { DEFAULT_TEAMS }
        val leagues = sportLeagues(teams, req.view)
        val fullWeek = fullWeekFor(teams)

        val schedJob = async { loadSchedules(req.offsetSeconds, leagues, fullWeek, forceToday = req.forceRefresh) }
        val standJob = async { loadStandings(leagues.filter(fullWeek) + League.NHL) }
        val votesJob = async { ensureVotes() }
        val memJob = async { loadMemory(req.profileId, force = req.forceRefresh) }

        val (matches, offline) = schedJob.await()
        val standings = standJob.await()
        val votes = votesJob.await()
        val memory = memJob.await()
        // Ne bloque jamais une requête TV sur les appels Xtream de catégories Sport : lit le cache chaud du bon compte.
        val extra = extraRef.get()?.takeIf { it.account == snap.account }
        val confirmedForAccount = confirmedAccount.get() == snap.account

        val hiddenItems = (req.hiddenCategories + snap.hidden).distinct().map { HiddenItem("live_category", it) } +
            req.hiddenChannels.distinct().map { HiddenItem("channel", it) }
        val prefs = ProfilePrefs(0, hiddenItems, req.favorites, emptyList(), null, groupCopies = req.groupCopies)
        val visible = visibleSportChannels(liveRepo, prefs, manualGroups())
        val idx = SportChannelIndex(visible)
        val regions = regionOrder(req.view?.langOrder)

        val tagged = matches.map { m -> m.copy(followed = teams.any { m.involves(it) }) }
        val builtBoard = buildBoard(tagged, now, req.offsetSeconds, teams)
        val todayDay = localDay(now, req.offsetSeconds)
        val relevant = (builtBoard.mine + builtBoard.tonight + builtBoard.teams.mapNotNull { it.next } +
            tagged.filter { !isOver(it, now) && localDay(it.start, req.offsetSeconds) in todayDay..(todayDay + 1) })
            .distinctBy { it.id }

        val candidates = LinkedHashMap<String, home.iptv.shared.home.SnapSportCandidates>(relevant.size)
        for (m in relevant) {
            val learned = memory.learned[m.league.key].orEmpty()
            val preferred = preferenceKeys(m, teams).mapNotNull { memory.preferred[it] }.distinct()
            val c = sportCandidates(
                match = m,
                learned = learned,
                idx = idx,
                favorites = req.favorites,
                preferred = preferred,
                extra = extra?.channels.orEmpty(),
                extraIdx = extra?.index ?: SportExtraIndex(emptyList()),
                confirmed = if (confirmedForAccount) confirmedByMatch[m.id].orEmpty() else emptySet(),
                votes = matchVotes(m, votes),
                regions = regions,
                offsetSeconds = req.offsetSeconds,
            )
            candidates[m.id] = c.toSnapSportCandidates()
        }
        val rev = "${snap.rev.take(8)}-${revCounter.get()}"
        SportBoardReply(
            rev = rev,
            account = snap.account,
            fetchedAtMs = nowMs,
            matches = tagged.map { it.toSnapMatch() },
            standings = standings,
            candidates = candidates,
            memory = memory,
            offline = offline,
        )
    }

    private fun putUrlCache(url: String, text: String, atMs: Long) {
        urlCache[url] = CachedBody(text, atMs)
        if (urlCache.size > URL_CACHE_MAX_ENTRIES) {
            val cutoff = atMs - URL_CACHE_MAX_AGE_MS
            urlCache.entries.removeIf { it.value.atMs < cutoff }
            val excess = urlCache.size - URL_CACHE_MAX_ENTRIES
            if (excess > 0) {
                val drop = urlCache.entries.sortedBy { it.value.atMs }.take(excess)
                drop.forEach { urlCache.remove(it.key) }
            }
        }
    }

    private suspend fun loadSchedules(
        offsetSeconds: Int,
        leagues: List<League>,
        fullWeek: (League) -> Boolean,
        forceToday: Boolean,
    ): Pair<List<Match>, Boolean> = coroutineScope {
        val nowMs = clockMs()
        val sources = scheduleSources(Instant.fromEpochMilliseconds(nowMs), offsetSeconds, leagues, fullWeek)
        val anyOffline = AtomicBoolean(false)
        val lists = sources.map { src ->
            async {
                val cached = urlCache[src.url]
                val maxAge = when {
                    src.day > 0 -> LATER_DAYS_MAX_AGE_MS
                    forceToday -> FORCE_MIN_AGE_MS
                    else -> TODAY_MAX_AGE_MS
                }
                val fresh = if (cached != null && nowMs - cached.atMs < maxAge) {
                    cached.text
                } else {
                    urlGate.withPermit {
                        val fetched = try {
                            fetchUrl(src.url).takeIf { it.trimStart().startsWith("{") }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                        if (fetched != null) {
                            putUrlCache(src.url, fetched, clockMs())
                            fetched
                        } else {
                            anyOffline.set(true)
                            cached?.text
                        }
                    }
                } ?: return@async emptyList()
                if (src.league == League.NHL) parseNhlSchedule(fresh) else parseEspnScoreboard(src.league, fresh)
            }
        }.awaitAll().flatten().distinctBy { it.id }
        lists to anyOffline.get()
    }

    private suspend fun loadStandings(leagues: List<League>): Map<String, String> = coroutineScope {
        val nowMs = clockMs()
        val out = LinkedHashMap<String, String>()
        val results = leagues.distinct().mapNotNull { l -> standingsUrl(l)?.let { l to it } }.map { (league, url) ->
            async {
                val cached = urlCache[url]
                val text = if (cached != null && nowMs - cached.atMs < LATER_DAYS_MAX_AGE_MS) {
                    cached.text
                } else {
                    urlGate.withPermit {
                        val fetched = try {
                            fetchUrl(url).takeIf { it.trimStart().startsWith("{") }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                        if (fetched != null) {
                            putUrlCache(url, fetched, clockMs())
                            fetched
                        } else cached?.text
                    }
                } ?: return@async emptyMap()
                if (league == League.NHL) parseNhlStandings(text) else parseEspnStandings(league, text)
            }
        }.awaitAll()
        results.forEach { out.putAll(it) }
        out
    }

    private suspend fun ensureExtraChannels(account: String): CachedExtra? {
        val current = extraRef.get()
        if (current != null && current.account == account && clockMs() - current.atMs < EXTRA_MAX_AGE_MS) return current
        return extraLock.withLock {
            val again = extraRef.get()
            if (again != null && again.account == account && clockMs() - again.atMs < EXTRA_MAX_AGE_MS) return@withLock again
            try {
                // Distingue null (automatique via isDefaultSportCategory) de [] (aucune catégorie Sport choisie).
                // En cas d'erreur Supabase, ne met JAMAIS en cache des catégories par défaut non autorisées.
                val chosenList: List<String>? = sync.householdSettings(householdId).sportCategories
                if (chosenList != null && chosenList.isEmpty()) {
                    val empty = CachedExtra(emptyList(), SportExtraIndex(emptyList()), clockMs(), account)
                    extraRef.set(empty)
                    return@withLock empty
                }
                beforeProviderFetch()
                val chosenSet = chosenList?.toSet()
                val cats = liveCategories().filter { c ->
                    if (chosenSet != null) c.id in chosenSet else isDefaultSportCategory(c.name)
                }
                val gate = Semaphore(4)
                val channels = coroutineScope {
                    cats.map { cat ->
                        async {
                            gate.withPermit {
                                val streams = try {
                                    liveStreams(cat.id)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    emptyList()
                                }
                                streams.map { s ->
                                    SportChannel("x:${s.id}", s.name, cat.name, s.id, direct = true)
                                }
                            }
                        }
                    }.awaitAll().flatten()
                }
                val built = CachedExtra(channels, SportExtraIndex(channels), clockMs(), account)
                extraRef.set(built)
                built
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                System.err.println("Chaînes Sport indisponibles (${e::class.simpleName}).")
                again?.takeIf { it.account == account }
            }
        }
    }

    private suspend fun ensureVotes(): List<SportVote> {
        val current = votesRef.get()
        if (current != null && clockMs() - current.atMs < VOTES_MAX_AGE_MS) return current.list
        return votesLock.withLock {
            val again = votesRef.get()
            if (again != null && clockMs() - again.atMs < VOTES_MAX_AGE_MS) return@withLock again.list
            try {
                val fresh = sync.sportVotes(householdId)
                votesRef.set(CachedVotes(fresh, clockMs()))
                fresh
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                again?.list.orEmpty()
            }
        }
    }

    private suspend fun loadMemory(profileId: String, force: Boolean): SportMemory {
        if (profileId.isBlank()) return SportMemory()
        val cached = memoryCache[profileId]
        if (!force && cached != null && clockMs() - cached.atMs < MEMORY_MAX_AGE_MS) return cached.memory
        return try {
            val fresh = sync.sportMemory(profileId)
            memoryCache[profileId] = CachedMemory(fresh, clockMs())
            fresh
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            cached?.memory ?: SportMemory()
        }
    }

    private companion object {
        const val FORCE_MIN_AGE_MS = 30_000L
        const val TODAY_MAX_AGE_MS = 2 * 60_000L
        const val LATER_DAYS_MAX_AGE_MS = 6 * 3_600_000L
        const val EXTRA_MAX_AGE_MS = 6 * 3_600_000L
        const val VOTES_MAX_AGE_MS = 5 * 60_000L
        const val MEMORY_MAX_AGE_MS = 5 * 60_000L
        const val LIVE_REFRESH_MS = 3 * 60_000L
        const val IDLE_REFRESH_MS = 15 * 60_000L
        const val URL_CACHE_MAX_AGE_MS = 24 * 3_600_000L
        const val URL_CACHE_MAX_ENTRIES = 250
    }
}
