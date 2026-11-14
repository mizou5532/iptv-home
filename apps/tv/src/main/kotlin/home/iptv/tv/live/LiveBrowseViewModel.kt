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
import kotlin.coroutines.cancellation.CancellationException

/** Chaînes chargées pour la section [key] ; vide tant qu'aucune section n'est chargée. */
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
) {
    private val _sections = MutableStateFlow<List<LiveSection>>(emptyList())
    private val _categories = MutableStateFlow<List<LiveCategory>>(emptyList())
    private val _streams = MutableStateFlow<List<LiveStream>>(emptyList())
    private val _loaded = MutableStateFlow(SectionStreams("", emptyList()))
    private val _selectedKey = MutableStateFlow<String?>(null)
    private val _selectedId = MutableStateFlow<String?>(null)
    private val _refreshing = MutableStateFlow(false)

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

    private var started = false
    private var streamsJob: Job? = null
    private var groupSources: List<GroupSource> = emptyList()
    private var groupIndex: Map<String, LiveGroup> = emptyMap()
    private var providerCategories: List<LiveCategory> = emptyList()
    private val viewLock = Mutex()

    fun select(categoryId: String) = selectSection("cat:$categoryId")

    fun selectSection(key: String) {
        if (key == _selectedKey.value && _streams.value.isNotEmpty()) return
        val section = _sections.value.firstOrNull { it.key == key } ?: return
        setSelected(key)
        loadStreams(section)
    }

    fun start() {
        if (started) return
        started = true
        scope.launch { prefs.collect { rebuild() } }
        scope.launch { groupSnapshot.collect { rebuild() } }
        scope.launch {
            reload()
            if (refresh == null) return@launch
            _refreshing.value = true
            var report: RefreshReport? = null
            try {
                report = withContext(io) { refresh(::onProgress) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Fournisseur ou liste des masquées injoignable : le cache reste affiché.
            } finally {
                _refreshing.value = false
            }
            reload()
            if (report != null) publishCatalog(report)
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

    private var lastReloadMs = 0L
    private var lastReloadDone = 0

    /**
     * Pendant un long rafraîchissement (premier lancement : des centaines de catégories une par une),
     * relit le cache après la première catégorie, puis toutes les [RELOAD_EVERY] catégories ou ~1 s.
     */
    private suspend fun onProgress(done: Int, total: Int) {
        val now = nowMs()
        val due = lastReloadDone == 0 || done - lastReloadDone >= RELOAD_EVERY || now - lastReloadMs >= RELOAD_EVERY_MS
        if (!due) return
        lastReloadDone = done
        lastReloadMs = now
        reload()
    }

    private suspend fun reload() {
        val (categories, sources) = withContext(io) { repo.categories() to repo.allGroupSources() }
        providerCategories = categories
        groupSources = sources
        rebuild()
    }

    /** Recalcule les sections depuis le cache et les préférences courantes, et recharge la section affichée. */
    private suspend fun rebuild() = viewLock.withLock {
        val p = prefs.value
        val snapshot = groupSnapshot.value?.takeIf { it.rulesVersion == 1 } ?: GroupSnapshot(0, 1, emptyList())
        groupIndex = withContext(io) {
            val channels = p?.hidden.orEmpty().filter { it.itemType == "channel" }.mapTo(HashSet()) { it.itemId }
            val categories = p?.hidden.orEmpty().filter { it.itemType == "live_category" }.mapTo(HashSet()) { it.itemId }
            groupSourceIndex(groupSources.filter { it.id !in channels && it.categoryId !in categories }, snapshot, p?.groupCopies != false)
        }
        val sections = buildSections(providerCategories, p)
        _sections.value = sections
        _categories.value = sections.filterIsInstance<LiveSection.Category>().map { LiveCategory(it.id, it.title) }
        val current = sections.firstOrNull { it.key == _selectedKey.value } ?: sections.firstOrNull()
        setSelected(current?.key)
        if (current == null) {
            publishStreams("", emptyList())
        } else {
            val list = withContext(io) { sectionStreams(current, p) }
            // Une sélection faite pendant la lecture du cache l'emporte.
            if (_selectedKey.value == current.key) publishStreams(current.key, list)
        }
    }

    private fun publishStreams(key: String, list: List<LiveStream>) {
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
        val p = prefs.value
        val candidates = withContext(io) { visibleStreams(repo.streamsByIds(row.group.sources.map { it.id }), p) }
        // Un favori concret masque ne peut jamais ressusciter par une autre copie.
        if (candidates.none { it.id == anchorSourceId }) return null
        val remembered = if (sourceId == null && profileId != null)
            withContext(io) { store?.lastGoodSource(profileId, row.group.id) } else null
        if (prefs.value != p || _loaded.value.rows.none { it == row }) return null
        if (sourceId != null) return candidates.firstOrNull { it.id == sourceId }
        return candidates.firstOrNull { it.id == remembered } ?: candidates.firstOrNull { it.id == anchorSourceId }
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
        _loaded.value = _loaded.value.copy(rows = emptyList())
    }

    private fun loadStreams(section: LiveSection) {
        streamsJob?.cancel()
        streamsJob = scope.launch {
            val p = prefs.value
            val list = withContext(io) { sectionStreams(section, p) }
            if (_selectedKey.value == section.key && prefs.value == p) publishStreams(section.key, list)
        }
    }
}

private const val RELOAD_EVERY = 25
private const val RELOAD_EVERY_MS = 1000L
