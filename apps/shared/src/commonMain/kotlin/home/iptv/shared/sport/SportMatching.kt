package home.iptv.shared.sport

import home.iptv.shared.text.searchNorm

/**
 * Langue / pays d'une chaîne, pour l'ordre choisi sur l'iPhone (réglage `lang_order` du profil). Par défaut :
 * français du Québec, français de France, américaines, anglais du Canada, puis le reste.
 */
enum class Region(val key: String) { QC("qc"), FR("fr"), US("us"), CA("ca"), OTHER("autre") }

val DEFAULT_REGIONS = listOf(Region.QC, Region.FR, Region.US, Region.CA)

/** Ordre des langues d'un profil (clés « qc », « fr », « us », « ca ») complété, le reste à la fin. */
fun regionOrder(keys: List<String>?): List<Region> =
    ((keys.orEmpty().mapNotNull { k -> Region.entries.firstOrNull { it.key == k && it != Region.OTHER } }) + DEFAULT_REGIONS + Region.OTHER).distinct()

private fun countryOf(s: String): String? = Regex("^\\s*\\|?\\s*([A-Za-z]{2,3})\\s*[|:]").find(s)?.groupValues?.get(1)?.uppercase()

/** Région d'une chaîne d'après le préfixe de sa catégorie (« CA| ») ou de son nom (« CA FR: »), et ses mots (RDS, TVA…). */
fun regionOf(ch: SportChannel): Region {
    val name = ch.title.uppercase()
    val cat = ch.categoryName.uppercase()
    val code = countryOf(ch.categoryName) ?: countryOf(ch.title)
    val words = (searchNorm(ch.title) + " " + searchNorm(ch.categoryName)).split(Regex("[^a-z0-9+]+")).toSet()
    val french = "CA FR" in name || Regex("\\bFR\\b").containsMatchIn(cat.substringAfter('|', cat)) || words.any { it in setOf("rds", "tva", "quebec", "french", "francais") }
    return when (code) {
        "CA" -> if (french) Region.QC else Region.CA
        "FR", "BE", "CH" -> if (code == "FR" || french) Region.FR else Region.OTHER
        "US" -> if ("french" in words) Region.FR else Region.US
        else -> if (words.any { it in setOf("rds", "tva") }) Region.QC else Region.OTHER
    }
}

/** Surnoms à reconnaître (sans accents, minuscules) : « Barça » = « Barcelona ». */
private val ALIASES = mapOf("barcelona" to listOf("barca", "barcelone"), "bayern munich" to listOf("bayern"), "manchester united" to listOf("man utd", "man united"))

/**
 * Noms d'une équipe à chercher dans un nom de chaîne : nom court (« Canadiens », « Raptors »), nom complet, surnoms,
 * et la ville seule (« Vancouver », « New York ») : Sportsnet+ et Viaplay écrivent « NHL: Vancouver vs Utah ».
 */
fun teamNames(full: String, short: String): List<String> {
    fun clean(x: String) = searchNorm(x).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex(" +"), " ").trim()
    val f = clean(full)
    val sh = clean(short.ifBlank { full.substringAfterLast(' ') })
    val city = if (sh.isNotEmpty() && f.endsWith(" $sh")) f.removeSuffix(" $sh").trim() else ""
    val base = listOf(sh, f, city).filter { it.length >= 3 }
    return (base + base.flatMap { ALIASES[it].orEmpty() } + ALIASES.filterValues { v -> v.any { it in base } }.keys).distinct()
}

/** Mots qui disent le sport d'une chaîne d'événement (« NBA: New York vs Boston » n'est pas un match de hockey). */
private val SPORT_TAGS: Map<String, Set<String>> = mapOf(
    "nhl" to setOf("hockey"), "lnh" to setOf("hockey"), "hockey" to setOf("hockey"), "ahl" to setOf("hockey"),
    "nba" to setOf("basketball"), "wnba" to setOf("basketball"), "basketball" to setOf("basketball"), "ncaab" to setOf("basketball"),
    "nfl" to setOf("football"), "ncaaf" to setOf("football"), "football" to setOf("football", "soccer"),
    "mls" to setOf("soccer"), "soccer" to setOf("soccer"), "futbol" to setOf("soccer"), "fawsl" to setOf("soccer"), "epl" to setOf("soccer"),
    "mlb" to setOf("baseball"), "baseball" to setOf("baseball"),
)

/** Le nom annonce-t-il un autre sport que celui du match ? (aucun mot de sport : non) */
internal fun otherSport(text: String, sport: String): Boolean {
    val tags = text.trim().split(' ').mapNotNull { SPORT_TAGS[it] }
    return tags.isNotEmpty() && tags.none { sport in it }
}

internal fun normText(title: String) = " " + searchNorm(title).replace(Regex("[^a-z0-9]+"), " ").trim() + " "
private fun mentions(text: String, names: List<String>) = names.any { " $it " in text }

private val MONTHS = listOf(
    listOf("jan", "janv", "january", "janvier"), listOf("feb", "fev", "fevr", "february", "fevrier"), listOf("mar", "march", "mars"),
    listOf("apr", "avr", "april", "avril"), listOf("may", "mai"), listOf("jun", "june", "juin"), listOf("jul", "juil", "july", "juillet"),
    listOf("aug", "aou", "aout", "august"), listOf("sep", "sept", "september", "septembre"), listOf("oct", "october", "octobre"),
    listOf("nov", "november", "novembre"), listOf("dec", "december", "decembre"),
)
private fun monthOf(w: String): Int? = MONTHS.indexOfFirst { w in it }.takeIf { it >= 0 }?.plus(1)

/** Dates (mois, jour) écrites dans un nom de chaîne d'événement : « 2026-11-17 », « 15-11-2026 », « Nov 15 », « 15 Nov ». */
fun datesIn(title: String): Set<Pair<Int, Int>> {
    val out = HashSet<Pair<Int, Int>>()
    Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})").findAll(title).forEach { out += it.groupValues[2].toInt() to it.groupValues[3].toInt() }
    Regex("(?<!\\d)(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{4})").findAll(title).forEach {
        val a = it.groupValues[1].toInt(); val b = it.groupValues[2].toInt()
        out += b to a; out += a to b // jour-mois ou mois-jour : les deux lectures
    }
    val words = searchNorm(title).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
    words.forEachIndexed { i, w ->
        val m = monthOf(w) ?: return@forEachIndexed
        words.getOrNull(i + 1)?.toIntOrNull()?.takeIf { it in 1..31 }?.let { out += m to it }
        words.getOrNull(i - 1)?.toIntOrNull()?.takeIf { it in 1..31 }?.let { out += m to it }
    }
    return out.filter { (m, d) -> m in 1..12 && d in 1..31 }.toSet()
}

/** Jours où le match peut être annoncé (heure universelle et locale, la veille et le lendemain : fuseaux des fournisseurs). */
private fun matchDays(m: Match, offsetSeconds: Int): Set<Pair<Int, Int>> =
    listOf(localDay(m.start, 0), localDay(m.start, offsetSeconds)).flatMap { listOf(it - 1, it, it + 1) }.map(::monthDay).toSet()

/** Jour depuis 1970 → (mois, jour) du calendrier grégorien. */
internal fun monthDay(epochDay: Long): Pair<Int, Int> {
    val z = epochDay + 719468
    val era = (if (z >= 0) z else z - 146096) / 146097
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
    val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
    return month to d
}

/**
 * Chaîne d'événement qui diffuse CE match : son nom cite les deux équipes (« Toronto Raptors vs New York Knicks @ Nov 15
 * 6:00 PM », « Steelers at Bengals », « (ESPN+ 459) | Hockey: Kings vs. Bruins (2026-11-17 18:30) ») et, s'il porte
 * une date, c'est le jour du match.
 */
fun eventMatches(title: String, m: Match, offsetSeconds: Int): Boolean = EventMatcher(m, offsetSeconds).matches(normText(title), datesIn(title))

/** Noms et jours d'un match calculés une fois, pour tester des milliers de chaînes d'événements. */
class EventMatcher(m: Match, offsetSeconds: Int) {
    private val home = teamNames(m.home, m.homeShort)
    private val away = teamNames(m.away, m.awayShort)
    private val days = matchDays(m, offsetSeconds)
    private val sport = m.league.sport
    fun matches(text: String, dates: Set<Pair<Int, Int>>): Boolean =
        mentions(text, home) && mentions(text, away) && !otherSport(text, sport) && (dates.isEmpty() || dates.any { it in days })
}

/** Chaînes des catégories Sport avec leur nom normalisé et leurs dates, préparés une fois. */
class SportExtraIndex(val channels: List<SportChannel>) {
    internal val texts = channels.map { normText(it.title) }
    private val dates = arrayOfNulls<Set<Pair<Int, Int>>>(channels.size)
    internal fun datesAt(i: Int) = dates[i] ?: datesIn(channels[i].title).also { dates[i] = it }
    internal fun events(m: Match, offsetSeconds: Int): List<SportChannel> {
        val em = EventMatcher(m, offsetSeconds)
        return channels.indices.filter { em.matches(texts[it], emptySet()) && em.matches(texts[it], datesAt(it)) }.map { channels[it] }
    }
}

/** Chaîne d'une équipe (« US: MONTREAL CANADIENS », « NBA: TORONTO RAPTORS ») : nom complet de l'équipe, pas un autre match. */
fun teamChannelFor(ch: SportChannel, m: Match): Boolean {
    if ("team" !in searchNorm(ch.categoryName)) return false
    val text = normText(ch.title)
    if (Regex(" (vs|v|at|contre|c) ").containsMatchIn(text) || "@" in ch.title) return false
    return listOf(m.home, m.away).any { full -> searchNorm(full).replace(Regex("[^a-z0-9]+"), " ").trim().let { it.length >= 6 && " $it " in text } }
}

/**
 * Le guide d'une chaîne annonce-t-il ce match ? Un programme qui chevauche le match et cite une des équipes (le guide
 * écrit souvent « LNH : Canadiens c. Bruins » ou « NBA Basketball: Raptors at Knicks »).
 */
fun guideConfirms(programs: List<Pair<String, kotlin.time.Instant>>, ends: List<kotlin.time.Instant>, m: Match): Boolean {
    val names = teamNames(m.home, m.homeShort) + teamNames(m.away, m.awayShort)
    val end = m.start + m.league.typical
    return programs.indices.any { i ->
        val (title, start) = programs[i]
        start < end && ends[i] > m.start && mentions(normText(title), names)
    }
}

/** Catégories proposées par défaut au Sport (même masquées dans le Direct) : événements et équipes du Canada, des É.-U. et de France. */
fun isDefaultSportCategory(name: String): Boolean {
    val n = name.uppercase()
    val code = countryOf(name) ?: return false
    if (code !in setOf("CA", "US", "FR")) return false
    if ("VIP" in n || "ⱽᴵᴾ" in name || "⁽ᴮᴷ⁾" in name || "REPLAY" in n) return false
    return Regex("\\b(NHL|NBA|NFL|MLS)\\b.*PPV|NBA PASS|TSN\\+|SPORTSNET\\+? PPV|ESPN\\+ PPV|DAZN( LA LIGA)? PPV|SOCCER PPV|PEACOCK PPV|PARAMOUNT\\+ PPV|CBC PPV").containsMatchIn(n)
}
