package home.iptv.tv.home

import home.iptv.shared.home.EpgReply
import home.iptv.shared.home.HOME_PROTOCOL
import home.iptv.shared.home.HomeToken
import home.iptv.shared.home.LiveSnapshot
import home.iptv.shared.sync.HomeServerInfo
import home.iptv.shared.sync.SyncClient
import home.iptv.shared.xtream.EpgEntry
import home.iptv.tv.data.LocalStore
import kotlinx.coroutines.CancellationException
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
import okhttp3.OkHttpClient
import okhttp3.Request
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

private const val ABSENT_WINDOW_MS = 60_000L
private const val INFO_TTL_MS = 10 * 60_000L
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
) {
    private val _state = MutableStateFlow<HomeState>(HomeState.Unknown)
    val state: StateFlow<HomeState> = _state.asStateFlow()

    @Volatile private var absentUntilMs = Long.MIN_VALUE
    @Volatile private var info: HomeServerInfo? = null
    @Volatile private var infoAtMs = 0L
    @Volatile private var clientPin: String? = null
    @Volatile private var client: OkHttpClient? = null
    private val tokenLock = Mutex()

    suspend fun liveSnapshot(since: String?): LiveSnapshotResult {
        val r = fetch { url ->
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
        val r = fetch { url ->
            url.newBuilder().addPathSegments("v1/epg").addPathSegment(streamId)
                .addQueryParameter("limit", limit.toString()).addQueryParameter("full", if (full) "1" else "0").build()
        } ?: return null
        if (r.code != 200) { failed(); return null }
        val reply = runCatching { json.decodeFromString(EpgReply.serializer(), r.body) }.getOrNull()
            ?: run { failed(); return null }
        return reply.entries.map {
            EpgEntry(it.title, Instant.fromEpochMilliseconds(it.startMs), Instant.fromEpochMilliseconds(it.endMs), it.nowPlaying)
        }
    }

    private class Reply(val code: Int, val body: String)

    /** Une demande complète (adresse, jeton, renouvellements). `null` = indisponible, déjà noté Absent. */
    private suspend fun fetch(target: (okhttp3.HttpUrl) -> okhttp3.HttpUrl): Reply? {
        if (clockMs() < absentUntilMs) return null // fenêtre de 60 s : aucune demande
        try {
            val h = householdId() ?: return failed().let { null }
            var server = serverInfo(h, force = false) ?: return failed().let { null }
            var token = tokenFor(h)
            var renewed = false
            var reread = false
            while (true) {
                val reply = try {
                    send(server, token, target)
                } catch (e: IOException) {
                    if (hasPinMismatch(e) && !reread) {
                        reread = true
                        // Serveur réinstallé ? Une seule relecture de l'adresse et de l'empreinte publiées.
                        val fresh = serverInfo(h, force = true)
                        if (fresh == null || (fresh.pin == server.pin && fresh.url == server.url)) return failed().let { null }
                        server = fresh
                        continue
                    }
                    return failed().let { null }
                }
                if (reply.code == 401) {
                    if (renewed) return failed().let { null }
                    renewed = true
                    token = renewToken(h, token)
                    continue
                }
                if (reply.code == 200 || reply.code == 304) succeeded(server)
                return reply
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return failed().let { null }
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

    private suspend fun tokenFor(h: String): HomeToken =
        HomeToken(store.homeToken(h) ?: tokenLock.withLock {
            // Une autre demande a pu en obtenir un entre-temps.
            store.homeToken(h) ?: sync.issueHomeServerToken(h).also { store.saveHomeToken(h, it) }
        })

    /** Un nouveau jeton remplace l'ancien côté serveur : une seule demande le fait, les autres reprennent le sien. */
    private suspend fun renewToken(h: String, rejected: HomeToken): HomeToken = tokenLock.withLock {
        val current = store.homeToken(h)
        if (current != null && current != rejected.value) HomeToken(current)
        else HomeToken(sync.issueHomeServerToken(h).also { store.saveHomeToken(h, it) })
    }

    private suspend fun send(server: HomeServerInfo, token: HomeToken, target: (okhttp3.HttpUrl) -> okhttp3.HttpUrl): Reply {
        val url = target(server.url.toHttpUrl())
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer " + token.value)
            .header("X-Home-Protocol", HOME_PROTOCOL.toString())
            .build()
        val http = clientFor(server.pin)
        val call = http.newCall(request)
        return call.await().use { Reply(it.code, if (it.code == 200) it.body.string() else "") }
    }

    private fun clientFor(pin: String): OkHttpClient {
        val existing = client
        if (existing != null && clientPin == pin) return existing
        val fresh = pinnedClient(
            base.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build(), pin,
        )
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

    private fun succeeded(server: HomeServerInfo) {
        absentUntilMs = Long.MIN_VALUE
        _state.value = HomeState.Present(server.version)
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
        override fun onResponse(call: Call, response: Response) { cont.resume(response) }
    })
}
