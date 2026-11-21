package home.iptv.tv.live

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.pow

/** Bilan d'une copie : réussites et échecs pondérés (les anciens comptent de moins en moins), dernière mise à jour. */
data class CopyScore(val ok: Double = 0.0, val ko: Double = 0.0, val at: Long = 0)

/**
 * Fiabilité des copies d'une chaîne (suggestion 10 du propriétaire, partagée entre télés en 0.46) : la télé retient,
 * pour chaque copie (« RDS HD », « RDS FHD »…), ce qui a marché et ce qui a coupé ces derniers jours, et commence par
 * la plus stable. Un événement compte moitié moins après [halfLifeMs] (3 jours) : une copie réparée reprend vite sa
 * place. Partagée en douceur via le serveur maison (constats réels uniquement, jamais de sondage chez le fournisseur).
 *
 * [load] / [save] : texte gardé (préférences Android), une ligne « copie<tab>réussites<tab>échecs<tab>heure » par copie.
 */
class CopyReliability(
    private val load: () -> String?,
    private val save: (String) -> Unit,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val halfLifeMs: Long = 3 * 24 * 60 * 60_000L,
    private val maxEntries: Int = 3000,
    private val onRecord: ((sourceId: String, kind: String) -> Unit)? = null,
) {
    private var scores: MutableMap<String, CopyScore>? = null
    /** Instantané immuable lu sans verrou ni lecture disque depuis la composition UI (fil principal). */
    @Volatile private var snapshot: Map<String, CopyScore> = emptyMap()
    private val _version = MutableStateFlow(0)
    /** Incrémenté à chaque changement (constat local ou fusion serveur maison) pour actualiser l'affichage. */
    val version: StateFlow<Int> = _version.asStateFlow()

    @Synchronized private fun all(): MutableMap<String, CopyScore> = scores ?: decode(load()).also {
        scores = it
        snapshot = HashMap(it)
    }

    /** Charge les préférences en mémoire (à appeler hors fil principal au démarrage). */
    fun preload() {
        all()
        _version.value++
    }

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

    @Synchronized private fun record(sourceId: String, ok: Double, ko: Double, kind: String) {
        val now = clockMs()
        val map = all()
        val s = decayed(map[sourceId] ?: CopyScore(at = now), now)
        map[sourceId] = CopyScore(s.ok + ok, s.ko + ko, now)
        if (map.size > maxEntries) {
            // Les copies oubliées depuis le plus longtemps partent d'abord.
            map.entries.sortedBy { it.value.at }.take(map.size - maxEntries).forEach { map.remove(it.key) }
        }
        snapshot = HashMap(map)
        runCatching { save(encode(map)) }
        _version.value++
        onRecord?.invoke(sourceId, kind)
    }

    /** L'image est arrivée sur cette copie. */
    fun worked(sourceId: String) = record(sourceId, 1.0, 0.0, "ok")
    /** Erreur, ou copie restée figée malgré une reconnexion. */
    fun failed(sourceId: String) = record(sourceId, 0.0, 1.0, "ko")
    /** Copie figée puis rouverte (« Reconnexion… ») : demi-échec. */
    fun stalled(sourceId: String) = record(sourceId, 0.0, 0.5, "stall")

    /** Fusionne les bilans partagés par le serveur maison (une entrée plus récente du foyer remplace l'ancienne locale). */
    @Synchronized fun merge(remote: Map<String, CopyScore>) {
        if (remote.isEmpty()) return
        val map = all()
        var changed = false
        for ((id, s) in remote) {
            if (id.isBlank() || !s.ok.isFinite() || !s.ko.isFinite() || s.ok < 0.0 || s.ko < 0.0) continue
            val local = map[id]
            if (local == null || s.at > local.at) {
                map[id] = s
                changed = true
            }
        }
        if (map.size > maxEntries) {
            map.entries.sortedBy { it.value.at }.take(map.size - maxEntries).forEach { map.remove(it.key) }
            changed = true
        }
        if (changed) {
            snapshot = HashMap(map)
            runCatching { save(encode(map)) }
            _version.value++
        }
    }

    /** Oublie les bilans (changement de fournisseur IPTV). */
    @Synchronized fun clear() {
        val map = all()
        if (map.isEmpty()) return
        map.clear()
        snapshot = emptyMap()
        runCatching { save("") }
        _version.value++
    }

    /** Fiabilité entre 0 et 1 (0,5 = inconnue) : réussites + 1 sur total + 2. */
    @Synchronized fun score(sourceId: String): Double {
        val s = all()[sourceId]?.let { decayed(it, clockMs()) } ?: return 0.5
        return (s.ok + 1) / (s.ok + s.ko + 2)
    }

    /**
     * Vrai si TOUTES les copies de la chaîne échouent de façon répétée sans aucune réussite récente
     * (3 échecs récents → repasse tout seul à faux après ~24 h, ou aussitôt qu'un essai réussit).
     * Lit uniquement l'instantané immuable en mémoire : jamais de blocage ni d'I/O sur le fil principal.
     */
    fun isLikelyDead(sourceIds: Collection<String>): Boolean {
        if (sourceIds.isEmpty()) return false
        val map = snapshot
        if (map.isEmpty()) return false
        val now = clockMs()
        return sourceIds.all { id ->
            val raw = map[id] ?: return@all false
            val d = decayed(raw, now)
            d.ko >= DEAD_KO_THRESHOLD && d.ok < DEAD_OK_LIMIT
        }
    }

    /**
     * [ids] du plus fiable au moins fiable ; à fiabilité égale, l'ordre reçu (celui du fournisseur, ou la dernière bonne
     * copie mise en tête par l'appelant) est gardé. Une copie inconnue (0,5) passe donc avant une copie qui coupe souvent.
     */
    fun rank(ids: List<String>): List<String> {
        val s = ids.associateWith { score(it) }
        return ids.withIndex().sortedWith(compareByDescending<IndexedValue<String>> { (s[it.value]!! * 20).toInt() }.thenBy { it.index }).map { it.value }
    }

    companion object {
        /** 3 échecs (3.0) retombent sous 2.5 après ~24 h (demi-vie 3 jours) → auto-guérison sans bloquer la chaîne. */
        private const val DEAD_KO_THRESHOLD = 2.5
        private const val DEAD_OK_LIMIT = 0.5
    }
}
