package home.iptv.tv.feature

/**
 * Version retenue (0.41, idée du propriétaire) : un film ne démarre pas, on essaie une autre version, elle marche →
 * c'est elle qui s'ouvre les fois suivantes à la place de celle en panne. La version en panne n'est pas effacée : si
 * la version retenue disparaît du catalogue, l'ancienne revient toute seule.
 */
interface VersionMemory {
    /** Version à ouvrir à la place de [id] (suivie de proche en proche, sans boucle) ; [id] lui-même sinon. */
    fun resolve(id: String, exists: (String) -> Boolean = { true }): String
    /** [to] a marché après l'échec de [from]. */
    fun remember(from: String, to: String)
}

/** En mémoire (tests). */
open class MapVersionMemory : VersionMemory {
    protected val map = LinkedHashMap<String, String>()

    @Synchronized override fun resolve(id: String, exists: (String) -> Boolean): String {
        var current = id
        val seen = HashSet<String>()
        while (seen.add(current)) {
            val next = map[current] ?: break
            if (!exists(next)) break
            current = next
        }
        return current
    }

    @Synchronized override fun remember(from: String, to: String) {
        if (from == to) return
        map.remove(to) // la version qui marche n'est plus redirigée ailleurs
        map[from] = to
        while (map.size > MAX) map.remove(map.keys.first())
        saved()
    }

    protected open fun saved() {}

    companion object { const val MAX = 500 }
}

/** Gardée sur la télé (fichier de préférences « versions-retenues »). */
class AndroidVersionMemory(context: android.content.Context) : MapVersionMemory() {
    private val prefs = context.applicationContext.getSharedPreferences("versions-retenues", android.content.Context.MODE_PRIVATE)

    init { prefs.all.forEach { (k, v) -> if (v is String) map[k] = v } }

    override fun saved() { prefs.edit().clear().apply { map.forEach { (k, v) -> putString(k, v) } }.apply() }
}
