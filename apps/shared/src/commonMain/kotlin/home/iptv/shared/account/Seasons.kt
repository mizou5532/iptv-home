package home.iptv.shared.account

import kotlinx.serialization.Serializable

/**
 * Thèmes de saison de « Qui regarde ? » (réglage de la maison `seasons`, page iPhone) : activés par défaut, chaque fête
 * peut être retirée ([off]) ; [force] montre une fête tout de suite (essai), quelle que soit la date.
 */
@Serializable
data class SeasonSettings(val on: Boolean = true, val off: List<String> = emptyList(), val force: String? = null)

enum class Season(val id: String, val label: String) {
    NOEL("noel", "Noël"),
    NOUVEL_AN("nouvel_an", "Nouvel An"),
    HALLOWEEN("halloween", "Halloween"),
    VALENTIN("valentin", "Saint-Valentin"),
    RAMADAN("ramadan", "Ramadan"),
    AID_FITR("aid_fitr", "Aïd el-Fitr"),
    AID_ADHA("aid_adha", "Aïd el-Adha"),
    FETE_NATIONALE("fete_nationale", "Fête nationale du Québec");

    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } }
}

/** Date civile simple (année, mois 1-12, jour). */
data class Day(val y: Int, val m: Int, val d: Int) : Comparable<Day> {
    override fun compareTo(other: Day) = compareValuesBy(this, other, { it.y }, { it.m }, { it.d })
    fun plus(days: Int): Day { val (y2, m2, d2) = civilDate(epochDay() + days); return Day(y2, m2, d2) }
    fun epochDay(): Long {
        // Inverse de civilDate (algorithme de Howard Hinnant).
        val yy = if (m <= 2) y - 1 else y
        val era = (if (yy >= 0) yy else yy - 399) / 400
        val yoe = yy - era * 400
        val mp = (m + 9) % 12
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468
    }
    companion object { fun parse(s: String) = s.split('-').let { Day(it[0].toInt(), it[1].toInt(), it[2].toInt()) } }
}

/** Fêtes musulmanes : dates estimées (à un jour près selon l'observation de la lune) ; même table que fixtures/seasons.json. */
private data class Islamic(val ramadan: Day, val fitr: Day, val adha: Day)
private val ISLAMIC = listOf(
    Islamic(Day(2026, 2, 18), Day(2026, 3, 20), Day(2026, 5, 27)),
    Islamic(Day(2027, 2, 8), Day(2027, 3, 10), Day(2027, 5, 16)),
    Islamic(Day(2028, 1, 28), Day(2028, 2, 26), Day(2028, 5, 5)),
    Islamic(Day(2029, 1, 16), Day(2029, 2, 14), Day(2029, 4, 24)),
    Islamic(Day(2030, 1, 6), Day(2030, 2, 5), Day(2030, 4, 13)),
)

/** Période d'une fête pour l'année de [day] (et la précédente pour le Nouvel An) ; null si inconnue (années sans table). */
fun seasonAt(day: Day): Season? {
    val (y, m, d) = Triple(day.y, day.m, day.d)
    ISLAMIC.firstOrNull { it.fitr.y == y || it.adha.y == y || it.ramadan.y == y }?.let { i ->
        if (day >= i.fitr && day <= i.fitr.plus(2)) return Season.AID_FITR
        if (day >= i.adha && day <= i.adha.plus(3)) return Season.AID_ADHA
        if (day >= i.ramadan && day < i.fitr) return Season.RAMADAN
    }
    return when {
        (m == 12 && d == 31) || (m == 1 && d <= 3) -> Season.NOUVEL_AN
        m == 12 && d in 1..30 -> Season.NOEL
        m == 10 && d in 24..31 -> Season.HALLOWEEN
        m == 2 && d in 12..14 -> Season.VALENTIN
        m == 6 && d in 22..24 -> Season.FETE_NATIONALE
        else -> null
    }
}

/** Fête à montrer aujourd'hui selon les réglages ; [force] l'emporte (même si la fête est retirée de la liste). */
fun activeSeason(day: Day, s: SeasonSettings?): Season? {
    val settings = s ?: SeasonSettings()
    Season.of(settings.force)?.let { return it }
    if (!settings.on) return null
    return seasonAt(day)?.takeIf { it.id !in settings.off }
}

/** Mot du jour affiché au-dessus de « Qui regarde ? ». */
fun seasonGreeting(season: Season, day: Day): String = when (season) {
    Season.NOEL -> if (day.m == 12 && day.d in 24..26) "Joyeux Noël !" else "Joyeuses fêtes !"
    Season.NOUVEL_AN -> "Bonne année ${if (day.m == 12) day.y + 1 else day.y} !"
    Season.HALLOWEEN -> "Joyeuse Halloween !"
    Season.VALENTIN -> "Joyeuse Saint-Valentin !"
    Season.RAMADAN -> "Ramadan Moubarak"
    Season.AID_FITR -> "Aïd Moubarak !"
    Season.AID_ADHA -> "Aïd el-Adha Moubarak !"
    Season.FETE_NATIONALE -> "Bonne fête nationale !"
}
