package home.iptv.shared.account

/** Jours avant la fin de l'abonnement à partir desquels « Qui regarde ? » prévient. */
const val EXPIRY_WARN_DAYS = 7

private val MONTHS = listOf("janv.", "févr.", "mars", "avr.", "mai", "juin", "juil.", "août", "sept.", "oct.", "nov.", "déc.")

/** Jour civil (année, mois 1-12, jour) d'un nombre de jours depuis le 1er janvier 1970 (algorithme de Howard Hinnant). */
fun civilDate(epochDay: Long): Triple<Int, Int, Int> {
    val z = epochDay + 719468
    val era = (if (z >= 0) z else z - 146096) / 146097
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
    val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
    val y = (yoe + era * 400 + if (m <= 2) 1 else 0).toInt()
    return Triple(y, m, d)
}

private fun localDay(epochSeconds: Long, offsetSeconds: Int): Long = (epochSeconds + offsetSeconds).floorDiv(86_400L)

/** « 22 nov. » (« 22 nov. 2027 » si l'année n'est pas celle d'aujourd'hui). */
fun shortDate(epochSeconds: Long, offsetSeconds: Int, nowSeconds: Long): String {
    val (y, m, d) = civilDate(localDay(epochSeconds, offsetSeconds))
    val thisYear = civilDate(localDay(nowSeconds, offsetSeconds)).first
    return "$d ${MONTHS[m - 1]}" + if (y != thisYear) " $y" else ""
}

/**
 * Message de « Qui regarde ? » quand l'abonnement se termine dans [EXPIRY_WARN_DAYS] jours au plus (ou est terminé) ;
 * null sinon (pas de date, ou fin lointaine). Jours comptés en jours de calendrier à l'heure de la télé.
 */
fun expiryNotice(expiresAt: Long?, nowSeconds: Long, offsetSeconds: Int): String? {
    if (expiresAt == null) return null
    val days = localDay(expiresAt, offsetSeconds) - localDay(nowSeconds, offsetSeconds)
    val date = shortDate(expiresAt, offsetSeconds, nowSeconds)
    return when {
        expiresAt <= nowSeconds -> "L'abonnement IPTV est terminé (depuis le $date). Pense à le renouveler."
        days > EXPIRY_WARN_DAYS -> null
        days <= 0L -> "L'abonnement IPTV se termine aujourd'hui. Pense à le renouveler."
        days == 1L -> "L'abonnement IPTV se termine demain ($date). Pense à le renouveler."
        else -> "L'abonnement IPTV se termine dans $days jours ($date). Pense à le renouveler."
    }
}
