package home.iptv.tv.sport

import home.iptv.shared.sport.*
import home.iptv.shared.sync.SportVote
import home.iptv.shared.xtream.EpgEntry
import home.iptv.shared.xtream.LiveCategory
import home.iptv.shared.xtream.LiveStream
import kotlinx.coroutines.*
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Ce que le Sport lit en plus du Direct :
 * - chaînes des catégories choisies pour le Sport (même masquées dans le Direct) : événements, chaînes d'équipes ;
 * - mémoire de la maison (votes) : « cette chaîne diffuse les matchs des Canadiens », « pas le bon match » ;
 * - guide du fournisseur d'une chaîne (confirmation d'un match).
 */
interface SportSources {
    suspend fun extraChannels(): List<SportChannel> = emptyList()
    /** Dernières chaînes gardées sur la télé, sans réseau ni attente (affichage immédiat ; [extraChannels] met à jour). */
    suspend fun savedChannels(): List<SportChannel> = emptyList()
    suspend fun votes(): List<SportVote> = emptyList()
    suspend fun vote(teamKey: String, channelKey: String, delta: Int) {}
    suspend fun guide(streamId: String): List<EpgEntry> = emptyList()
}

object NoSportSources : SportSources

private data class ExtraFile(val at: Long, val ids: List<String>, val channels: List<ExtraRow>) {
    /** Texte simple : 1re ligne « heure<TAB>catégories », puis « id<TAB>nom<TAB>catégorie » par chaîne. */
    fun encode(): String = buildString {
        append(at).append('\t').append(ids.joinToString(",")).append('\n')
        channels.forEach { append(clean(it.id)).append('\t').append(clean(it.name)).append('\t').append(clean(it.cat)).append('\n') }
    }
    companion object {
        fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
        fun decode(text: String): ExtraFile? {
            val lines = text.split('\n')
            val head = lines.firstOrNull()?.split('\t') ?: return null
            val at = head.getOrNull(0)?.toLongOrNull() ?: return null
            val ids = head.getOrNull(1).orEmpty().split(',').filter { it.isNotEmpty() }
            val rows = lines.drop(1).mapNotNull { l -> l.split('\t').takeIf { it.size == 3 }?.let { ExtraRow(it[0], it[1], it[2]) } }
            return ExtraFile(at, ids, rows)
        }
    }
}

private data class ExtraRow(val id: String, val name: String, val cat: String)

/**
 * Chaînes des catégories Sport relues au plus toutes les heures (en arrière-plan : l'écran montre d'abord la liste
 * gardée, [SportSources.savedChannels]) ; guide d'une chaîne gardé 15 min.
 */
const val EXTRA_MAX_AGE_MS = 60 * 60 * 1000L
const val GUIDE_MAX_AGE_MS = 15 * 60 * 1000L
private const val EXTRA_PARALLEL = 4
/** Une catégorie Sport n'a pas répondu : la liste est relue au bout de ce délai. */
const val EXTRA_RETRY_MS = 10 * 60 * 1000L

class AndroidSportSources(
    private val dir: java.io.File,
    /** Toutes les catégories du fournisseur, masquées comprises (`get_live_categories`), jamais le catalogue local. */
    private val categories: suspend () -> List<LiveCategory>,
    private val streams: suspend (String) -> List<LiveStream>,
    private val epg: suspend (String) -> List<EpgEntry>,
    /** Catégories choisies sur l'iPhone (réglage de la maison) ; null = choix par défaut. */
    private val chosen: suspend () -> List<String>?,
    private val readVotes: suspend () -> List<SportVote>,
    private val writeVote: suspend (String, String, Int) -> Unit,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : SportSources {
    private val file get() = java.io.File(dir.apply { mkdirs() }, "sport-extra-2.txt")
    @Volatile private var memo: ExtraFile? = null
    private val lock = kotlinx.coroutines.sync.Mutex()

    private fun read(): ExtraFile? = memo ?: runCatching { ExtraFile.decode(file.readText()) }.getOrNull()?.also { memo = it }
    private fun ExtraFile.toChannels() = channels.map { SportChannel("x:${it.id}", it.name, it.cat, it.id, direct = true) }

    override suspend fun savedChannels(): List<SportChannel> = read()?.toChannels().orEmpty()

    override suspend fun extraChannels(): List<SportChannel> = lock.withLock {
        val saved = read()
        // Un résultat vide n'est jamais gardé : relu à la prochaine ouverture.
        val young = saved != null && saved.channels.isNotEmpty() && now() - saved.at < EXTRA_MAX_AGE_MS
        val fresh: ExtraFile? = coroutineScope {
            // Liste trop vieille : catégories du fournisseur et choix de la maison demandés en même temps.
            val early = if (young) null else async { runCatching { categories() } }
            val wanted = runCatching { chosen() }.getOrElse { saved?.ids }
            if (young && (wanted == null || wanted == saved!!.ids)) return@coroutineScope saved
            val cats = (early ?: async { runCatching { categories() } }).await().getOrNull() ?: return@coroutineScope null
            val ids = wanted ?: cats.filter { isDefaultSportCategory(it.name) }.map { it.id }
            val names = cats.associate { it.id to it.name }
            val gate = kotlinx.coroutines.sync.Semaphore(EXTRA_PARALLEL)
            // Une catégorie sans réponse garde ses chaînes de la dernière fois (panne passagère du fournisseur).
            val previous = saved?.channels.orEmpty().groupBy { it.cat }
            var failed = 0
            val rows = ids.filter { it in names }.map { id ->
                async {
                    gate.acquire()
                    try {
                        val cat = ExtraFile.clean(names[id].orEmpty())
                        val list = try { streams(id) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                        list?.map { ExtraRow(ExtraFile.clean(it.id), ExtraFile.clean(it.name), cat) }
                            ?: previous[cat].orEmpty().also { synchronized(gate) { failed++ } }
                    } finally { gate.release() }
                }
            }.awaitAll().flatten()
            // Rien du tout : la liste gardée n'est jamais remplacée par une liste vide.
            // (Sauf si la maison n'a plus aucune catégorie choisie, ou si rien n'a échoué : la liste est vraiment vide.)
            if (rows.isEmpty() && failed > 0 && saved != null && saved.channels.isNotEmpty()) return@coroutineScope null
            // Une catégorie a manqué : nouvel essai dans 10 min au lieu d'1 h.
            val at = if (failed > 0) now() - EXTRA_MAX_AGE_MS + EXTRA_RETRY_MS else now()
            ExtraFile(at, wanted ?: ids, rows.distinctBy { it.id })
        }
        if (fresh == null) return saved?.toChannels().orEmpty()
        if (fresh !== saved) {
            memo = fresh
            runCatching { val tmp = java.io.File(file.path + ".tmp"); tmp.writeText(fresh.encode()); tmp.renameTo(file) }
        }
        fresh.toChannels()
    }

    override suspend fun votes() = runCatching { readVotes() }.getOrDefault(emptyList())
    override suspend fun vote(teamKey: String, channelKey: String, delta: Int) { runCatching { writeVote(teamKey, channelKey, delta) } }

    private val guides = HashMap<String, Pair<Long, List<EpgEntry>>>()
    override suspend fun guide(streamId: String): List<EpgEntry> {
        synchronized(guides) { guides[streamId]?.takeIf { now() - it.first < GUIDE_MAX_AGE_MS }?.let { return it.second } }
        val g = runCatching { epg(streamId) }.getOrNull() ?: return emptyList()
        synchronized(guides) { guides[streamId] = now() to g }
        return g
    }
}

private suspend inline fun <T> kotlinx.coroutines.sync.Mutex.withLock(block: () -> T): T { lock(); try { return block() } finally { unlock() } }

/** Chaînes à vérifier dans le guide pour un match : les proposées et les chaînes sport, au plus [max], hors événements. */
fun guideProbes(c: SportCandidates, max: Int = 10): List<SportChannel> =
    c.all.filter { !it.direct }.take(max)

/** Le guide de [ch] annonce-t-il [m] ? */
fun confirmedBy(guide: List<EpgEntry>, m: Match): Boolean =
    guideConfirms(guide.map { it.title to it.start }, guide.map { it.end }, m)
