package home.iptv.shared.xtream

import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.time.Instant

private val EXTENSION = Regex("[A-Za-z0-9]{1,5}")
private val BASE64 = Regex("[A-Za-z0-9+/]+={0,2}")

/**
 * Lecture tolérante de l'API Xtream (le format varie selon les panneaux).
 * Les URL complètes (qui contiennent les identifiants) ne sont jamais écrites dans une erreur.
 */
class XtreamApi(
    private val http: HttpClient,
    private val creds: XtreamCreds,
    private val timeoutMs: Long = 15_000,
) {
    suspend fun userInfo(): UserInfo {
        val info = (fetch(null, emptyMap()) as? JsonObject)?.get("user_info") as? JsonObject
            ?: throw XtreamError.Refused
        val auth = when (val a = info["auth"]) {
            is JsonPrimitive -> a.contentOrNull.let { it == "1" || it == "true" }
            else -> false
        }
        if (!auth) throw XtreamError.Refused
        val formats = (info["allowed_output_formats"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
        val expires = info["exp_date"].text()?.trim()?.toLongOrNull()?.takeIf { it > 0 }
        return UserInfo(true, info["max_connections"].text()?.trim()?.toIntOrNull(), formats, expires, info["status"].text()?.trim()?.takeIf { it.isNotEmpty() })
    }

    suspend fun liveCategories(): List<LiveCategory> =
        items(fetch("get_live_categories", emptyMap())).mapNotNull { o ->
            val id = o["category_id"].text() ?: return@mapNotNull null
            val name = o["category_name"].text() ?: return@mapNotNull null
            LiveCategory(id, name)
        }

    suspend fun liveStreams(categoryId: String): List<LiveStream> =
        items(fetch("get_live_streams", mapOf("category_id" to categoryId))).mapNotNull { o ->
            val id = o["stream_id"].text() ?: return@mapNotNull null
            val name = o["name"].text() ?: return@mapNotNull null
            LiveStream(
                id = id,
                name = name,
                categoryId = o["category_id"].text() ?: categoryId,
                icon = o["stream_icon"].text()?.takeIf { it.isNotBlank() },
                epgChannelId = o["epg_channel_id"].text()?.takeIf { it.isNotBlank() },
                number = o["num"].text()?.trim()?.toIntOrNull(),
            )
        }

    /**
     * Catégories de séries, dans l'ordre du fournisseur. Un identifiant vide est écarté : `get_series` sans catégorie
     * renvoie TOUT le catalogue sur la plupart des panneaux. Un nom absent ou vide ne fait pas disparaître la catégorie.
     */
    suspend fun seriesCategories(): List<SeriesCategory> =
        items(fetch("get_series_categories", emptyMap(), slow = true), emptyObjectIsEmpty = true).mapNotNull { o ->
            val id = idOf(o["category_id"]) ?: return@mapNotNull null
            SeriesCategory(id, o["category_name"].label() ?: UNNAMED_CATEGORY)
        }

    /**
     * Toutes les séries d'une catégorie. Tolérant entrée par entrée (nombres en texte ou non, `name` nul, `cover: false`,
     * `backdrop_path` tableau ou objet, `category_id` absent…) : seule une entrée sans identifiant est écartée, puisqu'on
     * ne pourrait pas l'ouvrir.
     */
    suspend fun series(categoryId: String): List<SeriesSummary> =
        items(fetch("get_series", mapOf("category_id" to categoryId), slow = true), emptyObjectIsEmpty = true).mapNotNull { o ->
            val id = idOf(o["series_id"]) ?: return@mapNotNull null
            summaryOf(id, o, categoryId).let { if (it.name.isBlank()) it.copy(name = UNTITLED_SERIES) else it }
        }

    /**
     * Fiche d'une série. `null` si le fournisseur n'a ni fiche ni épisode (réponse vide).
     * Tolère les saisons absentes ou vides, les durées/extensions/affiches manquantes.
     */
    suspend fun seriesInfo(seriesId: String): SeriesInfo? {
        val root = fetch("get_series_info", mapOf("series_id" to seriesId))
        if (root is JsonNull) return null
        if (root !is JsonObject) {
            if (root is JsonArray && root.isEmpty()) return null
            throw XtreamError.Unexpected(200)
        }
        val info = root["info"] as? JsonObject
        val episodesRaw = root["episodes"]
        val byseason = episodesBySeason(episodesRaw)
        if (info == null && byseason.isEmpty()) return null

        val names = linkedMapOf<Int, String?>()
        (root["seasons"] as? JsonArray)?.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val n = (o["season_number"].text() ?: o["season"].text())?.trim()?.toIntOrNull() ?: return@forEach
            names[n] = o["name"].text()?.takeIf { it.isNotBlank() }
        }
        byseason.keys.forEach { names.putIfAbsent(it, null) }
        val seasons = names.entries.sortedBy { it.key }.map { (n, name) ->
            Season(n, name ?: "Saison $n", byseason[n].orEmpty())
        }
        val summary = summaryOf(seriesId, info ?: JsonObject(emptyMap()), "")
        return SeriesInfo(summary, seasons)
    }

    /**
     * Catégories de films, dans l'ordre du fournisseur. Un identifiant vide est écarté : `get_vod_streams` sans
     * catégorie renvoie TOUT le catalogue. Un nom absent ou vide ne fait pas disparaître la catégorie.
     */
    suspend fun movieCategories(): List<MovieCategory> =
        items(fetch("get_vod_categories", emptyMap(), slow = true), emptyObjectIsEmpty = true).mapNotNull { o ->
            val id = idOf(o["category_id"]) ?: return@mapNotNull null
            MovieCategory(id, o["category_name"].label() ?: UNNAMED_CATEGORY)
        }

    /**
     * Tous les films d'une catégorie. `category_id` est toujours envoyé : une catégorie vide n'est jamais demandée
     * (la liste globale compte des centaines de milliers de titres), le résultat est vide sans aucun appel.
     * Tolérant entrée par entrée : seule une entrée sans identifiant est écartée, puisqu'on ne pourrait pas la lire.
     */
    suspend fun movies(categoryId: String): List<MovieSummary> {
        if (categoryId.isBlank()) return emptyList()
        return items(fetch("get_vod_streams", mapOf("category_id" to categoryId), slow = true), emptyObjectIsEmpty = true)
            .mapNotNull { o ->
                val id = idOf(o["stream_id"]) ?: return@mapNotNull null
                movieOf(id, o, o, categoryId)
            }
    }

    /**
     * Fiche d'un film (`get_vod_info`). `null` si le fournisseur ne le connaît pas (réponse vide, `info` et
     * `movie_data` absents ou vides). Un objet sans ces deux clés (erreur du panneau) est une réponse inattendue.
     */
    suspend fun movieInfo(movieId: String): MovieInfo? {
        if (movieId.isBlank()) return null
        val root = fetch("get_vod_info", mapOf("vod_id" to movieId))
        if (root is JsonNull) return null
        if (root !is JsonObject) {
            if (root is JsonArray && root.isEmpty()) return null
            throw XtreamError.Unexpected(200)
        }
        if (root.isNotEmpty() && "info" !in root && "movie_data" !in root) throw XtreamError.Unexpected(200)
        val info = (root["info"] as? JsonObject)?.takeIf { it.isNotEmpty() }
        val data = (root["movie_data"] as? JsonObject)?.takeIf { it.isNotEmpty() }
        if (info == null && data == null) return null
        // Un objet non vide peut être une erreur du panneau sous `info`/`movie_data`.
        // Exiger au moins un champ de film exploitable, tout en acceptant une fiche pauvre (résumé seul, id seul…).
        val movieFields = setOf(
            "stream_id", "name", "title", "category_id", "movie_image", "stream_icon", "cover_big", "cover",
            "year", "releasedate", "releaseDate", "release_date", "genre", "plot", "description", "rating",
            "added", "container_extension", "duration_secs", "duration",
        )
        if (listOfNotNull(info, data).none { obj -> movieFields.any { obj[it].label() != null } }) {
            throw XtreamError.Unexpected(200)
        }
        val empty = JsonObject(emptyMap())
        return MovieInfo(movieOf(movieId, info ?: empty, data ?: empty, ""))
    }

    /**
     * [details] : champs descriptifs (l'entrée de liste, ou `info` d'une fiche) ; [data] : champs techniques (la même
     * entrée de liste, ou `movie_data` d'une fiche). Chaque champ manquant est cherché dans l'autre objet.
     */
    private fun movieOf(id: String, details: JsonObject, data: JsonObject, defaultCategory: String): MovieSummary {
        fun label(vararg keys: String): String? =
            keys.firstNotNullOfOrNull { details[it].label() } ?: keys.firstNotNullOfOrNull { data[it].label() }
        val release = label("releasedate", "releaseDate", "release_date")
        val duration = (details["duration_secs"] ?: data["duration_secs"]).text()?.trim()?.toDoubleOrNull()
            ?.takeIf { it.isFinite() }?.toInt()?.takeIf { it > 0 }
            ?: (details["duration"] ?: data["duration"]).text()?.let(::parseDuration)
        return MovieSummary(
            id = id,
            categoryId = idOf(data["category_id"]) ?: idOf(details["category_id"]) ?: defaultCategory,
            name = details["name"].label() ?: details["title"].label() ?: data["name"].label() ?: data["title"].label()
                ?: UNTITLED_MOVIE,
            cover = label("movie_image", "stream_icon", "cover_big", "cover"),
            year = label("year") ?: release?.take(4)?.takeIf { y -> y.length == 4 && y.all { it.isDigit() } },
            genre = label("genre"),
            plot = label("plot", "description"),
            rating = label("rating"),
            addedAt = epochOf(data["added"]) ?: epochOf(details["added"]),
            extension = extensionOf(data["container_extension"]) ?: extensionOf(details["container_extension"]) ?: "mkv",
            durationS = duration?.takeIf { it > 0 },
            backdrop = backdropOf(details["backdrop_path"]) ?: backdropOf(data["backdrop_path"]),
        )
    }

    /** `backdrop_path` : tableau d'adresses (la première non vide), ou une adresse seule ; autre forme -> null. */
    private fun backdropOf(el: JsonElement?): String? = when (el) {
        is JsonArray -> el.firstNotNullOfOrNull { it.label()?.takeIf { u -> u.startsWith("http") } }
        else -> el.label()?.takeIf { it.startsWith("http") }
    }

    private fun extensionOf(el: JsonElement?): String? =
        el.text()?.trim()?.trimStart('.')?.takeIf { EXTENSION.matches(it) }

    private fun summaryOf(id: String, o: JsonObject, defaultCategory: String): SeriesSummary {
        val release = o["releaseDate"].label() ?: o["release_date"].label()
        val year = o["year"].label()
            ?: release?.take(4)?.takeIf { y -> y.length == 4 && y.all { it.isDigit() } }
        return SeriesSummary(
            id = id,
            categoryId = idOf(o["category_id"]) ?: defaultCategory,
            name = o["name"].label() ?: o["title"].label() ?: "",
            cover = o["cover"].label() ?: o["movie_image"].label(),
            year = year,
            genre = o["genre"].label(),
            plot = o["plot"].label(),
            rating = o["rating"].label(),
            backdrop = backdropOf(o["backdrop_path"]),
        )
    }

    /**
     * `episodes` : objet indexé par numéro de saison, ou tableau (de saisons, ou à plat avec un champ `season`).
     * Les épisodes sans identifiant sont ignorés.
     */
    private fun episodesBySeason(raw: JsonElement?): Map<Int, List<Episode>> {
        val result = sortedMapOf<Int, List<Episode>>()
        fun parse(season: Int, arr: JsonArray) {
            val list = arr.mapIndexedNotNull { i, el ->
                val o = el as? JsonObject ?: return@mapIndexedNotNull null
                episodeOf(o, season, i + 1)
            }
            result[season] = (result[season].orEmpty() + list).sortedBy { it.number }
        }
        when (raw) {
            is JsonObject -> raw.forEach { (key, value) ->
                val n = key.trim().toIntOrNull() ?: return@forEach
                (value as? JsonArray)?.let { parse(n, it) }
            }
            is JsonArray -> raw.forEachIndexed { index, el ->
                when (el) {
                    is JsonArray -> parse(index + 1, el)
                    is JsonObject -> {
                        val n = el["season"].text()?.trim()?.toIntOrNull() ?: 1
                        episodeOf(el, n, index + 1)?.let { result[n] = (result[n].orEmpty() + it).sortedBy { e -> e.number } }
                    }
                    else -> Unit
                }
            }
            else -> Unit
        }
        return result
    }

    private fun episodeOf(o: JsonObject, season: Int, fallbackNumber: Int): Episode? {
        val id = o["id"].text()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val number = o["episode_num"].text()?.trim()?.toIntOrNull() ?: fallbackNumber
        val info = o["info"] as? JsonObject
        val duration = info?.get("duration_secs").text()?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }?.toInt()
            ?: info?.get("duration").text()?.let(::parseDuration)
        return Episode(
            id = id,
            season = season,
            number = number,
            title = o["title"].text()?.takeIf { it.isNotBlank() } ?: "Épisode $number",
            durationS = duration?.takeIf { it > 0 },
            extension = o["container_extension"].text()?.trim()?.trimStart('.')?.takeIf { EXTENSION.matches(it) } ?: "mkv",
            cover = info?.get("movie_image").text()?.takeIf { it.isNotBlank() },
        )
    }

    /** "HH:MM:SS" ou "MM:SS" -> secondes ; toute autre forme -> null. */
    private fun parseDuration(text: String): Int? {
        val parts = text.trim().split(":").map { it.toIntOrNull() ?: return null }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            else -> null
        }
    }

    /**
     * Guide court (`get_short_epg`) : en principe le programme en cours et les suivants. Tolérant, entrée par entrée :
     * un titre illisible ou une date manquante écarte cette entrée seulement, jamais tout le guide (un titre en
     * Latin-1 faisait échouer toute la chaîne : « Programme indisponible »). Voir [parseEpg] pour les formats acceptés.
     */
    suspend fun shortEpg(streamId: String, limit: Int = 2): List<EpgEntry> =
        parseEpg(fetch("get_short_epg", mapOf("stream_id" to streamId, "limit" to limit.toString())))

    /**
     * Tout le guide d'une chaîne (`get_simple_data_table`), passé compris, avec le drapeau `now_playing`. Plus lourd :
     * secours quand le guide court ne donne ni programme en cours ni suivant (panneaux qui renvoient les premiers
     * programmes de la journée au lieu des prochains).
     */
    suspend fun fullEpg(streamId: String): List<EpgEntry> =
        parseEpg(fetch("get_simple_data_table", mapOf("stream_id" to streamId)))

    /**
     * Formats vus selon les panneaux : `epg_listings` en tableau ou en objet à clés numériques (ou la racine en
     * tableau) ; heures en secondes Unix (`start_timestamp`, `stop_timestamp` ou `end_timestamp`, nombre ou texte,
     * parfois en millisecondes) ou seulement en dates `start`/`end` « AAAA-MM-JJ HH:MM:SS » à l'heure locale du
     * serveur (décalage lu une fois dans `server_info`) ; titre en Base64 ou en clair ; `now_playing` 1/"1"/true.
     */
    private suspend fun parseEpg(root: JsonElement): List<EpgEntry> {
        val rows = epgListings(root)
        val needsOffset = rows.any { o -> epochOf(o["start_timestamp"]) == null && localDate(o["start"].text()) != null }
        val offset = if (needsOffset) serverOffsetSeconds() ?: 0L else 0L
        return rows.mapNotNull { o ->
            val title = decodeTitle(o["title"].text() ?: return@mapNotNull null) ?: return@mapNotNull null
            val start = epochOf(o["start_timestamp"]) ?: localDate(o["start"].text())?.minus(offset) ?: return@mapNotNull null
            val end = epochOf(o["stop_timestamp"]) ?: epochOf(o["end_timestamp"])
                ?: (localDate(o["end"].text()) ?: localDate(o["stop"].text()))?.minus(offset) ?: return@mapNotNull null
            val nowPlaying = (o["now_playing"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() in setOf("1", "true")
            EpgEntry(title, Instant.fromEpochSeconds(start), Instant.fromEpochSeconds(end), nowPlaying)
        }
    }

    private fun epgListings(root: JsonElement): List<JsonObject> {
        val list = when (root) {
            is JsonObject -> root["epg_listings"]
            is JsonArray -> root
            else -> null
        }
        return when (list) {
            is JsonArray -> list.mapNotNull { it as? JsonObject }
            is JsonObject -> if (list.isNotEmpty() && list.keys.all { it.toIntOrNull() != null }) {
                list.entries.sortedBy { it.key.toInt() }.mapNotNull { it.value as? JsonObject }
            } else {
                emptyList()
            }
            else -> emptyList()
        }
    }

    /** Secondes Unix ; des millisecondes (13 chiffres) sont ramenées en secondes ; 0 ou illisible : null. */
    private fun epochOf(el: JsonElement?): Long? {
        val raw = el.text()?.trim() ?: return null
        val n = raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong() ?: return null
        if (n <= 0) return null
        return if (n > 100_000_000_000L) n / 1000 else n
    }

    /**
     * Décalage de l'heure du serveur sur UTC (secondes), d'après `server_info.time_now` et `timestamp_now`, arrondi
     * au quart d'heure. Demandé une fois par serveur et par processus ; null si le panneau ne le donne pas.
     */
    private suspend fun serverOffsetSeconds(): Long? {
        offsetsLock.withLock {
            serverOffsets[creds.server]?.let { return it }
            // Panneau qui ne donne pas son heure (ou appel en échec) : pas de nouvel essai avant 30 min, sinon chaque
            // guide « dates seulement » referait une demande.
            serverOffsetMisses[creds.server]?.let { if (it.elapsedNow() < OFFSET_RETRY) return null }
        }
        val offset = readServerOffset()
        offsetsLock.withLock {
            if (offset != null) serverOffsets[creds.server] = offset
            else serverOffsetMisses[creds.server] = TimeSource.Monotonic.markNow()
        }
        return offset
    }

    private suspend fun readServerOffset(): Long? {
        val info = try {
            (fetch(null, emptyMap()) as? JsonObject)?.get("server_info") as? JsonObject
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        val now = epochOf(info["timestamp_now"]) ?: return null
        val local = localDate(info["time_now"].text()) ?: return null
        return ((local - now).toDouble() / 900).let { kotlin.math.round(it).toLong() } * 900
    }

    /** Base64 (UTF-8, sinon Latin-1) ; un titre qui n'est pas du Base64 est gardé tel quel. Jamais d'exception. */
    private fun decodeTitle(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        if (!BASE64.matches(text) || text.length % 4 != 0) return text
        val bytes = try {
            Base64.Default.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL).decode(text)
        } catch (e: IllegalArgumentException) {
            return text
        }
        val decoded = try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (e: Exception) {
            // Octets non UTF-8 : panneau en Latin-1 (« Télé »), chaque octet est un caractère.
            String(CharArray(bytes.size) { (bytes[it].toInt() and 0xFF).toChar() })
        }
        return decoded.trim().takeIf { it.isNotEmpty() }
    }

    /**
     * Tableau, ou objet à clés numériques (`{"0":{...}}`) ; toute autre forme (erreur `{"user_info":…}`, texte) est
     * inattendue. Un objet vide `{}` n'est une liste vide que si [emptyObjectIsEmpty] (séries : leur cache refuse déjà
     * une réponse vide) ; pour le direct c'est une réponse inattendue, sinon elle viderait le cache.
     */
    private fun items(root: JsonElement, emptyObjectIsEmpty: Boolean = false): List<JsonObject> = when {
        root is JsonArray -> root.mapNotNull { it as? JsonObject }
        root is JsonObject && root.isEmpty() && emptyObjectIsEmpty -> emptyList()
        root is JsonObject && root.isEmpty() -> throw XtreamError.Unexpected(200)
        root is JsonObject && root.keys.all { it.trim().toIntOrNull() != null } ->
            root.values.mapNotNull { it as? JsonObject }
        else -> throw XtreamError.Unexpected(200)
    }

    /** Nombre ou chaîne -> texte ; absent ou `null` -> null. */
    private fun JsonElement?.text(): String? =
        (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    /** Texte à afficher : chaîne ou nombre, sans espaces autour ; absent, vide, `null`, booléen, tableau ou objet -> null. */
    private fun JsonElement?.label(): String? {
        val p = this as? JsonPrimitive ?: return null
        if (p is JsonNull || (!p.isString && (p.content == "true" || p.content == "false"))) return null
        return p.content.trim().takeIf { it.isNotEmpty() }
    }

    /** Identifiant : chaîne ou nombre (`5005.0` -> `5005`), sans espaces ; vide ou absent -> null. */
    private fun idOf(el: JsonElement?): String? {
        val raw = el.label() ?: return null
        return INTEGRAL_DECIMAL.matchEntire(raw)?.groupValues?.get(1) ?: raw
    }

    /** [slow] : grosses listes (une catégorie peut peser plusieurs Mo sur une connexion http lente), délai triplé. */
    private suspend fun fetch(action: String?, params: Map<String, String>, slow: Boolean = false): JsonElement {
        val url = apiUrl(creds, action, params)
        val result: Pair<Int, String> = try {
            withTimeout(if (slow) timeoutMs * 3 else timeoutMs) {
                val response = http.get(url)
                response.status.value to response.bodyAsText()
            }
        } catch (e: TimeoutCancellationException) {
            throw XtreamError.Unreachable
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Le message d'origine peut contenir l'URL : on ne le propage pas.
            throw XtreamError.Unreachable
        }
        val (status, body) = result
        if (status == 401 || status == 403) throw XtreamError.Refused
        if (status !in 200..299) throw XtreamError.Unexpected(status)
        if (body.isBlank()) return JsonNull
        return parseBody(body) ?: throw XtreamError.Unexpected(status)
    }

    /**
     * JSON de la réponse. Tolère une marque d'ordre d'octets et un avertissement PHP affiché avant le JSON
     * (« <br /><b>Notice</b>… » : le JSON commence alors au début d'une ligne). `null` si rien n'est lisible.
     */
    private fun parseBody(body: String): JsonElement? {
        val text = body.trimStart('﻿', ' ', '\n', '\r', '\t')
        try {
            return Json.parseToJsonElement(text)
        } catch (e: Exception) {
            if (text.startsWith("[") || text.startsWith("{")) return null
        }
        val start = JSON_LINE_START.find(text)?.range?.last ?: return null
        return try {
            Json.parseToJsonElement(text.substring(start))
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        val INTEGRAL_DECIMAL = Regex("""(-?\d+)\.0+""")
        /** Début d'une ligne qui ouvre un tableau ou un objet JSON. */
        val JSON_LINE_START = Regex("""(?m)^[ \t]*[\[{]""")
    }
}

/** Décalages horaires des serveurs déjà lus (clé : adresse du serveur, jamais écrite ailleurs). */
private val serverOffsets = mutableMapOf<String, Long>()

/** Serveurs qui n'ont pas donné leur heure, avec le moment du dernier essai. */
private val serverOffsetMisses = mutableMapOf<String, TimeMark>()
private val OFFSET_RETRY = 30.minutes
private val offsetsLock = Mutex()

private val LOCAL_DATE = Regex("""(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})(?::(\d{2}))?""")

/** « AAAA-MM-JJ HH:MM[:SS] » lu comme une heure UTC (le décalage du serveur est retiré ensuite) ; sinon null. */
internal fun localDate(text: String?): Long? {
    val m = LOCAL_DATE.find(text?.trim() ?: return null) ?: return null
    val (y, mo, d, h, mi) = m.destructured
    val sec = m.groupValues[6].ifEmpty { "0" }.toLong()
    val year = y.toLong(); val month = mo.toLong(); val day = d.toLong()
    if (month !in 1..12 || day !in 1..31) return null
    // Jours depuis 1970-01-01 (algorithme de H. Hinnant, calendrier grégorien).
    val yy = if (month <= 2) year - 1 else year
    val era = (if (yy >= 0) yy else yy - 399) / 400
    val yoe = yy - era * 400
    val mp = (month + 9) % 12
    val doy = (153 * mp + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    val days = era * 146097 + doe - 719468
    return days * 86400 + h.toLong() * 3600 + mi.toLong() * 60 + sec
}
