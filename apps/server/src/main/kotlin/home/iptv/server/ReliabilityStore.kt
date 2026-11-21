package home.iptv.server

import home.iptv.shared.home.CopyScoreDto
import home.iptv.shared.home.LiveReliabilityReply
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.pow

/**
 * Mémoire partagée de fiabilité des copies de chaînes du Direct (détection douce des chaînes mortes) :
 * le serveur ne sonde JAMAIS les flux du fournisseur (zéro risque de blocage). Il reçoit uniquement les constats
 * réels de lecture des télés du foyer (`ok`, `ko`, `stall`) avec la même demi-vie de 3 jours que [CopyReliability],
 * et les partage entre toutes les télés pour sauter les copies en panne et signaler les chaînes hors service.
 *
 * Isolé par empreinte du compte IPTV (un changement de fournisseur repart à zéro).
 */
class ReliabilityStore(
    private val file: File,
    private val account: () -> String?,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val halfLifeMs: Long = 3L * 24 * 60 * 60_000L,
    private val maxEntries: Int = 3_000,
) {
    @Serializable
    private data class DiskState(val account: String = "", val scores: Map<String, CopyScoreDto> = emptyMap())

    private val lock = Any()
    private var loadedAccount: String? = null
    private val map = HashMap<String, CopyScoreDto>()

    init {
        loadFromDisk()
    }

    private fun loadFromDisk() {
        if (!file.exists()) return
        val state = runCatching { snapshotJson.decodeFromString(DiskState.serializer(), file.readText()) }.getOrNull() ?: return
        synchronized(lock) {
            loadedAccount = state.account.takeIf { it.isNotBlank() }
            map.clear()
            state.scores.forEach { (k, v) ->
                if (validSourceId(k) && v.ok.isFinite() && v.ko.isFinite() && v.ok >= 0.0 && v.ko >= 0.0) map[k] = v
            }
        }
    }

    private fun ensureAccountLocked(): String? {
        val current = account()?.takeIf { it.isNotBlank() } ?: return loadedAccount
        if (loadedAccount != null && loadedAccount != current) {
            map.clear()
        }
        loadedAccount = current
        return current
    }

    private fun decayed(s: CopyScoreDto, now: Long): CopyScoreDto {
        if (s.at <= 0L || now <= s.at) return s.copy(at = now)
        val f = 0.5.pow((now - s.at).toDouble() / halfLifeMs)
        return CopyScoreDto(s.ok * f, s.ko * f, now)
    }

    fun snapshot(): LiveReliabilityReply = synchronized(lock) {
        ensureAccountLocked()
        val now = clockMs()
        val out = LinkedHashMap<String, CopyScoreDto>()
        val stale = mutableListOf<String>()
        for ((id, s) in map) {
            val d = decayed(s, now)
            if (d.ok + d.ko < 0.05) {
                stale += id
            } else {
                out[id] = s
            }
        }
        stale.forEach { map.remove(it) }
        LiveReliabilityReply(out)
    }

    /** Enregistre un constat réel (`ok`, `ko` ou `stall`). Retourne faux si les paramètres sont invalides. */
    fun record(sourceId: String, kind: String): Boolean {
        if (!validSourceId(sourceId)) return false
        val (dOk, dKo) = when (kind) {
            "ok" -> 1.0 to 0.0
            "ko" -> 0.0 to 1.0
            "stall" -> 0.0 to 0.5
            else -> return false
        }
        synchronized(lock) {
            val acc = ensureAccountLocked() ?: ""
            val now = clockMs()
            val prev = decayed(map[sourceId] ?: CopyScoreDto(at = now), now)
            map[sourceId] = CopyScoreDto(prev.ok + dOk, prev.ko + dKo, now)
            if (map.size > maxEntries) {
                map.entries.sortedBy { it.value.at }.take(map.size - maxEntries).forEach { map.remove(it.key) }
            }
            // Écriture atomique sous verrou : évite toute course sur le fichier .tmp ou écrasement par un état plus ancien.
            writeAtomic(DiskState(acc, HashMap(map)))
        }
        return true
    }

    private fun writeAtomic(state: DiskState) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(snapshotJson.encodeToString(DiskState.serializer(), state))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure {
            runCatching {
                val tmp = File(file.parentFile, "${file.name}.tmp")
                tmp.writeText(snapshotJson.encodeToString(DiskState.serializer(), state))
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    companion object {
        private val ID_RE = Regex("^[0-9A-Za-z_-]{1,40}$")
        fun validSourceId(id: String): Boolean = ID_RE.matches(id)
    }
}
