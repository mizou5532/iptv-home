package home.iptv.shared.sport

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Classements (une ligne par équipe, sous son nom sur l'affiche) : « 3e Atlantique · 24 pts · 4 victoires de suite »,
 * « 1er de la Liga · 33 pts », « 8-1 · 1er AFC · 5 victoires de suite ».
 *
 * Clés : « nhl:MTL » (sigle de l'horaire officiel), « nfl:12 », « nba:5 » (identifiant ESPN) et « soccer:83 »
 * (identifiant ESPN, tous championnats confondus : un match de coupe du Barça montre son rang en Liga).
 */
const val NHL_STANDINGS_URL = "https://api-web.nhle.com/v1/standings/now"
private const val ESPN_STANDINGS = "https://site.api.espn.com/apis/v2/sports/"

/** Championnats dont on lit le classement (les coupes n'en ont pas d'utile). */
fun League.hasStandings(): Boolean = this != League.COPA && this != League.SUPERCOPA && this != League.UCL && this != League.UEL

/** Adresse du classement d'une ligue. */
fun standingsUrl(league: League): String? = when {
    league == League.NHL -> NHL_STANDINGS_URL
    !league.hasStandings() || league.espnPath == null -> null
    else -> "$ESPN_STANDINGS${league.espnPath}/standings"
}

/** Clé de classement d'une équipe d'un match ([home] : équipe à domicile, sinon visiteurs). */
fun standingKey(m: Match, home: Boolean): String = when {
    m.league == League.NHL -> "nhl:" + (if (home) m.homeAbbr else m.awayAbbr).uppercase()
    m.league.soccer -> "soccer:" + (if (home) m.homeId else m.awayId)
    else -> m.league.key + ":" + (if (home) m.homeId else m.awayId)
}

/** « 1er », « 2e »… */
fun ordinal(n: Int): String = if (n == 1) "1er" else "${n}e"

private val CONFERENCES = mapOf("East" to "Est", "West" to "Ouest")
private val NHL_DIVISIONS = mapOf("Atlantic" to "Atlantique", "Metropolitan" to "Métropolitaine", "Central" to "Centrale", "Pacific" to "Pacifique")

/** « 4 victoires de suite », « 2 défaites de suite » ; rien pour une série de 1 ou un code inconnu. */
fun streakText(code: String?, count: Int?): String? {
    val n = count ?: return null
    if (n < 2) return null
    return when (code?.uppercase()) {
        "W" -> "$n victoires de suite"
        "L" -> "$n défaites de suite"
        else -> null
    }
}

private fun JsonElement?.obj() = this as? JsonObject
private fun JsonElement?.arr() = (this as? JsonArray).orEmpty()
private fun JsonElement?.str() = (this as? JsonPrimitive)?.content
private fun JsonElement?.int() = (this as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()

/** Classement officiel de la LNH (`/v1/standings/now`). Réponse illisible : vide (jamais d'exception). */
fun parseNhlStandings(text: String): Map<String, String> = runCatching {
    val root = Json.parseToJsonElement(text).obj() ?: return emptyMap()
    root["standings"].arr().mapNotNull { it.obj() }.mapNotNull { t ->
        val abbr = t["teamAbbrev"].obj()?.get("default").str() ?: return@mapNotNull null
        val rank = t["divisionSequence"].int() ?: return@mapNotNull null
        val division = t["divisionName"].str()?.let { NHL_DIVISIONS[it] ?: it }.orEmpty()
        val pts = t["points"].int()
        val line = listOfNotNull(
            "${ordinal(rank)} $division".trim(),
            pts?.let { "$it pts" },
            streakText(t["streakCode"].str(), t["streakCount"].int()),
        ).joinToString(" · ")
        "nhl:${abbr.uppercase()}" to line
    }.toMap()
}.getOrDefault(emptyMap())

/**
 * Classement ESPN (`/apis/v2/sports/<sport>/<ligue>/standings`) : groupes (conférences, ou le championnat entier),
 * chaque équipe avec ses statistiques. Soccer : rang et points ; football et basket : bilan, rang de conférence, série.
 */
fun parseEspnStandings(league: League, text: String): Map<String, String> = runCatching {
    val root = Json.parseToJsonElement(text).obj() ?: return emptyMap()
    val groups = root["children"].arr().mapNotNull { it.obj() }.ifEmpty { listOf(root) }
    groups.flatMap { g ->
        val groupName = (g["abbreviation"].str() ?: g["name"].str().orEmpty()).let { CONFERENCES[it] ?: it }
        g["standings"].obj()?.get("entries").arr().mapNotNull { it.obj() }.mapNotNull { e ->
            val id = e["team"].obj()?.get("id").str() ?: return@mapNotNull null
            val stats = e["stats"].arr().mapNotNull { it.obj() }.associate { (it["name"].str() ?: "") to it["displayValue"].str().orEmpty() }
            fun stat(name: String) = stats[name]?.takeIf { it.isNotBlank() && it != "-" }
            if (league.soccer) {
                val rank = stat("rank")?.toIntOrNull() ?: return@mapNotNull null
                val line = listOfNotNull("${ordinal(rank)} de la ${league.label}", stat("points")?.let { "$it pts" }).joinToString(" · ")
                "soccer:$id" to line
            } else {
                val record = stat("overall") ?: listOfNotNull(stat("wins"), stat("losses")).takeIf { it.size == 2 }?.joinToString("-")
                val seed = stat("playoffSeed")?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 }
                val streak = stat("streak")?.let { Regex("^([WL])(\\d+)$").find(it) }?.let { streakText(it.groupValues[1], it.groupValues[2].toInt()) }
                val line = listOfNotNull(record, seed?.let { "${ordinal(it)} $groupName".trim() }, streak).joinToString(" · ")
                if (line.isBlank()) null else "${league.key}:$id" to line
            }
        }
    }.toMap()
}.getOrDefault(emptyMap())
