package home.iptv.shared.versions

import home.iptv.shared.groups.withGroupLock
import home.iptv.shared.home.sha256Hex
import home.iptv.shared.meta.MediaKind
import home.iptv.shared.meta.MediaRef
import home.iptv.shared.search.SearchItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.Volatile

/** Index des versions gardé sur le disque (un fichier par sorte), relu seulement si son empreinte est la même. */
interface VersionIndexDisk {
    fun load(kind: MediaKind, fingerprint: String): Map<String, List<Version>>?
    fun save(kind: MediaKind, fingerprint: String, groups: Map<String, List<Version>>)
}

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
     * Index gardé d'un lancement à l'autre : relu tel quel si le catalogue, les séparations et les fiches connues n'ont
     * pas changé, au lieu de tout recalculer.
     */
    private val disk: VersionIndexDisk? = null,
    /** Enveloppe optionnelle pour le calcul (ex. priorité basse + mesure de performance côté télé). */
    private val measure: (MediaKind, () -> VersionIndex) -> VersionIndex = { _, block -> block() },
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
    fun current(kind: MediaKind): VersionIndex = withGroupLock(lock) { built[kind] } ?: VersionIndex.EMPTY

    /** Index de [kind], construit au besoin (à appeler hors du fil principal). */
    fun index(kind: MediaKind): VersionIndex {
        withGroupLock(lock) { built[kind] }?.let { return it }
        return withGroupLock(buildLock) {
            // Un autre appel vient peut-être de le construire pendant qu'on attendait.
            val existing = withGroupLock(lock) { built[kind] }
            if (existing != null) return@withGroupLock existing
            val gen = generation
            val index = measure(kind) { build(kind) }
            // Invalidé pendant le calcul (catalogue changé) : rendu tel quel, mais pas gardé.
            withGroupLock(lock) { if (generation == gen) built[kind] = index }
            _revision.value++
            index
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
        val sb = StringBuilder(inputs.size * 48 + 64)
        fun add(s: String?) { sb.append(s ?: "\u0000").append('\u001F') }
        add(FORMAT); add(kind.name)
        inputs.forEach { add(it.id); add(it.title); add(it.categoryId); add(it.categoryName); add(it.cover); add(it.year) }
        splits().sorted().forEach(::add)
        tmdb().entries.sortedBy { it.key }.forEach { add(it.key); add(it.value.toString()) }
        return sha256Hex(sb.toString().encodeToByteArray())
    }

    /** Catalogue complété ou réglage changé : les index seront refaits à la prochaine demande. */
    fun invalidate() { withGroupLock(lock) { built.clear(); generation++ }; _revision.value++ }

    private companion object { const val FORMAT = "versions-2" }

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
