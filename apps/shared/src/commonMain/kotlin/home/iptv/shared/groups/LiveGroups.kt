package home.iptv.shared.groups

import home.iptv.shared.text.searchNorm

data class GroupSource(val id: String, val categoryId: String, val name: String, val position: Int)
data class ManualGroup(val id: String, val title: String, val sourceIds: List<String>, val blockedAutoKeys: List<String>)
data class GroupSnapshot(val rev: Long, val rulesVersion: Int, val groups: List<ManualGroup>)
data class LiveGroup(val id: String, val title: String, val sources: List<GroupSource>)

// Meme ensemble d'espaces que JavaScript ; les separateurs ne sont retires qu'avec un suffixe.
private const val SPACE = "[\\t-\\r \\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]"
private val suffix = Regex("(?:$SPACE+|$SPACE*[-|/]$SPACE*)(?:hd|fhd|uhd|4k|sd)$|$SPACE*\\[(?:hd|fhd|uhd|4k|sd)\\]$|$SPACE*\\((?:hd|fhd|uhd|4k|sd)\\)$", RegexOption.IGNORE_CASE)
private val technicalOnly = Regex("^(?:hd|fhd|uhd|4k|sd)$")
private fun String.trimTitle() = trim { it.isWhitespace() || it == '\uFEFF' }
private fun baseTitle(name: String): String {
    var title = name.trimTitle()
    while (true) {
        val stripped = suffix.replace(title, "").trimTitle()
        if (stripped == title) return title
        title = stripped
    }
}

fun autoKey(name: String): String? {
    val key = searchNorm(baseTitle(name))
    return key.takeIf { it.any { c -> c in 'a'..'z' } && !technicalOnly.matches(it) }
}

private val sourceOrder = compareBy<GroupSource> { it.position }.thenBy { it.id }

/** Pur : indexation O(n + corrections), puis tri O(n log n), sans comparaison de paires. */
fun buildGroups(sources: List<GroupSource>, snapshot: GroupSnapshot): List<LiveGroup> {
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
        val key = autoKey(source.name)
        if (key == null || key in blocked) {
            result.add(LiveGroup("source:${source.id}", source.name, listOf(source)))
        } else {
            automatic.getOrPut(key) { mutableListOf() }.add(source)
        }
    }
    for ((key, members) in automatic) {
        val sorted = members.sortedWith(sourceOrder)
        result.add(LiveGroup("auto:v1:$key", baseTitle(sorted.first().name), sorted))
    }
    return result.sortedWith(compareBy<LiveGroup> { it.sources.first().position }.thenBy { it.id })
}
