package home.iptv.tv.meta

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
const val META_DEBOUNCE_MS = 250L
const val META_RETRY_MS = 15_000L
const val META_MAX_TRIES = 6
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
    private val lock = Any()
    private val pending = LinkedHashMap<String, MediaRef>()
    private val tries = HashMap<String, Int>()
    private var flushing: Job? = null
    private var loaded = false

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val disk = store.load()
        if (disk.isNotEmpty()) _state.value = disk + _state.value
    }

    private fun fresh(m: MediaMeta?): Boolean = m != null && when (m.status) {
        "ok" -> now() - m.at < META_OK_TTL_MS
        "not_found" -> now() - m.at < META_MISS_TTL_MS
        else -> false
    }

    /** Fiches déjà connues (mémoire et disque), sans rien demander. */
    fun known(): Map<String, MediaMeta> { synchronized(lock) { ensureLoaded() }; return _state.value }

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
            // En attente (ou réseau en panne) : redemandées un peu plus tard, quelques fois au plus.
            if (again.isNotEmpty()) scope.launch { delay(retryMs); again.forEach { request(it) } }
        }
    }
}

/** Fiches de l'app ; null (tests, aperçus) = jamais de fiche, les images du fournisseur restent. */
val LocalMediaMeta = staticCompositionLocalOf<MediaMetaRepository?> { null }

/** Fiche TMDB de [ref] si connue (demandée au besoin) ; null tant qu'il n'y en a pas. */
@Composable
fun rememberMeta(ref: MediaRef?): MediaMeta? {
    val repo = LocalMediaMeta.current ?: return null
    if (ref == null) return null
    LaunchedEffect(ref.key) { repo.request(ref) }
    // Seule la fiche de ce titre est suivie : l'arrivée des autres ne redessine pas cette image.
    val flow = androidx.compose.runtime.remember(ref.key) {
        repo.state.mapKey(ref.key)
    }
    val m by flow.collectAsState(initial = repo.state.value[ref.key])
    return m?.takeIf { it.found }
}

private fun StateFlow<Map<String, MediaMeta>>.mapKey(key: String) = map { it[key] }.distinctUntilChanged()
