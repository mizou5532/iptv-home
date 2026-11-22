package home.iptv.server

import home.iptv.shared.home.EpgReply
import home.iptv.shared.home.LiveReliabilityEvent
import home.iptv.shared.home.LiveReliabilityReply
import home.iptv.shared.home.LiveSnapshot
import home.iptv.shared.home.MovieCategoryReply
import home.iptv.shared.home.SearchReply
import home.iptv.shared.home.SearchRequest
import home.iptv.shared.home.SeriesCategoryReply
import home.iptv.shared.home.SnapEpg
import home.iptv.shared.home.SnapMovieInfo
import home.iptv.shared.home.SnapSeriesInfo
import home.iptv.shared.home.SportBoardReply
import home.iptv.shared.home.SportBoardRequest
import home.iptv.shared.home.StreamStartReply
import home.iptv.shared.home.StreamStartRequest
import home.iptv.shared.home.StreamStatusReply
import home.iptv.shared.home.VersionsReply
import home.iptv.shared.home.VersionsRequest
import home.iptv.shared.home.VersionMemoryReply
import home.iptv.shared.home.VersionMemoryUpdate
import home.iptv.shared.home.VodManifest
import home.iptv.shared.home.toSnapMovieInfo
import home.iptv.shared.home.toSnapSeriesInfo
import home.iptv.shared.meta.MediaKind
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `GET /v1/live?since=<rev>` : 304 si la télé a déjà cette version, 503 si aucune liste n'existe encore, sinon la liste
 * en JSON (compressée par le plugin Compression). À déclarer dans le bloc /v1 (protégé).
 */
fun Route.liveRoute(snapshot: () -> LiveSnapshot?) {
    // La liste est longue : on garde son texte JSON tant que sa version ne change pas.
    val cache = java.util.concurrent.atomic.AtomicReference<Pair<String, String>?>(null)
    get("/live") {
        val s = snapshot()
        when {
            s == null -> call.respond(HttpStatusCode.ServiceUnavailable)
            call.request.queryParameters["since"] == s.rev -> call.respond(HttpStatusCode.NotModified)
            else -> {
                val c = cache.get()
                val texte = if (c != null && c.first == s.rev) c.second else {
                    snapshotJson.encodeToString(LiveSnapshot.serializer(), s).also { cache.set(s.rev to it) }
                }
                call.respondText(texte, ContentType.Application.Json)
            }
        }
    }
    get("/ping") {
        call.respond(HttpStatusCode.NoContent)
    }
}

/**
 * `GET /v1/epg/{streamId}?limit=8&full=0|1` : guide d'une chaîne, partagé par la maison. 400 si l'identifiant ou les
 * paramètres sont invalides, 502 si le fournisseur échoue (jamais de détail : une exception Xtream peut contenir une
 * adresse avec identifiants, on ne journalise que le nom de sa classe). À déclarer dans le bloc /v1 (protégé).
 */
fun Route.epgRoute(cache: EpgCache) {
    get("/epg/{streamId}") {
        val id = call.parameters["streamId"].orEmpty()
        val limitText = call.request.queryParameters["limit"]
        val fullText = call.request.queryParameters["full"]
        val limit = if (limitText == null) 8 else limitText.takeIf { it.length <= 4 && it.all(Char::isDigit) }?.toIntOrNull()
        val full = when (fullText) { null, "0" -> false; "1" -> true; else -> null }
        if (id.isEmpty() || id.length > 32 || !id.all { it.isLetterOrDigit() || it == '_' || it == '-' } ||
            limit == null || limit < 1 || full == null
        ) {
            call.respond(HttpStatusCode.BadRequest)
            return@get
        }
        val entries = try {
            cache.entries(id, limit, full)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            System.err.println("Guide indisponible (${e::class.simpleName}).")
            call.respond(HttpStatusCode.BadGateway)
            return@get
        }
        val reply = EpgReply(entries.map { SnapEpg(it.title, it.start.toEpochMilliseconds(), it.end.toEpochMilliseconds(), it.nowPlaying) })
        call.respondText(snapshotJson.encodeToString(EpgReply.serializer(), reply), ContentType.Application.Json)
    }
}

private fun validId(id: String): Boolean =
    id.isNotEmpty() && id.length <= 32 && id.all { it.isLetterOrDigit() || it == '_' || it == '-' }

private const val MAX_VERSIONS_BODY = 128 * 1024
private const val MAX_VERSIONS_IDS = 2_000

/**
 * Routes Films, Séries, Fiches et Versions (étape 2). À déclarer dans le bloc `/v1` (protégé par jeton + protocole).
 * Aucune erreur ne contient de détail fournisseur (jamais d'URL ni d'identifiants).
 */
fun Route.vodRoutes(vod: VodJob, info: InfoCache) {
    get("/movies/manifest") {
        val m = vod.movieManifest.value
        when {
            m == null -> call.respond(HttpStatusCode.ServiceUnavailable)
            call.request.queryParameters["since"] == m.rev -> call.respond(HttpStatusCode.NotModified)
            else -> call.respondText(snapshotJson.encodeToString(VodManifest.serializer(), m), ContentType.Application.Json)
        }
    }

    get("/movies/category/{id}") {
        val id = call.parameters["id"].orEmpty()
        if (!validId(id)) {
            call.respond(HttpStatusCode.BadRequest)
            return@get
        }
        val reply = vod.movieCategoryReply(id)
        when {
            reply == null -> call.respond(HttpStatusCode.NotFound)
            call.request.queryParameters["since"] == reply.rev -> call.respond(HttpStatusCode.NotModified)
            else -> call.respondText(snapshotJson.encodeToString(MovieCategoryReply.serializer(), reply), ContentType.Application.Json)
        }
    }

    get("/movies/info/{id}") {
        if (!vod.hiddenLoaded) {
            call.respond(HttpStatusCode.ServiceUnavailable)
            return@get
        }
        val id = call.parameters["id"].orEmpty()
        if (!validId(id)) {
            call.respond(HttpStatusCode.BadRequest)
            return@get
        }
        val detail = try {
            info.movieInfo(id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: HiddenCategoryException) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        } catch (e: Exception) {
            System.err.println("Fiche film indisponible (${e::class.simpleName}).")
            call.respond(HttpStatusCode.BadGateway)
            return@get
        }
        if (detail == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        call.respondText(snapshotJson.encodeToString(SnapMovieInfo.serializer(), detail.toSnapMovieInfo()), ContentType.Application.Json)
    }

    get("/series/manifest") {
        val m = vod.seriesManifest.value
        when {
            m == null -> call.respond(HttpStatusCode.ServiceUnavailable)
            call.request.queryParameters["since"] == m.rev -> call.respond(HttpStatusCode.NotModified)
            else -> call.respondText(snapshotJson.encodeToString(VodManifest.serializer(), m), ContentType.Application.Json)
        }
    }

    get("/series/category/{id}") {
        val id = call.parameters["id"].orEmpty()
        if (!validId(id)) {
            call.respond(HttpStatusCode.BadRequest)
            return@get
        }
        val reply = vod.seriesCategoryReply(id)
        when {
            reply == null -> call.respond(HttpStatusCode.NotFound)
            call.request.queryParameters["since"] == reply.rev -> call.respond(HttpStatusCode.NotModified)
            else -> call.respondText(snapshotJson.encodeToString(SeriesCategoryReply.serializer(), reply), ContentType.Application.Json)
        }
    }

    get("/series/info/{id}") {
        if (!vod.hiddenLoaded) {
            call.respond(HttpStatusCode.ServiceUnavailable)
            return@get
        }
        val id = call.parameters["id"].orEmpty()
        if (!validId(id)) {
            call.respond(HttpStatusCode.BadRequest)
            return@get
        }
        val detail = try {
            info.seriesInfo(id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: HiddenCategoryException) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        } catch (e: Exception) {
            System.err.println("Fiche série indisponible (${e::class.simpleName}).")
            call.respond(HttpStatusCode.BadGateway)
            return@get
        }
        if (detail == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        call.respondText(snapshotJson.encodeToString(SnapSeriesInfo.serializer(), detail.toSnapSeriesInfo()), ContentType.Application.Json)
    }

    post("/versions/movies") { handleVersions(MediaKind.MOVIE, vod) }
    post("/versions/series") { handleVersions(MediaKind.SERIES, vod) }

    post("/versions/invalidate") {
        vod.reloadVersionSplits()
        call.respond(HttpStatusCode.NoContent)
    }
}

private const val MAX_MEMORY_BODY = 4 * 1024

/**
 * Routes mémoire partagée des versions (films et séries) :
 * - `GET /v1/versions/memory` : remplace automatiquement les versions en panne pour toutes les télés du foyer.
 * - `POST /v1/versions/memory` : retient une version qui a marché (`movie:<id>` ou `series:<id>` → `<id>`).
 */
fun Route.versionMemoryRoutes(store: VersionMemoryStore) {
    get("/versions/memory") {
        call.respondText(snapshotJson.encodeToString(VersionMemoryReply.serializer(), store.snapshot()), ContentType.Application.Json)
    }
    post("/versions/memory") {
        val body = call.receiveText()
        if (body.length > MAX_MEMORY_BODY) {
            call.respond(HttpStatusCode.BadRequest)
            return@post
        }
        val req = try {
            snapshotJson.decodeFromString(VersionMemoryUpdate.serializer(), body)
        } catch (_: Exception) {
            call.respond(HttpStatusCode.BadRequest)
            return@post
        }
        val ok = withContext(Dispatchers.IO) { store.remember(req.from, req.to) }
        if (!ok) {
            call.respond(HttpStatusCode.BadRequest)
            return@post
        }
        call.respond(HttpStatusCode.NoContent)
    }
}

/**
 * Routes fiabilité des copies du Direct (détection douce et partagée des chaînes mortes) :
 * - `GET /v1/live/reliability` : bilans connus de la maison.
 * - `POST /v1/live/reliability` : constat réel d'une télé (`ok`, `ko`, `stall`).
 */
fun Route.reliabilityRoutes(store: ReliabilityStore) {
    get("/live/reliability") {
        call.respondText(snapshotJson.encodeToString(LiveReliabilityReply.serializer(), store.snapshot()), ContentType.Application.Json)
    }
    post("/live/reliability") {
        val body = call.receiveText()
        if (body.length > MAX_MEMORY_BODY) {
            call.respond(HttpStatusCode.BadRequest)
            return@post
        }
        val req = try {
            snapshotJson.decodeFromString(LiveReliabilityEvent.serializer(), body)
        } catch (_: Exception) {
            call.respond(HttpStatusCode.BadRequest)
            return@post
        }
        val ok = withContext(Dispatchers.IO) { store.record(req.sourceId, req.kind) }
        if (!ok) {
            call.respond(HttpStatusCode.BadRequest)
            return@post
        }
        call.respond(HttpStatusCode.NoContent)
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleVersions(kind: MediaKind, vod: VodJob) {
    val body = call.receiveText()
    if (body.length > MAX_VERSIONS_BODY) {
        call.respond(HttpStatusCode.BadRequest)
        return
    }
    val req = try {
        snapshotJson.decodeFromString(VersionsRequest.serializer(), body)
    } catch (_: Exception) {
        call.respond(HttpStatusCode.BadRequest)
        return
    }
    if (req.ids.size > MAX_VERSIONS_IDS) {
        call.respond(HttpStatusCode.BadRequest)
        return
    }
    val reply = vod.lookupVersions(kind, req.ids)
    call.respondText(snapshotJson.encodeToString(VersionsReply.serializer(), reply), ContentType.Application.Json)
}

private const val MAX_SEARCH_BODY = 128 * 1024
private const val MAX_SEARCH_QUERY = 200
private const val MAX_SEARCH_HIDDEN = 5_000

/**
 * `POST /v1/search` : recherche Direct / Séries / Films / personne sur le serveur maison.
 * 400 si requête invalide ou trop longue, 503 si le catalogue de la section n'est pas encore prêt.
 */
fun Route.searchRoute(service: SearchService) {
    post("/search") {
        val body = call.receiveText()
        if (body.length > MAX_SEARCH_BODY) {
            call.respond(HttpStatusCode.BadRequest)
            return@post
        }
        val req = try {
            snapshotJson.decodeFromString(SearchRequest.serializer(), body)
        } catch (_: Exception) {
            call.respond(HttpStatusCode.BadRequest)
            return@post
        }
        if (req.section !in setOf("live", "series", "movies") ||
            req.query.length > MAX_SEARCH_QUERY ||
            req.hiddenCategories.size > MAX_SEARCH_HIDDEN ||
            req.hiddenChannels.size > MAX_SEARCH_HIDDEN ||
            req.limit !in 1..home.iptv.shared.search.SEARCH_LIMIT
        ) {
            call.respond(HttpStatusCode.BadRequest)
            return@post
        }
        val reply = try {
            service.search(req)
        } catch (_: SearchNotReadyException) {
            call.respond(HttpStatusCode.ServiceUnavailable)
            return@post
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            System.err.println("Recherche indisponible (${e::class.simpleName}).")
            call.respond(HttpStatusCode.InternalServerError)
            return@post
        }
        call.respondText(snapshotJson.encodeToString(SearchReply.serializer(), reply), ContentType.Application.Json)
    }
}

private const val MAX_SPORT_BODY = 64 * 1024

/**
 * Routes Sport (étape 4) :
 * - `POST /v1/sport/board` : horaires, classements, chaînes candidates et mémoire partagée prêts à afficher.
 * - `POST /v1/sport/invalidate` : invalide le cache mémoire/votes après étoile ou « Pas le bon match ».
 */
fun Route.sportRoutes(service: SportService) {
    post("/sport/board") {
        val body = call.receiveText()
        if (body.length > MAX_SPORT_BODY) {
            call.respond(HttpStatusCode.BadRequest)
            return@post
        }
        val req = try {
            snapshotJson.decodeFromString(SportBoardRequest.serializer(), body)
        } catch (_: Exception) {
            call.respond(HttpStatusCode.BadRequest)
            return@post
        }
        if (req.profileId.length > 64 ||
            req.hiddenCategories.size > MAX_SEARCH_HIDDEN ||
            req.hiddenChannels.size > MAX_SEARCH_HIDDEN ||
            req.favorites.size > MAX_SEARCH_HIDDEN
        ) {
            call.respond(HttpStatusCode.BadRequest)
            return@post
        }
        val reply = try {
            service.board(req)
        } catch (_: SportNotReadyException) {
            call.respond(HttpStatusCode.ServiceUnavailable)
            return@post
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            System.err.println("Grille Sport indisponible (${e::class.simpleName}).")
            call.respond(HttpStatusCode.BadGateway)
            return@post
        }
        call.respondText(snapshotJson.encodeToString(SportBoardReply.serializer(), reply), ContentType.Application.Json)
    }

    post("/sport/invalidate") {
        val profileId = call.request.queryParameters["profileId"]?.takeIf { it.length <= 64 }
        service.invalidate(profileId)
        call.respond(HttpStatusCode.NoContent)
    }
}

private const val MAX_STREAM_BODY = 8 * 1024
private val HLS_PLAYLIST_TYPE = ContentType("application", "vnd.apple.mpegurl")
private val HLS_SEGMENT_TYPE = ContentType("video", "mp2t")

/**
 * Routes protégées de démarrage/arrêt d'un flux vidéo HLS pour téléphone (`POST /v1/stream/start` et `POST /v1/stream/{sessionId}/stop`).
 */
fun Route.streamProtectedRoutes(relay: StreamRelay) {
    post("/stream/start") {
        val body = call.receiveText()
        if (body.length > MAX_STREAM_BODY) {
            call.respond(HttpStatusCode.BadRequest)
            return@post
        }
        val req = try {
            snapshotJson.decodeFromString(StreamStartRequest.serializer(), body)
        } catch (_: Exception) {
            call.respond(HttpStatusCode.BadRequest)
            return@post
        }
        val reply = relay.start(req)
        call.respondText(snapshotJson.encodeToString(StreamStartReply.serializer(), reply), ContentType.Application.Json)
    }

    post("/stream/{sessionId}/stop") {
        val id = call.parameters["sessionId"].orEmpty()
        relay.stop(id)
        call.respond(HttpStatusCode.NoContent)
    }

    get("/stream/{sessionId}/status") {
        val id = call.parameters["sessionId"].orEmpty()
        call.respondText(snapshotJson.encodeToString(StreamStatusReply.serializer(), relay.status(id)), ContentType.Application.Json)
    }
}

/**
 * Route de lecture des fichiers HLS (`GET /hls/{sessionId}/{file}`) : hors du nœud `/v1` et authentifiée par
 * l'identifiant de session aléatoire 128 bits (`sessionId`) pour que `<video>` natif d'iOS Safari lise le flux.
 */
fun Route.streamPublicRoutes(relay: StreamRelay) {
    head("/hls/{sessionId}/{file}") {
        val sessionId = call.parameters["sessionId"].orEmpty()
        val fileName = call.parameters["file"].orEmpty()
        val file = relay.readFile(sessionId, fileName)
        if (file == null) {
            call.respond(HttpStatusCode.NotFound)
            return@head
        }
        val isPlaylist = fileName.endsWith(".m3u8")
        val type = if (isPlaylist) HLS_PLAYLIST_TYPE else HLS_SEGMENT_TYPE
        val len = file.length()
        call.response.header(HttpHeaders.CacheControl, if (isPlaylist) "no-cache, no-store" else "public, max-age=60")
        call.respond(object : io.ktor.http.content.OutgoingContent.NoContent() {
            override val status = HttpStatusCode.OK
            override val contentType = type
            override val contentLength = len
        })
    }

    get("/hls/{sessionId}/{file}") {
        val sessionId = call.parameters["sessionId"].orEmpty()
        val fileName = call.parameters["file"].orEmpty()
        val file = relay.readFile(sessionId, fileName)
        if (file == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        call.response.header("Cache-Control", if (fileName.endsWith(".m3u8")) "no-cache, no-store" else "public, max-age=60")
        val contentType = if (fileName.endsWith(".m3u8")) HLS_PLAYLIST_TYPE else HLS_SEGMENT_TYPE
        val bytes = try {
            withContext(Dispatchers.IO) { file.readBytes() }
        } catch (_: Exception) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        call.respondBytes(bytes, contentType)
    }
}
