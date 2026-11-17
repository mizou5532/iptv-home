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
) {
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
        val index = VersionIndex(group(kind, inputs(kind, items(kind))))
        synchronized(lock) { built[kind] = index }
        _revision.value++
        return index
    }

    /** Catalogue complété ou réglage changé : les index seront refaits à la prochaine demande. */
    fun invalidate() { synchronized(lock) { built.clear() }; _revision.value++ }

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
