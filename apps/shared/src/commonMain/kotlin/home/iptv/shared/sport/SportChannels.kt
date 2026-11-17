package home.iptv.shared.sport

import home.iptv.shared.search.SearchIndex
import home.iptv.shared.search.SearchItem
import home.iptv.shared.text.searchNorm

/** Un groupe de chaînes visible du Direct (copies masquées déjà retirées). */
data class SportChannel(val groupId: String, val title: String, val categoryName: String, val anchorSourceId: String = groupId) {
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

/** Pourquoi une chaîne est proposée pour un match (affiché dans le choix de la chaîne). */
enum class ChannelReason { PREFERRED, OFFICIAL, LEARNED, NAME, FAVORITE, SPORT }

/**
 * Chaînes proposées sous le match (au plus 4), les chaînes qui le diffusent probablement ([likely], dans l'ordre),
 * et suite du parcours avec Droite (toutes les chaînes « sport »). [reasons] : pourquoi chacune est là.
 */
data class SportCandidates(
    val proposed: List<SportChannel>,
    val all: List<SportChannel>,
    val likely: List<SportChannel> = proposed,
    val reasons: Map<String, ChannelReason> = emptyMap(),
)

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
 * Chaînes visibles prêtes pour la recherche, construites UNE fois pour tous les matchs (avant : un index complet
 * reconstruit pour chaque match, l'essentiel des 10 à 15 s d'attente de l'onglet Sport sur la télé). Les recherches
 * répétées d'un match à l'autre (« nba », « TSN 2 »…) sont gardées.
 */
class SportChannelIndex(val channels: List<SportChannel>) {
    internal val byId = channels.associateBy { it.groupId }
    internal val index = SearchIndex(channels.map { SearchItem(it.groupId, it.title, setOf(it.categoryName.ifBlank { "?" })) })
    internal val sporty = channels.filter { it.sporty }
    internal val sportyFrenchFirst = frenchFirst(sporty)
    private val names = HashMap<String, SportChannel?>()
    private val words = HashMap<String, List<SportChannel>>()

    internal fun lookupName(name: String): SportChannel? {
        synchronized(names) { if (name in names) return names[name] }
        return lookup(index, byId, name).also { synchronized(names) { names[name] = it } }
    }

    internal fun sportyByWord(word: String): List<SportChannel> =
        synchronized(words) { words[word] } ?: index.search(word).mapNotNull { byId[it.id] }.filter { it.sporty }.also { synchronized(words) { words[word] = it } }
}

/**
 * Ordre (sans doublon) : LNH = diffuseurs officiels puis appris ; autres = appris puis repli.
 * Repli : chaînes « sport » trouvées par les mots de la compétition et des équipes, puis favoris.
 * Toujours dans [channels] (visibles seulement) : rien de masqué ne peut sortir d'ici.
 */
fun sportCandidates(match: Match, learned: List<String>, channels: List<SportChannel>, favorites: List<String> = emptyList(), preferred: List<String> = emptyList()): SportCandidates =
    sportCandidates(match, learned, SportChannelIndex(channels), favorites, preferred)

fun sportCandidates(match: Match, learned: List<String>, idx: SportChannelIndex, favorites: List<String> = emptyList(), preferred: List<String> = emptyList()): SportCandidates {
    val byId = idx.byId
    val learnedCh = learned.mapNotNull { byId[it] }
    // Codes LNH traduits ; noms d'ESPN (TNT, ESPN, CBS…) cherchés tels quels.
    val official = match.broadcasters.mapNotNull { code -> (NETWORK_NAMES[code.uppercase()] ?: listOf(code)).firstNotNullOfOrNull { idx.lookupName(it) } }
    val words = (match.league.searchWords + listOf(match.home, match.away).flatMap { searchNorm(it).split(' ') }).filter { it.length >= 3 }
    val byWords = words.flatMap { idx.sportyByWord(it) }
    val byName = frenchFirst(byWords.distinct())
    val favCh = favorites.mapNotNull { byId[it] }
    // Chaîne préférée (étoile posée par appui long) : toujours la première.
    val preferredCh = preferred.mapNotNull { byId[it] }
    val ordered = (preferredCh + (if (match.league == League.NHL) frenchFirst(official) + learnedCh else learnedCh + frenchFirst(official)) + byName + favCh)
        .distinctBy { it.groupId }
    val reasons = HashMap<String, ChannelReason>()
    fun mark(list: List<SportChannel>, r: ChannelReason) = list.forEach { reasons.putIfAbsent(it.groupId, r) }
    mark(preferredCh, ChannelReason.PREFERRED); mark(official, ChannelReason.OFFICIAL); mark(learnedCh, ChannelReason.LEARNED)
    mark(byName, ChannelReason.NAME); mark(favCh, ChannelReason.FAVORITE); mark(idx.sporty, ChannelReason.SPORT)
    val proposed = ordered.take(PROPOSED_MAX)
    val all = (ordered + idx.sportyFrenchFirst).distinctBy { it.groupId }
    return SportCandidates(proposed, all, ordered, reasons)
}

/** Ajoute [groupId] en tête des chaînes apprises (sans doublon, au plus 6). */
fun learn(previous: List<String>, groupId: String): List<String> = (listOf(groupId) + previous.filter { it != groupId }).take(LEARNED_MAX)
