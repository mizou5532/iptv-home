package home.iptv.shared.home

import home.iptv.shared.search.PersonCredits
import home.iptv.shared.search.SEARCH_LIMIT
import home.iptv.shared.search.SearchResult
import home.iptv.shared.sport.ChannelReason
import home.iptv.shared.sport.League
import home.iptv.shared.sport.Match
import home.iptv.shared.sport.MatchState
import home.iptv.shared.sport.SportCandidates
import home.iptv.shared.sport.SportChannel
import home.iptv.shared.sync.SportMemory
import home.iptv.shared.sync.SportTeam
import home.iptv.shared.sync.SportView
import home.iptv.shared.versions.Lang
import home.iptv.shared.versions.LanguagePrefs
import home.iptv.shared.versions.Version
import home.iptv.shared.versions.VersionIndex
import home.iptv.shared.versions.VersionTag
import home.iptv.shared.xtream.Episode
import home.iptv.shared.xtream.MediaTech
import home.iptv.shared.xtream.MovieInfo
import home.iptv.shared.xtream.MovieSummary
import home.iptv.shared.xtream.Season
import home.iptv.shared.xtream.SeriesInfo
import home.iptv.shared.xtream.SeriesSummary
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Version du protocole télé <-> serveur maison, envoyée dans l'en-tête `X-Home-Protocol` ; refus net si différente. */
const val HOME_PROTOCOL = 1

@Serializable
data class SnapStream(
    val id: String,
    val categoryId: String,
    val name: String,
    val icon: String?,
    val epgChannelId: String?,
    val number: Int?,
    val position: Int,
    val autoKey: String?,
    val title: String,
)

@Serializable
data class SnapCategory(val id: String, val name: String, val position: Int)

/**
 * Le Direct tel que le serveur maison le prépare. [rev] : empreinte SHA-256 (hexadécimal) du contenu ;
 * [account] : empreinte du compte IPTV ([accountFingerprint]) ; [hidden] : catégories masquées pour tout le monde.
 */
@Serializable
data class LiveSnapshot(
    val rev: String,
    val account: String,
    val builtAt: Long,
    val categories: List<SnapCategory>,
    val streams: List<SnapStream>,
    val hidden: List<String>,
)

@Serializable
data class SnapEpg(val title: String, val startMs: Long, val endMs: Long, val nowPlaying: Boolean)

@Serializable
data class EpgReply(val entries: List<SnapEpg>)

// --- Films, séries, fiches et versions (étape 2) ---

/** Catégorie de films ou de séries dans le manifeste ; [rev] vide si la catégorie n'est pas encore téléchargée. */
@Serializable
data class SnapVodCategory(val id: String, val name: String, val position: Int, val rev: String)

/** Manifeste des catégories Films ou Séries : permet à la télé de ne télécharger que ce qui a changé. */
@Serializable
data class VodManifest(
    val rev: String,
    val account: String,
    val builtAt: Long,
    val categories: List<SnapVodCategory>,
    val hidden: List<String>,
)

@Serializable
data class SnapMovie(
    val id: String,
    val categoryId: String,
    val name: String,
    val cover: String? = null,
    val year: String? = null,
    val genre: String? = null,
    val plot: String? = null,
    val rating: String? = null,
    val addedAt: Long? = null,
    val extension: String = "mkv",
    val durationS: Int? = null,
    val position: Int = 0,
    val backdrop: String? = null,
)

@Serializable
data class MovieCategoryReply(
    val categoryId: String,
    val rev: String,
    val account: String,
    val items: List<SnapMovie>,
)

@Serializable
data class SnapSeries(
    val id: String,
    val categoryId: String,
    val name: String,
    val cover: String? = null,
    val year: String? = null,
    val genre: String? = null,
    val plot: String? = null,
    val rating: String? = null,
    val position: Int = 0,
    val backdrop: String? = null,
)

@Serializable
data class SeriesCategoryReply(
    val categoryId: String,
    val rev: String,
    val account: String,
    val items: List<SnapSeries>,
)

@Serializable
data class SnapMediaTech(
    val width: Int? = null,
    val height: Int? = null,
    val hdr: String? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val audioChannels: Int? = null,
)

@Serializable
data class SnapMovieInfo(val summary: SnapMovie, val tech: SnapMediaTech? = null)

@Serializable
data class SnapEpisode(
    val id: String,
    val season: Int,
    val number: Int,
    val title: String,
    val durationS: Int? = null,
    val extension: String = "mkv",
    val cover: String? = null,
)

@Serializable
data class SnapSeason(val number: Int, val name: String, val episodes: List<SnapEpisode>)

@Serializable
data class SnapSeriesInfo(val summary: SnapSeries, val seasons: List<SnapSeason>)

@Serializable
data class SnapVersionTag(
    val lang: String? = null,
    val uhd: Boolean = false,
    val hdr: Boolean = false,
    val source: String? = null,
)

@Serializable
data class SnapVersion(
    val id: String,
    val categoryId: String,
    val title: String,
    val tag: SnapVersionTag,
    val cover: String? = null,
    val year: String? = null,
)

@Serializable
data class VersionsRequest(val ids: List<String>)

@Serializable
data class VersionsReply(val rev: String, val groups: Map<String, List<SnapVersion>>)

fun MovieSummary.toSnapMovie(position: Int = 0) = SnapMovie(
    id, categoryId, name, cover, year, genre, plot, rating, addedAt, extension, durationS, position, backdrop,
)

fun SnapMovie.toMovieSummary() = MovieSummary(
    id, categoryId, name, cover, year, genre, plot, rating, addedAt, extension, durationS, backdrop,
)

fun SeriesSummary.toSnapSeries(position: Int = 0) = SnapSeries(
    id, categoryId, name, cover, year, genre, plot, rating, position, backdrop,
)

fun SnapSeries.toSeriesSummary() = SeriesSummary(
    id, categoryId, name, cover, year, genre, plot, rating, backdrop,
)

fun MediaTech.toSnapMediaTech() = SnapMediaTech(width, height, hdr, videoCodec, audioCodec, audioChannels)
fun SnapMediaTech.toMediaTech() = MediaTech(width, height, hdr, videoCodec, audioCodec, audioChannels)

fun MovieInfo.toSnapMovieInfo() = SnapMovieInfo(summary.toSnapMovie(), tech?.toSnapMediaTech())
fun SnapMovieInfo.toMovieInfo() = MovieInfo(summary.toMovieSummary(), tech?.toMediaTech())

fun SeriesInfo.toSnapSeriesInfo() = SnapSeriesInfo(
    summary = summary.toSnapSeries(),
    seasons = seasons.map { s ->
        SnapSeason(s.number, s.name, s.episodes.map { e -> SnapEpisode(e.id, e.season, e.number, e.title, e.durationS, e.extension, e.cover) })
    },
)

fun SnapSeriesInfo.toSeriesInfo() = SeriesInfo(
    summary = summary.toSeriesSummary(),
    seasons = seasons.map { s ->
        Season(s.number, s.name, s.episodes.map { e -> Episode(e.id, e.season, e.number, e.title, e.durationS, e.extension, e.cover) })
    },
)

fun Version.toSnapVersion() = SnapVersion(
    id, categoryId, title, SnapVersionTag(tag.lang?.code, tag.uhd, tag.hdr, tag.source), cover, year,
)

fun SnapVersion.toVersion() = Version(
    id, categoryId, title, VersionTag(Lang.of(tag.lang), tag.uhd, tag.hdr, tag.source), cover, year,
)

fun VersionsReply.toVersionIndex(): VersionIndex =
    VersionIndex(groups.mapValues { (_, vs) -> vs.map { it.toVersion() } })

// --- Recherche sur le serveur maison (étape 3) ---

@Serializable
data class SearchRequest(
    /** `"live"`, `"series"` ou `"movies"`. */
    val section: String,
    val query: String,
    val hiddenCategories: List<String> = emptyList(),
    val hiddenChannels: List<String> = emptyList(),
    val groupCopies: Boolean = true,
    val languages: LanguagePrefs = LanguagePrefs(),
    val limit: Int = SEARCH_LIMIT,
    /** Crédits TMDB d'une personne (acteur/réalisateur) ; si présent, le serveur renvoie les titres correspondants. */
    val personCredits: PersonCredits? = null,
)

@Serializable
data class SnapSearchResult(
    val id: String,
    val title: String,
    val cover: String? = null,
    val categoryId: String,
    val anchorSourceId: String? = null,
    val versions: List<SnapVersion> = emptyList(),
    val note: String? = null,
)

@Serializable
data class SearchReply(
    val rev: String,
    val account: String,
    val results: List<SnapSearchResult>,
)

fun SearchResult.toSnapSearchResult() = SnapSearchResult(
    id = id, title = title, cover = cover, categoryId = categoryId,
    anchorSourceId = anchorSourceId, versions = versions.map { it.toSnapVersion() }, note = note,
)

fun SnapSearchResult.toSearchResult() = SearchResult(
    id = id, title = title, cover = cover, categoryId = categoryId,
    anchorSourceId = anchorSourceId, versions = versions.map { it.toVersion() }, note = note,
)

// --- Sport sur le serveur maison (étape 4) ---

@Serializable
data class SnapMatch(
    val id: String,
    val league: String,
    val startMs: Long,
    val state: String,
    val home: String,
    val away: String,
    val followed: Boolean = false,
    val broadcasters: List<String> = emptyList(),
    val homeId: String = "",
    val awayId: String = "",
    val homeAbbr: String = "",
    val awayAbbr: String = "",
    val homeLogo: String? = null,
    val awayLogo: String? = null,
    val homeScore: Int? = null,
    val awayScore: Int? = null,
    val detail: String? = null,
    val homeColor: String? = null,
    val awayColor: String? = null,
    val homeShort: String = "",
    val awayShort: String = "",
    val period: Int? = null,
)

@Serializable
data class SnapSportChannel(
    val groupId: String,
    val title: String,
    val categoryName: String,
    val anchorSourceId: String = groupId,
    val direct: Boolean = false,
)

@Serializable
data class SnapSportCandidates(
    val proposed: List<SnapSportChannel> = emptyList(),
    val all: List<SnapSportChannel> = emptyList(),
    val likely: List<SnapSportChannel> = proposed,
    val reasons: Map<String, String> = emptyMap(),
    /** Mode compact : identifiants vers [SportBoardReply.channels] (évite de recopier 40 fois la liste des chaînes). */
    val proposedIds: List<String> = emptyList(),
    val allIds: List<String> = emptyList(),
    val likelyIds: List<String> = emptyList(),
)

@Serializable
data class SportBoardRequest(
    val profileId: String,
    val offsetSeconds: Int,
    val teams: List<SportTeam> = emptyList(),
    val view: SportView? = null,
    val hiddenCategories: List<String> = emptyList(),
    val hiddenChannels: List<String> = emptyList(),
    val favorites: List<String> = emptyList(),
    val groupCopies: Boolean = true,
    val forceRefresh: Boolean = false,
)

@Serializable
data class SportBoardReply(
    val rev: String,
    val account: String,
    val fetchedAtMs: Long,
    val matches: List<SnapMatch>,
    val standings: Map<String, String> = emptyMap(),
    val candidates: Map<String, SnapSportCandidates> = emptyMap(),
    val memory: SportMemory = SportMemory(),
    val offline: Boolean = false,
    /** Table partagée des chaînes référencées par `proposedIds`/`allIds`/`likelyIds` (dédupliquée). */
    val channels: Map<String, SnapSportChannel> = emptyMap(),
)

fun Match.toSnapMatch() = SnapMatch(
    id = id, league = league.key, startMs = start.toEpochMilliseconds(), state = state.name,
    home = home, away = away, followed = followed, broadcasters = broadcasters,
    homeId = homeId, awayId = awayId, homeAbbr = homeAbbr, awayAbbr = awayAbbr,
    homeLogo = homeLogo, awayLogo = awayLogo, homeScore = homeScore, awayScore = awayScore,
    detail = detail, homeColor = homeColor, awayColor = awayColor,
    homeShort = homeShort, awayShort = awayShort, period = period,
)

fun SnapMatch.toMatch(): Match? {
    val l = League.of(league) ?: return null
    val st = MatchState.entries.firstOrNull { it.name == state } ?: MatchState.UPCOMING
    return Match(
        id = id, league = l, start = kotlin.time.Instant.fromEpochMilliseconds(startMs), state = st,
        home = home, away = away, followed = followed, broadcasters = broadcasters,
        homeId = homeId, awayId = awayId, homeAbbr = homeAbbr, awayAbbr = awayAbbr,
        homeLogo = homeLogo, awayLogo = awayLogo, homeScore = homeScore, awayScore = awayScore,
        detail = detail, homeColor = homeColor, awayColor = awayColor,
        homeShort = homeShort, awayShort = awayShort, period = period,
    )
}

fun SportChannel.toSnapSportChannel() = SnapSportChannel(groupId, title, categoryName, anchorSourceId, direct)
fun SnapSportChannel.toSportChannel() = SportChannel(groupId, title, categoryName, anchorSourceId, direct)

fun SportCandidates.toSnapSportCandidates() = SnapSportCandidates(
    proposed = proposed.map { it.toSnapSportChannel() },
    all = all.map { it.toSnapSportChannel() },
    likely = likely.map { it.toSnapSportChannel() },
    reasons = reasons.mapValues { (_, r) -> r.name },
)

/** Sérialisation compacte : ajoute chaque chaîne une seule fois dans [channelTable] et ne garde que ses identifiants. */
fun SportCandidates.toCompactSnapSportCandidates(channelTable: MutableMap<String, SnapSportChannel>): SnapSportCandidates {
    fun reg(ch: SportChannel): String {
        if (ch.groupId !in channelTable) channelTable[ch.groupId] = ch.toSnapSportChannel()
        return ch.groupId
    }
    return SnapSportCandidates(
        proposedIds = proposed.map(::reg),
        allIds = all.map(::reg),
        likelyIds = likely.map(::reg),
        reasons = reasons.mapValues { (_, r) -> r.name },
    )
}

fun SnapSportCandidates.toSportCandidates(table: Map<String, SportChannel> = emptyMap()): SportCandidates {
    val compact = proposedIds.isNotEmpty() || allIds.isNotEmpty() || likelyIds.isNotEmpty()
    val prop = if (compact) proposedIds.mapNotNull { table[it] } else proposed.map { it.toSportChannel() }
    val allList = if (compact) allIds.mapNotNull { table[it] } else all.map { it.toSportChannel() }
    val like = if (compact) likelyIds.mapNotNull { table[it] } else likely.map { it.toSportChannel() }
    return SportCandidates(
        proposed = prop,
        all = allList,
        likely = like,
        reasons = reasons.mapNotNull { (k, v) -> ChannelReason.entries.firstOrNull { it.name == v }?.let { k to it } }.toMap(),
    )
}

fun SportBoardReply.resolvedCandidates(): Map<String, SportCandidates> {
    val table = channels.mapValues { it.value.toSportChannel() }
    return candidates.mapValues { (_, sc) -> sc.toSportCandidates(table) }
}

/** Jeton d'accès à un serveur maison : ne s'affiche jamais, même par erreur dans un journal. */
class HomeToken(val value: String) {
    override fun toString() = "HomeToken(***)"
}

/** Réponse à `GET /v1/versions/memory` : versions retenues pour le compte actuel (`"movie:12"` / `"series:34"` → `"56"`). */
@Serializable
data class VersionMemoryReply(
    val entries: Map<String, String> = emptyMap(),
)

/** Requête `POST /v1/versions/memory` : la version [to] a marché à la place de [from] (`"movie:<id>"` ou `"series:<id>"`). */
@Serializable
data class VersionMemoryUpdate(
    val from: String,
    val to: String,
)

/** Bilan de fiabilité d'une copie de chaîne du Direct, partagé par le foyer (sans jamais interroger le fournisseur). */
@Serializable
data class CopyScoreDto(
    val ok: Double = 0.0,
    val ko: Double = 0.0,
    val at: Long = 0L,
)

/** Réponse à `GET /v1/live/reliability` : bilans de fiabilité connus de la maison pour le compte actuel. */
@Serializable
data class LiveReliabilityReply(
    val scores: Map<String, CopyScoreDto> = emptyMap(),
)

/** Requête `POST /v1/live/reliability` : un constat réel de lecture sur une télé (`kind`: `"ok"`, `"ko"` ou `"stall"`). */
@Serializable
data class LiveReliabilityEvent(
    val sourceId: String,
    val kind: String,
)

/** Requête `POST /v1/stream/start` : lance un flux vidéo HLS converti à la volée pour téléphone/navigateur. */
@Serializable
data class StreamStartRequest(
    val kind: String,
    val id: String,
    val seriesId: String? = null,
    val season: Int? = null,
    val episodeNum: Int? = null,
    val profileId: String? = null,
    val deviceName: String = "iPhone",
    val deviceKey: String,
    val label: String = "",
    val takeover: String? = null,
    val startS: Int = 0,
)

/** Occupant d'un compte IPTV quand tous les comptes sont pris (`status = "busy"`). */
@Serializable
data class StreamOccupantDto(
    val id: String,
    val deviceName: String,
    val profileName: String? = null,
    val label: String? = null,
)

/** Réponse à `POST /v1/stream/start` (`status = "ok"` avec `sessionId` et `playlistPath`, ou `"busy"` avec `occupants`). */
@Serializable
data class StreamStartReply(
    val status: String,
    val sessionId: String? = null,
    val playlistPath: String? = null,
    val durationS: Int? = null,
    val title: String? = null,
    val occupants: List<StreamOccupantDto> = emptyList(),
)

/** État d'une session de lecture mobile (`GET /v1/stream/{sessionId}/status`). */
@Serializable
data class StreamStatusReply(
    val active: Boolean,
    val takenOverBy: String? = null,
)

@Serializable
private class RevInput(val categories: List<SnapCategory>, val streams: List<SnapStream>, val hidden: List<String>)

@Serializable
private class VodManifestRevInput(val categories: List<SnapVodCategory>, val hidden: List<String>)

private val revJson = Json { encodeDefaults = true }

/** Empreinte stable du contenu : le même contenu donne la même, un texte changé en donne une autre. */
fun liveSnapshotRev(categories: List<SnapCategory>, streams: List<SnapStream>, hidden: List<String>): String =
    sha256Hex(revJson.encodeToString(RevInput.serializer(), RevInput(categories, streams, hidden)).encodeToByteArray())

/** Empreinte stable d'un manifeste Films ou Séries. */
fun vodManifestRev(categories: List<SnapVodCategory>, hidden: List<String>): String =
    sha256Hex(revJson.encodeToString(VodManifestRevInput.serializer(), VodManifestRevInput(categories, hidden)).encodeToByteArray())

/** Empreinte stable du contenu d'une catégorie de films. */
fun movieCategoryRev(items: List<SnapMovie>): String =
    sha256Hex(revJson.encodeToString(ListSerializer(SnapMovie.serializer()), items).encodeToByteArray())

/** Empreinte stable du contenu d'une catégorie de séries. */
fun seriesCategoryRev(items: List<SnapSeries>): String =
    sha256Hex(revJson.encodeToString(ListSerializer(SnapSeries.serializer()), items).encodeToByteArray())

/** Empreinte du compte IPTV (serveur + nom d'utilisateur), jamais les identifiants eux-mêmes. */
fun accountFingerprint(server: String, username: String): String =
    sha256Hex((server.trim().lowercase() + "|" + username.trim()).encodeToByteArray()).take(32)

/** SHA-256 en Kotlin pur (code commun, pas d'API JVM) ; résultat en hexadécimal minuscule. */
internal fun sha256Hex(data: ByteArray): String {
    val k = intArrayOf(
        0x428a2f98, 0x71374491, -0x4a3f0431, -0x164a245b, 0x3956c25b, 0x59f111f1, -0x6dc07d5c, -0x54e3a12b,
        -0x27f85568, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, -0x7f214e02, -0x6423f959, -0x3e640e8c,
        -0x1b64963f, -0x1041b87a, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        -0x67c1aeae, -0x57ce3993, -0x4ffcd838, -0x40a68039, -0x391ff40d, -0x2a586eb9, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, -0x7e3d36d2, -0x6d8dd37b,
        -0x5d40175f, -0x57e599b5, -0x3db47490, -0x3893ae5d, -0x2e6d17e7, -0x2966f9dc, -0xbf1ca7b, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, -0x7b3787ec, -0x7338fdf8, -0x6f410006, -0x5baf9315, -0x41065c09, -0x398e870e,
    )
    val h = intArrayOf(
        0x6a09e667, -0x4498517b, 0x3c6ef372, -0x5ab00ac6, 0x510e527f, -0x64fa9774, 0x1f83d9ab, 0x5be0cd19,
    )
    val bitLen = data.size.toLong() * 8
    val padded = ByteArray(((data.size + 9 + 63) / 64) * 64)
    data.copyInto(padded)
    padded[data.size] = 0x80.toByte()
    for (i in 0 until 8) padded[padded.size - 1 - i] = (bitLen ushr (8 * i)).toByte()
    val w = IntArray(64)
    for (block in 0 until padded.size / 64) {
        for (t in 0 until 16) {
            val o = block * 64 + t * 4
            w[t] = ((padded[o].toInt() and 0xff) shl 24) or ((padded[o + 1].toInt() and 0xff) shl 16) or
                ((padded[o + 2].toInt() and 0xff) shl 8) or (padded[o + 3].toInt() and 0xff)
        }
        for (t in 16 until 64) {
            val s0 = w[t - 15].rotateRight(7) xor w[t - 15].rotateRight(18) xor (w[t - 15] ushr 3)
            val s1 = w[t - 2].rotateRight(17) xor w[t - 2].rotateRight(19) xor (w[t - 2] ushr 10)
            w[t] = w[t - 16] + s0 + w[t - 7] + s1
        }
        var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
        var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
        for (t in 0 until 64) {
            val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val ch = (e and f) xor (e.inv() and g)
            val t1 = hh + s1 + ch + k[t] + w[t]
            val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val t2 = s0 + maj
            hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh
    }
    val hex = "0123456789abcdef"
    val sb = StringBuilder()
    for (x in h) for (i in 3 downTo 0) {
        val byte = (x ushr (8 * i)) and 0xff
        sb.append(hex[byte ushr 4]).append(hex[byte and 15])
    }
    return sb.toString()
}
