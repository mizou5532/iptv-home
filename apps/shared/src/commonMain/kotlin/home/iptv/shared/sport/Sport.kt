package home.iptv.shared.sport

import kotlinx.serialization.json.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Compétitions suivies. [key] sert aussi de clé d'apprentissage ; [espnPath] = chemin ESPN (null pour la LNH). */
enum class League(val key: String, val label: String, val espnPath: String?, val typical: Duration, val searchWords: List<String>) {
    NHL("nhl", "LNH", null, 3.hours, listOf("lnh", "nhl", "hockey")),
    NFL("nfl", "NFL", "football/nfl", 3.5.hours, listOf("nfl", "football")),
    NBA("nba", "NBA", "basketball/nba", 3.hours, listOf("nba", "basket")),
    LIGA("esp.1", "Liga", "soccer/esp.1", 2.25.hours, listOf("liga", "laliga")),
    UCL("uefa.champions", "Ligue des champions", "soccer/uefa.champions", 2.25.hours, listOf("champions", "uefa")),
    COPA("esp.copa_del_rey", "Coupe du Roi", "soccer/esp.copa_del_rey", 2.25.hours, listOf("copa", "rey")),
    SUPERCOPA("esp.super_cup", "Supercoupe d'Espagne", "soccer/esp.super_cup", 2.25.hours, listOf("supercopa", "supercoupe"));
    companion object { fun of(key: String) = entries.firstOrNull { it.key == key } }
}

enum class MatchState { UPCOMING, LIVE, FINAL }

data class Match(
    val id: String,
    val league: League,
    val start: Instant,
    val state: MatchState,
    val home: String,
    val away: String,
    /** Équipe suivie (Canadiens, Barça) présente dans le match. */
    val followed: Boolean,
    /** Codes réseau canadiens officiels (LNH seulement), dans l'ordre de la LNH. */
    val broadcasters: List<String> = emptyList(),
) {
    val title: String get() = "$away – $home"
}

const val CANADIENS = "MTL"
const val BARCA_ESPN_ID = "83"

private fun JsonElement?.obj() = this as? JsonObject
private fun JsonElement?.arr() = (this as? JsonArray).orEmpty()
private fun JsonElement?.str() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun instant(s: String?) = s?.let { runCatching { Instant.parse(it.replace(Regex("T(\\d\\d):(\\d\\d)Z$"), "T$1:$2:00Z")) }.getOrNull() }

/** API officielle LNH (`/v1/schedule/<date>`) ; une réponse illisible donne une liste vide (jamais d'exception). */
fun parseNhlSchedule(text: String): List<Match> = runCatching {
    val root = Json.parseToJsonElement(text).obj() ?: return emptyList()
    root["gameWeek"].arr().flatMap { day -> day.obj()?.get("games").arr() }.mapNotNull { g ->
        val o = g.obj() ?: return@mapNotNull null
        val id = (o["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
        val start = instant(o["startTimeUTC"].str()) ?: return@mapNotNull null
        fun team(key: String): Pair<String, String> {
            val t = o[key].obj()
            val name = listOfNotNull(t?.get("placeName").obj()?.get("fr").str() ?: t?.get("placeName").obj()?.get("default").str(),
                t?.get("commonName").obj()?.get("default").str()).joinToString(" ").ifBlank { t?.get("abbrev").str().orEmpty() }
            return (t?.get("abbrev").str().orEmpty()) to name
        }
        val (awayCode, away) = team("awayTeam")
        val (homeCode, home) = team("homeTeam")
        val state = when (o["gameState"].str()) { "LIVE", "CRIT" -> MatchState.LIVE; "FINAL", "OFF" -> MatchState.FINAL; else -> MatchState.UPCOMING }
        val tv = o["tvBroadcasts"].arr().mapNotNull { it.obj() }.filter { it["countryCode"].str() == "CA" }
            .mapNotNull { it["network"].str()?.trim()?.takeIf(String::isNotEmpty) }.distinct()
        Match("nhl:$id", League.NHL, start, state, home, away, CANADIENS in setOf(awayCode, homeCode), tv)
    }
}.getOrDefault(emptyList())

/** Tableau d'ESPN (`scoreboard?dates=…`) ; diffuseurs ESPN (américains) ignorés. */
fun parseEspnScoreboard(league: League, text: String): List<Match> = runCatching {
    val root = Json.parseToJsonElement(text).obj() ?: return emptyList()
    root["events"].arr().mapNotNull { e ->
        val o = e.obj() ?: return@mapNotNull null
        val id = o["id"].str() ?: return@mapNotNull null
        val start = instant(o["date"].str()) ?: return@mapNotNull null
        val comp = o["competitions"].arr().firstOrNull().obj() ?: return@mapNotNull null
        val teams = comp["competitors"].arr().mapNotNull { it.obj() }
        fun side(which: String) = teams.firstOrNull { it["homeAway"].str() == which }?.get("team").obj()
        val home = side("home"); val away = side("away")
        val state = when (o["status"].obj()?.get("type").obj()?.get("state").str()) { "in" -> MatchState.LIVE; "post" -> MatchState.FINAL; else -> MatchState.UPCOMING }
        val followed = league.espnPath?.startsWith("soccer/") == true && teams.any { it["team"].obj()?.get("id").str() == BARCA_ESPN_ID }
        Match("${league.key}:$id", league, start, state, home?.get("displayName").str().orEmpty(), away?.get("displayName").str().orEmpty(), followed)
    }
}.getOrDefault(emptyList())

/** Jour local (jours depuis 1970) pour un décalage horaire donné. */
fun localDay(at: Instant, offsetSeconds: Int): Long = (at.epochSeconds + offsetSeconds).floorDiv(86_400L)

/** Un match terminé (ou supposé terminé : début + durée typique + 30 min) n'est plus proposé. */
fun isOver(m: Match, now: Instant): Boolean = m.state == MatchState.FINAL || now > m.start + m.league.typical + 30.minutes

data class SportBoard(val mine: List<Match>, val tonight: List<Match>)

/**
 * « Mes équipes » : matchs suivis en cours ou dans les 7 jours, en cours d'abord puis par heure.
 * « Ce soir » : matchs du jour local (ou en cours), toutes compétitions, par heure ; sans doublon avec « Mes équipes ».
 */
fun buildBoard(matches: List<Match>, now: Instant, offsetSeconds: Int): SportBoard {
    val alive = matches.distinctBy { it.id }.filterNot { isOver(it, now) }
    val order = compareBy<Match>({ if (it.state == MatchState.LIVE) 0 else 1 }, { it.start }, { it.id })
    val mine = alive.filter { it.followed && it.start <= now + (7 * 24).hours }.sortedWith(order)
    val today = localDay(now, offsetSeconds)
    val mineIds = mine.mapTo(HashSet()) { it.id }
    val tonight = alive.filter { it.id !in mineIds && (it.state == MatchState.LIVE || localDay(it.start, offsetSeconds) == today) }.sortedWith(order)
    return SportBoard(mine, tonight)
}
