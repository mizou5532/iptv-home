package home.iptv.shared.sport

import kotlinx.serialization.json.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Compétitions suivies. [key] sert aussi de clé d'apprentissage ; [espnPath] = chemin ESPN (null pour la LNH). */
enum class League(val key: String, val label: String, val espnPath: String?, val typical: Duration, val searchWords: List<String>, val optional: Boolean = false) {
    NHL("nhl", "LNH", null, 3.hours, listOf("lnh", "nhl", "hockey")),
    NFL("nfl", "NFL", "football/nfl", 3.5.hours, listOf("nfl", "football")),
    NBA("nba", "NBA", "basketball/nba", 3.hours, listOf("nba", "basket")),
    LIGA("esp.1", "Liga", "soccer/esp.1", 2.25.hours, listOf("liga", "laliga")),
    UCL("uefa.champions", "Ligue des champions", "soccer/uefa.champions", 2.25.hours, listOf("champions", "uefa")),
    COPA("esp.copa_del_rey", "Coupe du Roi", "soccer/esp.copa_del_rey", 2.25.hours, listOf("copa", "rey")),
    SUPERCOPA("esp.super_cup", "Supercoupe d'Espagne", "soccer/esp.super_cup", 2.25.hours, listOf("supercopa", "supercoupe")),
    // Lues seulement si une équipe favorite y joue (une demande de plus par ligue).
    EPL("eng.1", "Premier League", "soccer/eng.1", 2.25.hours, listOf("premier", "epl"), optional = true),
    LIGUE1("fra.1", "Ligue 1", "soccer/fra.1", 2.25.hours, listOf("ligue 1", "ligue1"), optional = true),
    SERIEA("ita.1", "Serie A", "soccer/ita.1", 2.25.hours, listOf("serie a", "calcio"), optional = true),
    BUNDES("ger.1", "Bundesliga", "soccer/ger.1", 2.25.hours, listOf("bundesliga"), optional = true),
    UEL("uefa.europa", "Ligue Europa", "soccer/uefa.europa", 2.25.hours, listOf("europa", "uefa"), optional = true),
    MLS("usa.1", "MLS", "soccer/usa.1", 2.25.hours, listOf("mls"), optional = true);
    val soccer: Boolean get() = espnPath?.startsWith("soccer/") == true
    /** Sport (réglages de l'onglet Sport) : « hockey », « football », « basketball » ou « soccer ». */
    val sport: String get() = when (this) { NHL -> "hockey"; NFL -> "football"; NBA -> "basketball"; else -> "soccer" }
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
    /** Diffuseurs : codes réseau canadiens (LNH) ou noms des chaînes d'ESPN (américaines), dans l'ordre de la source. */
    val broadcasters: List<String> = emptyList(),
    /** Identifiants (ESPN) ou sigles (LNH) des équipes, logos, score et avancement (« 67' », « 2e pér. »). */
    val homeId: String = "", val awayId: String = "",
    val homeAbbr: String = "", val awayAbbr: String = "",
    val homeLogo: String? = null, val awayLogo: String? = null,
    val homeScore: Int? = null, val awayScore: Int? = null,
    val detail: String? = null,
    /** Couleur principale des équipes (« AF1E2D », sans #) : fond de l'affiche ; null si inconnue. */
    val homeColor: String? = null, val awayColor: String? = null,
    /** Nom court (« Canadiens », « Barcelona ») pour l'affiche ; vide si inconnu. */
    val homeShort: String = "", val awayShort: String = "",
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
        fun short(key: String) = o[key].obj()?.get("commonName").obj()?.get("default").str().orEmpty()
        fun team(key: String): Pair<String, String> {
            val t = o[key].obj()
            val name = listOfNotNull(t?.get("placeName").obj()?.get("fr").str() ?: t?.get("placeName").obj()?.get("default").str(),
                t?.get("commonName").obj()?.get("default").str()).joinToString(" ").ifBlank { t?.get("abbrev").str().orEmpty() }
            return (t?.get("abbrev").str().orEmpty()) to name
        }
        val (awayCode, away) = team("awayTeam")
        val (homeCode, home) = team("homeTeam")
        fun score(key: String) = (o[key].obj()?.get("score") as? JsonPrimitive)?.content?.toIntOrNull()
        val period = (o["periodDescriptor"].obj()?.get("number") as? JsonPrimitive)?.content?.toIntOrNull()
        val state = when (o["gameState"].str()) { "LIVE", "CRIT" -> MatchState.LIVE; "FINAL", "OFF" -> MatchState.FINAL; else -> MatchState.UPCOMING }
        val tv = o["tvBroadcasts"].arr().mapNotNull { it.obj() }.filter { it["countryCode"].str() == "CA" }
            .mapNotNull { it["network"].str()?.trim()?.takeIf(String::isNotEmpty) }.distinct()
        Match("nhl:$id", League.NHL, start, state, home, away, CANADIENS in setOf(awayCode, homeCode), tv,
            homeId = homeCode, awayId = awayCode, homeAbbr = homeCode, awayAbbr = awayCode,
            homeLogo = nhlLogo(homeCode), awayLogo = nhlLogo(awayCode),
            homeScore = if (state == MatchState.UPCOMING) null else score("homeTeam"), awayScore = if (state == MatchState.UPCOMING) null else score("awayTeam"),
            detail = if (state == MatchState.LIVE && period != null) periodLabel("hockey", period) else null,
            homeColor = NHL_COLORS[homeCode.uppercase()], awayColor = NHL_COLORS[awayCode.uppercase()],
            homeShort = short("homeTeam"), awayShort = short("awayTeam"))
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
        fun comp(which: String) = teams.firstOrNull { it["homeAway"].str() == which }
        fun score(which: String) = (comp(which)?.get("score") as? JsonPrimitive)?.content?.toIntOrNull()
        val status = o["status"].obj()
        val period = (status?.get("period") as? JsonPrimitive)?.content?.toIntOrNull()
        val sport = league.espnPath?.substringBefore('/').orEmpty()
        val detail = if (state != MatchState.LIVE) null else if (sport == "soccer") status?.get("displayClock").str()?.takeIf { it.isNotBlank() }
            else period?.let { periodLabel(sport, it) }
        // Diffuseurs d'ESPN (souvent américains) : sources de plus, après les chaînes françaises.
        val tv = (comp["broadcasts"].arr().flatMap { b -> b.obj()?.get("names").arr().mapNotNull { it.str() } } +
            comp["geoBroadcasts"].arr().mapNotNull { g -> g.obj()?.get("media").obj()?.get("shortName").str() })
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        Match("${league.key}:$id", league, start, state, home?.get("displayName").str().orEmpty(), away?.get("displayName").str().orEmpty(), followed, tv,
            homeId = home?.get("id").str().orEmpty(), awayId = away?.get("id").str().orEmpty(),
            homeAbbr = home?.get("abbreviation").str().orEmpty(), awayAbbr = away?.get("abbreviation").str().orEmpty(),
            homeLogo = home?.get("logo").str(), awayLogo = away?.get("logo").str(),
            homeScore = if (state == MatchState.UPCOMING) null else score("home"), awayScore = if (state == MatchState.UPCOMING) null else score("away"),
            detail = detail,
            homeColor = home?.get("color").str()?.takeIf { HEX.matches(it) }, awayColor = away?.get("color").str()?.takeIf { HEX.matches(it) },
            homeShort = home?.get("shortDisplayName").str().orEmpty(), awayShort = away?.get("shortDisplayName").str().orEmpty())
    }
}.getOrDefault(emptyList())

/** « 2e pér. », « 3e QT », « Prol. » selon le sport. */
fun periodLabel(sport: String, period: Int): String = when (sport) {
    "hockey" -> if (period > 3) "Prol." else "${period}e pér."
    "basketball", "football" -> if (period > 4) "Prol." else "${period}e QT"
    else -> "${period}e"
}

private val HEX = Regex("[0-9a-fA-F]{6}")

/** Couleur principale des 32 équipes de la LNH (l'horaire officiel n'en donne pas). */
val NHL_COLORS: Map<String, String> = mapOf(
    "ANA" to "FC4C02", "BOS" to "FFB81C", "BUF" to "003087", "CGY" to "C8102E", "CAR" to "CE1126", "CHI" to "CF0A2C",
    "COL" to "6F263D", "CBJ" to "002654", "DAL" to "006847", "DET" to "CE1126", "EDM" to "041E42", "FLA" to "C8102E",
    "LAK" to "111111", "MIN" to "154734", "MTL" to "AF1E2D", "NSH" to "FFB81C", "NJD" to "CE1126", "NYI" to "00539B",
    "NYR" to "0038A8", "OTT" to "C52032", "PHI" to "F74902", "PIT" to "FCB514", "SJS" to "006D75", "SEA" to "001628",
    "STL" to "002F87", "TBL" to "002868", "TOR" to "00205B", "UTA" to "6CACE4", "VAN" to "00205B", "VGK" to "B4975A",
    "WSH" to "C8102E", "WPG" to "041E42",
)

/** Sigles LNH qui diffèrent chez ESPN (adresse de ses logos PNG). */
private val NHL_TO_ESPN = mapOf("NJD" to "nj", "LAK" to "la", "SJS" to "sj", "TBL" to "tb", "UTA" to "utah")
fun nhlLogo(abbr: String): String? = abbr.takeIf { it.isNotBlank() }?.let { "https://a.espncdn.com/i/teamlogos/nhl/500/${NHL_TO_ESPN[it.uppercase()] ?: it.lowercase()}.png" }
/** Sigle ESPN d'une équipe LNH → sigle de l'horaire officiel. */
fun espnToNhl(abbr: String): String = NHL_TO_ESPN.entries.firstOrNull { it.value.equals(abbr, true) }?.key ?: abbr.uppercase()

/** Équipes suivies par défaut (avant tout choix sur la page iPhone) : Canadiens et Barça. */
val DEFAULT_TEAMS = listOf(
    home.iptv.shared.sync.SportTeam("nhl", "10", "Canadiens de Montréal", "MTL", nhlLogo("MTL"), "#AF1E2D"),
    home.iptv.shared.sync.SportTeam("esp.1", BARCA_ESPN_ID, "FC Barcelone", "BAR", "https://a.espncdn.com/i/teamlogos/soccer/500/83.png", "#A50044"),
)

/** L'équipe joue-t-elle ce match ? LNH : par sigle ; soccer : toute compétition ; sinon même ligue et même identifiant. */
fun Match.involves(t: home.iptv.shared.sync.SportTeam): Boolean = when {
    league == League.NHL -> t.league == "nhl" && espnToNhl(t.abbr.orEmpty()) in setOf(homeAbbr.uppercase(), awayAbbr.uppercase())
    league.soccer -> (League.of(t.league)?.soccer ?: t.league.contains('.')) && t.id in setOf(homeId, awayId)
    else -> t.league == league.key && t.id in setOf(homeId, awayId)
}

/**
 * Clés des équipes dont la chaîne préférée compte pour ce match (« nhl:MTL », « esp.1:83 ») : les favorites qui y
 * jouent, sinon les deux équipes.
 */
fun preferenceKeys(m: Match, favorites: List<home.iptv.shared.sync.SportTeam>): List<String> {
    fun key(id: String, abbr: String) = if (m.league == League.NHL) "nhl:${abbr.uppercase()}" else "${if (m.league.soccer) "soccer" else m.league.key}:$id"
    val both = listOf(key(m.awayId, m.awayAbbr), key(m.homeId, m.homeAbbr))
    val mine = favorites.filter { m.involves(it) }.map { t ->
        if (m.league == League.NHL) "nhl:${espnToNhl(t.abbr.orEmpty())}" else key(t.id, t.abbr.orEmpty())
    }.filter { it in both }
    return mine.ifEmpty { both }.distinct()
}

/** Ligues à lire : les ligues de base, plus celles (facultatives) des équipes favorites. */
fun leaguesFor(teams: List<home.iptv.shared.sync.SportTeam>): List<League> =
    League.entries.filter { !it.optional || teams.any { t -> t.league == it.key } }

/** Jour local (jours depuis 1970) pour un décalage horaire donné. */
fun localDay(at: Instant, offsetSeconds: Int): Long = (at.epochSeconds + offsetSeconds).floorDiv(86_400L)

/** Un match terminé (ou supposé terminé : début + durée typique + 30 min) n'est plus proposé. */
fun isOver(m: Match, now: Instant): Boolean = m.state == MatchState.FINAL || now > m.start + m.league.typical + 30.minutes

/** Carte d'une équipe favorite : son match en cours ou le prochain (dans les 7 jours), sinon null. */
data class TeamCard(val team: home.iptv.shared.sync.SportTeam, val next: Match?)

data class SportBoard(val mine: List<Match>, val tonight: List<Match>, val teams: List<TeamCard> = emptyList())

/**
 * « Mes équipes » : matchs suivis en cours ou dans les 7 jours, en cours d'abord puis par heure.
 * « Ce soir » : matchs du jour local (ou en cours), toutes compétitions, par heure ; sans doublon avec « Mes équipes ».
 */
fun buildBoard(matches: List<Match>, now: Instant, offsetSeconds: Int, teams: List<home.iptv.shared.sync.SportTeam>? = null): SportBoard {
    val alive = matches.distinctBy { it.id }.filterNot { isOver(it, now) }
    val order = compareBy<Match>({ if (it.state == MatchState.LIVE) 0 else 1 }, { it.start }, { it.id })
    // Équipes choisies sur l'iPhone ; sans choix, l'ancien suivi (Canadiens, Barça).
    fun followed(m: Match) = if (teams == null) m.followed else teams.any { m.involves(it) }
    // Ordre des équipes choisi sur l'iPhone : départage les matchs de mes équipes (en direct ou à la même heure).
    fun priority(m: Match) = (teams ?: DEFAULT_TEAMS).indexOfFirst { m.involves(it) }.let { if (it < 0) Int.MAX_VALUE else it }
    val mine = alive.filter { followed(it) && it.start <= now + (7 * 24).hours }
        .sortedWith(compareBy<Match>({ if (it.state == MatchState.LIVE) 0 else 1 }, { if (it.state == MatchState.LIVE) priority(it) else 0 }, { it.start }, { priority(it) }, { it.id }))
    val cards = (teams ?: DEFAULT_TEAMS).map { t -> TeamCard(t, mine.firstOrNull { it.involves(t) }) }
    val today = localDay(now, offsetSeconds)
    val mineIds = mine.mapTo(HashSet()) { it.id }
    val tonight = alive.filter { it.id !in mineIds && (it.state == MatchState.LIVE || localDay(it.start, offsetSeconds) == today) }.sortedWith(order)
    return SportBoard(mine, tonight, cards)
}

/** Sports dans l'ordre par défaut de l'onglet Sport. */
val SPORTS = listOf("hockey", "football", "basketball", "soccer")

/**
 * Bande du bas (et match à l'affiche, en tête) selon les réglages : mes matchs d'aujourd'hui ou en direct puis les
 * autres d'aujourd'hui, ou tout mélangé ([SportView.mineFirst] faux) ; les autres matchs triés par sport dans
 * l'ordre voulu, en direct d'abord, puis par heure ; sports masqués retirés (sauf mes équipes). Le prochain match de
 * mes équipes (même un autre jour) reste en tête, sauf [SportView.featured] = « direct » et un match en direct.
 */
fun stripFor(board: SportBoard, now: Instant, offsetSeconds: Int, view: home.iptv.shared.sync.SportView?, max: Int = 16): List<Match> {
    val v = view ?: home.iptv.shared.sync.SportView()
    val today = localDay(now, offsetSeconds)
    fun nowOrToday(m: Match) = m.state == MatchState.LIVE || localDay(m.start, offsetSeconds) == today
    val rank = (v.order.filter { it in SPORTS } + SPORTS).distinct()
    val bySport = compareBy<Match>({ rank.indexOf(it.league.sport) }, { if (it.state == MatchState.LIVE) 0 else 1 }, { it.start }, { it.id })
    val mine = board.mine.filter(::nowOrToday)
    val others = board.tonight.filter { nowOrToday(it) && it.league.sport !in v.hidden }
    val body = if (v.mineFirst) mine + others.sortedWith(bySport) else (mine + others).sortedWith(bySport)
    val head = if (v.featured == "direct") body.firstOrNull { it.state == MatchState.LIVE } ?: board.mine.firstOrNull()
        else board.mine.firstOrNull() ?: body.firstOrNull()
    return (listOfNotNull(head) + body).distinctBy { it.id }.take(max)
}
