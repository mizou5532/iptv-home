package home.iptv.shared.groups

import home.iptv.shared.text.searchNorm
import kotlinx.serialization.Serializable

data class GroupSource(val id: String, val categoryId: String, val name: String, val position: Int)
@Serializable
data class ManualGroup(val id: String, val title: String, val sourceIds: List<String>, val blockedAutoKeys: List<String>)
@Serializable
data class GroupSnapshot(val rev: Long, val rulesVersion: Int, val groups: List<ManualGroup>)
data class LiveGroup(val id: String, val title: String, val sources: List<GroupSource>)

// Meme ensemble d'espaces que JavaScript ; les separateurs ne sont retires qu'avec un suffixe.
private const val SPACE = "[\\t-\\r \\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]"
private val suffix = Regex("(?:$SPACE+|$SPACE*[-|/]$SPACE*)(?:hd|fhd|uhd|4k|sd)$|$SPACE*\\[(?:hd|fhd|uhd|4k|sd)\\]$|$SPACE*\\((?:hd|fhd|uhd|4k|sd)\\)$", RegexOption.IGNORE_CASE)
private val technicalOnly = Regex("^(?:hd|fhd|uhd|4k|sd)$")
private val edges = Regex("^$SPACE+|$SPACE+$")
private val ambiguousControl = Regex("[\\u0000-\\u0008\\u000e-\\u001f\\u007f-\\u009f]")
private fun String.trimTitle() = edges.replace(this, "")
/** Titre d'un groupe : le nom de sa première copie sans la qualité (« ESPN HD » → « ESPN »). */
fun baseTitle(name: String): String {
    var title = name.trimTitle()
    while (true) {
        val stripped = suffix.replace(title, "").trimTitle()
        if (stripped == title) return title
        title = stripped
    }
}

/**
 * Clé de regroupement (règle 2, 17 nov. 2026) : pays + nom nettoyé. Deux chaînes de même clé sont des copies de la
 * même chaîne. Le pays vient du nom (« US| ESPN ») sinon de la catégorie ([categoryCountry]) ; jamais deux pays
 * mélangés (choix du propriétaire). Le nom perd la qualité (HD, FHD, 4K, ᴴᴰ, RAW 60fps…) et les mots de secours
 * (Backup, Alt). Même règle sur la page (`groups.ts`), mêmes exemples (`fixtures/live-groups-v2.json`).
 */
fun autoKey(name: String, categoryCountry: String? = null): String? {
    // Controles C0/C1 ambigus exclus ; tabulations et retours standards restent permis.
    if (ambiguousControl.containsMatchIn(name)) return null
    // Ligne de séparation du fournisseur (« ##### AT&T ##### ») : un titre, jamais regroupé.
    if (home.iptv.shared.text.separatorTitle(name) != null) return null
    val core = searchNorm(home.iptv.shared.text.groupCore(name))
    if (!core.any { c -> c in 'a'..'z' } || technicalOnly.matches(core)) return null
    val country = (home.iptv.shared.text.channelCountry(name) ?: categoryCountry)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    return if (country != null) "$country|$core" else core
}

private val sourceOrder = compareBy<GroupSource> { it.position }.thenBy { it.id }

/**
 * Pur : indexation O(n + corrections), puis tri O(n log n), sans comparaison de paires. [keyOf] : clé automatique
 * d'une chaîne (par défaut [autoKey] avec [countryOf]) et [titleOf] : titre d'un groupe ; la télé les garde en mémoire
 * (même nom : même résultat).
 */
fun buildGroups(
    sources: List<GroupSource>,
    snapshot: GroupSnapshot,
    keyOf: ((GroupSource) -> String?)? = null,
    titleOf: (String) -> String = ::baseTitle,
    countryOf: (GroupSource) -> String? = { null },
): List<LiveGroup> {
    require(snapshot.rulesVersion == 1) { "Version de regroupement inconnue" }
    val byId = sources.associateBy { it.id }
    val claimed = mutableSetOf<String>()
    val blocked = snapshot.groups.flatMap { it.blockedAutoKeys }.toHashSet()
    val result = mutableListOf<LiveGroup>()
    for (manual in snapshot.groups.sortedBy { it.id }) {
        val members = mutableListOf<GroupSource>()
        for (id in manual.sourceIds) {
            val source = byId[id]
            if (source != null && claimed.add(id)) members.add(source)
        }
        if (members.isNotEmpty()) result.add(LiveGroup("manual:${manual.id}", manual.title, members.sortedWith(sourceOrder)))
    }
    val automatic = mutableMapOf<String, MutableList<GroupSource>>()
    for (source in sources) {
        if (source.id in claimed) continue
        val key = if (keyOf != null) keyOf(source) else autoKey(source.name, countryOf(source))
        if (key == null || key in blocked) {
            result.add(LiveGroup("source:${source.id}", source.name, listOf(source)))
        } else {
            automatic.getOrPut(key) { mutableListOf() }.add(source)
        }
    }
    for ((key, members) in automatic) {
        val sorted = members.sortedWith(sourceOrder)
        result.add(LiveGroup("auto:v1:$key", titleOf(sorted.first().name), sorted))
    }
    return result.sortedWith(compareBy<LiveGroup> { it.sources.first().position }.thenBy { it.id })
}
