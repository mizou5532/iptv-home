package home.iptv.tv.sport

import android.content.Context
import home.iptv.shared.catalog.CatalogRepository
import home.iptv.shared.groups.GroupSnapshot
import home.iptv.shared.home.SportBoardRequest
import home.iptv.shared.home.toMatch
import home.iptv.shared.home.toSportCandidates
import home.iptv.shared.sport.*
import home.iptv.shared.sync.ProfilePrefs
import home.iptv.shared.sync.SportMemory
import home.iptv.shared.sync.SyncClient
import home.iptv.shared.groups.groupSourceIndex
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** Synchronisation de la mémoire Sport du profil (étoiles et chaînes apprises) via Supabase. */
interface SportMemorySync {
    suspend fun get(profileId: String): SportMemory
    suspend fun merge(profileId: String, local: SportMemory): SportMemory
    suspend fun setPreferred(profileId: String, teamKey: String, groupId: String?)
    suspend fun learn(profileId: String, leagueKey: String, groupId: String)
    suspend fun forgetLearned(profileId: String, leagueKey: String, groupId: String)
}

fun sportMemorySync(sync: SyncClient): SportMemorySync = object : SportMemorySync {
    override suspend fun get(profileId: String) = sync.sportMemory(profileId)
    override suspend fun merge(profileId: String, local: SportMemory) = sync.sportMergeMemory(profileId, local)
    override suspend fun setPreferred(profileId: String, teamKey: String, groupId: String?) = sync.sportSetPreferred(profileId, teamKey, groupId)
    override suspend fun learn(profileId: String, leagueKey: String, groupId: String) = sync.sportLearnChannel(profileId, leagueKey, groupId)
    override suspend fun forgetLearned(profileId: String, leagueKey: String, groupId: String) = sync.sportForgetLearned(profileId, leagueKey, groupId)
}

/** Mémoire locale du sport : dernières réponses des sites (hors ligne) et chaînes apprises par profil et compétition. */
interface SportStore {
    fun cached(key: String): String?
    fun saveCached(key: String, text: String, at: Long)
    fun cachedAt(): Long
    /** Oublie les réponses gardées qui ne sont plus demandées (jours passés). */
    fun prune(keep: Set<String>) {}
    /** Heure d'enregistrement d'une réponse gardée (ms), ou null. */
    fun savedAt(key: String): Long? = null
    fun learned(profileId: String, league: League): List<String>
    fun saveLearned(profileId: String, league: League, groups: List<String>)
    /** Chaîne préférée (étoile) d'un profil pour une équipe (clé de [preferenceKeys]). */
    fun preferred(profileId: String, teamKey: String): String? = null
    fun savePreferred(profileId: String, teamKey: String, groupId: String?) {}
    /** Toutes les étoiles enregistrées sur cette télé pour [profileId]. */
    fun allPreferred(profileId: String): Map<String, String> = emptyMap()
    /** Toutes les chaînes apprises enregistrées sur cette télé pour [profileId]. */
    fun allLearned(profileId: String): Map<String, List<String>> = emptyMap()
    /** Vrai si la mémoire locale de ce profil a déjà été fusionnée vers Supabase. */
    fun isMemoryMerged(profileId: String): Boolean = false
    fun markMemoryMerged(profileId: String) {}
    /** Met la mémoire locale du profil au niveau de la mémoire partagée (secours hors-ligne). */
    fun applySharedMemory(profileId: String, memory: SportMemory) {
        for ((team, group) in memory.preferred) savePreferred(profileId, team, group)
        for ((leagueKey, groups) in memory.learned) {
            League.of(leagueKey)?.let { saveLearned(profileId, it, groups) }
        }
    }
}

class AndroidSportStore(context: Context) : SportStore {
    private val prefs = context.applicationContext.getSharedPreferences("iptv_home_sport", Context.MODE_PRIVATE)
    // Réponses des sites : un fichier chacune (un tableau NBA d'un jour pèse ~160 Ko ; dans les préférences,
    // chaque écriture réécrirait tout le fichier). Les anciennes réponses gardées dans les préférences sont retirées.
    private val dir = java.io.File(context.applicationContext.cacheDir, "sport").apply { mkdirs() }
    init {
        val old = prefs.all.keys.filter { it.startsWith("cache:") }
        if (old.isNotEmpty()) prefs.edit().apply { old.forEach { remove(it) } }.apply()
    }
    private fun file(key: String) = java.io.File(dir, java.security.MessageDigest.getInstance("SHA-1")
        .digest(key.toByteArray()).joinToString("") { "%02x".format(it) } + ".json")
    override fun cached(key: String) = runCatching { file(key).takeIf { it.exists() }?.readText() }.getOrNull()
    override fun saveCached(key: String, text: String, at: Long) {
        runCatching { val f = file(key); val tmp = java.io.File(dir, f.name + ".tmp"); tmp.writeText(text); tmp.renameTo(f) }
        prefs.edit().putLong("cached_at", at).apply()
    }
    override fun cachedAt() = prefs.getLong("cached_at", 0)
    override fun savedAt(key: String) = file(key).takeIf { it.exists() }?.lastModified()
    override fun prune(keep: Set<String>) {
        val names = keep.mapTo(HashSet()) { file(it).name }
        dir.listFiles()?.filter { it.name !in names }?.forEach { it.delete() }
    }
    override fun learned(profileId: String, league: League) =
        prefs.getString("learned:$profileId:${league.key}", null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
    override fun saveLearned(profileId: String, league: League, groups: List<String>) {
        prefs.edit().putString("learned:$profileId:${league.key}", groups.joinToString("\n")).apply()
    }
    override fun preferred(profileId: String, teamKey: String) = prefs.getString("preferred:$profileId:$teamKey", null)
    override fun savePreferred(profileId: String, teamKey: String, groupId: String?) {
        prefs.edit().apply { if (groupId == null) remove("preferred:$profileId:$teamKey") else putString("preferred:$profileId:$teamKey", groupId) }.apply()
    }
    override fun allPreferred(profileId: String): Map<String, String> {
        val prefix = "preferred:$profileId:"
        val out = LinkedHashMap<String, String>()
        for ((k, v) in prefs.all) {
            if (k.startsWith(prefix) && v is String && v.isNotBlank()) out[k.removePrefix(prefix)] = v
        }
        return out
    }
    override fun allLearned(profileId: String): Map<String, List<String>> {
        val prefix = "learned:$profileId:"
        val out = LinkedHashMap<String, List<String>>()
        for ((k, v) in prefs.all) {
            if (k.startsWith(prefix) && v is String) {
                val list = v.split('\n').filter { it.isNotBlank() }
                if (list.isNotEmpty()) out[k.removePrefix(prefix)] = list
            }
        }
        return out
    }
    override fun isMemoryMerged(profileId: String): Boolean = prefs.getBoolean("memory_merged:$profileId", false)
    override fun markMemoryMerged(profileId: String) {
        prefs.edit().putBoolean("memory_merged:$profileId", true).apply()
    }
    override fun applySharedMemory(profileId: String, memory: SportMemory) {
        val prefPrefix = "preferred:$profileId:"
        val learnPrefix = "learned:$profileId:"
        val editor = prefs.edit()
        for (k in prefs.all.keys) {
            if (k.startsWith(prefPrefix) && k.removePrefix(prefPrefix) !in memory.preferred) editor.remove(k)
            if (k.startsWith(learnPrefix) && k.removePrefix(learnPrefix) !in memory.learned) editor.remove(k)
        }
        for ((team, group) in memory.preferred) editor.putString("$prefPrefix$team", group)
        for ((leagueKey, groups) in memory.learned) editor.putString("$learnPrefix$leagueKey", groups.joinToString("\n"))
        editor.apply()
    }
}

private const val NHL_BASE = "https://api-web.nhle.com/v1/schedule/"
private const val ESPN_BASE = "https://site.api.espn.com/apis/site/v2/sports/"

/** Une adresse d'horaires : sa ligue et le jour qu'elle couvre (0 = aujourd'hui ; LNH : 0 et 7, une semaine chacune). */
data class ScheduleSource(val league: League, val url: String, val day: Int) {
    val key: String get() = url.substringAfter("://")
}

/**
 * Adresses à lire : LNH (2 semaines), et pour chaque ligue ESPN un tableau PAR JOUR (ESPN refuse depuis novembre
 * 2026 les plages de dates « AAAAMMJJ-AAAAMMJJ », réponse 400). 8 jours seulement pour les ligues où joue une équipe
 * favorite ([fullWeek]) ; les autres : aujourd'hui et demain (demain lu la veille sert de mémoire au réveil :
 * sans lui, une ligue sans favorite n'avait rien à montrer avant la réponse du réseau).
 */
fun scheduleSources(today: Instant, offsetSeconds: Int, leagues: List<League> = League.entries.filter { !it.optional },
                    fullWeek: (League) -> Boolean = { true }): List<ScheduleSource> {
    val first = java.time.LocalDate.ofEpochDay(localDay(today, offsetSeconds))
    return listOf(ScheduleSource(League.NHL, "$NHL_BASE$first", 0), ScheduleSource(League.NHL, "$NHL_BASE${first.plusDays(7)}", 7)) +
        leagues.filter { it.espnPath != null }.flatMap { l ->
            (0..(if (fullWeek(l)) 7 else 1)).map { d ->
                ScheduleSource(l, "$ESPN_BASE${l.espnPath}/scoreboard?dates=${first.plusDays(d.toLong()).toString().replace("-", "")}", d)
            }
        }
}

/** Ligues de la semaine complète : celles des équipes favorites (et tout le soccer si une favorite y joue). */
fun fullWeekFor(teams: List<home.iptv.shared.sync.SportTeam>): (League) -> Boolean {
    val keys = teams.map { it.league }.toSet()
    val soccer = teams.any { t -> League.of(t.league)?.soccer ?: t.league.contains('.') }
    return { l -> l.key in keys || (l.soccer && soccer) }
}

/** Demandes d'horaires en même temps. */
const val SCHEDULE_PARALLEL = 6
/** Horaires des jours suivants : relus au plus toutes les 6 h (aujourd'hui : à chaque mise à jour, pour les scores). */
const val LATER_DAYS_MAX_AGE_MS = 6 * 60 * 60 * 1000L

/**
 * Lit les horaires (quelques sites à la fois), garde chaque réponse lisible en cache et retombe sur le cache si un
 * site ne répond pas ou répond mal. [offline] = au moins une source vient du cache faute de réponse.
 */
class SportSchedule(
    private val fetch: suspend (String) -> String,
    private val store: SportStore,
    /**
     * Horaires préparés par le serveur de la maison (fonction « sport-horaires ») : toutes les adresses d'un coup ;
     * `null` ou une adresse absente = la télé la demande elle-même, comme avant.
     */
    private val batch: (suspend (List<String>) -> Map<String, String>?)? = null,
    private val now: () -> Instant = { Clock.System.now() },
) {
    data class Result(val matches: List<Match>, val offline: Boolean, val any: Boolean, val fetched: Int = 0, val ms: Long = 0)

    /** Horaires gardés seulement, sans réseau : affichage immédiat à l'ouverture. */
    suspend fun cached(offsetSeconds: Int, leagues: List<League>, fullWeek: (League) -> Boolean = { true }): Result =
        run(offsetSeconds, leagues, fullWeek, network = false)

    suspend fun load(offsetSeconds: Int, leagues: List<League> = League.entries.filter { !it.optional }, fullWeek: (League) -> Boolean = { true }): Result =
        run(offsetSeconds, leagues, fullWeek, network = true)

    /**
     * Premier temps de l'ouverture : seules les adresses d'aujourd'hui sont demandées (une par ligue : c'est tout ce
     * que montre l'écran), les jours suivants viennent de la mémoire. [load] complète ensuite.
     */
    suspend fun loadToday(offsetSeconds: Int, leagues: List<League> = League.entries.filter { !it.optional }, fullWeek: (League) -> Boolean = { true },
                          /** Faux quand on ne lit qu'une partie des ligues (scores en direct) : les autres réponses gardées restent. */
                          prune: Boolean = true): Result =
        run(offsetSeconds, leagues, fullWeek, network = true, wanted = { it == 0 }, prune = prune)

    /** Second temps : les jours suivants (aujourd'hui vient d'être lu par [loadToday] et reste en mémoire). */
    suspend fun loadLater(offsetSeconds: Int, leagues: List<League> = League.entries.filter { !it.optional }, fullWeek: (League) -> Boolean = { true }): Result =
        run(offsetSeconds, leagues, fullWeek, network = true, wanted = { it > 0 }, prune = true)

    private suspend fun run(offsetSeconds: Int, leagues: List<League>, fullWeek: (League) -> Boolean, network: Boolean,
                            wanted: (Int) -> Boolean = { true }, prune: Boolean = network): Result = coroutineScope {
        val started = System.nanoTime()
        val offline = java.util.concurrent.atomic.AtomicBoolean(false)
        val any = java.util.concurrent.atomic.AtomicBoolean(false)
        val fetched = java.util.concurrent.atomic.AtomicInteger(0)
        val gate = kotlinx.coroutines.sync.Semaphore(SCHEDULE_PARALLEL)
        val sources = scheduleSources(now(), offsetSeconds, leagues, fullWeek)
        val nowMs = now().toEpochMilliseconds()
        fun asks(src: ScheduleSource): Boolean {
            val saved = store.savedAt(src.key)
            val recent = src.day > 0 && saved != null && nowMs - saved < LATER_DAYS_MAX_AGE_MS
            return network && !recent && wanted(src.day)
        }
        // Une seule demande au serveur de la maison pour tout ce qui manque ; le reste (serveur muet) comme avant.
        val relayed = if (!network) emptyMap() else relay(sources.filter(::asks).map { it.url })
        val matches = sources.map { src ->
            async {
                gate.acquire()
                try {
                    // Jours suivants : la réponse gardée suffit si elle a moins de 6 h.
                    val ask = asks(src)
                    val fresh = if (!ask) null
                        else (relayed[src.url]?.takeIf { it.trimStart().startsWith("{") } ?: try { fetch(src.url) } catch (e: CancellationException) { throw e } catch (e: Exception) { null })
                            ?.takeIf { it.trimStart().startsWith("{") }
                    if (fresh != null) { fetched.incrementAndGet(); store.saveCached(src.key, fresh, nowMs) }
                    else if (ask) offline.set(true)
                    val text = fresh ?: store.cached(src.key) ?: return@async emptyList()
                    any.set(true)
                    if (src.league == League.NHL) parseNhlSchedule(text) else parseEspnScoreboard(src.league, text)
                } finally { gate.release() }
            }
        }.awaitAll().flatten()
        if (prune) store.prune(sources.mapTo(HashSet()) { it.key } + STANDINGS_KEYS)
        Result(matches, offline.get(), any.get(), fetched.get(), (System.nanoTime() - started) / 1_000_000)
    }

    private suspend fun relay(urls: List<String>): Map<String, String> {
        val b = batch ?: return emptyMap()
        if (urls.isEmpty()) return emptyMap()
        return try { withTimeoutOrNull(RELAY_TIMEOUT_MS) { b(urls) }.orEmpty() } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyMap() }
    }

    /**
     * Classements des ligues de mes équipes (LNH, championnats ; jamais les coupes), relus au plus toutes les 6 h :
     * clé de [standingKey] → « 3e Atlantique · 24 pts · 4 victoires de suite ». [network] faux : mémoire seulement.
     */
    suspend fun standings(leagues: List<League>, network: Boolean = true): Map<String, String> {
        val wanted = leagues.filter { it.hasStandings() }.mapNotNull { l -> standingsUrl(l)?.let { l to it } }
        if (wanted.isEmpty()) return emptyMap()
        val nowMs = now().toEpochMilliseconds()
        fun key(u: String) = u.substringAfter("://")
        val stale = wanted.filter { (_, u) -> store.savedAt(key(u))?.let { nowMs - it >= STANDINGS_MAX_AGE_MS } ?: true }.map { it.second }
        if (network && stale.isNotEmpty()) {
            val relayed = relay(stale)
            for (u in stale) {
                val text = (relayed[u]?.takeIf { it.trimStart().startsWith("{") } ?: try { fetch(u) } catch (e: CancellationException) { throw e } catch (e: Exception) { null })
                    ?.takeIf { it.trimStart().startsWith("{") } ?: continue
                store.saveCached(key(u), text, nowMs)
            }
        }
        return wanted.fold(emptyMap()) { acc, (l, u) ->
            val text = store.cached(key(u)) ?: return@fold acc
            acc + if (l == League.NHL) parseNhlStandings(text) else parseEspnStandings(l, text)
        }
    }
}

/** Le serveur de la maison a ce temps pour répondre ; ensuite la télé demande elle-même. */
const val RELAY_TIMEOUT_MS = 6_000L
/** Classements relus au plus toutes les 6 h. */
const val STANDINGS_MAX_AGE_MS = 6 * 60 * 60 * 1000L
/** Clés des classements gardés : jamais effacées avec les vieux horaires. */
val STANDINGS_KEYS: Set<String> = League.entries.mapNotNull { standingsUrl(it)?.substringAfter("://") }.toSet()

/** Mot montré à la place du score d'un match lu dans la mémoire (état réel inconnu jusqu'à la mise à jour). */
const val STARTED_LABEL = "Commencé"

/**
 * E : horaires de la mémoire montrés honnêtement. Un match gardé « à venir » dont l'heure est passée est « commencé » ;
 * un match gardé « en cours » perd son score figé. Les matchs terminés gardent le leur (il ne change plus).
 */
fun honestMemory(matches: List<Match>, now: Instant): List<Match> = matches.map { m ->
    when {
        m.state == MatchState.UPCOMING && m.start <= now -> m.copy(state = MatchState.LIVE, detail = STARTED_LABEL)
        m.state == MatchState.LIVE -> m.copy(homeScore = null, awayScore = null, detail = STARTED_LABEL)
        else -> m
    }
}

/** Groupes de chaînes visibles du Direct (masquées retirées), avec le nom de leur catégorie : base des candidats. */
fun visibleSportChannels(repo: CatalogRepository, prefs: ProfilePrefs?, snapshot: GroupSnapshot?): List<SportChannel> {
    if (prefs == null) return emptyList()
    val hidden = prefs.hidden
    val hiddenChannels = hidden.filter { it.itemType == "channel" }.mapTo(HashSet()) { it.itemId }
    val hiddenCategories = hidden.filter { it.itemType == "live_category" }.mapTo(HashSet()) { it.itemId }
    val cats = repo.categories()
    val names = cats.associate { it.id to it.name }
    val visible = repo.allGroupSources().filter { it.id !in hiddenChannels && it.categoryId !in hiddenCategories }
    val visibleIds = visible.associateBy { it.id }
    val index = groupSourceIndex(
        visible,
        snapshot ?: GroupSnapshot(0, 1, emptyList()),
        prefs.groupCopies,
        home.iptv.shared.groups.categoryCountries(cats),
        precomputed = repo.liveKeys()::get,
    )
    return index.values.distinctBy { it.id }.mapNotNull { group ->
        val anchor = group.sources.firstOrNull { it.id in visibleIds } ?: return@mapNotNull null
        SportChannel(group.id, group.title, names[anchor.categoryId].orEmpty(), anchor.id)
    }.sortedBy { visibleIds[it.anchorSourceId]?.position ?: Int.MAX_VALUE }
}

/** Ligues à lire pour un profil : celles des favorites toujours ; les autres sauf les sports masqués. */
fun sportLeagues(t: List<home.iptv.shared.sync.SportTeam>, view: home.iptv.shared.sync.SportView?): List<League> {
    val hidden = view?.hidden.orEmpty()
    val week = fullWeekFor(t)
    return leaguesFor(t).filter { it.sport !in hidden || week(it) }
}

/** Aucun profil ne s'ouvre sur Sport : la préparation attend ce temps (le Direct, les Films… passent d'abord). */
const val PREWARM_LATER_MS = 4_000L

/** Préparation au plus une fois par période : une nouvelle demande dans les 10 min est ignorée. */
const val PREWARM_EVERY_MS = 10 * 60 * 1000L

/**
 * Sport partagé par l'écran et sa préparation (G) : mêmes horaires gardés, mêmes chaînes d'événements (mémoire et
 * verrou de [AndroidSportSources]). Pendant « Qui regarde ? », [prewarm] lit les horaires d'aujourd'hui puis des jours
 * suivants et les chaînes d'événements pour les profils qui s'ouvrent sur Sport : à l'arrivée, l'écran trouve des
 * horaires de moins de 5 min (pas de réseau) et ses chaînes déjà prêtes.
 */
class SportPrep(
    val store: SportStore,
    fetch: suspend (String) -> String,
    val sources: SportSources,
    private val now: () -> Instant = { Clock.System.now() },
    private val offsetSeconds: () -> Int = { java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000 },
    batch: (suspend (List<String>) -> Map<String, String>?)? = null,
    /** Quand le serveur maison est actif, c'est lui qui garde horaires et chaînes d'événements à chaud. */
    private val skipWhen: () -> Boolean = { false },
) {
    val schedule = SportSchedule(fetch, store, batch, now)
    @Volatile private var lastRun = 0L
    @Volatile internal var runs = 0
        private set

    /**
     * Lance la préparation dans [scope] sans attendre, pour tous les profils qui ont la section Sport ; rien sinon.
     * Si aucun ne s'ouvre sur Sport, elle attend un peu ([PREWARM_LATER_MS]) : le reste de l'écran passe d'abord.
     */
    fun prewarm(profiles: List<home.iptv.shared.sync.Profile>, scope: CoroutineScope, io: CoroutineDispatcher = Dispatchers.IO): Job? {
        if (skipWhen()) return null
        // 0.40 (enquête lenteur) : seulement les profils qui s'ouvrent sur Sport ; les autres le préparent en l'ouvrant
        // (la préparation pour tous, à chaque « Qui regarde ? », chargeait la télé au pire moment : le démarrage).
        val sporty = profiles.filter { home.iptv.tv.data.openingTab(it.home, it.settings.sections) == home.iptv.tv.data.HomeTab.SPORT }
        if (sporty.isEmpty()) return null
        val opensOnSport = true
        val t = now().toEpochMilliseconds()
        synchronized(this) { if (t - lastRun < PREWARM_EVERY_MS) return null; lastRun = t; runs++ }
        return scope.launch(io) {
            if (!opensOnSport) delay(PREWARM_LATER_MS)
            home.iptv.tv.diag.PerfMonitor.data.event("Préparation du Sport")
            launch { runCatching { sources.extraChannels() } }
            // Ligues de tous ces profils réunies : une seule lecture par adresse.
            val leagues = sporty.flatMap { p -> sportLeagues(p.settings.sportTeams ?: DEFAULT_TEAMS, p.settings.sportView) }.distinct()
            val teams = sporty.flatMap { it.settings.sportTeams ?: DEFAULT_TEAMS }
            runCatching { schedule.loadToday(offsetSeconds(), leagues, fullWeekFor(teams)) }
            runCatching { schedule.loadLater(offsetSeconds(), leagues, fullWeekFor(teams)) }
            runCatching { schedule.standings(leagues.filter(fullWeekFor(teams)) + League.NHL) }
        }
    }
}

/** Crée le modèle de l'onglet Sport pour un profil et sa session du Direct. */
typealias SportFactory = (home.iptv.shared.sync.Profile, home.iptv.tv.live.LiveSession, CoroutineScope) -> SportViewModel

data class SportUi(
    val board: SportBoard = SportBoard(emptyList(), emptyList()),
    val candidates: Map<String, SportCandidates> = emptyMap(),
    val loading: Boolean = true,
    /** « Horaires du … (hors ligne) » ou message d'erreur ; null si tout est à jour. */
    val notice: String? = null,
    /**
     * Affiche et bande du bas : le match en cours ou le prochain de mes équipes, puis ce qui se joue maintenant ou
     * aujourd'hui (mes autres matchs du jour, puis tous les autres), sans les matchs des jours suivants.
     */
    val strip: List<Match> = emptyList(),
    /** Affiché depuis la mémoire, mise à jour par le réseau en cours (« Mise à jour… » dans la barre). */
    val updating: Boolean = false,
    /** Réglages d'affichage du profil (thème de l'affiche, ligne d'aide). */
    val view: home.iptv.shared.sync.SportView = home.iptv.shared.sync.SportView(),
    /** Classement par équipe ([standingKey]) ; vide si « sans résultat ». */
    val standings: Map<String, String> = emptyMap(),
    /** « Sans résultat » : scores retirés des matchs, sauf ceux dévoilés ([revealed]). */
    val hideScores: Boolean = false,
    val revealed: Set<String> = emptySet(),
    /** Grille : rangées par moment (En direct, Ce soir, Demain, Cette semaine pour mes équipes). */
    val rows: List<SportRow> = emptyList(),
    /** Matchs d'aujourd'hui terminés (pour proposer un autre match à la fin de celui qu'on regarde). */
    val finished: Set<String> = emptySet(),
)

/**
 * Fin du match regardé ([finishedId]) : un autre match en direct à proposer, s'il a des chaînes : mes équipes
 * d'abord, puis un match serré en fin de partie (jamais en mode « sans résultat » : les scores y sont retirés), puis
 * le plus avancé. `null` : rien à proposer.
 */
fun nextLiveMatch(ui: SportUi, finishedId: String): Match? {
    val live = ui.strip.filter { it.state == MatchState.LIVE && it.id != finishedId && ui.candidates[it.id]?.all?.isNotEmpty() == true }
    val mine = ui.board.mine.mapTo(HashSet()) { it.id }
    return live.firstOrNull { it.id in mine } ?: live.filter(::isCloseLate).minByOrNull { it.start } ?: live.minByOrNull { it.start }
}

/** « Barça – Real est en direct (1-1, 70’) : le regarder ? » */
fun nextMatchText(m: Match): String {
    val who = "${shortNameOf(m.away, m.awayAbbr, m.awayShort)} – ${shortNameOf(m.home, m.homeAbbr, m.homeShort)}"
    val state = listOfNotNull(if (m.awayScore != null && m.homeScore != null) "${m.awayScore}-${m.homeScore}" else null, m.detail).joinToString(", ")
    return "Match terminé. $who est en direct" + (if (state.isNotEmpty()) " ($state)" else "") + " : le regarder ?"
}

private fun shortNameOf(name: String, abbr: String, short: String) = short.ifBlank { if (name.length > 14 && abbr.isNotBlank()) abbr else name }

/** Une rangée de la grille : son titre et ses matchs. */
data class SportRow(val title: String, val matches: List<Match>)

/**
 * Rangées de la grille : « En direct maintenant », « Ce soir » (aujourd'hui, à venir), « Demain » (mes équipes
 * d'abord), « Cette semaine pour mes équipes » ; rangées vides retirées, chaque match une seule fois, [max] en tout.
 */
fun sportRows(strip: List<Match>, board: SportBoard, all: List<Match>, now: Instant, offsetSeconds: Int,
              view: home.iptv.shared.sync.SportView, teams: List<home.iptv.shared.sync.SportTeam>, max: Int = ROWS_MAX): List<SportRow> {
    val today = localDay(now, offsetSeconds)
    val seen = HashSet<String>()
    fun take(list: List<Match>) = list.filter { seen.add(it.id) }
    val live = take(strip.filter { it.state == MatchState.LIVE })
    val tonight = take(strip.filter { it.state != MatchState.LIVE && localDay(it.start, offsetSeconds) == today })
    val rank = (view.order.filter { it in SPORTS } + SPORTS).distinct()
    fun mine(m: Match) = teams.any { m.involves(it) }
    val tomorrow = take(all.distinctBy { it.id }.filter { !isOver(it, now) && it.state == MatchState.UPCOMING && localDay(it.start, offsetSeconds) == today + 1 &&
        (mine(it) || it.league.sport !in view.hidden) }
        .sortedWith(compareBy({ if (mine(it)) 0 else 1 }, { rank.indexOf(it.league.sport) }, { it.start }, { it.id })))
    val week = take(board.mine.filter { localDay(it.start, offsetSeconds) > today + 1 })
    var left = max
    return listOf("En direct maintenant" to live, "Ce soir" to tonight, "Demain" to tomorrow, "Cette semaine pour mes équipes" to week)
        .mapNotNull { (t, ms) -> ms.take(left).also { left -= it.size }.takeIf { it.isNotEmpty() }?.let { SportRow(t, it) } }
}

/** Matchs en tout dans les rangées de la grille. */
const val ROWS_MAX = 40

const val SPORT_REFRESH_MS = 30 * 60 * 1000L
/** Un match de la bande est en cours : ses tableaux d'aujourd'hui sont relus à ce rythme (scores). */
const val SPORT_LIVE_REFRESH_MS = 2 * 60 * 1000L
/** À l'ouverture, des horaires gardés de moins de 5 min ne sont pas relus. */
const val SPORT_FRESH_MS = 5 * 60 * 1000L
/** E : mémoire plus vieille que [SPORT_FRESH_MS] : on laisse au plus ce temps au réseau (aujourd'hui) avant de la montrer. */
const val MEMORY_WAIT_MS = 1_000L
const val STRIP_MAX = 16
const val LEARN_AFTER_MS = 3 * 60 * 1000L

/** Écran Sport d'un profil : horaires, chaînes à essayer, apprentissage. Calculs et lectures hors du fil principal. */
class SportViewModel(
    private val profileId: String,
    private val schedule: SportSchedule,
    private val channels: suspend () -> List<SportChannel>,
    private val favorites: () -> List<String>,
    private val store: SportStore,
    private val prefs: StateFlow<ProfilePrefs?>,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Instant = { Clock.System.now() },
    private val offsetSeconds: () -> Int = { java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000 },
    /** Équipes favorites du profil (page iPhone) ; null = Canadiens et Barça. */
    private val teams: () -> List<home.iptv.shared.sync.SportTeam>? = { null },
    /** Réglages de l'onglet Sport du profil (page iPhone) ; null = par défaut. */
    private val view: () -> home.iptv.shared.sync.SportView? = { null },
    /** Catégories Sport (même masquées), mémoire de la maison et guide du fournisseur. */
    private val sources: SportSources = NoSportSources,
    /** Serveur maison (Étape 4) : horaires, classements, guide et chaînes déjà calculés. */
    private val homeSport: home.iptv.tv.home.HomeSportClient? = null,
    /** Empreinte du compte IPTV courant : ignore la réponse du serveur s'il pointe sur un autre compte. */
    private val account: () -> String? = { null },
    /** Synchronisation Supabase des étoiles et chaînes apprises entre toutes les télés du foyer. */
    private val memorySync: SportMemorySync? = null,
) {
    private fun leagues(t: List<home.iptv.shared.sync.SportTeam>): List<League> = sportLeagues(t, view())

    /** Heure du modèle (celle de l'affiche : compte à rebours). */
    fun clock(): Instant = now()

    private val _ui = MutableStateFlow(SportUi())
    val ui: StateFlow<SportUi> = _ui
    @Volatile private var matches = emptyList<Match>()
    /** Des horaires lus sur le réseau ont été montrés : la mémoire ne doit plus les remplacer. */
    private var fresh = false
    private val matchLock = Any()
    private val todayArrived = CompletableDeferred<Unit>()
    @Volatile private var refreshing: Job? = null
    @Volatile private var started = false
    /** Candidats déjà calculés par le serveur maison (non-null = mode serveur maison actif). */
    @Volatile private var serverCandidates: Map<String, SportCandidates>? = null
    /** Dernière mise à jour réussie depuis le serveur maison (ms). */
    @Volatile private var serverLastAtMs: Long = 0L
    private val mergeLock = kotlinx.coroutines.sync.Mutex()

    fun start() {
        if (started) return
        started = true
        if (homeSport != null) {
            scope.launch(io) {
                if (!tryServerBoard(forceRefresh = false)) startLocalFallback()
            }
            // Masquage depuis l'iPhone : nouveau tableau hors fil principal (collectLatest évite les réponses périmées).
            scope.launch(io) { prefs.drop(1).collectLatest { onPrefsChanged() } }
            return
        }
        startLocalFallback()
        // Masquage depuis l'iPhone : candidats recalculés aussitôt (jamais de chaîne masquée proposée).
        scope.launch(io) { prefs.drop(1).collectLatest { rebuild() } }
    }

    private fun startLocalFallback() {
        if (memorySync != null) {
            scope.launch(io) {
                val merged = ensureMemoryMerged()
                if (merged) refreshSharedMemory()
            }
        }
        // A : ce qu'on a en mémoire s'affiche tout de suite ; le réseau met à jour ensuite (sauf horaires très récents).
        // E : une mémoire vieille de plus de 5 min n'est montrée qu'après 1 s sans réponse du réseau, et honnêtement.
        scope.launch(io) {
            val t = teams() ?: DEFAULT_TEAMS
            val cached = schedule.cached(offsetSeconds(), leagues(t), fullWeekFor(t))
            val recent = cached.any && now().toEpochMilliseconds() - store.cachedAt() < SPORT_FRESH_MS
            if (!recent) refresh()
            if (!cached.any) return@launch
            if (!recent) withTimeoutOrNull(MEMORY_WAIT_MS) { todayArrived.await() }
            val show = synchronized(matchLock) { if (fresh) false else { matches = honestMemory(cached.matches, now()); true } }
            if (!show) return@launch
            timing(if (recent) "horaires récents, pas de réseau" else "mémoire ${cached.ms} ms")
            rebuild(notice = null, updating = !recent)
        }
        // Chaînes d'événements et d'équipes, votes de la maison : en parallèle, puis nouveau classement.
        // Recalcul dès qu'elles arrivent, s'il y a déjà des matchs : un calcul en cours (fait avec l'ancienne liste)
        // est attendu puis refait. Sans matchs, le premier affichage les prendra.
        scope.launch(io) { loadSources(); rebuildIfStarted() }
        scope.launch(io) { loadStandings(network = false); loadStandings(network = true) }
    }

    /**
     * Fusion unique de la mémoire locale (étoiles et chaînes apprises) vers Supabase au premier lancement :
     * sans perte, les étoiles déjà connues dans Supabase restent prioritaires. Renvoie vrai si le profil est fusionné.
     */
    private suspend fun ensureMemoryMerged(): Boolean {
        val ms = memorySync ?: return true
        if (store.isMemoryMerged(profileId)) return true
        return mergeLock.withLock {
            if (store.isMemoryMerged(profileId)) return@withLock true
            val local = SportMemory(store.allPreferred(profileId), store.allLearned(profileId))
            try {
                val merged = ms.merge(profileId, local)
                store.applySharedMemory(profileId, merged)
                store.markMemoryMerged(profileId)
                if (local.preferred.isNotEmpty() || local.learned.isNotEmpty()) {
                    homeSport?.invalidateSport(profileId)
                }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        }
    }

    private suspend fun refreshSharedMemory() {
        val ms = memorySync ?: return
        if (!store.isMemoryMerged(profileId)) return
        try {
            val shared = ms.get(profileId)
            store.applySharedMemory(profileId, shared)
            rebuildIfStarted()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Hors-ligne : on garde la copie locale.
        }
    }

    private fun buildBoardRequest(snapPrefs: ProfilePrefs?, forceRefresh: Boolean): SportBoardRequest {
        val hiddenCats = snapPrefs?.hidden.orEmpty().filter { it.itemType == "live_category" }.map { it.itemId }
        val hiddenChans = snapPrefs?.hidden.orEmpty().filter { it.itemType == "channel" }.map { it.itemId }
        return SportBoardRequest(
            profileId = profileId,
            offsetSeconds = offsetSeconds(),
            teams = teams() ?: DEFAULT_TEAMS,
            view = view(),
            hiddenCategories = hiddenCats,
            hiddenChannels = hiddenChans,
            favorites = favorites(),
            groupCopies = snapPrefs?.groupCopies ?: true,
            forceRefresh = forceRefresh,
        )
    }

    private suspend fun tryServerBoard(forceRefresh: Boolean): Boolean {
        val client = homeSport ?: return false
        val t0 = System.nanoTime()
        val snapPrefs = prefs.value
        val merged = ensureMemoryMerged()
        val reply = try {
            client.sportBoard(buildBoardRequest(snapPrefs, forceRefresh))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return false
        val expectedAccount = account()
        if (expectedAccount != null && reply.account != expectedAccount) return false
        // Préférences modifiées pendant l'appel réseau : on ignore cette réponse périmée (collectLatest relance).
        if (prefs.value != snapPrefs) return true
        val parsedMatches = reply.matches.mapNotNull { it.toMatch() }
        val parsedCandidates = reply.candidates.mapValues { it.value.toSportCandidates() }
        if (merged) store.applySharedMemory(profileId, reply.memory)
        synchronized(matchLock) {
            matches = parsedMatches
            fresh = true
        }
        standings = reply.standings
        serverCandidates = parsedCandidates
        serverLastAtMs = now().toEpochMilliseconds()
        todayArrived.complete(Unit)
        val notice = if (reply.offline) "Horaires hors ligne (serveur maison)" else null
        rebuild(notice = notice, updating = false, probe = false)
        timing("serveur maison ${parsedMatches.size} matchs ${(System.nanoTime() - t0) / 1_000_000} ms")
        return true
    }

    private suspend fun onPrefsChanged() {
        serverCandidates = null
        if (homeSport != null && tryServerBoard(forceRefresh = false)) return
        rebuild()
    }

    /** F : durées des étapes, visibles dans le calque de diagnostic (« Sport : … »). */
    private val steps = ArrayDeque<String>()
    // Appelé par plusieurs tâches à la fois (horaires, chaînes du fournisseur, calcul) : tout sous le même verrou,
    // lecture comprise (sinon ConcurrentModificationException, vue dans les tests du 17 nov.).
    private fun timing(step: String) {
        val text = synchronized(steps) {
            steps.addLast(step)
            while (steps.size > 6) steps.removeFirst()
            steps.joinToString(" · ")
        }
        home.iptv.tv.diag.Diagnostics.sport = text
    }

    fun refresh() = refresh(force = true)

    private fun refresh(force: Boolean) {
        if (refreshing?.isActive == true) return
        refreshing = scope.launch(io) { try { refreshBody(force) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            // Jamais de plantage : l'affiche garde ce qu'elle montrait, sans « Mise à jour » sans fin.
            timing("mise à jour : échec (${e::class.simpleName})")
            todayArrived.complete(Unit)
            _ui.value = _ui.value.copy(updating = false, loading = false)
        } }
    }

    private suspend fun refreshBody(force: Boolean) {
        run {
            if (!_ui.value.loading) _ui.value = _ui.value.copy(updating = true)
            if (homeSport != null) {
                if (tryServerBoard(forceRefresh = force)) return
                serverCandidates = null
                if (standings.isEmpty()) scope.launch(io) { loadStandings(network = false); loadStandings(network = true) }
            }
            val t = teams() ?: DEFAULT_TEAMS
            // Les chaînes d'événements (fournisseur, parfois lent) complètent l'écran quand elles arrivent : les
            // horaires ne les attendent plus.
            scope.launch(io) { loadSources(); rebuildIfStarted() }
            // Aujourd'hui d'abord (une demande par ligue), affiché aussitôt ; les jours suivants ensuite.
            val today = schedule.loadToday(offsetSeconds(), leagues(t), fullWeekFor(t))
            timing("aujourd'hui ${today.fetched} demandes ${today.ms} ms")
            if (today.any) synchronized(matchLock) { matches = today.matches; fresh = true }
            todayArrived.complete(Unit)
            if (today.any) rebuild(notice = null, updating = true)
            val later = schedule.loadLater(offsetSeconds(), leagues(t), fullWeekFor(t))
            timing("jours suivants ${later.fetched} demandes ${later.ms} ms")
            val result = later.copy(offline = today.offline || later.offline)
            synchronized(matchLock) { matches = result.matches; fresh = fresh || result.any }
            val notice = when {
                !result.any -> "Horaires indisponibles. Vérifie la connexion puis réessaie."
                result.offline -> "Horaires du ${java.text.SimpleDateFormat("d MMM 'à' HH'h'mm", java.util.Locale.CANADA_FRENCH).format(java.util.Date(store.cachedAt()))} (hors ligne)"
                else -> null
            }
            rebuild(notice, updating = false)
        }
    }

    /**
     * Délai avant la prochaine relecture : 2 min si un match de la bande est en cours (scores), 30 min sinon.
     */
    fun nextRefreshMs(): Long =
        if (_ui.value.strip.any { it.state == MatchState.LIVE }) SPORT_LIVE_REFRESH_MS else SPORT_REFRESH_MS

    /** Retour à l'écran (veille, autre app) : mise à jour aussitôt si la dernière lecture a plus de 5 min. */
    fun refreshIfStale() {
        if (!started) return
        val lastAt = maxOf(store.cachedAt(), serverLastAtMs)
        if (now().toEpochMilliseconds() - lastAt >= SPORT_FRESH_MS) refresh(force = homeSport == null)
    }

    /** Tour régulier : scores des matchs en cours seulement (aujourd'hui, ligues concernées), sinon mise à jour complète. */
    fun tick() {
        if (homeSport != null && serverCandidates != null) {
            refresh(force = false)
            return
        }
        // Matchs en cours, et ceux dont l'heure est passée sans nouvelles (ils viennent sans doute de commencer).
        val t = now()
        val live = _ui.value.strip.filter { it.state == MatchState.LIVE || (it.state == MatchState.UPCOMING && it.start <= t) }
            .map { it.league }.distinct()
        if (live.isEmpty()) refresh() else refreshScores(live)
    }

    /** Tableaux d'aujourd'hui des ligues [live] seulement ; leurs matchs remplacent les anciens, le reste ne bouge pas. */
    private fun refreshScores(live: List<League>) {
        if (refreshing?.isActive == true) return
        refreshing = scope.launch(io) {
            try {
                val t = teams() ?: DEFAULT_TEAMS
                val res = schedule.loadToday(offsetSeconds(), live, fullWeekFor(t), prune = false)
                timing("scores ${res.fetched} demandes ${res.ms} ms")
                if (!res.any) return@launch
                val leagues = live.toSet()
                synchronized(matchLock) { matches = matches.filterNot { it.league in leagues } + res.matches; fresh = true }
                rebuild(notice = if (res.offline) _ui.value.notice else null, updating = false, probe = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Jamais de plantage pour une mise à jour de scores : l'affiche garde ce qu'elle montrait.
                timing("scores : échec (${e::class.simpleName})")
            }
        }
    }

    @Volatile private var standings: Map<String, String> = emptyMap()
    /** Matchs dont le score a été dévoilé (touche Info, « sans résultat »). */
    @Volatile private var revealed: Set<String> = emptySet()

    /** « Sans résultat » : montre (ou recache) le score de ce match. */
    fun reveal(matchId: String) {
        revealed = if (matchId in revealed) revealed - matchId else revealed + matchId
        scope.launch(io) { rebuild(probe = false) }
    }

    private suspend fun loadStandings(network: Boolean) {
        val t = teams() ?: DEFAULT_TEAMS
        val wanted = leagues(t).filter(fullWeekFor(t)) + League.NHL
        val got = runCatching { schedule.standings(wanted.distinct(), network) }.getOrDefault(emptyMap())
        if (got.isNotEmpty() && got != standings) { standings = got; rebuildIfStarted() }
    }

    @Volatile private var extra = SportExtraIndex(emptyList())
    @Volatile private var votes = emptyList<home.iptv.shared.sync.SportVote>()
    /** Chaînes confirmées par le guide, par match. */
    private val confirmed = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()

    private suspend fun loadSources() = coroutineScope {
        val t0 = System.nanoTime()
        // F : la liste gardée sur la télé sert tout de suite ; la liste du fournisseur la remplace quand elle arrive.
        if (extra.channels.isEmpty()) {
            val saved = sources.savedChannels()
            if (saved.isNotEmpty() && extra.channels.isEmpty()) {
                extra = SportExtraIndex(saved)
                timing("catégories sport en mémoire ${saved.size} chaînes")
                rebuildIfStarted()
            }
        }
        val e = async { sources.extraChannels() }
        val v = async { sources.votes() }
        val list = e.await()
        if (list.size != extra.channels.size || list != extra.channels) extra = SportExtraIndex(list)
        votes = v.await()
        timing("catégories sport ${list.size} chaînes ${(System.nanoTime() - t0) / 1_000_000} ms")
    }

    private var probing: Job? = null

    /**
     * C : guide du fournisseur des chaînes probables des premiers matchs de la bande (en cours ou dans les 3 h) ;
     * une chaîne dont le guide annonce le match passe « Confirmé par le guide ».
     */
    private fun probeGuides(ui: SportUi) {
        if (probing?.isActive == true) return
        val soon = ui.strip.filter { it.state == MatchState.LIVE || it.start - now() < kotlin.time.Duration.parse("3h") }.take(3)
        if (soon.isEmpty()) return
        probing = scope.launch(io) {
            var changed = false
            for (m in soon) {
                val c = ui.candidates[m.id] ?: continue
                val gate = kotlinx.coroutines.sync.Semaphore(4)
                val ok = coroutineScope {
                    guideProbes(c).map { ch -> async { gate.acquire(); try { ch.groupId.takeIf { confirmedBy(sources.guide(ch.anchorSourceId), m) } } finally { gate.release() } } }
                        .awaitAll().filterNotNull().toSet()
                }
                if (ok != confirmed[m.id].orEmpty()) { confirmed[m.id] = ok; changed = true }
            }
            if (changed) rebuild(probe = false)
        }
    }

    /** B : chaînes visibles et leur index, gardés tant que la liste des chaînes ne change pas. */
    private var channelIndex: Pair<List<SportChannel>, SportChannelIndex>? = null

    /** Ce dont dépendent les chaînes d'un match (ni le score ni l'avancement) : même clé, même résultat. */
    private data class CandidateKey(
        val match: Match, val learned: List<String>, val favorites: List<String>, val preferred: List<String>,
        val confirmed: Set<String>, val votes: Map<String, Int>, val regions: List<Region>, val offset: Int,
    )
    /** Résultats gardés par match, pour les index [memoFor] ; vidés quand la liste des chaînes change. */
    private val memo = HashMap<String, Pair<CandidateKey, SportCandidates>>()
    private var memoFor: Pair<SportChannelIndex, SportExtraIndex>? = null
    /** Un seul calcul à la fois : les demandes qui arrivent ensemble (mémoire, réseau, catégories) se suivent. */
    private val rebuilding = kotlinx.coroutines.sync.Mutex()
    /** Nombre de matchs réellement recalculés au dernier passage (les autres viennent de la mémoire). */
    @Volatile internal var lastComputed = 0
        private set

    private suspend fun rebuild(notice: String? = _ui.value.notice, updating: Boolean = _ui.value.updating && notice == _ui.value.notice, probe: Boolean = true) = withContext(io) {
        rebuilding.lock()
        try { built = true; rebuildLocked(notice, updating, probe) } finally { rebuilding.unlock() }
    }

    /** Un calcul a déjà commencé (peut-être avec l'ancienne liste de chaînes). */
    @Volatile private var built = false

    /**
     * Chaînes du fournisseur arrivées : si un calcul a déjà commencé, on attend sa fin puis on le refait avec le même
     * message et le même indicateur « Mise à jour » ; sinon le premier calcul les prendra.
     */
    private suspend fun rebuildIfStarted() = withContext(io) {
        if (!built) return@withContext
        rebuilding.lock()
        try { rebuildLocked(_ui.value.notice, _ui.value.updating, true) } finally { rebuilding.unlock() }
    }

    private suspend fun rebuildLocked(notice: String?, updating: Boolean, probe: Boolean) {
        val t0 = System.nanoTime()
        val board = buildBoard(matches, now(), offsetSeconds(), teams())
        val favTeams = teams() ?: DEFAULT_TEAMS
        val v = view() ?: home.iptv.shared.sync.SportView()
        // Seuls les matchs de la bande ont des chaînes à l'écran (affiche, « Regarder », choix de la chaîne).
        val strip = stripFor(board, now(), offsetSeconds(), v, STRIP_MAX)
        // Grille : aussi demain et la semaine de mes équipes (leurs chaînes sont calculées comme celles de la bande).
        val rows = if (v.grid) sportRows(strip, board, matches, now(), offsetSeconds(), v, favTeams) else emptyList()
        val shown = (strip + rows.flatMap { it.matches }).distinctBy { it.id }
        val sc = serverCandidates
        val cands = if (sc != null) {
            val curPrefs = prefs.value
            val hiddenChans = curPrefs?.hidden.orEmpty().filter { it.itemType == "channel" }.mapTo(HashSet()) { it.itemId }
            lastComputed = 0
            shown.associate { m ->
                val raw = sc[m.id] ?: SportCandidates(emptyList(), emptyList(), emptyList(), emptyMap())
                // Règle « masqué gagne » : tant que les préférences du profil ne sont pas connues (null), aucune
                // chaîne du Direct n'est proposée (comme visibleSportChannels) — seules les chaînes Sport dédiées passent.
                val keep: (SportChannel) -> Boolean = if (curPrefs == null) {
                    { it.direct }
                } else if (hiddenChans.isEmpty()) {
                    { true }
                } else {
                    { it.anchorSourceId !in hiddenChans }
                }
                val filtered = SportCandidates(
                    proposed = raw.proposed.filter(keep),
                    all = raw.all.filter(keep),
                    likely = raw.likely.filter(keep),
                    reasons = raw.reasons,
                )
                m.id to filtered
            }
        } else {
            val visible = channels()
            val idx = channelIndex?.takeIf { it.first === visible || it.first == visible }?.second
                ?: SportChannelIndex(visible).also { channelIndex = visible to it }
            val t1 = System.nanoTime()
            val fav = favorites()
            val regions = regionOrder(v.langOrder)
            val ex = extra; val vs = votes; val offset = offsetSeconds()
            if (memoFor?.first !== idx || memoFor?.second !== ex) { memo.clear(); memoFor = idx to ex }
            var computed = 0
            val builtCands = shown.associate { m ->
                val preferred = preferenceKeys(m, favTeams).mapNotNull { store.preferred(profileId, it) }.distinct()
                val key = CandidateKey(m.copy(state = MatchState.UPCOMING, homeScore = null, awayScore = null, detail = null),
                    store.learned(profileId, m.league), fav, preferred, confirmed[m.id].orEmpty(), matchVotes(m, vs), regions, offset)
                val kept = memo[m.id]?.takeIf { it.first == key }?.second
                m.id to (kept ?: sportCandidates(m, key.learned, idx, fav, preferred,
                    extraIdx = ex, confirmed = key.confirmed, votes = key.votes, regions = regions, offsetSeconds = offset)
                    .also { memo[m.id] = key to it; computed++ })
            }
            memo.keys.retainAll(builtCands.keys)
            lastComputed = computed
            timing("chaînes ${(t1 - t0) / 1_000_000} ms, $computed/${builtCands.size} matchs ${(System.nanoTime() - t1) / 1_000_000} ms")
            builtCands
        }
        val hide = v.hideScores
        val open = revealed
        fun mask(m: Match) = if (hide && m.id !in open) m.withoutScore() else m
        val finished = matches.filter { it.state == MatchState.FINAL }.mapTo(HashSet()) { it.id }
        val ui = SportUi(board, cands, loading = false, notice = notice, strip = strip.map(::mask), updating = updating, view = v,
            standings = if (hide) emptyMap() else standings, hideScores = hide, revealed = open,
            rows = rows.map { r -> r.copy(matches = r.matches.map(::mask)) }, finished = finished)
        _ui.value = ui
        if (probe && sc == null) probeGuides(ui)
    }

    /**
     * Étoile (appui long dans le choix de la chaîne) : [groupId] devient la chaîne préférée des équipes du match
     * (favorites, sinon les deux) ; si elle l'était déjà, l'étoile est retirée.
     */
    fun togglePreferred(match: Match, groupId: String) {
        scope.launch(io) {
            val keys = preferenceKeys(match, teams() ?: DEFAULT_TEAMS)
            val already = keys.all { store.preferred(profileId, it) == groupId }
            val next = if (already) null else groupId
            keys.forEach { store.savePreferred(profileId, it, next) }
            val synced = try {
                keys.forEach { memorySync?.setPreferred(profileId, it, next) }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (homeSport != null) {
                homeSport.invalidateSport(profileId)
                if (synced && tryServerBoard(forceRefresh = false)) return@launch
                serverCandidates = null
            }
            rebuild(probe = false)
        }
    }

    /**
     * La chaîne regardée assez longtemps depuis un match devient la première proposée pour sa compétition, et la
     * maison retient qu'elle diffuse les matchs de ces équipes (+1).
     */
    fun learned(match: Match, channel: SportChannel) {
        scope.launch(io) {
            if (!channel.direct) store.saveLearned(profileId, match.league, learn(store.learned(profileId, match.league), channel.groupId))
            val synced = if (!channel.direct) {
                try { memorySync?.learn(profileId, match.league.key, channel.groupId); true }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { false }
            } else true
            vote(match, channel, +1)
            if (homeSport != null) {
                homeSport.invalidateSport(profileId)
                if (synced && serverCandidates != null && tryServerBoard(forceRefresh = false)) return@launch
                serverCandidates = null
            }
            rebuild(probe = false)
        }
    }

    /** « Pas le bon match » : la maison écarte cette chaîne pour ces équipes (-3) ; elle n'est plus proposée. */
    fun wrongChannel(match: Match, channel: SportChannel) {
        scope.launch(io) {
            store.saveLearned(profileId, match.league, store.learned(profileId, match.league).filter { it != channel.groupId })
            confirmed[match.id] = confirmed[match.id].orEmpty() - channel.groupId
            val synced = try { memorySync?.forgetLearned(profileId, match.league.key, channel.groupId); true }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { false }
            vote(match, channel, -3)
            if (homeSport != null) {
                homeSport.invalidateSport(profileId)
                if (synced && serverCandidates != null && tryServerBoard(forceRefresh = false)) return@launch
                serverCandidates = null
            }
            rebuild(probe = false)
        }
    }

    private suspend fun vote(match: Match, channel: SportChannel, delta: Int) {
        val keys = voteKeys(match)
        // Tout de suite sur cette télé ; puis la maison.
        votes = keys.fold(votes) { acc, k ->
            val old = acc.firstOrNull { it.teamKey == k && it.channelKey == channel.key }
            val score = ((old?.score ?: 0) + delta).coerceIn(-9, 9)
            acc.filterNot { it === old } + home.iptv.shared.sync.SportVote(k, channel.key, score)
        }
        keys.forEach { sources.vote(it, channel.key, delta) }
    }
}
