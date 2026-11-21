package home.iptv.shared.sport

import home.iptv.shared.catalog.CatalogRepository
import home.iptv.shared.groups.GroupSnapshot
import home.iptv.shared.groups.categoryCountries
import home.iptv.shared.groups.groupSourceIndex
import home.iptv.shared.sync.ProfilePrefs
import home.iptv.shared.sync.SportTeam
import home.iptv.shared.sync.SportView
import home.iptv.shared.xtream.EpgEntry
import kotlin.time.Instant

private const val NHL_BASE = "https://api-web.nhle.com/v1/schedule/"
private const val ESPN_BASE = "https://site.api.espn.com/apis/site/v2/sports/"

/** Conversion pure (multiplateforme) d'un jour Unix en date ISO « AAAA-MM-JJ » (algorithme de Hinnant). */
fun epochDayToIso(epochDay: Long): String {
    val z = epochDay + 719468L
    val era = (if (z >= 0) z else z - 146096L) / 146097L
    val doe = z - era * 146097L
    val yoe = (doe - doe / 1460L + doe / 36524L - doe / 146096L) / 365L
    var y = yoe + era * 400L
    val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)
    val mp = (5L * doy + 2L) / 153L
    val d = doy - (153L * mp + 2L) / 5L + 1L
    val m = mp + (if (mp < 10L) 3L else -9L)
    if (m <= 2L) y++
    return "${y.toString().padStart(4, '0')}-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
}

/** Une adresse d'horaires : sa ligue et le jour qu'elle couvre (0 = aujourd'hui ; LNH : 0 et 7, une semaine chacune). */
data class ScheduleSource(val league: League, val url: String, val day: Int) {
    val key: String get() = url.substringAfter("://")
}

/**
 * Adresses à lire : LNH (2 semaines), et pour chaque ligue ESPN un tableau PAR JOUR (ESPN refuse depuis novembre
 * 2026 les plages de dates « AAAAMMJJ-AAAAMMJJ »). 8 jours seulement pour les ligues où joue une équipe favorite
 * ([fullWeek]) ; les autres : aujourd'hui et demain.
 */
fun scheduleSources(
    today: Instant,
    offsetSeconds: Int,
    leagues: List<League> = League.entries.filter { !it.optional },
    fullWeek: (League) -> Boolean = { true },
): List<ScheduleSource> {
    val firstDay = localDay(today, offsetSeconds)
    val d0 = epochDayToIso(firstDay)
    val d7 = epochDayToIso(firstDay + 7L)
    return listOf(ScheduleSource(League.NHL, "$NHL_BASE$d0", 0), ScheduleSource(League.NHL, "$NHL_BASE$d7", 7)) +
        leagues.filter { it.espnPath != null }.flatMap { l ->
            (0..(if (fullWeek(l)) 7 else 1)).map { d ->
                val compact = epochDayToIso(firstDay + d.toLong()).replace("-", "")
                ScheduleSource(l, "$ESPN_BASE${l.espnPath}/scoreboard?dates=$compact", d)
            }
        }
}

/** Ligues de la semaine complète : celles des équipes favorites (et tout le soccer si une favorite y joue). */
fun fullWeekFor(teams: List<SportTeam>): (League) -> Boolean {
    val keys = teams.map { it.league }.toSet()
    val soccer = teams.any { t -> League.of(t.league)?.soccer ?: t.league.contains('.') }
    return { l -> l.key in keys || (l.soccer && soccer) }
}

/** Ligues à lire pour un profil : celles des favorites toujours ; les autres sauf les sports masqués. */
fun sportLeagues(t: List<SportTeam>, view: SportView?): List<League> {
    val hidden = view?.hidden.orEmpty()
    val week = fullWeekFor(t)
    return leaguesFor(t).filter { it.sport !in hidden || week(it) }
}

/**
 * Horaires gardés (mémoire) rendus honnêtes : un match prévu dont l'heure est passée ne reste jamais « à venir »
 * avec un faux score, et les scores gardés d'un match en cours ne sont pas affichés périmés.
 */
fun honestMemory(matches: List<Match>, now: Instant): List<Match> = matches.map { m ->
    if (m.state == MatchState.LIVE) m.copy(homeScore = null, awayScore = null, detail = null)
    else if (m.state == MatchState.UPCOMING && now > m.start) m.copy(state = MatchState.LIVE, detail = null)
    else m
}

/** Chaînes visibles d'un profil (groupées comme dans le Direct), prêtes pour [SportChannelIndex]. */
fun visibleSportChannels(
    repo: CatalogRepository,
    prefs: ProfilePrefs?,
    snapshot: GroupSnapshot?,
): List<SportChannel> {
    if (prefs == null) return emptyList()
    val hidden = prefs.hidden
    val hiddenChannels = hidden.filter { it.itemType == "channel" }.mapTo(HashSet()) { it.itemId }
    val hiddenCategories = hidden.filter { it.itemType == "live_category" }.mapTo(HashSet()) { it.itemId }
    val cats = repo.categories()
    val names = cats.associate { it.id to it.name }
    val visible = repo.allGroupSources().filter { it.id !in hiddenChannels && it.categoryId !in hiddenCategories }
    val visibleIds = visible.associateBy { it.id }
    val index = groupSourceIndex(
        visible,
        snapshot ?: GroupSnapshot(0, 1, emptyList()),
        prefs.groupCopies,
        categoryCountries(cats),
        precomputed = repo.liveKeys()::get,
    )
    return index.values.distinctBy { it.id }.mapNotNull { group ->
        val anchor = group.sources.firstOrNull { it.id in visibleIds } ?: return@mapNotNull null
        SportChannel(group.id, group.title, names[anchor.categoryId].orEmpty(), anchor.id)
    }.sortedBy { visibleIds[it.anchorSourceId]?.position ?: Int.MAX_VALUE }
}

/** Chaînes non-direct les plus plausibles dont le guide mérite d'être sondé (au plus [max]). */
fun guideProbes(c: SportCandidates, max: Int = 10): List<SportChannel> =
    (c.proposed + c.all).filterNot { it.direct }.distinctBy { it.groupId }.take(max)

/** Vrai si le guide ([guide]) confirme le match [m] sur la chaîne. */
fun confirmedBy(guide: List<EpgEntry>, m: Match): Boolean =
    guideConfirms(guide.map { it.title to it.start }, guide.map { it.end }, m)
