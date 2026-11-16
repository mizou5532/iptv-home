package home.iptv.shared.sport

import home.iptv.shared.search.SearchIndex
import home.iptv.shared.search.SearchItem
import home.iptv.shared.text.searchNorm

/** Un groupe de chaînes visible du Direct (copies masquées déjà retirées). */
data class SportChannel(val groupId: String, val title: String, val categoryName: String) {
    val french: Boolean get() = isFrench(title, categoryName)
    val sporty: Boolean get() = "sport" in searchNorm(categoryName) || "sport" in searchNorm(title)
}

private val FRENCH_WORDS = setOf("fr", "french", "francais", "quebec", "qc", "rds", "tva")
fun isFrench(title: String, category: String): Boolean {
    val words = (searchNorm(title) + " " + searchNorm(category)).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()
    return words.any { it in FRENCH_WORDS }
}

/** Codes réseau LNH (diffuseurs canadiens) → noms à chercher parmi les chaînes du fournisseur. */
val NETWORK_NAMES: Map<String, List<String>> = mapOf(
    "RDS" to listOf("RDS"), "RDS2" to listOf("RDS 2"), "RDSI" to listOf("RDS Info"),
    "TVAS" to listOf("TVA Sports"), "TVAS2" to listOf("TVA Sports 2"), "TVA" to listOf("TVA"),
    "SN" to listOf("Sportsnet"), "SNE" to listOf("Sportsnet East"), "SNO" to listOf("Sportsnet Ontario"),
    "SNW" to listOf("Sportsnet West"), "SNP" to listOf("Sportsnet Pacific"), "SN1" to listOf("Sportsnet One"),
    "SN360" to listOf("Sportsnet 360"), "SN+" to listOf("Sportsnet Plus", "Sportsnet+"),
    "TSN" to listOf("TSN"), "TSN1" to listOf("TSN 1"), "TSN2" to listOf("TSN 2"), "TSN3" to listOf("TSN 3"),
    "TSN4" to listOf("TSN 4"), "TSN5" to listOf("TSN 5"),
    "CBC" to listOf("CBC"), "CITY" to listOf("Citytv", "City TV"), "PRIME" to listOf("Prime Video", "Amazon Prime"),
)

const val PROPOSED_MAX = 4
const val LEARNED_MAX = 6

/** Chaînes proposées sous le match (au plus 4) et suite du parcours avec Droite (toutes les chaînes « sport »). */
data class SportCandidates(val proposed: List<SportChannel>, val all: List<SportChannel>)

private fun frenchFirst(list: List<SportChannel>) = list.sortedBy { if (it.french) 0 else 1 }

/** Meilleur groupe pour un nom (« TVA Sports ») : français d'abord, puis l'ordre du moteur de recherche. */
private fun lookup(index: SearchIndex, byId: Map<String, SportChannel>, name: String): SportChannel? {
    val wanted = searchNorm(name)
    val found = index.search(name).mapNotNull { byId[it.id] }
        // Le nom doit vraiment y être (« TSN 2 » ne doit pas donner « TSN 1 ») : mots de la demande tous présents.
        .filter { ch -> wanted.split(' ').all { w -> w in searchNorm(ch.title).split(Regex("[^a-z0-9+]+")) } }
    // Nom exact (hors mentions de qualité) d'abord : « TVA Sports » préfère « TVA Sports HD » à « TVA Sports 2 ».
    fun exact(ch: SportChannel) = searchNorm(ch.title).split(Regex("[^a-z0-9+]+")).filter { it.isNotEmpty() && it !in QUALITY } == wanted.split(' ')
    return found.sortedWith(compareBy({ if (exact(it)) 0 else 1 }, { if (it.french) 0 else 1 })).firstOrNull()
}

private val QUALITY = setOf("hd", "fhd", "uhd", "sd", "4k", "8k", "hevc", "raw", "60fps", "vip", "h265", "backup")

/**
 * Ordre (sans doublon) : LNH = diffuseurs officiels puis appris ; autres = appris puis repli.
 * Repli : chaînes « sport » trouvées par les mots de la compétition et des équipes, puis favoris.
 * Toujours dans [channels] (visibles seulement) : rien de masqué ne peut sortir d'ici.
 */
fun sportCandidates(match: Match, learned: List<String>, channels: List<SportChannel>, favorites: List<String> = emptyList()): SportCandidates {
    val byId = channels.associateBy { it.groupId }
    val index = SearchIndex(channels.map { SearchItem(it.groupId, it.title, setOf(it.categoryName.ifBlank { "?" })) })
    val learnedCh = learned.mapNotNull { byId[it] }
    val official = match.broadcasters.mapNotNull { code -> NETWORK_NAMES[code.uppercase()]?.firstNotNullOfOrNull { lookup(index, byId, it) } }
    val sporty = channels.filter { it.sporty }
    val words = (match.league.searchWords + listOf(match.home, match.away).flatMap { searchNorm(it).split(' ') }).filter { it.length >= 3 }
    val byWords = words.flatMap { w -> index.search(w).mapNotNull { byId[it.id] } }.filter { it.sporty }
    val fallback = frenchFirst(byWords.distinct()) + favorites.mapNotNull { byId[it] }
    val ordered = (if (match.league == League.NHL) frenchFirst(official) + learnedCh + fallback else learnedCh + frenchFirst(official) + fallback)
        .distinctBy { it.groupId }
    val proposed = ordered.take(PROPOSED_MAX)
    val all = (ordered + frenchFirst(sporty)).distinctBy { it.groupId }
    return SportCandidates(proposed, all)
}

/** Ajoute [groupId] en tête des chaînes apprises (sans doublon, au plus 6). */
fun learn(previous: List<String>, groupId: String): List<String> = (listOf(groupId) + previous.filter { it != groupId }).take(LEARNED_MAX)
