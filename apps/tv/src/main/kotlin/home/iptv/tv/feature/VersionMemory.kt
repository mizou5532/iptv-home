package home.iptv.tv.feature

import home.iptv.shared.meta.MediaKind

/**
 * Version retenue (0.41, étendue aux séries et partagée entre télés en 0.46) : un film ou une série ne démarre pas,
 * on essaie une autre version, elle marche → c'est elle qui s'ouvre les fois suivantes à la place de celle en panne.
 * La version en panne n'est pas effacée : si la version retenue disparaît du catalogue, l'ancienne revient toute seule.
 *
 * Les clés sont préfixées (`movie:<id>`, `series:<id>`) pour éviter toute collision entre identifiants de films et
 * de séries chez Xtream ; les anciennes entrées films non préfixées restent lues sans perte.
 */
interface VersionMemory {
    /** Version à ouvrir à la place de [id] pour [kind] (suivie de proche en proche, sans boucle) ; [id] sinon. */
    fun resolve(kind: MediaKind, id: String, exists: (String) -> Boolean = { true }): String

    /** [to] a marché après l'échec de [from] pour [kind]. */
    fun remember(kind: MediaKind, from: String, to: String)

    /** Raccourci historique (films) pour les anciens appels et tests. */
    fun resolve(id: String, exists: (String) -> Boolean = { true }): String = resolve(MediaKind.MOVIE, id, exists)

    /** Raccourci historique (films) pour les anciens appels et tests. */
    fun remember(from: String, to: String) = remember(MediaKind.MOVIE, from, to)

    /** Fusionne les choix partagés par le serveur maison (`"movie:<id>"` / `"series:<id>"` → `"<id>"`). */
    fun merge(remote: Map<String, String>) {}

    /** Oublie les choix retenus (changement de fournisseur IPTV : les identifiants changent). */
    fun clear() {}
}

/** Préfixe stable utilisé en local et sur le serveur maison. */
fun versionMemoryKey(kind: MediaKind, id: String): String =
    (if (kind == MediaKind.MOVIE) "movie:" else "series:") + id

/** En mémoire (tests). */
open class MapVersionMemory(
    private val onRemember: ((fromKey: String, toId: String) -> Unit)? = null,
) : VersionMemory {
    protected val map = LinkedHashMap<String, String>()

    @Synchronized override fun resolve(kind: MediaKind, id: String, exists: (String) -> Boolean): String {
        var current = id
        val seen = HashSet<String>()
        while (seen.add(current)) {
            val key = versionMemoryKey(kind, current)
            // Compatibilité descendante : les choix films 0.41–0.45 étaient stockés sans préfixe.
            val next = map[key] ?: if (kind == MediaKind.MOVIE) map[current] else null
            if (next == null || !exists(next)) break
            current = next
        }
        return current
    }

    @Synchronized override fun remember(kind: MediaKind, from: String, to: String) {
        if (from == to) return
        val fromKey = versionMemoryKey(kind, from)
        val toKey = versionMemoryKey(kind, to)
        map.remove(toKey) // la version qui marche n'est plus redirigée ailleurs
        if (kind == MediaKind.MOVIE) {
            map.remove(to)
            map.remove(from)
        }
        map.remove(fromKey)
        map[fromKey] = to
        while (map.size > MAX) map.remove(map.keys.first())
        saved()
        onRemember?.invoke(fromKey, to)
    }

    @Synchronized override fun merge(remote: Map<String, String>) {
        if (remote.isEmpty()) return
        var changed = false
        for ((k, v) in remote) {
            val sep = k.indexOf(':')
            if (sep <= 0) continue
            val prefix = k.substring(0, sep)
            val fromId = k.substring(sep + 1)
            if ((prefix != "movie" && prefix != "series") || fromId.isEmpty() || v.isEmpty() || fromId == v) continue
            if (map[k] != v) {
                map.remove("$prefix:$v")
                if (prefix == "movie") {
                    map.remove(v)
                    map.remove(fromId)
                }
                map.remove(k)
                map[k] = v
                changed = true
            }
        }
        while (map.size > MAX) {
            map.remove(map.keys.first())
            changed = true
        }
        if (changed) saved()
    }

    @Synchronized override fun clear() {
        if (map.isEmpty()) return
        map.clear()
        saved()
    }

    protected open fun saved() {}

    companion object { const val MAX = 500 }
}

/** Gardée sur la télé (fichier de préférences « versions-retenues »). */
class AndroidVersionMemory(
    context: android.content.Context,
    onRemember: ((fromKey: String, toId: String) -> Unit)? = null,
) : MapVersionMemory(onRemember) {
    private val prefs = context.applicationContext.getSharedPreferences("versions-retenues", android.content.Context.MODE_PRIVATE)

    init { prefs.all.forEach { (k, v) -> if (v is String) map[k] = v } }

    override fun saved() { prefs.edit().clear().apply { map.forEach { (k, v) -> putString(k, v) } }.apply() }
}
