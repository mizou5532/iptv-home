package home.iptv.tv.live

import kotlin.math.pow

/** Bilan d'une copie : réussites et échecs pondérés (les anciens comptent de moins en moins), dernière mise à jour. */
data class CopyScore(val ok: Double = 0.0, val ko: Double = 0.0, val at: Long = 0)

/**
 * Fiabilité des copies d'une chaîne (suggestion 10 du propriétaire) : la télé retient, pour chaque copie (« RDS HD »,
 * « RDS FHD »…), ce qui a marché et ce qui a coupé ces derniers jours, et commence par la plus stable. Un événement
 * compte moitié moins après [halfLifeMs] (3 jours) : une copie réparée reprend vite sa place. Gardé sur la télé seulement.
 *
 * [load] / [save] : texte gardé (préférences Android), une ligne « copie<tab>réussites<tab>échecs<tab>heure » par copie.
 */
class CopyReliability(
    private val load: () -> String?,
    private val save: (String) -> Unit,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val halfLifeMs: Long = 3 * 24 * 60 * 60_000L,
    private val maxEntries: Int = 3000,
) {
    private var scores: MutableMap<String, CopyScore>? = null

    @Synchronized private fun all(): MutableMap<String, CopyScore> = scores ?: decode(load()).also { scores = it }

    private fun decode(text: String?): MutableMap<String, CopyScore> {
        val out = HashMap<String, CopyScore>()
        text?.lineSequence()?.forEach { line ->
            val p = line.split('\t')
            if (p.size == 4) {
                val ok = p[1].toDoubleOrNull(); val ko = p[2].toDoubleOrNull(); val at = p[3].toLongOrNull()
                if (p[0].isNotBlank() && ok != null && ko != null && at != null) out[p[0]] = CopyScore(ok, ko, at)
            }
        }
        return out
    }

    private fun encode(map: Map<String, CopyScore>): String =
        map.entries.joinToString("\n") { (id, s) -> "$id\t${s.ok}\t${s.ko}\t${s.at}" }

    private fun decayed(s: CopyScore, now: Long): CopyScore {
        if (s.at <= 0 || now <= s.at) return s.copy(at = now)
        val f = 0.5.pow((now - s.at).toDouble() / halfLifeMs)
        return CopyScore(s.ok * f, s.ko * f, now)
    }

    @Synchronized private fun record(sourceId: String, ok: Double, ko: Double) {
        val now = clockMs()
        val map = all()
        val s = decayed(map[sourceId] ?: CopyScore(at = now), now)
        map[sourceId] = CopyScore(s.ok + ok, s.ko + ko, now)
        if (map.size > maxEntries) {
            // Les copies oubliées depuis le plus longtemps partent d'abord.
            map.entries.sortedBy { it.value.at }.take(map.size - maxEntries).forEach { map.remove(it.key) }
        }
        runCatching { save(encode(map)) }
    }

    /** L'image est arrivée sur cette copie. */
    fun worked(sourceId: String) = record(sourceId, 1.0, 0.0)
    /** Erreur, ou copie restée figée malgré une reconnexion. */
    fun failed(sourceId: String) = record(sourceId, 0.0, 1.0)
    /** Copie figée puis rouverte (« Reconnexion… ») : demi-échec. */
    fun stalled(sourceId: String) = record(sourceId, 0.0, 0.5)

    /** Fiabilité entre 0 et 1 (0,5 = inconnue) : réussites + 1 sur total + 2. */
    @Synchronized fun score(sourceId: String): Double {
        val s = all()[sourceId]?.let { decayed(it, clockMs()) } ?: return 0.5
        return (s.ok + 1) / (s.ok + s.ko + 2)
    }

    /**
     * [ids] du plus fiable au moins fiable ; à fiabilité égale, l'ordre reçu (celui du fournisseur, ou la dernière bonne
     * copie mise en tête par l'appelant) est gardé. Une copie inconnue (0,5) passe donc avant une copie qui coupe souvent.
     */
    fun rank(ids: List<String>): List<String> {
        val s = ids.associateWith { score(it) }
        return ids.withIndex().sortedWith(compareByDescending<IndexedValue<String>> { (s[it.value]!! * 20).toInt() }.thenBy { it.index }).map { it.value }
    }
}
