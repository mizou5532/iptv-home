package home.iptv.shared.sport

import home.iptv.shared.search.SearchIndex
import home.iptv.shared.search.SearchItem
import home.iptv.shared.text.searchNorm

/**
 * Un groupe de chaînes visible du Direct (copies masquées déjà retirées), ou une chaîne d'une catégorie « sport »
 * ([direct] vrai : catégorie choisie pour le Sport, même masquée dans le Direct ; lue par son identifiant).
 */
data class SportChannel(val groupId: String, val title: String, val categoryName: String, val anchorSourceId: String = groupId, val direct: Boolean = false) {
    val french: Boolean get() = isFrench(title, categoryName)
    val sporty: Boolean get() = "sport" in searchNorm(categoryName) || "sport" in searchNorm(title)
    /** Clé pour la mémoire de la maison ([channelKey]), calculée une fois. */
    val key: String by lazy { channelKey(title) }
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
enum class ChannelReason { PREFERRED, CONFIRMED, EVENT, OFFICIAL, TEAM, VOTED, LEARNED, NAME, FAVORITE, SPORT }

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
 * Chaînes d'un match. Seules les chaînes FIABLES sont proposées ([likely]) :
 * - [preferred] : l'étoile du profil (toujours la première) ;
 * - CONFIRMED : le guide du fournisseur annonce le match sur cette chaîne ([confirmed]) ;
 * - EVENT : chaîne d'événement dont le nom cite les deux équipes et le bon jour ([extra], catégories du Sport) ;
 * - OFFICIAL : diffuseur annoncé (LNH : chaînes canadiennes ; ESPN : noms des chaînes) ;
 * - TEAM : chaîne d'une des deux équipes (« US: MONTREAL CANADIENS ») ;
 * - VOTED : regardée par la maison pour ces équipes ([votes] > 0). LEARNED (regardée pour la ligue) : parcours seulement.
 * Puis triées par langue ([regions] : français du Québec, de France, américaines… au choix) et par fiabilité.
 * Une chaîne écartée par la maison (« Pas le bon match », [votes] < 0) n'est plus proposée. Le reste des chaînes
 * sport sert seulement à parcourir ([all]) : plus de devinettes par mots génériques. Rien de masqué hors [extra].
 */
fun sportCandidates(match: Match, learned: List<String>, channels: List<SportChannel>, favorites: List<String> = emptyList(), preferred: List<String> = emptyList()): SportCandidates =
    sportCandidates(match, learned, SportChannelIndex(channels), favorites, preferred)

fun sportCandidates(
    match: Match, learned: List<String>, idx: SportChannelIndex, favorites: List<String> = emptyList(), preferred: List<String> = emptyList(),
    extra: List<SportChannel> = emptyList(), extraIdx: SportExtraIndex = SportExtraIndex(extra), confirmed: Set<String> = emptySet(), votes: Map<String, Int> = emptyMap(),
    regions: List<Region> = regionOrder(null), offsetSeconds: Int = 0,
): SportCandidates {
    val extra = extraIdx.channels
    val byId = idx.byId + extra.associateBy { it.groupId }
    fun vote(ch: SportChannel) = if (votes.isEmpty()) 0 else votes[ch.key] ?: 0
    val learnedCh = learned.mapNotNull { idx.byId[it] }
    // Codes LNH traduits ; noms d'ESPN (TNT, ESPN, CBS…) cherchés tels quels.
    val official = match.broadcasters.mapNotNull { code -> (NETWORK_NAMES[code.uppercase()] ?: listOf(code)).firstNotNullOfOrNull { idx.lookupName(it) } }
    val events = extraIdx.events(match, offsetSeconds)
    val teams = extra.filter { teamChannelFor(it, match) }
    val voted = if (votes.values.none { it > 0 }) emptyList() else (idx.channels + extra).filter { vote(it) > 0 }
    val preferredCh = preferred.mapNotNull { byId[it] }
    val reasons = LinkedHashMap<String, ChannelReason>()
    fun mark(list: List<SportChannel>, r: ChannelReason) = list.forEach { reasons.putIfAbsent(it.groupId, r) }
    mark(preferredCh, ChannelReason.PREFERRED)
    mark(confirmed.mapNotNull { byId[it] }, ChannelReason.CONFIRMED)
    mark(events, ChannelReason.EVENT); mark(official, ChannelReason.OFFICIAL); mark(teams, ChannelReason.TEAM)
    mark(voted, ChannelReason.VOTED)
    val credible = reasons.keys.mapNotNull { byId[it] }
        .filter { it.groupId in preferred || vote(it) >= 0 }
    fun regionRank(ch: SportChannel) = regions.indexOf(regionOf(ch)).let { if (it < 0) regions.size else it }
    val ordered = preferredCh + credible.filter { it.groupId !in preferred }
        .sortedWith(compareBy({ regionRank(it) }, { reasons[it.groupId]!!.ordinal }, { it.title.length }))
    val likely = ordered.distinctBy { it.groupId }
    // Apprises pour la ligue (« déjà regardée pour le hockey ») : en tête du parcours seulement, elles ne disent rien de CE match.
    val browse = learnedCh + (idx.sporty + favorites.mapNotNull { idx.byId[it] }).filter { vote(it) >= 0 }.sortedBy { regionRank(it) }
    mark(learnedCh, ChannelReason.LEARNED); mark(browse, ChannelReason.SPORT)
    val proposed = likely.take(PROPOSED_MAX)
    val all = (likely + browse).distinctBy { it.groupId }
    return SportCandidates(proposed, all, likely, reasons)
}

/** Clé d'une chaîne pour la mémoire de la maison : son nom normalisé (le même sur toutes les télés). */
fun channelKey(title: String): String = searchNorm(title).replace(Regex("[^a-z0-9+]+"), " ").trim()

/** Ajoute [groupId] en tête des chaînes apprises (sans doublon, au plus 6). */
fun learn(previous: List<String>, groupId: String): List<String> = (listOf(groupId) + previous.filter { it != groupId }).take(LEARNED_MAX)

/** Clés des deux équipes d'un match pour la mémoire de la maison (« nhl:MTL », « soccer:83 »). */
fun voteKeys(m: Match): List<String> = preferenceKeys(m, emptyList()).filterNot { it.endsWith(":") }

/** Scores de la maison pour un match : somme des votes des deux équipes, par chaîne. */
fun matchVotes(m: Match, votes: List<home.iptv.shared.sync.SportVote>): Map<String, Int> {
    val keys = voteKeys(m).toSet()
    return votes.filter { it.teamKey in keys }.groupBy { it.channelKey }.mapValues { (_, v) -> v.sumOf { it.score } }
}
