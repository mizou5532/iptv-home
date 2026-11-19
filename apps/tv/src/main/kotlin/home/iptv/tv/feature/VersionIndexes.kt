package home.iptv.tv.feature

import home.iptv.shared.meta.MediaKind
import home.iptv.shared.meta.MediaRef
import home.iptv.shared.search.SearchItem
import home.iptv.shared.versions.LanguagePrefs
import home.iptv.shared.versions.Version
import home.iptv.shared.versions.VersionIndex
import home.iptv.shared.versions.VersionInput
import home.iptv.shared.versions.groupVersions
import home.iptv.shared.versions.mergeByTmdb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Versions de tous les films et séries du cache local (langues, 4K), pour l'accueil, Ma liste, les fiches et la
 * recherche : regroupées par titre, sauf les versions séparées à la main, et réunies quand la fiche TMDB connue est la
 * même (titres traduits). Construit à la demande, hors du fil principal ; refait quand le catalogue ou un réglage change.
 */
class VersionIndexes(
    private val items: (MediaKind) -> List<SearchItem>,
    private val categoryNames: (MediaKind) -> Map<String, String>,
    /** Fiche TMDB connue d'un titre du fournisseur (cache seulement), par clé [MediaRef.key]. */
    private val tmdb: () -> Map<String, Int> = { emptyMap() },
    /** Versions séparées à la main (« movie:123 », « series:9 »). */
    private val splits: () -> Set<String> = { emptySet() },
    /** Enregistre une séparation (« movie:123 », vrai) ou une réunion pour toute la maison. */
    private val saveSplit: suspend (String, Boolean) -> Unit = { _, _ -> },
    /**
     * Index gardé sur la télé d'un lancement à l'autre (0.40) : relu tel quel si le catalogue, les séparations et
     * les fiches connues n'ont pas changé, au lieu de tout recalculer (2 à 4 s de calcul plein sur une télé).
     */
    private val disk: VersionIndexDisk? = null,
) {
    /** Un seul calcul à la fois, films et séries compris (jamais deux fois le même en parallèle). */
    private val buildLock = Any()
    @Volatile private var generation = 0L
    /** Langues du profil ouvert (réglées à l'ouverture des onglets Séries et Films). */
    @Volatile var languages: LanguagePrefs = LanguagePrefs()
    private val lock = Any()
    private val built = HashMap<MediaKind, VersionIndex>()
    private val _revision = MutableStateFlow(0)
    /** Change quand un index est (re)construit : les écrans relisent leurs rangées. */
    val revision: StateFlow<Int> = _revision

    /** Index déjà construit, sans attendre ; vide sinon. */
    fun current(kind: MediaKind): VersionIndex = synchronized(lock) { built[kind] } ?: VersionIndex.EMPTY

    /** Index de [kind], construit au besoin (à appeler hors du fil principal). */
    fun index(kind: MediaKind): VersionIndex {
        synchronized(lock) { built[kind]?.let { return it } }
        synchronized(buildLock) {
            // Un autre appel vient peut-être de le construire pendant qu'on attendait.
            synchronized(lock) { built[kind]?.let { return it } }
            val gen = generation
            val index = home.iptv.tv.diag.lowPriority {
                home.iptv.tv.diag.PerfMonitor.work("Index des versions (${if (kind == MediaKind.MOVIE) "films" else "séries"})") { build(kind) }
            }
            // Invalidé pendant le calcul (catalogue changé) : rendu tel quel, mais pas gardé.
            synchronized(lock) { if (generation == gen) built[kind] = index }
            _revision.value++
            return index
        }
    }

    private fun build(kind: MediaKind): VersionIndex {
        val inputs = inputs(kind, items(kind))
        val d = disk ?: return VersionIndex(group(kind, inputs))
        val fp = fingerprint(kind, inputs)
        d.load(kind, fp)?.let { return VersionIndex(it) }
        val groups = group(kind, inputs)
        runCatching { d.save(kind, fp, groups) }
        return VersionIndex(groups)
    }

    /** Empreinte de tout ce dont dépend l'index : titres, catégories, séparations, fiches TMDB connues. */
    private fun fingerprint(kind: MediaKind, inputs: List<VersionInput>): String {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        fun add(s: String?) { md.update((s ?: "\u0000").toByteArray()); md.update(0x1F) }
        add(FORMAT); add(kind.name)
        inputs.forEach { add(it.id); add(it.title); add(it.categoryId); add(it.categoryName); add(it.cover); add(it.year) }
        splits().sorted().forEach(::add)
        tmdb().entries.sortedBy { it.key }.forEach { add(it.key); add(it.value.toString()) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Catalogue complété ou réglage changé : les index seront refaits à la prochaine demande. */
    fun invalidate() { synchronized(lock) { built.clear(); generation++ }; _revision.value++ }

    private companion object { const val FORMAT = "versions-1" }

    /** « Ce n'est pas le même titre » : [id] devient un titre à part (réglage de la maison), index refait. */
    suspend fun split(kind: MediaKind, id: String) {
        saveSplit((if (kind == MediaKind.MOVIE) "movie:" else "series:") + id, true)
        invalidate()
    }

    fun inputs(kind: MediaKind, list: List<SearchItem>): List<VersionInput> {
        val names = runCatching { categoryNames(kind) }.getOrDefault(emptyMap())
        return list.map { item ->
            val cat = item.categoryIds.minOrNull().orEmpty()
            VersionInput(item.id, item.title, cat, names[cat], item.cover)
        }
    }

    /** Regroupement complet (séparations et fiches TMDB comprises) d'une liste de versions. */
    fun group(kind: MediaKind, inputs: List<VersionInput>): Map<String, List<Version>> {
        val prefix = if (kind == MediaKind.MOVIE) "movie:" else "series:"
        val split = splits().filter { it.startsWith(prefix) }.mapTo(HashSet()) { it.removePrefix(prefix) }
        val known = tmdb()
        val groups = groupVersions(inputs, split)
        if (known.isEmpty()) return groups
        return mergeByTmdb(groups) { v -> known[MediaRef(kind, v.title).key] ?: known[MediaRef(kind, v.title, v.year).key] }
    }
}

/** Index des versions de l'app ; null (tests, aperçus) = aucune version connue, aucun bouton « Version ». */
val LocalVersionIndexes = androidx.compose.runtime.staticCompositionLocalOf<VersionIndexes?> { null }

/** Index des versions gardé sur la télé (un fichier par sorte), relu seulement si son empreinte est la même. */
interface VersionIndexDisk {
    fun load(kind: MediaKind, fingerprint: String): Map<String, List<Version>>?
    fun save(kind: MediaKind, fingerprint: String, groups: Map<String, List<Version>>)
}

/** Fichier texte simple (séparateurs de contrôle), écrit d'un coup puis renommé (jamais à moitié). */
class FileVersionIndexDisk(private val dir: java.io.File) : VersionIndexDisk {
    private fun file(kind: MediaKind) = java.io.File(dir.apply { mkdirs() }, "versions-${kind.name.lowercase()}.txt")

    override fun load(kind: MediaKind, fingerprint: String): Map<String, List<Version>>? = runCatching {
        val f = file(kind)
        if (!f.exists()) return null
        f.bufferedReader().use { r ->
            if (r.readLine() != fingerprint) return null
            val out = LinkedHashMap<String, List<Version>>()
            r.lineSequence().forEach { line ->
                val key = line.substringBefore(GROUP)
                val versions = line.substringAfter(GROUP).split(ITEM).filter { it.isNotEmpty() }.map { decode(it) }
                out[key] = versions
            }
            out
        }
    }.getOrNull()

    override fun save(kind: MediaKind, fingerprint: String, groups: Map<String, List<Version>>) {
        val f = file(kind)
        val tmp = java.io.File(f.path + ".tmp")
        tmp.bufferedWriter().use { w ->
            w.write(fingerprint); w.write("\n")
            groups.forEach { (key, vs) ->
                w.write(clean(key)); w.write(GROUP.toString())
                vs.forEachIndexed { i, v -> if (i > 0) w.write(ITEM.toString()); w.write(encode(v)) }
                w.write("\n")
            }
        }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    private fun clean(s: String?) = s.orEmpty().replace(GROUP, ' ').replace(ITEM, ' ').replace(FIELD, ' ').replace('\n', ' ').replace('\r', ' ')

    private fun encode(v: Version) = listOf(v.id, v.categoryId, v.title, v.tag.lang?.name.orEmpty(), if (v.tag.uhd) "1" else "",
        if (v.tag.hdr) "1" else "", v.tag.source.orEmpty(), v.cover.orEmpty(), v.year.orEmpty()).joinToString(FIELD.toString()) { clean(it) }

    private fun decode(s: String): Version {
        val p = s.split(FIELD)
        fun at(i: Int) = p.getOrNull(i).orEmpty()
        val lang = at(3).takeIf { it.isNotEmpty() }?.let { n -> home.iptv.shared.versions.Lang.entries.firstOrNull { it.name == n } }
        return Version(at(0), at(1), at(2), home.iptv.shared.versions.VersionTag(lang, at(4) == "1", at(5) == "1", at(6).ifEmpty { null }),
            at(7).ifEmpty { null }, at(8).ifEmpty { null })
    }

    private companion object { const val GROUP = '\u0001'; const val ITEM = '\u0002'; const val FIELD = '\u0003' }
}
