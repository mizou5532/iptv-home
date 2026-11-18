package home.iptv.shared.search

import home.iptv.shared.versions.versionCore
import home.iptv.shared.versions.versionYear
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Un film ou une série d'une personne (TMDB) : titre français, titre original, année ; [role] « acteur » ou « realisateur ». */
@Serializable
data class PersonCredit(
    val kind: String, val title: String, @SerialName("original_title") val originalTitle: String? = null,
    val year: Int? = null, val role: String = "acteur",
)

@Serializable
data class PersonInfo(val name: String, val department: String? = null)

/** Réponse de la fonction serveur : la personne trouvée (ou rien) et ses titres. */
@Serializable
data class PersonCredits(val person: PersonInfo? = null, val credits: List<PersonCredit> = emptyList())

private val json = Json { ignoreUnknownKeys = true }
fun parsePersonCredits(text: String): PersonCredits? = runCatching { json.decodeFromString(PersonCredits.serializer(), text) }.getOrNull()

/**
 * Titres du catalogue où joue (ou que réalise) la personne : même titre nettoyé (titre français ou original, sans
 * langue ni qualité : « FR - Training Day (2001) » = « Training Day ») et, quand les deux années sont connues, même
 * année à un an près (deux films de même nom ne sont pas confondus). Ordre : celui de [credits] (les plus connus d'abord).
 * [kind] : « movie » ou « series ».
 */
fun matchPersonCredits(items: List<SearchItem>, credits: List<PersonCredit>, kind: String): List<SearchItem> {
    val byCore = HashMap<String, MutableList<SearchItem>>()
    for (item in items) {
        val core = versionCore(item.title)
        if (core.isNotEmpty()) byCore.getOrPut(core) { mutableListOf() } += item
    }
    val out = LinkedHashMap<String, SearchItem>()
    for (c in credits) {
        if (c.kind != kind) continue
        val cores = listOfNotNull(c.title, c.originalTitle).map(::versionCore).filter { it.isNotEmpty() }.distinct()
        for (core in cores) for (item in byCore[core].orEmpty()) {
            val y = versionYear(item.title)?.toIntOrNull()
            if (c.year != null && y != null && kotlin.math.abs(c.year - y) > 1) continue
            out.putIfAbsent(item.id, item)
        }
    }
    return out.values.toList()
}
