package home.iptv.server

import home.iptv.shared.home.VersionMemoryReply
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Mémoire partagée des versions retenues (films et séries) pour tout le foyer : quand une télé bascule sur une autre
 * version qui marche après une panne, toutes les télés du foyer ouvrent ensuite directement la bonne version.
 *
 * Isolée par empreinte du compte IPTV (si le fournisseur change, les anciens numéros sont oubliés).
 */
class VersionMemoryStore(
    private val file: File,
    private val account: () -> String?,
) {
    @Serializable
    private data class DiskState(val account: String = "", val entries: Map<String, String> = emptyMap())

    private val lock = Any()
    private var loadedAccount: String? = null
    private val map = LinkedHashMap<String, String>()

    init {
        loadFromDisk()
    }

    private fun loadFromDisk() {
        if (!file.exists()) return
        val state = runCatching { snapshotJson.decodeFromString(DiskState.serializer(), file.readText()) }.getOrNull() ?: return
        synchronized(lock) {
            loadedAccount = state.account.takeIf { it.isNotBlank() }
            map.clear()
            state.entries.forEach { (k, v) -> if (validFromKey(k) && validTargetId(v)) map[k] = v }
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

    fun snapshot(): VersionMemoryReply = synchronized(lock) {
        ensureAccountLocked()
        VersionMemoryReply(LinkedHashMap(map))
    }

    /** Enregistre [from] (`movie:<id>` ou `series:<id>`) → [to] (`<id>`). Retourne faux si les identifiants sont invalides. */
    fun remember(from: String, to: String): Boolean {
        if (!validFromKey(from) || !validTargetId(to)) return false
        val prefix = from.substringBefore(':')
        val fromId = from.substringAfter(':')
        if (fromId == to) return false
        synchronized(lock) {
            val acc = ensureAccountLocked() ?: ""
            // La version qui marche n'est plus redirigée ailleurs.
            map.remove("$prefix:$to")
            // Réinsérer pour maintenir l'ordre chronologique LRU.
            map.remove(from)
            map[from] = to
            while (map.size > MAX_ENTRIES) map.remove(map.keys.first())
            // Écriture atomique sous verrou : évite toute course sur le fichier .tmp ou écrasement par un état plus ancien.
            writeAtomic(DiskState(acc, LinkedHashMap(map)))
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
        const val MAX_ENTRIES = 500
        private val FROM_RE = Regex("^(movie|series):[0-9A-Za-z_-]{1,40}$")
        private val ID_RE = Regex("^[0-9A-Za-z_-]{1,40}$")

        fun validFromKey(key: String): Boolean = FROM_RE.matches(key)
        fun validTargetId(id: String): Boolean = ID_RE.matches(id)
    }
}
