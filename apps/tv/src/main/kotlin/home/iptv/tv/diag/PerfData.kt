package home.iptv.tv.diag

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Mesures de lenteur gardées en mémoire (les dernières minutes), pour les rapports « Signaler une lenteur ».
 * Rien ici ne contient d'adresse de serveur, de nom d'utilisateur ni de mot de passe : les demandes réseau ne sont
 * gardées que par TYPE (fournisseur, serveur de la maison, sport…) et par opération sans secret (« get_live_streams »,
 * nom d'une fonction du serveur), les erreurs par leur seul nom de classe.
 *
 * Partie pure (testable sans télé) : [PerfMonitor] l'alimente sur la télé.
 */
class PerfData(private val clockMs: () -> Long = { System.currentTimeMillis() }) {
    private class Ring<T>(private val max: Int) {
        private val items = ArrayDeque<T>()
        @Synchronized fun add(t: T) { items.addLast(t); while (items.size > max) items.removeFirst() }
        @Synchronized fun list(): List<T> = items.toList()
        @Synchronized fun clear() = items.clear()
    }

    data class Stall(val atMs: Long, val durationMs: Long, val screen: String, val stack: List<String>)
    data class SlowFrame(val atMs: Long, val ms: Long, val screen: String)
    data class Key(val atMs: Long, val key: String, val lagMs: Long, val handleMs: Long, val screen: String)
    data class Net(val atMs: Long, val kind: String, val op: String, val ms: Long, val status: String)
    data class Work(val name: String, val startMs: Long, val ms: Long?)
    data class Event(val atMs: Long, val text: String)
    data class Memory(val atMs: Long, val heapUsedMb: Int, val heapMaxMb: Int, val nativeMb: Int, val availMb: Int, val lowMemory: Boolean,
                      val gcCount: Long, val gcMs: Long, val blockingGcCount: Long, val threads: Int)

    private val stalls = Ring<Stall>(20)
    private val slowFrames = Ring<SlowFrame>(60)
    private val keys = Ring<Key>(300)
    private val slowNet = Ring<Net>(60)
    private val works = Ring<Work>(60)
    private val events = Ring<Event>(120)
    private val memory = Ring<Memory>(60)
    private val running = LinkedHashMap<Long, Pair<String, Long>>()
    private var nextWork = 0L

    // Images de l'écran : total, plus de 33 ms (saccade visible), plus de 100 ms, plus de 500 ms, la pire.
    @Volatile private var frames = 0L
    @Volatile private var frames33 = 0L
    @Volatile private var frames100 = 0L
    @Volatile private var frames500 = 0L
    @Volatile private var worstFrame = 0L

    private class NetStat { var n = 0; var errors = 0; var totalMs = 0L; var maxMs = 0L; var slow = 0 }
    private val net = LinkedHashMap<String, NetStat>()

    @Volatile var screen: String = ""

    fun frame(ms: Long) {
        frames++
        if (ms > 33) frames33++
        if (ms > 100) { frames100++; slowFrames.add(SlowFrame(clockMs(), ms, screen)) }
        if (ms > 500) frames500++
        if (ms > worstFrame) worstFrame = ms
    }

    fun stall(durationMs: Long, stack: List<String>) = stalls.add(Stall(clockMs(), durationMs, screen, stack.take(STACK_MAX)))

    fun key(key: String, lagMs: Long, handleMs: Long) = keys.add(Key(clockMs(), key, lagMs.coerceAtLeast(0), handleMs.coerceAtLeast(0), screen))

    fun network(kind: String, op: String, ms: Long, status: String) {
        val slow = ms >= SLOW_NET_MS || status != "ok"
        synchronized(net) {
            val s = net.getOrPut(kind) { NetStat() }
            s.n++; s.totalMs += ms; if (ms > s.maxMs) s.maxMs = ms
            if (status != "ok") s.errors++
            if (ms >= SLOW_NET_MS) s.slow++
        }
        if (slow) slowNet.add(Net(clockMs(), kind, op, ms, status))
    }

    /** Début d'un travail de fond ; rend un jeton pour [workEnd]. */
    fun workStart(name: String): Long = synchronized(running) { val id = nextWork++; running[id] = name to clockMs(); id }

    fun workEnd(token: Long) {
        val (name, start) = synchronized(running) { running.remove(token) } ?: return
        works.add(Work(name, start, clockMs() - start))
    }

    fun event(text: String) = events.add(Event(clockMs(), text.take(160)))

    fun memory(m: Memory) = memory.add(m)

    /** Rapport complet (JSON) : [extra] = ce que la télé ajoute (appareil, mémoire actuelle, catalogue…). */
    fun report(kind: String, reason: String, extra: Map<String, JsonElement>): JsonObject = buildJsonObject {
        put("rapport", buildJsonObject {
            put("type", kind); put("raison", reason); put("a", iso(clockMs())); put("ecran", screen)
        })
        extra.forEach { (k, v) -> put(k, v) }
        put("images", buildJsonObject {
            put("total", frames); put("plus_33ms", frames33); put("plus_100ms", frames100); put("plus_500ms", frames500); put("pire_ms", worstFrame)
            put("lentes", buildJsonArray { slowFrames.list().takeLast(40).forEach { f -> add(buildJsonObject { put("a", iso(f.atMs)); put("ms", f.ms); put("ecran", f.screen) }) } })
        })
        put("blocages", buildJsonArray {
            stalls.list().forEach { s ->
                add(buildJsonObject {
                    put("a", iso(s.atMs)); put("duree_ms", s.durationMs); put("ecran", s.screen)
                    put("pile", JsonArray(s.stack.map(::JsonPrimitive)))
                })
            }
        })
        put("touches", keyStats())
        put("reseau", buildJsonObject {
            put("par_type", buildJsonObject {
                synchronized(net) {
                    net.forEach { (k, s) ->
                        put(k, buildJsonObject {
                            put("demandes", s.n); put("erreurs", s.errors); put("lentes_2s", s.slow)
                            put("moyenne_ms", if (s.n > 0) s.totalMs / s.n else 0); put("max_ms", s.maxMs)
                        })
                    }
                }
            })
            put("lentes_ou_en_erreur", buildJsonArray { slowNet.list().forEach { n -> add(buildJsonObject {
                put("a", iso(n.atMs)); put("type", n.kind); put("op", n.op); put("ms", n.ms); put("statut", n.status) }) } })
        })
        put("travaux", buildJsonObject {
            put("en_cours", buildJsonArray {
                synchronized(running) { running.values.toList() }.forEach { (n, s) -> add(buildJsonObject { put("nom", n); put("depuis_s", (clockMs() - s) / 1000) }) }
            })
            put("recents", buildJsonArray { works.list().forEach { w -> add(buildJsonObject { put("nom", w.name); put("debut", iso(w.startMs)); put("ms", w.ms ?: -1) }) } })
        })
        put("memoire_historique", buildJsonArray { memory.list().forEach { m -> add(buildJsonObject {
            put("a", iso(m.atMs)); put("tas_mo", m.heapUsedMb); put("tas_max_mo", m.heapMaxMb); put("natif_mo", m.nativeMb)
            put("libre_systeme_mo", m.availMb); put("memoire_basse", m.lowMemory); put("gc", m.gcCount); put("gc_ms", m.gcMs)
            put("gc_bloquants", m.blockingGcCount); put("fils", m.threads) }) } })
        put("evenements", buildJsonArray { events.list().forEach { e -> add(buildJsonObject { put("a", iso(e.atMs)); put("texte", e.text) }) } })
    }

    private fun keyStats(): JsonObject {
        val list = keys.list()
        val lags = list.map { it.lagMs }.sorted()
        val handles = list.map { it.handleMs }.sorted()
        fun pct(l: List<Long>, p: Double) = if (l.isEmpty()) 0 else l[((l.size - 1) * p).toInt()]
        return buildJsonObject {
            put("nombre", list.size)
            put("retard_moyen_ms", if (lags.isEmpty()) 0 else lags.sum() / lags.size)
            put("retard_p95_ms", pct(lags, 0.95)); put("retard_max_ms", lags.lastOrNull() ?: 0)
            put("traitement_p95_ms", pct(handles, 0.95)); put("traitement_max_ms", handles.lastOrNull() ?: 0)
            put("lentes", buildJsonArray { list.filter { it.lagMs + it.handleMs >= SLOW_KEY_MS }.takeLast(40).forEach { k -> add(buildJsonObject {
                put("a", iso(k.atMs)); put("touche", k.key); put("retard_ms", k.lagMs); put("traitement_ms", k.handleMs); put("ecran", k.screen) }) } })
        }
    }

    /** Résumé d'une ligne pour la liste des rapports (« 3 blocages, pire 4,2 s · touches p95 640 ms · Sport »). */
    fun summary(reason: String): String {
        val st = stalls.list()
        val lags = keys.list().map { it.lagMs + it.handleMs }.sorted()
        val p95 = if (lags.isEmpty()) 0 else lags[((lags.size - 1) * 0.95).toInt()]
        return buildString {
            append(reason)
            if (st.isNotEmpty()) append(" · ${st.size} blocage(s), pire ${st.maxOf { it.durationMs }} ms")
            append(" · touches p95 $p95 ms")
            if (frames500 > 0) append(" · $frames500 image(s) > 0,5 s")
            if (screen.isNotBlank()) append(" · $screen")
        }.take(300)
    }

    /** Après un envoi réussi : on repart de zéro (le prochain rapport ne montre que ce qui s'est passé depuis). */
    fun resetCounters() {
        frames = 0; frames33 = 0; frames100 = 0; frames500 = 0; worstFrame = 0
        stalls.clear(); slowFrames.clear(); keys.clear(); slowNet.clear(); works.clear()
        synchronized(net) { net.clear() }
    }

    companion object {
        const val STACK_MAX = 40
        const val SLOW_NET_MS = 2_000L
        const val SLOW_KEY_MS = 250L

        fun iso(ms: Long): String = kotlin.time.Instant.fromEpochMilliseconds(ms).toString()

        /**
         * Type et opération d'une demande, SANS l'adresse : les adresses du fournisseur contiennent le nom d'utilisateur
         * et le mot de passe. Fournisseur : seulement le paramètre `action` (« get_live_streams »), « vidéo » ou « image ».
         */
        fun classify(host: String, path: String, action: String?): Pair<String, String> {
            val h = host.lowercase()
            return when {
                path == "/v1/live" || path.startsWith("/v1/epg/") ->
                    "maison" to if (path.startsWith("/v1/epg/")) "/v1/epg/:id" else "/v1/live"
                h.endsWith(".supabase.co") -> "serveur" to path.replace(Regex("[0-9a-f]{8}-[0-9a-f-]{27,}"), ":id").take(80)
                h.contains("espn.com") || h.contains("nhle.com") || h.contains("espncdn.com") -> "sport" to (if (h.contains("espncdn")) "logo" else path.substringBeforeLast('/').take(80))
                h.contains("themoviedb.org") || h.contains("tmdb.org") || h.contains("omdbapi.com") -> (if (h.startsWith("image.")) "images" else "fiches") to "-"
                h.endsWith("vercel.app") -> "mise_a_jour" to path.take(40)
                else -> "fournisseur" to when {
                    !action.isNullOrBlank() -> action.take(40)
                    VIDEO.containsMatchIn(path) -> "video"
                    IMAGE.containsMatchIn(path) -> "image"
                    else -> "autre"
                }
            }
        }

        private val VIDEO = Regex("\\.(ts|m3u8|mkv|mp4|avi)$", RegexOption.IGNORE_CASE)
        private val IMAGE = Regex("\\.(png|jpe?g|webp|gif)$", RegexOption.IGNORE_CASE)
    }
}
