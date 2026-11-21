package home.iptv.tv.home

import home.iptv.shared.home.EpgReply
import home.iptv.shared.home.HOME_PROTOCOL
import home.iptv.shared.home.HomeToken
import home.iptv.shared.home.LiveSnapshot
import home.iptv.shared.home.MovieCategoryReply
import home.iptv.shared.home.SearchReply
import home.iptv.shared.home.SearchRequest
import home.iptv.shared.home.SeriesCategoryReply
import home.iptv.shared.home.SnapMovieInfo
import home.iptv.shared.home.SnapSeriesInfo
import home.iptv.shared.home.SportBoardReply
import home.iptv.shared.home.SportBoardRequest
import home.iptv.shared.home.VersionsReply
import home.iptv.shared.home.VersionsRequest
import home.iptv.shared.home.VodManifest
import home.iptv.shared.home.toMovieInfo
import home.iptv.shared.home.toSeriesInfo
import home.iptv.shared.meta.MediaKind
import home.iptv.shared.sync.HomeServerInfo
import home.iptv.shared.sync.SyncClient
import home.iptv.shared.xtream.EpgEntry
import home.iptv.shared.xtream.MovieInfo
import home.iptv.shared.xtream.SeriesInfo
import home.iptv.tv.data.LocalStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Instant

/** Ce que la télé sait du serveur maison : pas encore essayé, injoignable pour l'instant, ou joint (avec sa version). */
sealed interface HomeState {
    data object Unknown : HomeState
    data object Absent : HomeState
    data class Present(val version: String?) : HomeState
}

sealed interface LiveSnapshotResult {
    data class Fresh(val snapshot: LiveSnapshot) : LiveSnapshotResult
    /** 304 : le Direct déjà connu (même `since`) est à jour. */
    data object Unchanged : LiveSnapshotResult
    data object Unavailable : LiveSnapshotResult
}

sealed interface VodManifestResult {
    data class Fresh(val manifest: VodManifest) : VodManifestResult
    data object Unchanged : VodManifestResult
    data object Unavailable : VodManifestResult
}

sealed interface MovieCategoryResult {
    data class Fresh(val reply: MovieCategoryReply) : MovieCategoryResult
    data object Unchanged : MovieCategoryResult
    data object NotFound : MovieCategoryResult
    data object Unavailable : MovieCategoryResult
}

sealed interface SeriesCategoryResult {
    data class Fresh(val reply: SeriesCategoryReply) : SeriesCategoryResult
    data object Unchanged : SeriesCategoryResult
    data object NotFound : SeriesCategoryResult
    data object Unavailable : SeriesCategoryResult
}

/** Sous-ensemble Films / Séries / Fiches / Versions du client serveur maison (facilite les essais). */
interface HomeVodClient {
    val state: StateFlow<HomeState>
    suspend fun movieManifest(since: String?): VodManifestResult
    suspend fun seriesManifest(since: String?): VodManifestResult
    suspend fun movieCategory(id: String, since: String?): MovieCategoryResult
    suspend fun seriesCategory(id: String, since: String?): SeriesCategoryResult
    suspend fun movieInfo(id: String): MovieInfo?
    suspend fun seriesInfo(id: String): SeriesInfo?
    suspend fun versions(kind: MediaKind, ids: List<String>): VersionsReply?
    suspend fun invalidateVersions()
}

/** Sous-ensemble Recherche du client serveur maison (facilite les essais). */
interface HomeSearchClient {
    suspend fun search(request: SearchRequest): SearchReply?
}

/** Sous-ensemble Sport du client serveur maison (facilite les essais). */
interface HomeSportClient {
    suspend fun sportBoard(request: SportBoardRequest): SportBoardReply?
    suspend fun invalidateSport(profileId: String? = null)
}

private const val ABSENT_WINDOW_MS = 60_000L
private const val SNAPSHOT_BODY_TIMEOUT_MS = 20_000L
private const val INFO_TTL_MS = 10 * 60_000L
private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
private val json = Json { ignoreUnknownKeys = true }

/**
 * Client du serveur maison : adresse et empreinte lues dans Supabase (gardées 10 min), jeton propre à cette télé,
 * TLS épinglé. Après un échec ou un délai dépassé, le serveur est tenu pour [HomeState.Absent] pendant 60 s : aucune
 * demande ne part pendant ce temps. Le jeton ne figure dans aucun journal, message d'erreur ni `toString`, et le
 * jeton de session Supabase n'est jamais envoyé.
 */
class HomeServerClient(
    private val sync: SyncClient,
    private val store: LocalStore,
    private val householdId: () -> String?,
    private val base: OkHttpClient,
    private val clockMs: () -> Long,
    private val timeoutMs: Long = 1_500,
) : HomeVodClient, HomeSearchClient, HomeSportClient {
    private val _state = MutableStateFlow<HomeState>(HomeState.Unknown)
    override val state: StateFlow<HomeState> = _state.asStateFlow()

    @Volatile private var absentUntilMs = Long.MIN_VALUE
    @Volatile private var info: HomeServerInfo? = null
    @Volatile private var infoAtMs = 0L
    @Volatile private var clientPin: String? = null
    @Volatile private var client: OkHttpClient? = null
    private val tokenLock = Mutex()

    suspend fun liveSnapshot(since: String?): LiveSnapshotResult {
        // Lectures Supabase + connexion + en-têtes : 1 500 ms ; le corps (jusqu'à quelques centaines de Ko) : 20 s.
        val r = fetch(SNAPSHOT_BODY_TIMEOUT_MS) { url ->
            url.newBuilder().addPathSegments("v1/live").apply { since?.let { addQueryParameter("since", it) } }.build()
        } ?: return LiveSnapshotResult.Unavailable
        return when (r.code) {
            304 -> LiveSnapshotResult.Unchanged
            200 -> runCatching { json.decodeFromString(LiveSnapshot.serializer(), r.body) }.getOrNull()
                ?.let { LiveSnapshotResult.Fresh(it) } ?: failed().let { LiveSnapshotResult.Unavailable }
            else -> failed().let { LiveSnapshotResult.Unavailable } // 503, 426, autre : on réessaiera dans 60 s
        }
    }

    suspend fun epg(streamId: String, limit: Int, full: Boolean): List<EpgEntry>? {
        // Tout l'appel (Supabase compris) tient dans le délai.
        val r = withContext(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMs) {
                fetch(timeoutMs) { url ->
                    url.newBuilder().addPathSegments("v1/epg").addPathSegment(streamId)
                        .addQueryParameter("limit", limit.toString()).addQueryParameter("full", if (full) "1" else "0").build()
                }
            }
        } ?: run {
            // Délai dépassé (la fenêtre de 60 s n'est pas encore ouverte) : on l'ouvre ; sinon fetch l'a déjà fait ou elle court.
            if (clockMs() >= absentUntilMs) failed()
            return null
        }
        // Une erreur propre à ce guide (id ou limite invalides, fournisseur en panne pour UNE chaîne) ne coupe pas
        // le serveur : le Direct continue d'y passer.
        if (r.code == 400 || r.code == 404 || r.code == 502 || r.code == 503) return null
        if (r.code != 200) { failed(); return null }
        val reply = runCatching { json.decodeFromString(EpgReply.serializer(), r.body) }.getOrNull()
            ?: run { failed(); return null }
        return reply.entries.map {
            EpgEntry(it.title, Instant.fromEpochMilliseconds(it.startMs), Instant.fromEpochMilliseconds(it.endMs), it.nowPlaying)
        }
    }

    override suspend fun movieManifest(since: String?): VodManifestResult = vodManifest("v1/movies/manifest", since)
    override suspend fun seriesManifest(since: String?): VodManifestResult = vodManifest("v1/series/manifest", since)

    private suspend fun vodManifest(path: String, since: String?): VodManifestResult {
        val r = strictFetch { url ->
            url.newBuilder().addPathSegments(path).apply { since?.let { addQueryParameter("since", it) } }.build()
        } ?: return VodManifestResult.Unavailable
        return when (r.code) {
            304 -> VodManifestResult.Unchanged
            200 -> runCatching { json.decodeFromString(VodManifest.serializer(), r.body) }.getOrNull()
                ?.let { VodManifestResult.Fresh(it) } ?: failed().let { VodManifestResult.Unavailable }
            // 404 ou 503 (ex. serveur encore en 0.1.0 ou première passe en cours) : ne coupe pas le serveur si 404/503 ?
            // Sur 503 le serveur répond qu'il prépare encore le catalogue : on retombe en secours sans le déclarer absent.
            404, 503 -> VodManifestResult.Unavailable
            else -> failed().let { VodManifestResult.Unavailable }
        }
    }

    override suspend fun movieCategory(id: String, since: String?): MovieCategoryResult {
        val r = fetch(SNAPSHOT_BODY_TIMEOUT_MS) { url ->
            url.newBuilder().addPathSegments("v1/movies/category").addPathSegment(id)
                .apply { since?.let { addQueryParameter("since", it) } }.build()
        } ?: return MovieCategoryResult.Unavailable
        return when (r.code) {
            304 -> MovieCategoryResult.Unchanged
            404 -> MovieCategoryResult.NotFound
            200 -> runCatching { json.decodeFromString(MovieCategoryReply.serializer(), r.body) }.getOrNull()
                ?.let { MovieCategoryResult.Fresh(it) } ?: failed().let { MovieCategoryResult.Unavailable }
            else -> failed().let { MovieCategoryResult.Unavailable }
        }
    }

    override suspend fun seriesCategory(id: String, since: String?): SeriesCategoryResult {
        val r = fetch(SNAPSHOT_BODY_TIMEOUT_MS) { url ->
            url.newBuilder().addPathSegments("v1/series/category").addPathSegment(id)
                .apply { since?.let { addQueryParameter("since", it) } }.build()
        } ?: return SeriesCategoryResult.Unavailable
        return when (r.code) {
            304 -> SeriesCategoryResult.Unchanged
            404 -> SeriesCategoryResult.NotFound
            200 -> runCatching { json.decodeFromString(SeriesCategoryReply.serializer(), r.body) }.getOrNull()
                ?.let { SeriesCategoryResult.Fresh(it) } ?: failed().let { SeriesCategoryResult.Unavailable }
            else -> failed().let { SeriesCategoryResult.Unavailable }
        }
    }

    /**
     * Préchauffe silencieusement la connexion (adresse Supabase, jeton, TLS et cache de jeton côté serveur)
     * pendant « Qui regarde ? » pour que le premier écran du profil réponde en quelques millisecondes.
     */
    suspend fun warmUp() {
        val budget = maxOf(timeoutMs, 3_500L)
        val r = fetch(bodyMs = budget, headerMs = budget) { url ->
            url.newBuilder().addPathSegments("v1/ping").build()
        } ?: return
        // Sur un serveur < 0.4.1 qui ne connaît pas /v1/ping, 404 arrive APRÈS vérification du jeton : serveur bien présent !
        if (r.code == 404) succeeded()
    }

    override suspend fun movieInfo(id: String): MovieInfo? {
        val r = softFetch(maxOf(timeoutMs, 2_500L)) { url ->
            url.newBuilder().addPathSegments("v1/movies/info").addPathSegment(id).build()
        } ?: return null
        if (r.code == 400 || r.code == 404 || r.code == 502 || r.code == 503) return null
        if (r.code != 200) { failed(); return null }
        return runCatching { json.decodeFromString(SnapMovieInfo.serializer(), r.body).toMovieInfo() }.getOrNull()
            ?: run { failed(); null }
    }

    override suspend fun seriesInfo(id: String): SeriesInfo? {
        val r = softFetch(maxOf(timeoutMs, 2_500L)) { url ->
            url.newBuilder().addPathSegments("v1/series/info").addPathSegment(id).build()
        } ?: return null
        if (r.code == 400 || r.code == 404 || r.code == 502 || r.code == 503) return null
        if (r.code != 200) { failed(); return null }
        return runCatching { json.decodeFromString(SnapSeriesInfo.serializer(), r.body).toSeriesInfo() }.getOrNull()
            ?: run { failed(); null }
    }

    override suspend fun versions(kind: MediaKind, ids: List<String>): VersionsReply? {
        if (ids.isEmpty()) return VersionsReply("", emptyMap())
        val segment = if (kind == MediaKind.MOVIE) "v1/versions/movies" else "v1/versions/series"
        val payload = json.encodeToString(VersionsRequest.serializer(), VersionsRequest(ids.distinct().take(2_000)))
        val budget = if (_state.value == HomeState.Unknown) maxOf(timeoutMs, 3_500L) else timeoutMs
        val r = fetch(bodyMs = budget, postBody = payload, headerMs = budget) { url ->
            url.newBuilder().addPathSegments(segment).build()
        } ?: return null
        if (r.code == 400 || r.code == 404) return null
        if (r.code != 200) { failed(); return null }
        return runCatching { json.decodeFromString(VersionsReply.serializer(), r.body) }.getOrNull()
            ?: run { failed(); null }
    }

    override suspend fun invalidateVersions() {
        try {
            strictFetch(postBody = "{}") { url -> url.newBuilder().addPathSegments("v1/versions/invalidate").build() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Meilleur effort : jamais bloquant pour l'utilisateur.
        }
    }

    override suspend fun search(request: SearchRequest): SearchReply? {
        val payload = json.encodeToString(SearchRequest.serializer(), request)
        val r = strictFetch(postBody = payload) { url ->
            url.newBuilder().addPathSegments("v1/search").build()
        } ?: return null
        // 400, 404 (serveur encore en 0.2.0) ou 503 (première passe en cours) : secours local sans couper le serveur.
        if (r.code == 400 || r.code == 404 || r.code == 503) return null
        if (r.code != 200) { failed(); return null }
        return runCatching { json.decodeFromString(SearchReply.serializer(), r.body) }.getOrNull()
            ?: run { failed(); null }
    }

    override suspend fun sportBoard(request: SportBoardRequest): SportBoardReply? {
        val payload = json.encodeToString(SportBoardRequest.serializer(), request)
        // Sur serveur chaud : < 50 ms. Sur forceRefresh ou ligue optionnelle froide : on laisse un peu de marge
        // tout en gardant connectTimeout = timeoutMs (serveur éteint détecté en 1,5 s).
        val headerMs = if (request.forceRefresh) maxOf(timeoutMs, 6_000L) else maxOf(timeoutMs, 3_000L)
        val r = fetch(bodyMs = maxOf(headerMs, 10_000L), postBody = payload, headerMs = headerMs) { url ->
            url.newBuilder().addPathSegments("v1/sport/board").build()
        } ?: return null
        // 400, 404 (serveur < 0.4.0), 502 ou 503 (catalogue pas encore prêt) : secours local sans couper le serveur.
        if (r.code == 400 || r.code == 404 || r.code == 502 || r.code == 503) return null
        if (r.code != 200) { failed(); return null }
        return runCatching { json.decodeFromString(SportBoardReply.serializer(), r.body) }.getOrNull()
            ?: run { failed(); null }
    }

    override suspend fun invalidateSport(profileId: String?) {
        try {
            strictFetch(postBody = "{}") { url ->
                url.newBuilder().addPathSegments("v1/sport/invalidate")
                    .apply { if (!profileId.isNullOrBlank()) addQueryParameter("profileId", profileId) }
                    .build()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Meilleur effort : jamais bloquant pour l'utilisateur.
        }
    }

    /**
     * Appel pour une fiche isolée (relais vers le fournisseur) : si le serveur maison est déjà connu présent et que
     * le délai expire en attendant le fournisseur, on retombe en secours pour CETTE fiche sans couper le serveur 60 s.
     */
    private suspend fun softFetch(budgetMs: Long, target: (okhttp3.HttpUrl) -> okhttp3.HttpUrl): Reply? =
        withContext(Dispatchers.IO) {
            if (clockMs() < absentUntilMs) return@withContext null
            val alreadyPresent = _state.value is HomeState.Present
            try {
                val response = withTimeoutOrNull(budgetMs) { exchange(target, budgetMs, null, markAbsentOnTimeout = !alreadyPresent) }
                    ?: return@withContext if (alreadyPresent) null else failed().let { null }
                response.use {
                    val body = if (it.code == 200) it.body.string() else ""
                    Reply(it.code, body)
                }.also { if (it.code == 200 || it.code == 304 || it.code == 204) succeeded() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (!alreadyPresent) failed()
                null
            }
        }

    /** Appel « comptoir » : tout (Supabase + connexion + corps) tient dans [timeoutMs] (1 500 ms). */
    private suspend fun strictFetch(postBody: String? = null, target: (okhttp3.HttpUrl) -> okhttp3.HttpUrl): Reply? {
        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMs) {
                fetch(timeoutMs, postBody, target = target)
            }
        } ?: run {
            if (clockMs() >= absentUntilMs) failed()
            null
        }
    }

    private class Reply(val code: Int, val body: String)

    /**
     * Une demande complète (adresse, jeton, renouvellements). `null` = indisponible, déjà noté Absent.
     * Tout se passe sur Dispatchers.IO (délais en temps réel, lecture du corps bloquante bornée par OkHttp).
     */
    private suspend fun fetch(
        bodyMs: Long,
        postBody: String? = null,
        headerMs: Long = timeoutMs,
        target: (okhttp3.HttpUrl) -> okhttp3.HttpUrl,
    ): Reply? =
        withContext(Dispatchers.IO) {
            if (clockMs() < absentUntilMs) return@withContext null // fenêtre de 60 s : aucune demande
            try {
                // Jusqu'aux en-têtes de réponse : le délai court.
                val response = withTimeoutOrNull(headerMs) { exchange(target, bodyMs, postBody) }
                    ?: return@withContext failed().let { null }
                response.use {
                    val body = if (it.code == 200) it.body.string() else ""
                    Reply(it.code, body)
                }.also { if (it.code == 200 || it.code == 304 || it.code == 204) succeeded() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed().let { null }
            }
        }

    /** Demande jusqu'aux en-têtes ; renvoie la réponse ouverte (à fermer), ou `null` après un échec. */
    private suspend fun exchange(
        target: (okhttp3.HttpUrl) -> okhttp3.HttpUrl,
        bodyMs: Long,
        postBody: String? = null,
        markAbsentOnTimeout: Boolean = true,
    ): Response? {
        val h = householdId() ?: return failed().let { null }
        var server = serverInfo(h, force = false) ?: return failed().let { null }
        var token = tokenFor(h)
        var renewed = false
        var reread = false
        while (true) {
            val response = try {
                send(server, token, target, bodyMs, postBody)
            } catch (e: IOException) {
                if (hasPinMismatch(e) && !reread) {
                    reread = true
                    // Serveur réinstallé ? Une seule relecture de l'adresse et de l'empreinte publiées.
                    val fresh = serverInfo(h, force = true)
                    if (fresh == null || (fresh.pin == server.pin && fresh.url == server.url)) return failed().let { null }
                    server = fresh
                    continue
                }
                if (!markAbsentOnTimeout && e is java.io.InterruptedIOException) return null
                return failed().let { null }
            }
            if (response.code == 401) {
                response.close()
                if (renewed) return failed().let { null }
                renewed = true
                token = renewToken(h, token)
                continue
            }
            serverVersion = server.version
            return response
        }
    }

    private suspend fun serverInfo(h: String, force: Boolean): HomeServerInfo? {
        val cached = info
        if (!force && cached != null && clockMs() - infoAtMs < INFO_TTL_MS) return cached
        val fresh = sync.homeServer(h)
        info = fresh
        infoAtMs = clockMs()
        return fresh
    }

    private suspend fun tokenFor(h: String): HomeToken {
        store.homeToken(h)?.let { return HomeToken(it) }
        return tokenLock.withLock {
            // Une autre demande a pu en obtenir un entre-temps.
            HomeToken(store.homeToken(h) ?: issueAndSave(h))
        }
    }

    /** Un nouveau jeton remplace l'ancien côté serveur : une seule demande le fait, les autres reprennent le sien. */
    private suspend fun renewToken(h: String, rejected: HomeToken): HomeToken = tokenLock.withLock {
        val current = store.homeToken(h)
        if (current != null && current != rejected.value) HomeToken(current) else HomeToken(issueAndSave(h))
    }

    /** Hors annulation : un jeton émis invalide l'ancien, il doit toujours être enregistré. */
    private suspend fun issueAndSave(h: String): String = withContext(NonCancellable) {
        sync.issueHomeServerToken(h).also { store.saveHomeToken(h, it) }
    }

    private suspend fun send(
        server: HomeServerInfo,
        token: HomeToken,
        target: (okhttp3.HttpUrl) -> okhttp3.HttpUrl,
        bodyMs: Long,
        postBody: String? = null,
    ): Response {
        val url = target(server.url.toHttpUrl())
        val builder = Request.Builder().url(url)
            .header("Authorization", "Bearer " + token.value)
            .header("X-Home-Protocol", HOME_PROTOCOL.toString())
        if (postBody != null) builder.post(postBody.toRequestBody(JSON_MEDIA))
        val request = builder.build()
        // Le délai d'OkHttp couvre toute la demande, corps compris ; la connexion reste bornée par timeoutMs.
        val http = clientFor(server.pin).newBuilder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(bodyMs, TimeUnit.MILLISECONDS)
            .build()
        return http.newCall(request).await()
    }

    private fun clientFor(pin: String): OkHttpClient {
        val existing = client
        if (existing != null && clientPin == pin) return existing
        val fresh = pinnedClient(base, pin)
        client = fresh
        clientPin = pin
        return fresh
    }

    /**
     * L'erreur d'empreinte peut être la cause directe, ou une erreur « supprimée » : OkHttp essaie plusieurs routes
     * (IPv6 puis IPv4) et garde la première erreur en tête, les autres en `suppressed`.
     */
    private fun hasPinMismatch(e: Throwable, depth: Int = 0): Boolean {
        if (e is PinMismatchException) return true
        if (depth >= 6) return false
        return (listOfNotNull(e.cause) + e.suppressed).any { hasPinMismatch(it, depth + 1) }
    }

    @Volatile private var serverVersion: String? = null

    private fun succeeded() {
        absentUntilMs = Long.MIN_VALUE
        store.saveLastServerContactMs(clockMs())
        _state.value = HomeState.Present(serverVersion)
    }

    private fun failed() {
        absentUntilMs = clockMs() + ABSENT_WINDOW_MS
        _state.value = HomeState.Absent
    }
}

/** Attend la réponse d'OkHttp ; l'annulation de la coroutine annule la demande. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { cont.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { cont.resume(response) { _, _, _ -> response.close() } }
    })
}
