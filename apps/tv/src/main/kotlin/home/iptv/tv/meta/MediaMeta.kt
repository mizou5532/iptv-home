package home.iptv.tv.meta

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import home.iptv.shared.meta.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Mémoire des fiches sur la télé (survit au redémarrage). */
interface MediaMetaStore {
    fun load(): Map<String, MediaMeta>
    fun save(all: Map<String, MediaMeta>)
}

class FileMediaMetaStore(context: android.content.Context) : MediaMetaStore {
    private val file = java.io.File(context.applicationContext.filesDir, "media-meta-2.json")
    override fun load(): Map<String, MediaMeta> = runCatching { decodeMetaMap(file.readText()) }.getOrDefault(emptyMap())
    override fun save(all: Map<String, MediaMeta>) {
        runCatching { val tmp = java.io.File(file.path + ".tmp"); tmp.writeText(encodeMetaMap(all)); tmp.renameTo(file) }
    }
}

const val META_BATCH = 40
const val META_DEBOUNCE_MS = 60L
const val META_RETRY_MS = 15_000L
const val META_MAX_TRIES = 6
/** Attente maximale de la fiche officielle avant de se rabattre sur l'image du fournisseur (1,2 s). */
const val META_FALLBACK_WAIT_MS = 1_200L
/** Fiche trouvée : redemandée après 7 jours (note IMDb) ; introuvable : après 3 jours. */
const val META_OK_TTL_MS = 7L * 24 * 3600 * 1000
const val META_MISS_TTL_MS = 3L * 24 * 3600 * 1000
const val META_MAX_KEPT = 6000

/**
 * Fiches demandées par l'écran ([request]) et regroupées en lots ; réponses gardées en mémoire et sur le disque.
 * Le serveur cherche peu de titres nouveaux par appel : ceux « en attente » sont redemandés quelques fois.
 */
class MediaMetaRepository(
    private val fetch: suspend (List<MediaRef>) -> List<MediaMeta?>,
    private val store: MediaMetaStore,
    private val scope: CoroutineScope,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val debounceMs: Long = META_DEBOUNCE_MS,
    private val retryMs: Long = META_RETRY_MS,
) {
    private val _state = MutableStateFlow<Map<String, MediaMeta>>(emptyMap())
    val state: StateFlow<Map<String, MediaMeta>> = _state
    /** Clés dont au moins un premier appel serveur est terminé (même si la réponse était « pending » ou en panne). */
    private val _tried = MutableStateFlow<Set<String>>(emptySet())
    val tried: StateFlow<Set<String>> = _tried
    private val lock = Any()
    private val pending = LinkedHashMap<String, MediaRef>()
    private val tries = HashMap<String, Int>()
    private var flushing: Job? = null
    private var loaded = false

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val disk = store.load()
        if (disk.isNotEmpty()) {
            _state.value = disk + _state.value
            _tried.value = _tried.value + disk.keys
        }
    }

    private fun fresh(m: MediaMeta?): Boolean = m != null && when (m.status) {
        "ok" -> now() - m.at < META_OK_TTL_MS
        "not_found" -> now() - m.at < META_MISS_TTL_MS
        else -> false
    }

    /** Fiches déjà connues (mémoire et disque), sans rien demander. */
    fun known(): Map<String, MediaMeta> { synchronized(lock) { ensureLoaded() }; return _state.value }

    /** Fiche déjà connue pour [key] (mémoire et disque), lue dès la 1re image sans attendre un effet. */
    fun peek(key: String): MediaMeta? { synchronized(lock) { ensureLoaded() }; return _state.value[key] }

    /** Demande la fiche (sans effet si elle est connue et récente). */
    fun request(ref: MediaRef) {
        if (ref.title.isBlank()) return
        synchronized(lock) {
            ensureLoaded()
            if (fresh(_state.value[ref.key]) || ref.key in pending || (tries[ref.key] ?: 0) >= META_MAX_TRIES) return
            pending[ref.key] = ref
            if (flushing?.isActive == true) return
            flushing = scope.launch { delay(debounceMs); flush() }
        }
    }

    /** Prépare un lot de fiches d'avance (dès qu'une rangée est chargée, avant même son dessin). */
    fun requestAll(refs: Iterable<MediaRef>) {
        refs.forEach(::request)
    }

    private suspend fun flush() {
        while (true) {
            val batch = synchronized(lock) { pending.values.take(META_BATCH).also { b -> b.forEach { pending.remove(it.key); tries[it.key] = (tries[it.key] ?: 0) + 1 } } }
            if (batch.isEmpty()) return
            val result = runCatching { fetch(batch) }.getOrNull()
            val stamp = now()
            val got = HashMap<String, MediaMeta>()
            val again = ArrayList<MediaRef>()
            batch.forEachIndexed { i, ref ->
                val m = result?.getOrNull(i)
                if (m != null && m.status != "pending") got[ref.key] = m.copy(at = stamp) else again += ref
            }
            if (got.isNotEmpty()) {
                val next = synchronized(lock) { (_state.value + got).let { if (it.size > META_MAX_KEPT) it.entries.sortedByDescending { e -> e.value.at }.take(META_MAX_KEPT).associate { e -> e.key to e.value } else it } }
                store.save(next)
                _state.value = next
            }
            _tried.value = _tried.value + batch.mapTo(HashSet()) { it.key }
            // En attente (ou réseau en panne) : redemandées un peu plus tard, quelques fois au plus.
            if (again.isNotEmpty()) scope.launch { delay(retryMs); again.forEach { request(it) } }
        }
    }
}

/** Fiches de l'app ; null (tests, aperçus) = jamais de fiche, les images du fournisseur restent. */
val LocalMediaMeta = staticCompositionLocalOf<MediaMetaRepository?> { null }

/**
 * Choix d'image d'un titre : l'image officielle (TMDB) d'abord ; l'image du fournisseur sert uniquement de secours
 * quand on sait qu'il n'y a pas d'image officielle ([useFallback] vrai : pas de dépôt, fiche introuvable, image
 * absente de la fiche, ou serveur qui n'a pas répondu dans [META_FALLBACK_WAIT_MS]), jamais en bouche-trou pendant une
 * fraction de seconde avant l'arrivée de l'image officielle.
 *
 * Si le secours a dû être affiché avant l'arrivée d'une fiche officielle ([fallbackLocked]), la carte garde l'image
 * de secours tant qu'elle reste à l'écran pour ne jamais remplacer l'image sous les yeux de l'utilisateur.
 */
data class MetaChoice(
    val meta: MediaMeta?,
    val useFallback: Boolean,
    val fallbackLocked: Boolean = false,
) {
    fun poster(size: String, fallback: () -> String?): String? {
        val tmdb = tmdbImage(meta?.poster, size)
        if (fallbackLocked) return fallback() ?: tmdb
        return tmdb ?: if (useFallback) fallback() else null
    }

    fun backdrop(size: String, fallback: () -> String?): String? {
        val tmdb = tmdbImage(meta?.backdrop, size)
        if (fallbackLocked) return fallback() ?: tmdb
        return tmdb ?: if (useFallback) fallback() else null
    }
}

/** Fiche TMDB et règle de repli de [ref] ; dès la 1re image si la fiche est déjà sur le disque. */
@android.annotation.SuppressLint("StateFlowValueCalledInComposition")
@Composable
fun rememberMetaChoice(ref: MediaRef?): MetaChoice {
    val repo = LocalMediaMeta.current
    if (repo == null || ref == null || ref.title.isBlank()) return MetaChoice(null, useFallback = true)
    val key = ref.key
    val initial = androidx.compose.runtime.remember(repo, key) { repo.peek(key) }
    val fastScroll = home.iptv.tv.feature.LocalFastScroll.current
    // Pendant un défilement rapide, attend l'arrêt avant de demander les fiches absentes du cache disque.
    LaunchedEffect(repo, key, fastScroll) {
        if (!fastScroll || initial != null) repo.request(ref)
    }
    // Seule la fiche de ce titre est suivie : l'arrivée des autres ne redessine pas cette image.
    val flow = androidx.compose.runtime.remember(repo, key) { repo.state.mapKey(key) }
    val collected by flow.collectAsState(initial = initial)
    val entry = collected ?: repo.peek(key)
    val triedFlow = androidx.compose.runtime.remember(repo, key) { repo.tried.map { key in it }.distinctUntilChanged() }
    val wasTried by triedFlow.collectAsState(initial = key in repo.tried.value)
    var timedOut by androidx.compose.runtime.remember(repo, key) { androidx.compose.runtime.mutableStateOf(false) }
    val settled = entry != null || wasTried || timedOut
    LaunchedEffect(repo, key, settled, fastScroll) {
        if (!settled && !fastScroll) {
            delay(META_FALLBACK_WAIT_MS)
            timedOut = true
        }
    }
    var locked by androidx.compose.runtime.remember(repo, key) { androidx.compose.runtime.mutableStateOf(false) }
    if (settled && entry == null && !locked) locked = true
    return MetaChoice(meta = entry?.takeIf { it.found }, useFallback = settled, fallbackLocked = locked)
}

/** Fiche TMDB de [ref] si connue (demandée au besoin) ; null tant qu'il n'y en a pas. */
@Composable
fun rememberMeta(ref: MediaRef?): MediaMeta? = rememberMetaChoice(ref).meta

private fun StateFlow<Map<String, MediaMeta>>.mapKey(key: String) = map { it[key] }.distinctUntilChanged()
