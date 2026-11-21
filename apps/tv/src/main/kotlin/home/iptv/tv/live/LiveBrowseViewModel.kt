package home.iptv.tv.live

import home.iptv.shared.groups.*
import home.iptv.tv.data.LocalStore
import home.iptv.shared.catalog.CatalogRepository
import home.iptv.shared.catalog.RefreshReport
import home.iptv.shared.sync.ProfilePrefs
import home.iptv.shared.xtream.LiveCategory
import home.iptv.shared.xtream.LiveStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException

/** Chaînes chargées pour la section [key] ; vide tant qu'aucune section n'est chargée. */
data class RevealChannel(val categoryId: String, val sourceId: String)

data class SectionStreams(val key: String, val streams: List<LiveStream>, val rows: List<GroupRow> = emptyList())

/**
 * Affiche le cache tout de suite, rafraîchit en arrière-plan (une fois par démarrage), puis met à jour.
 * L'organisation du profil ([prefs] : favoris, playlists, masqués, ordre) est appliquée au cache à chaque changement ;
 * sans préférences (profil neuf, télé hors ligne au premier lancement), les catégories sont dans l'ordre du fournisseur.
 * Après le rafraîchissement, [publish] envoie la copie du catalogue au téléphone, hors du fil principal et sans
 * jamais retenir [refreshing] ni le rechargement des catégories.
 */
class LiveBrowseViewModel(
    private val repo: CatalogRepository,
    /** `null` : le cache a déjà été rafraîchi dans ce processus, on se contente de l'afficher. */
    private val refresh: (suspend (onProgress: suspend (done: Int, total: Int) -> Unit) -> RefreshReport)?,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val prefs: StateFlow<ProfilePrefs?> = MutableStateFlow(null),
    private val publish: suspend (RefreshReport) -> Unit = {},
    private val groupSnapshot: StateFlow<GroupSnapshot?> = MutableStateFlow(null),
    private val store: LocalStore? = null,
    private val profileId: String? = null,
    /**
     * Filet de sécurité : au-delà, la mise à jour est abandonnée et « Mise à jour » s'efface quoi qu'il arrive
     * (le cache reste affiché). [LiveSessions] arrête le travail lui-même un peu avant.
     */
    private val refreshDeadlineMs: Long = REFRESH_DEADLINE_MS + 30_000L,
    /** Fiabilité des copies (ce qui a marché ou coupé ces derniers jours) ; `null` = ordre du fournisseur. */
    val reliability: CopyReliability? = null,
) {
    private val _sections = MutableStateFlow<List<LiveSection>>(emptyList())
    private val _categories = MutableStateFlow<List<LiveCategory>>(emptyList())
    private val _streams = MutableStateFlow<List<LiveStream>>(emptyList())
    private val _loaded = MutableStateFlow(SectionStreams("", emptyList()))
    private val _selectedKey = MutableStateFlow<String?>(null)
    private val _selectedId = MutableStateFlow<String?>(null)
    private val _refreshing = MutableStateFlow(false)
    private val _refreshNotice = MutableStateFlow<String?>(null)
    private var noticeJob: Job? = null
    private val _groupsRevision = MutableStateFlow(0L)

    /** Favoris, playlists puis catégories visibles du profil, dans l'ordre d'affichage. */
    val sections: StateFlow<List<LiveSection>> = _sections
    val selectedKey: StateFlow<String?> = _selectedKey

    /** Catégories visibles seulement (sans favoris ni playlists), et l'identifiant de la catégorie choisie, s'il y en a une. */
    val categories: StateFlow<List<LiveCategory>> = _categories
    val selectedId: StateFlow<String?> = _selectedId
    val streams: StateFlow<List<LiveStream>> = _streams

    /** Les chaînes avec la section à laquelle elles appartiennent : l'écran ne les montre que si la clé est celle choisie. */
    val loaded: StateFlow<SectionStreams> = _loaded
    val refreshing: StateFlow<Boolean> = _refreshing
    private val _listShown = MutableStateFlow(false)
    /** Vrai une fois la liste enregistrée lue et affichée (avant : « Chargement… », jamais « Aucune chaîne »). */
    val listShown: StateFlow<Boolean> = _listShown

    /** Message bref après une mise à jour ratée, arrêtée ou incomplète ; `null` sinon. N'empêche jamais de naviguer. */
    val refreshNotice: StateFlow<String?> = _refreshNotice

    /** Change a chaque reconstruction des groupes (masques, corrections, catalogue) : la lecture en cours doit etre reverifiee. */
    val groupsRevision: StateFlow<Long> = _groupsRevision

    @Volatile private var started = false
    @Volatile private var pendingRefresh: (suspend (onProgress: suspend (done: Int, total: Int) -> Unit) -> RefreshReport)? = null
    private var streamsJob: Job? = null
    private var groupSources: List<GroupSource> = emptyList()
    private var groupIndex: Map<String, LiveGroup> = emptyMap()
    private var providerCategories: List<LiveCategory> = emptyList()
    private val viewLock = Mutex()
    private var catalogGeneration = 0L
    private data class BrowseInputs(val prefs: ProfilePrefs?, val snapshot: GroupSnapshot?, val catalogGeneration: Long)
    private var indexInputs: BrowseInputs? = null
    private fun inputs() = BrowseInputs(prefs.value, groupSnapshot.value, catalogGeneration)

    private val pendingReveal = MutableStateFlow<RevealChannel?>(null)
    val revealTarget: StateFlow<RevealChannel?> = pendingReveal
    private val revealMessage = MutableStateFlow<String?>(null)
    val revealNotice: StateFlow<String?> = revealMessage

    fun reveal(categoryId: String, sourceId: String) {
        revealMessage.value = null
        val p = prefs.value
        val hidden = p?.hidden.orEmpty()
        if (p == null || hidden.any { (it.itemType == "live_category" && it.itemId == categoryId) || (it.itemType == "channel" && it.itemId == sourceId) } || _sections.value.none { it.key == "cat:$categoryId" }) {
            refuseReveal(); return
        }
        pendingReveal.value = RevealChannel(categoryId, sourceId)
        selectSection("cat:$categoryId")
    }

    fun refuseReveal() { pendingReveal.value = null; revealMessage.value = "Chaîne indisponible." }

    /** Consommé après le défilement et le focus ; les masques et le groupe sont relus avant l'aperçu. */
    suspend fun consumeReveal(target: RevealChannel): GroupRow? {
        if (pendingReveal.value != target) return null
        val row = _loaded.value.takeIf { it.key == "cat:${target.categoryId}" }?.rows
            ?.firstOrNull { row -> row.group.sources.any { it.id == target.sourceId } }
        val group = groupOf(target.sourceId)
        if (row == null || group == null || group.id != row.group.id || _selectedKey.value != "cat:${target.categoryId}" ||
            prefs.value?.hidden?.any { it.itemType == "live_category" && it.itemId == target.categoryId } != false) {
            refuseReveal(); return null
        }
        pendingReveal.value = null
        return row
    }

    fun select(categoryId: String) = selectSection("cat:$categoryId")

    /**
     * Le focus passe sur une section : elle est sélectionnée tout de suite (titre, surlignage), mais ses chaînes ne sont
     * lues qu'après [FOCUS_SETTLE_MS] sans autre mouvement (un défilement rapide ne lit que la section où l'on s'arrête).
     * Une section déjà vue s'affiche sans attendre (mémoire des dernières sections).
     */
    fun focusSection(key: String) = choose(key, FOCUS_SETTLE_MS)

    /** OK sur une section : lecture immédiate. */
    fun selectSection(key: String) = choose(key, 0)

    private fun choose(key: String, waitMs: Long) {
        if (key == _selectedKey.value && _streams.value.isNotEmpty()) return
        val section = _sections.value.firstOrNull { it.key == key } ?: return
        setSelected(key)
        val cached = cachedStreams(key)
        if (cached != null) {
            streamsJob?.cancel()
            publishStreams(key, cached)
            return
        }
        loadStreams(section, waitMs)
    }

    private val cacheLock = Any()
    private var cacheInputs: BrowseInputs? = null
    /** Chaînes des dernières sections, valables pour un état donné (catalogue, masques, groupes). */
    private val sectionCache = object : LinkedHashMap<String, List<LiveStream>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<LiveStream>>?) = size > SECTION_CACHE
    }

    private fun cachedStreams(key: String): List<LiveStream>? = synchronized(cacheLock) {
        val now = inputs()
        if (cacheInputs != now || indexInputs != now) null else sectionCache[key]
    }

    private fun resetCache(inputs: BrowseInputs) = synchronized(cacheLock) {
        if (cacheInputs != inputs) { sectionCache.clear(); cacheInputs = inputs }
    }

    fun start() {
        synchronized(refreshGate) {
            if (started) return
            started = true
        }
        scope.launch { prefs.collect { rebuild() } }
        scope.launch { groupSnapshot.collect { rebuild() } }
        scope.launch {
            reload()
            // Une demande arrivée avant l'ouverture (liste vieillie, catégorie réaffichée) passe devant celle du démarrage.
            val fn = synchronized(refreshGate) {
                val chosen = pendingRefresh ?: refresh
                pendingRefresh = null
                if (chosen != null) _refreshing.value = true
                chosen
            } ?: return@launch
            runRefresh(fn)
        }
    }

    private val refreshGate = Any()

    /**
     * Nouvelle mise à jour en arrière-plan (liste ancienne au retour de veille, catégorie réaffichée sur l'iPhone) :
     * même déroulé qu'au démarrage, cache affiché pendant ; sans effet si une mise à jour tourne déjà.
     */
    fun refreshNow(fn: suspend (onProgress: suspend (done: Int, total: Int) -> Unit) -> RefreshReport) {
        synchronized(refreshGate) {
            // Direct pas encore ouvert pour ce profil (il regarde une série) : la mise à jour se fera à son ouverture.
            if (!started) { pendingRefresh = fn; return }
            if (_refreshing.value) return
            _refreshing.value = true
        }
        scope.launch { runRefresh(fn) }
    }

    private suspend fun runRefresh(refresh: suspend (onProgress: suspend (done: Int, total: Int) -> Unit) -> RefreshReport) {
        run {
            _refreshing.value = true
            lastReloadDone = 0
            hadCache = providerCategories.isNotEmpty()
            var report: RefreshReport? = null
            var notice: String? = null
            try {
                report = withTimeout(refreshDeadlineMs) { withContext(io) { refresh(::onProgress) } }
                if (report.failedCategories.isNotEmpty()) notice = refreshPartial(report.failedCategories.size)
            } catch (e: TimeoutCancellationException) {
                // Jamais de « Mise à jour » sans fin (essai TCL 0.4.0) : le travail est annulé, le cache reste affiché.
                notice = REFRESH_TOO_LONG
            } catch (e: CancellationException) {
                throw e
            } catch (e: RefreshTooLong) {
                notice = REFRESH_TOO_LONG
            } catch (e: Exception) {
                // Fournisseur ou liste des masquées injoignable : le cache reste affiché, le souci est signalé.
                notice = REFRESH_FAILED
            } finally {
                _refreshing.value = false
            }
            if (notice != null) showNotice(notice)
            reload()
            if (report != null) publishCatalog(report)
        }
    }

    /** Le message reste [NOTICE_MS] dans la barre du haut, puis s'efface ; il ne prend jamais le focus. */
    private fun showNotice(text: String) {
        noticeJob?.cancel()
        _refreshNotice.value = text
        noticeJob = scope.launch {
            delay(NOTICE_MS)
            if (_refreshNotice.value == text) _refreshNotice.value = null
        }
    }

    private suspend fun publishCatalog(report: RefreshReport) {
        try {
            withContext(io) { publish(report) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Publication interrompue : sans conséquence pour l'affichage, reprise au prochain rafraîchissement.
        }
    }

    /** Des chaînes étaient déjà enregistrées au début de la mise à jour : pas de relecture avant la fin. */
    @Volatile private var hadCache = false
    private var lastReloadMs = 0L
    private var lastReloadDone = 0

    /**
     * Pendant un long rafraîchissement (premier lancement : des centaines de catégories une par une), relit le cache
     * après la première catégorie, puis au plus toutes les [RELOAD_EVERY_MS]. Chaque relecture reconstruit tout le
     * catalogue (groupes compris) : une par seconde rendait le rafraîchissement interminable et bloquait l'écran.
     */
    private suspend fun onProgress(done: Int, total: Int) {
        // Liste déjà affichée : elle reste jusqu'à la fin (chaque relecture regroupe tout le catalogue, lourd sur la télé).
        if (hadCache) return
        val now = nowMs()
        val due = lastReloadDone == 0 || now - lastReloadMs >= RELOAD_EVERY_MS
        if (!due) return
        lastReloadDone = done
        lastReloadMs = now
        reload()
    }

    private suspend fun reload() {
        val (categories, sources) = withContext(io) { repo.categories() to repo.allGroupSources() }
        providerCategories = categories
        groupSources = sources
        catalogGeneration++
        cacheRead = true
        rebuild()
    }

    /** Ce dont dépendent les copies regroupées (pas les favoris ni l'ordre) ; index gardé tant qu'il ne change pas. */
    private data class IndexKey(val catalog: Long, val hiddenChannels: Set<String>, val hiddenCategories: Set<String>, val snapshot: GroupSnapshot, val groupCopies: Boolean)
    @Volatile private var lastIndex: Pair<IndexKey, Map<String, LiveGroup>>? = null

    /** Vrai une fois le cache lu au moins une fois : avant, une reconstruction n'aurait ni catégories ni groupes. */
    @Volatile private var cacheRead = false

    /**
     * Recalcule les sections depuis le cache et les préférences courantes, et recharge la section affichée.
     * Rien n'est publié avant la première lecture du cache : sinon les préférences, arrivées avant, affichaient
     * « Favoris » seul et vide (index des groupes encore vide) le temps de lire un gros catalogue (essai TCL 0.4.0).
     */
    private suspend fun rebuild() = viewLock.withLock {
        if (!cacheRead) return@withLock
        while (true) {
            val captured = inputs()
            val p = captured.prefs
            val snapshot = captured.snapshot?.takeIf { it.rulesVersion == 1 } ?: GroupSnapshot(0, 1, emptyList())
            val sources = groupSources
            val categories = providerCategories
            val index = withContext(io) {
                val channels = p?.hidden.orEmpty().filter { it.itemType == "channel" }.mapTo(HashSet()) { it.itemId }
                val hiddenCategories = p?.hidden.orEmpty().filter { it.itemType == "live_category" }.mapTo(HashSet()) { it.itemId }
                // Favoris, ordre, playlists : les copies ne changent pas, l'index précédent sert tel quel.
                val key = IndexKey(captured.catalogGeneration, channels, hiddenCategories, snapshot, p?.groupCopies != false)
                lastIndex?.takeIf { it.first == key }?.second
                    ?: groupSourceIndex(
                        sources.filter { it.id !in channels && it.categoryId !in hiddenCategories },
                        snapshot,
                        p?.groupCopies != false,
                        categoryCountries(categories),
                        precomputed = repo.liveKeys()::get,
                    ).also { lastIndex = key to it }
            }
            if (inputs() != captured) continue
            val sections = buildSections(categories, p)
            val selectedBefore = _selectedKey.value
            val current = sections.firstOrNull { it.key == selectedBefore } ?: sections.firstOrNull()
            val list = if (current == null) emptyList() else withContext(io) { sectionStreams(current, p) }
            // Rien du calcul perime n'est publie, meme temporairement : masques, corrections et catalogue.
            if (inputs() != captured || _selectedKey.value != selectedBefore) continue
            groupIndex = index
            indexInputs = captured
            resetCache(captured)
            _groupsRevision.value++
            _sections.value = sections
            _listShown.value = true
            _categories.value = sections.filterIsInstance<LiveSection.Category>().map { LiveCategory(it.id, it.title) }
            setSelected(current?.key)
            publishStreams(current?.key ?: "", list)
            break
        }
    }

    private fun publishStreams(key: String, list: List<LiveStream>) {
        synchronized(cacheLock) { if (key.isNotEmpty() && cacheInputs == indexInputs) sectionCache[key] = list }
        val index = groupIndex
        val rows = rowsFromIds(list.map { it.id }, index)
        val anchors = list.associateBy { it.id }
        val displayed = rows.mapNotNull { anchors[it.anchorSourceId] }
        _loaded.value = SectionStreams(key, displayed, rows)
        _streams.value = displayed
    }

    private fun setSelected(key: String?) {
        // Autre section : on n'expose plus les chaînes de la précédente pendant le chargement.
        if (key != _selectedKey.value) _streams.value = emptyList()
        _selectedKey.value = key
        _selectedId.value = key?.takeIf { it.startsWith("cat:") }?.removePrefix("cat:")
    }

    private fun sectionStreams(section: LiveSection, p: ProfilePrefs?): List<LiveStream> {
        val raw = when (section) {
            is LiveSection.Category -> repo.streams(section.id)
            is LiveSection.Favorites -> repo.streamsByIds(p?.favorites.orEmpty())
            is LiveSection.Playlist -> repo.streamsByIds(p?.playlists?.firstOrNull { it.id == section.id }?.items.orEmpty())
        }
        return visibleStreams(raw, p)
    }

    /** OK seulement : relit la visibilite courante, puis le choix manuel encore disponible. */
    suspend fun playbackSource(anchorSourceId: String, sourceId: String? = null): LiveStream? {
        val row = _loaded.value.rows.firstOrNull { it.anchorSourceId == anchorSourceId } ?: return null
        return playbackFor(row, sourceId) { _loaded.value.rows.any { it.group.id == row.group.id && it.anchorSourceId == row.anchorSourceId } }
    }

    /**
     * Copie concrete a lire pour une ligne de n'importe quelle section (lecture lancee depuis la liste ou en plein
     * ecran). Meme garanties : masque gagne, un favori concret masque ne ressuscite pas, la derniere bonne copie
     * n'est reprise que si elle est encore visible. [stillCurrent] verifie que la ligne n'a pas change pendant l'attente.
     */
    suspend fun playbackFor(
        row: GroupRow,
        sourceId: String? = null,
        stillCurrent: () -> Boolean = { groupIndex[row.anchorSourceId]?.id == row.group.id },
    ): LiveStream? {
        // Un changement de preferences pendant l'attente donne un nouvel essai (un seul), jamais une erreur ;
        // chaque essai relit la visibilite, donc une copie masquee entre-temps n'est jamais lue.
        repeat(2) {
            val p = prefs.value
            // Copies actuelles du groupe (l'iPhone a pu en ajouter, l'identifiant du groupe etant conserve).
            val group = groupIndex[row.anchorSourceId]?.takeIf { it.id == row.group.id } ?: row.group
            val candidates = withContext(io) { visibleStreams(repo.streamsByIds(group.sources.map { it.id }), p) }
            // Un favori concret masque ne peut jamais ressusciter par une autre copie.
            if (candidates.none { it.id == row.anchorSourceId }) return null
            val remembered = if (sourceId == null && profileId != null)
                withContext(io) { store?.lastGoodSource(profileId, row.group.id) } else null
            if (prefs.value != p || !stillCurrent()) return@repeat
            if (sourceId != null) return candidates.firstOrNull { it.id == sourceId }
            // La dernière bonne copie d'abord, puis l'ordre du fournisseur ; la fiabilité des derniers jours passe
            // devant (une copie qui coupe souvent recule, même si elle a marché la dernière fois).
            val order = candidates.sortedBy { if (it.id == remembered) 0 else 1 }
            val best = reliability?.let { r -> withContext(io) { r.rank(order.map { it.id }) }.firstOrNull() }
            return order.firstOrNull { it.id == best } ?: order.firstOrNull()
        }
        return null
    }

    /** Lignes visibles d'une section, dans l'ordre affiche ; `null` si la section n'existe plus. */
    suspend fun sectionRows(key: String): List<GroupRow>? {
        val section = _sections.value.firstOrNull { it.key == key } ?: return null
        val p = prefs.value
        val list = withContext(io) { sectionStreams(section, p) }
        return rowsFromIds(list.map { it.id }, groupIndex)
    }

    /** Groupe visible actuel d'une copie ; `null` si elle est masquee ou retiree (masque gagne, meme avant la reconstruction). */
    /**
     * Sources à interroger pour le guide de [sourceId] : elle-même si le fournisseur lui relie un guide
     * (`epg_channel_id`), puis les autres copies visibles de son groupe qui en ont un (un guide distinct chacune).
     * Sans aucun guide connu : elle seule (le panneau peut quand même répondre).
     */
    suspend fun guideCandidates(sourceId: String): List<String> {
        val group = groupIndex[sourceId] ?: return listOf(sourceId)
        val streams = withContext(io) { repo.streamsByIds(listOf(sourceId) + group.sources.map { it.id }) }
        val self = streams.firstOrNull { it.id == sourceId }
        val others = streams.filter { it.id != sourceId && !it.epgChannelId.isNullOrBlank() }
            .distinctBy { it.epgChannelId }.filter { it.epgChannelId != self?.epgChannelId }
        val selfFirst = self == null || !self.epgChannelId.isNullOrBlank() || others.isEmpty()
        return ((if (selfFirst) listOf(sourceId) else emptyList()) + others.map { it.id }).distinct()
    }

    suspend fun groupOf(sourceId: String): LiveGroup? {
        val p = prefs.value
        val visible = withContext(io) { visibleStreams(repo.streamsByIds(listOf(sourceId)), p) }
        return if (visible.isEmpty()) null else groupIndex[sourceId]
    }

    /** A appeler uniquement apres une lecture reussie, jamais au focus ou lors d'un echec. */
    suspend fun saveLastGoodSource(groupId: String, sourceId: String) {
        val profile = profileId ?: return
        val group = groupIndex[sourceId]?.takeIf { it.id == groupId } ?: return
        val p = prefs.value
        val visible = withContext(io) { visibleStreams(repo.streamsByIds(listOf(sourceId)), p) }
        if (visible.isNotEmpty() && prefs.value == p && groupIndex[sourceId]?.id == groupId) withContext(io) { store?.saveLastGoodSource(profile, group.id, sourceId) }
    }

    /** La session remplacee ne retient plus le catalogue global ni les copies des groupes. */
    internal fun releaseGroups() {
        groupSources = emptyList()
        groupIndex = emptyMap()
        lastIndex = null
        indexInputs = null
        catalogGeneration++
        _groupsRevision.value++
        _loaded.value = _loaded.value.copy(rows = emptyList())
    }

    private fun loadStreams(section: LiveSection, waitMs: Long = 0) {
        streamsJob?.cancel()
        streamsJob = scope.launch {
            if (waitMs > 0) kotlinx.coroutines.delay(waitMs)
            viewLock.withLock {
                val captured = inputs()
                // Une reconstruction en attente doit fournir l'index correspondant aux masques actuels.
                if (indexInputs != captured) return@withLock
                val list = withContext(io) { sectionStreams(section, captured.prefs) }
                if (_selectedKey.value == section.key && inputs() == captured && indexInputs == captured) {
                    publishStreams(section.key, list)
                }
            }
        }
    }

}

private const val RELOAD_EVERY_MS = 15_000L
private const val FOCUS_SETTLE_MS = 150L
private const val SECTION_CACHE = 24

/** Durée maximale d'une mise à jour du catalogue ; au-delà elle est arrêtée (le cache reste affiché). */
internal const val REFRESH_DEADLINE_MS = 10 * 60_000L
private const val NOTICE_MS = 30_000L

/** Mise à jour arrêtée parce qu'elle durait trop (voir [REFRESH_DEADLINE_MS]). */
class RefreshTooLong : java.io.IOException("mise à jour du catalogue trop longue")

/** Textes de la barre du haut après une mise à jour (français simple, pour le propriétaire). */
internal const val REFRESH_FAILED = "Mise à jour impossible : chaînes déjà enregistrées"
internal const val REFRESH_TOO_LONG = "Mise à jour trop longue, arrêtée : chaînes déjà enregistrées"
internal fun refreshPartial(categories: Int) =
    if (categories == 1) "Mise à jour incomplète : 1 catégorie sans réponse" else "Mise à jour incomplète : $categories catégories sans réponse"
