package home.iptv.tv.sport

import android.content.Context
import home.iptv.shared.catalog.CatalogRepository
import home.iptv.shared.groups.GroupSnapshot
import home.iptv.shared.sport.*
import home.iptv.shared.sync.ProfilePrefs
import home.iptv.tv.live.groupSourceIndex
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

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
class SportSchedule(private val fetch: suspend (String) -> String, private val store: SportStore, private val now: () -> Instant = { Clock.System.now() }) {
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
    suspend fun loadToday(offsetSeconds: Int, leagues: List<League> = League.entries.filter { !it.optional }, fullWeek: (League) -> Boolean = { true }): Result =
        run(offsetSeconds, leagues, fullWeek, network = true, wanted = { it == 0 })

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
        val matches = sources.map { src ->
            async {
                gate.acquire()
                try {
                    val saved = store.savedAt(src.key)
                    // Jours suivants : la réponse gardée suffit si elle a moins de 6 h.
                    val recent = src.day > 0 && saved != null && nowMs - saved < LATER_DAYS_MAX_AGE_MS
                    val ask = network && !recent && wanted(src.day)
                    val fresh = if (!ask) null
                        else runCatching { fetch(src.url) }.getOrNull()?.takeIf { it.trimStart().startsWith("{") }
                    if (fresh != null) { fetched.incrementAndGet(); store.saveCached(src.key, fresh, nowMs) }
                    else if (ask) offline.set(true)
                    val text = fresh ?: store.cached(src.key) ?: return@async emptyList()
                    any.set(true)
                    if (src.league == League.NHL) parseNhlSchedule(text) else parseEspnScoreboard(src.league, text)
                } finally { gate.release() }
            }
        }.awaitAll().flatten()
        if (prune) store.prune(sources.mapTo(HashSet()) { it.key })
        Result(matches, offline.get(), any.get(), fetched.get(), (System.nanoTime() - started) / 1_000_000)
    }
}

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
    val index = groupSourceIndex(visible, snapshot ?: GroupSnapshot(0, 1, emptyList()), prefs.groupCopies, home.iptv.tv.live.categoryCountries(cats))
    return index.values.distinctBy { it.id }.mapNotNull { group ->
        val anchor = group.sources.firstOrNull { it.id in visibleIds } ?: return@mapNotNull null
        SportChannel(group.id, group.title, names[anchor.categoryId].orEmpty(), anchor.id)
    }.sortedBy { visibleIds[it.anchorSourceId]?.position ?: Int.MAX_VALUE }
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
)

const val SPORT_REFRESH_MS = 30 * 60 * 1000L
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
) {
    /** Ligues à lire : celles des favorites toujours ; les autres sauf les sports masqués. */
    private fun leagues(t: List<home.iptv.shared.sync.SportTeam>): List<League> {
        val hidden = view()?.hidden.orEmpty()
        val week = fullWeekFor(t)
        return leaguesFor(t).filter { it.sport !in hidden || week(it) }
    }

    private val _ui = MutableStateFlow(SportUi())
    val ui: StateFlow<SportUi> = _ui
    @Volatile private var matches = emptyList<Match>()
    /** Des horaires lus sur le réseau ont été montrés : la mémoire ne doit plus les remplacer. */
    private var fresh = false
    private val matchLock = Any()
    private val todayArrived = CompletableDeferred<Unit>()
    private var refreshing: Job? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
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
        // Masquage depuis l'iPhone : candidats recalculés aussitôt (jamais de chaîne masquée proposée).
        scope.launch { prefs.drop(1).collect { rebuild() } }
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

    fun refresh() {
        if (refreshing?.isActive == true) return
        refreshing = scope.launch(io) {
            if (!_ui.value.loading) _ui.value = _ui.value.copy(updating = true)
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
        val visible = channels()
        val idx = channelIndex?.takeIf { it.first === visible || it.first == visible }?.second
            ?: SportChannelIndex(visible).also { channelIndex = visible to it }
        val t1 = System.nanoTime()
        val fav = favorites()
        val favTeams = teams() ?: DEFAULT_TEAMS
        val v = view() ?: home.iptv.shared.sync.SportView()
        val regions = regionOrder(v.langOrder)
        val ex = extra; val vs = votes; val offset = offsetSeconds()
        // Seuls les matchs de la bande ont des chaînes à l'écran (affiche, « Regarder », choix de la chaîne).
        val strip = stripFor(board, now(), offsetSeconds(), v, STRIP_MAX)
        if (memoFor?.first !== idx || memoFor?.second !== ex) { memo.clear(); memoFor = idx to ex }
        var computed = 0
        val cands = strip.associate { m ->
            val preferred = preferenceKeys(m, favTeams).mapNotNull { store.preferred(profileId, it) }.distinct()
            val key = CandidateKey(m.copy(state = MatchState.UPCOMING, homeScore = null, awayScore = null, detail = null),
                store.learned(profileId, m.league), fav, preferred, confirmed[m.id].orEmpty(), matchVotes(m, vs), regions, offset)
            val kept = memo[m.id]?.takeIf { it.first == key }?.second
            m.id to (kept ?: sportCandidates(m, key.learned, idx, fav, preferred,
                extraIdx = ex, confirmed = key.confirmed, votes = key.votes, regions = regions, offsetSeconds = offset)
                .also { memo[m.id] = key to it; computed++ })
        }
        memo.keys.retainAll(cands.keys)
        lastComputed = computed
        val ui = SportUi(board, cands, loading = false, notice = notice, strip = strip, updating = updating, view = v)
        _ui.value = ui
        if (probe) probeGuides(ui)
        timing("chaînes ${(t1 - t0) / 1_000_000} ms, $computed/${cands.size} matchs ${(System.nanoTime() - t1) / 1_000_000} ms")
    }

    /**
     * Étoile (appui long dans le choix de la chaîne) : [groupId] devient la chaîne préférée des équipes du match
     * (favorites, sinon les deux) ; si elle l'était déjà, l'étoile est retirée.
     */
    fun togglePreferred(match: Match, groupId: String) {
        scope.launch(io) {
            val keys = preferenceKeys(match, teams() ?: DEFAULT_TEAMS)
            val already = keys.all { store.preferred(profileId, it) == groupId }
            keys.forEach { store.savePreferred(profileId, it, if (already) null else groupId) }
            rebuild()
        }
    }

    /**
     * La chaîne regardée assez longtemps depuis un match devient la première proposée pour sa compétition, et la
     * maison retient qu'elle diffuse les matchs de ces équipes (+1).
     */
    fun learned(match: Match, channel: SportChannel) {
        if (!channel.direct) store.saveLearned(profileId, match.league, learn(store.learned(profileId, match.league), channel.groupId))
        scope.launch(io) { vote(match, channel, +1); rebuild() }
    }

    /** « Pas le bon match » : la maison écarte cette chaîne pour ces équipes (-3) ; elle n'est plus proposée. */
    fun wrongChannel(match: Match, channel: SportChannel) {
        scope.launch(io) {
            store.saveLearned(profileId, match.league, store.learned(profileId, match.league).filter { it != channel.groupId })
            confirmed[match.id] = confirmed[match.id].orEmpty() - channel.groupId
            vote(match, channel, -3)
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
