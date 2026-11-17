package home.iptv.tv.live.epg

import home.iptv.shared.xtream.EpgEntry
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Instant

/** Programme actuel et suivant d'une source ; `null` = « Programme indisponible » pour cette partie. */
data class NowNext(val current: EpgEntry?, val next: EpgEntry?, val progress: Float?) {
    companion object { val EMPTY = NowNext(null, null, null) }
}

/**
 * Projette un guide court sur un instant : le programme actuel vérifie `start <= now < end` (fin exclue), le suivant
 * est le premier qui commence à la fin de l'actuel (ou après maintenant s'il n'y en a pas). Les intervalles vides ou
 * inversés sont écartés ; aucun programme terminé n'est présenté d'après ses heures.
 *
 * Secours : si les heures ne donnent ni actuel ni suivant (panneau aux heures décalées) mais qu'une entrée porte le
 * drapeau `now_playing` du panneau, elle devient l'actuel et la suivante dans l'ordre l'« ensuite », sans barre
 * d'avancement (ses heures ne sont pas fiables).
 */
fun nowNext(entries: List<EpgEntry>, now: Instant): NowNext {
    val valid = entries.filter { it.end > it.start }.sortedBy { it.start }
    val current = valid.lastOrNull { it.start <= now && now < it.end }
    val after = current?.end ?: now
    val next = valid.firstOrNull { it.start >= after && it.start > now }
    if (current == null && next == null) {
        val flagged = valid.lastOrNull { it.nowPlaying }
        if (flagged != null) return NowNext(flagged, valid.firstOrNull { it.start >= flagged.end && it !== flagged }, null)
    }
    val progress = current?.let {
        val total = (it.end - it.start).inWholeMilliseconds.toDouble()
        ((now - it.start).inWholeMilliseconds / total).toFloat().coerceIn(0f, 1f)
    }
    return NowNext(current, next, progress)
}

private val HourMinute = DateTimeFormatter.ofPattern("HH:mm")

/** Heure locale « HH:mm » dans la zone donnée (suit les changements d'heure). */
fun formatLocalTime(instant: Instant, zone: ZoneId): String =
    HourMinute.format(java.time.Instant.ofEpochSecond(instant.epochSeconds).atZone(zone))

/** Guide des lignes de la liste : [cached] répond sans attendre, [load] demande au besoin (voir `EpgRepository.glance`). */
class RowGuide(val cached: (String) -> NowNext?, val load: suspend (String) -> NowNext)
