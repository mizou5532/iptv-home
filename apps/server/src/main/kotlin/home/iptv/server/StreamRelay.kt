package home.iptv.server

import home.iptv.shared.home.StreamOccupantDto
import home.iptv.shared.home.StreamStartReply
import home.iptv.shared.home.StreamStartRequest
import home.iptv.shared.home.StreamStatusReply
import home.iptv.shared.sync.AccountClaim
import home.iptv.shared.sync.ExtraAccount
import home.iptv.shared.sync.SyncClient
import home.iptv.shared.xtream.StreamKind
import home.iptv.shared.xtream.XtreamCreds
import home.iptv.shared.xtream.streamUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Relais vidéo HLS (`.m3u8` + segments `.ts`) sur le serveur maison pour regarder le Direct, les Films, les Séries
 * et le Sport depuis un téléphone (iPhone Safari, Android Chrome) ou un ordinateur :
 * - Réserve un compte IPTV via [SyncClient.claimAccount] (même partage de comptes que les télés du foyer).
 * - Convertit/ré-encapsule le flux à la volée avec `ffmpeg` sans jamais exposer les identifiants ni l'URL fournisseur.
 * - Arrête automatiquement `ffmpeg` et libère le compte IPTV dès que le téléphone s'arrête ou après 35 s d'inactivité.
 */
class StreamRelay(
    private val sync: SyncClient,
    private val householdId: String,
    private val mainCreds: suspend () -> XtreamCreds?,
    private val infoCache: InfoCache,
    private val workDir: File,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val ffmpegBinary: String = "ffmpeg",
    private val ffprobeBinary: String = "ffprobe",
    private val idleTimeoutMs: Long = 35_000L,
    private val maxSessions: Int = 4,
) {
    class Session(
        val id: String,
        val deviceKey: String,
        val leaseId: String?,
        val label: String,
        val dir: File,
        @Volatile var process: Process?,
        @Volatile var lastAccessMs: Long,
        @Volatile var takenOverBy: String? = null,
    )

    private val sessions = ConcurrentHashMap<String, Session>()
    private val lock = Mutex()
    private val random = SecureRandom()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        workDir.mkdirs()
        workDir.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun newSessionId(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Démarre une session HLS pour [req] (arrête d'abord toute session précédente du même [StreamStartRequest.deviceKey]). */
    suspend fun start(req: StreamStartRequest): StreamStartReply = withContext(Dispatchers.IO) {
        if (!validId(req.id) || req.deviceKey.length !in 8..64 || req.kind !in setOf("live", "movie", "episode")) {
            return@withContext StreamStartReply(status = "error")
        }
        // Arrête l'éventuel flux précédent de ce même téléphone avant de réserver/mettre à jour le compte.
        stopByDeviceKey(req.deviceKey)

        // Si le plafond de sessions simultanées est atteint, ferme la plus ancienne inactive.
        lock.withLock {
            if (sessions.size >= maxSessions) {
                val oldest = sessions.values.minByOrNull { it.lastAccessMs }
                if (oldest != null) closeSession(oldest)
            }
        }

        val resolved = resolveTarget(req) ?: return@withContext StreamStartReply(status = "not_found")
        val cleanLabel = req.label.trim().ifEmpty { resolved.title }.take(80)
        val cleanDevice = req.deviceName.trim().ifEmpty { "iPhone" }.take(40)

        val claim = try {
            sync.claimAccount(
                householdId = householdId,
                profileId = req.profileId?.takeIf { it.isNotBlank() },
                deviceName = cleanDevice,
                label = cleanLabel,
                deviceKey = req.deviceKey,
                takeover = req.takeover?.takeIf { it.isNotBlank() },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return@withContext StreamStartReply(status = "error")
        }

        when (claim) {
            is AccountClaim.Busy -> {
                return@withContext StreamStartReply(
                    status = "busy",
                    occupants = claim.occupants.map {
                        StreamOccupantDto(
                            id = it.streamId.orEmpty(),
                            deviceName = it.deviceName,
                            profileName = it.profileName,
                            label = it.label,
                        )
                    },
                )
            }
            is AccountClaim.Granted -> {
                if (!claim.replaced.isNullOrBlank() && claim.streamId != null) {
                    // Attend jusqu'à 5 s que l'appareil remplacé libère sa connexion chez le fournisseur.
                    repeat(10) {
                        val done = runCatching { sync.takeoverDone(claim.streamId!!) }.getOrDefault(true)
                        if (done) return@repeat
                        delay(500L)
                    }
                }
                val creds = credsForPosition(claim.position)
                if (creds == null) {
                    claim.streamId?.let { runCatching { sync.releaseStream(it) } }
                    return@withContext StreamStartReply(status = "error")
                }
                val url = streamUrl(creds, resolved.streamKind, resolved.streamId, resolved.ext)
                val sessionId = newSessionId()
                val dir = File(workDir, sessionId).apply { mkdirs() }
                val startS = req.startS.coerceAtLeast(0)
                val isLive = req.kind == "live"
                // Piste audio : celle de la langue de la version (« FR - South Park » joue en français même si l'anglais est
                // la 1re piste du fichier), sinon celle des langues du profil, sinon la 1re (24 nov.). Pas pour le Direct.
                val audioMap = if (isLive) DEFAULT_AUDIO_MAP else chooseAudioMap(url, resolved.versionAudio, req.audio)
                // Safari iOS / WebKit refuse HEVC-in-TS (VOD) et fige certains Direct 1080i entrelacés : on transcode toujours en H.264 progressif 8-bit universel.
                val startTranscode = true
                val proc = launchFfmpeg(url, dir, isLive = isLive, startS = startS, transcodeVideo = startTranscode, audioMap = audioMap)
                val ready = awaitPlaylist(dir, proc, timeoutMs = 12_000L, minSegments = if (isLive) 3 else 2)
                val needCodecFallback = if (ready && !startTranscode) {
                    val seg0 = dir.listFiles()?.filter { it.name.endsWith(".ts") && it.length() > 0 }?.minByOrNull { it.name }
                    val codec = seg0?.let { probeVideoCodec(it) }
                    codec != null && codec != "h264"
                } else {
                    false
                }
                val finalProc = when {
                    ready && !needCodecFallback -> proc
                    startTranscode -> {
                        proc?.destroyForcibly()
                        dir.deleteRecursively()
                        claim.streamId?.let { runCatching { sync.releaseStream(it) } }
                        return@withContext StreamStartReply(status = "unavailable")
                    }
                    else -> {
                        proc?.destroyForcibly()
                        cleanDir(dir)
                        val fallbackProc = launchFfmpeg(url, dir, isLive = isLive, startS = startS, transcodeVideo = true, audioMap = audioMap)
                        if (awaitPlaylist(dir, fallbackProc, timeoutMs = 12_000L)) fallbackProc else {
                            fallbackProc?.destroyForcibly()
                            dir.deleteRecursively()
                            claim.streamId?.let { runCatching { sync.releaseStream(it) } }
                            return@withContext StreamStartReply(status = "unavailable")
                        }
                    }
                }
                val session = Session(
                    id = sessionId,
                    deviceKey = req.deviceKey,
                    leaseId = claim.streamId,
                    label = cleanLabel,
                    dir = dir,
                    process = finalProc,
                    lastAccessMs = clockMs(),
                )
                sessions[sessionId] = session
                return@withContext StreamStartReply(
                    status = "ok",
                    sessionId = sessionId,
                    playlistPath = "/hls/$sessionId/index.m3u8",
                    durationS = resolved.durationS,
                    title = resolved.title,
                )
            }
        }
    }

    /** Lit un fichier HLS (`index.m3u8` ou `seg_NNNNN.ts`) d'une session active et rafraîchit son horodatage d'activité. */
    fun readFile(sessionId: String, fileName: String): File? {
        if (!SESSION_RE.matches(sessionId) || !FILE_RE.matches(fileName)) return null
        val s = sessions[sessionId] ?: return null
        s.lastAccessMs = clockMs()
        val f = File(s.dir, fileName)
        return if (f.isFile && f.parentFile?.canonicalFile == s.dir.canonicalFile) f else null
    }

    fun status(sessionId: String): StreamStatusReply {
        val s = sessions[sessionId] ?: return StreamStatusReply(active = false)
        return StreamStatusReply(active = s.takenOverBy == null && s.process?.isAlive == true, takenOverBy = s.takenOverBy)
    }

    suspend fun stop(sessionId: String) {
        val s = sessions.remove(sessionId) ?: return
        closeSession(s)
    }

    private suspend fun stopByDeviceKey(deviceKey: String) {
        val matching = sessions.values.filter { it.deviceKey == deviceKey }
        for (s in matching) {
            if (sessions.remove(s.id, s)) closeSession(s)
        }
    }

    private suspend fun closeSession(s: Session) {
        runCatching { s.process?.destroyForcibly() }
        runCatching { s.dir.deleteRecursively() }
        s.leaseId?.let { id -> runCatching { sync.releaseStream(id) } }
    }

    /** Boucle de maintenance : signe de vie des comptes IPTV réservés, détection de reprise de main et nettoyage d'inactivité. */
    suspend fun loop() {
        while (true) {
            delay(5_000L)
            val now = clockMs()
            for (s in sessions.values.toList()) {
                if (now - s.lastAccessMs > idleTimeoutMs) {
                    if (sessions.remove(s.id, s)) closeSession(s)
                    continue
                }
                val leaseId = s.leaseId ?: continue
                val alive = runCatching { sync.heartbeatStream(leaseId, s.label) }.getOrDefault(true)
                if (!alive) {
                    val taken = runCatching { sync.streamTakeover(leaseId) }.getOrNull()
                    s.takenOverBy = taken?.let { listOfNotNull(it.profileName, it.deviceName).joinToString(" · ").ifEmpty { "Un autre écran" } } ?: "Un autre écran"
                    runCatching { s.process?.destroyForcibly() }
                }
            }
        }
    }

    private data class Target(
        val streamKind: StreamKind,
        val streamId: String,
        val ext: String,
        val title: String,
        val durationS: Int?,
        val videoCodec: String? = null,
        /** Langue audio annoncée par la version (« FR - South Park » → « fr ») ; null si inconnue. */
        val versionAudio: String? = null,
    )

    private suspend fun resolveTarget(req: StreamStartRequest): Target? = when (req.kind) {
        "live" -> Target(StreamKind.LIVE, req.id, "ts", req.label.ifBlank { "Direct" }, null)
        "movie" -> {
            val info = runCatching { infoCache.movieInfo(req.id) }.getOrNull() ?: return null
            val ext = info.summary.extension.ifBlank { "mkv" }
            Target(StreamKind.MOVIE, req.id, ext, info.summary.name, info.summary.durationS, info.tech?.videoCodec,
                versionAudio(info.summary.name, infoCache.movieCategoryName(info.summary.categoryId)))
        }
        "episode" -> {
            val seriesId = req.seriesId?.takeIf { validId(it) } ?: return null
            val info = runCatching { infoCache.seriesInfo(seriesId) }.getOrNull() ?: return null
            val all = info.seasons.flatMap { it.episodes }
            val ep = all.firstOrNull { req.id.isNotBlank() && it.id == req.id }
                ?: all.firstOrNull { it.season == req.season && it.number == req.episodeNum }
                ?: return null
            val ext = ep.extension.ifBlank { "mkv" }
            val title = "${info.summary.name} · S${ep.season} É${ep.number}"
            Target(StreamKind.SERIES, ep.id, ext, title, ep.durationS,
                versionAudio = versionAudio(info.summary.name, infoCache.seriesCategoryName(info.summary.categoryId)))
        }
        else -> null
    }

    private suspend fun credsForPosition(position: Int): XtreamCreds? {
        val main = mainCreds() ?: return null
        if (position <= 1) return main
        val extra: ExtraAccount = runCatching { sync.extraAccounts(householdId) }.getOrDefault(emptyList())
            .firstOrNull { it.position == position && it.status == "ok" } ?: return null
        return XtreamCreds(main.server, extra.username, extra.password)
    }

    /** Langue annoncée par le titre ou la catégorie de la version, en code audio (« qc » et « fr » → « fr »). */
    private fun versionAudio(title: String, categoryName: String?): String? =
        home.iptv.shared.versions.versionTag(title, categoryName).lang?.audio

    /** `-map` de la piste audio voulue ; la 1re piste quand rien ne correspond ou que la lecture des pistes échoue. */
    private fun chooseAudioMap(url: String, versionAudio: String?, preferred: List<String>): String {
        if (versionAudio == null && preferred.isEmpty()) return DEFAULT_AUDIO_MAP
        val tracks = probeAudioTracks(url)
        if (tracks.size < 2) return DEFAULT_AUDIO_MAP
        return home.iptv.shared.versions.pickAudioTrack(tracks, versionAudio, preferred)?.let { "0:$it" } ?: DEFAULT_AUDIO_MAP
    }

    /** Pistes audio du fichier (numéro, langue) lues par `ffprobe` ; vide en cas d'échec. L'adresse n'est jamais journalisée. */
    private fun probeAudioTracks(url: String): List<Pair<Int, String?>> = runCatching {
        val proc = ProcessBuilder(
            ffprobeBinary,
            "-v", "error", "-probesize", "1000000", "-analyzeduration", "1000000",
            "-select_streams", "a", "-show_entries", "stream=index:stream_tags=language", "-of", "csv=p=0",
            url,
        ).redirectErrorStream(false).start()
        // Sortie lue à part : un tampon plein ne doit jamais bloquer ffprobe.
        val out = java.util.concurrent.CompletableFuture.supplyAsync { proc.inputStream.bufferedReader().use { it.readText() } }
        scope.launch { runCatching { proc.errorStream.use { it.readBytes() } } }
        if (!proc.waitFor(PROBE_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return emptyList()
        }
        parseAudioProbe(out.get(2, java.util.concurrent.TimeUnit.SECONDS))
    }.getOrDefault(emptyList())

    private fun launchFfmpeg(url: String, dir: File, isLive: Boolean, startS: Int, transcodeVideo: Boolean, audioMap: String = DEFAULT_AUDIO_MAP): Process? = runCatching {
        val cmd = mutableListOf(
            ffmpegBinary,
            "-hide_banner", "-loglevel", "error", "-nostdin",
            "-probesize", "1000000", "-analyzeduration", "1000000",
            "-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "5",
        )
        if (isLive) {
            cmd += listOf("-readrate", "1.0", "-readrate_initial_burst", "6")
        } else {
            cmd += listOf("-readrate", "3.0", "-readrate_initial_burst", "25")
            if (startS > 0) cmd += listOf("-ss", startS.toString())
        }
        cmd += listOf(
            "-fflags", "+genpts+discardcorrupt",
            "-i", url,
            "-map", "0:v:0", "-map", audioMap,
        )
        if (transcodeVideo) {
            val vf = if (isLive) "yadif=0:-1:1,scale='min(1280,iw)':-2" else "scale='min(1280,iw)':-2"
            cmd += listOf(
                "-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency",
                "-profile:v", "high", "-level", "4.1",
                "-crf", "24", "-maxrate", "3500k", "-bufsize", "7000k",
                "-vf", vf, "-pix_fmt", "yuv420p",
                "-g", "48", "-sc_threshold", "0", "-force_key_frames", "expr:gte(t,n_forced*2)",
            )
        } else {
            cmd += listOf("-c:v", "copy")
        }
        cmd += listOf("-max_muxing_queue_size", "1024", "-af", "aresample=async=1:first_pts=0", "-c:a", "aac", "-ar", "48000", "-ac", "2", "-b:a", "128k", "-f", "hls")
        cmd += if (isLive) {
            listOf(
                "-hls_time", "2",
                "-hls_list_size", "15",
                "-hls_flags", "delete_segments+independent_segments+omit_endlist+temp_file",
            )
        } else {
            listOf(
                "-hls_time", "2",
                "-hls_playlist_type", "event",
                "-hls_list_size", "0",
                "-hls_flags", "independent_segments+temp_file",
            )
        }
        cmd += listOf(
            "-hls_segment_filename", File(dir, "seg_%05d.ts").absolutePath,
            File(dir, "index.m3u8").absolutePath,
        )
        ProcessBuilder(cmd)
            .directory(dir)
            .redirectErrorStream(true)
            .start()
            .also { proc ->
                // Draine la sortie d'erreur sans jamais la journaliser (évite de bloquer le tampon et ne fuite jamais d'URL).
                scope.launch { runCatching { proc.inputStream.use { it.readBytes() } } }
            }
    }.getOrNull()

    private fun probeVideoCodec(segment: File): String? = runCatching {
        val proc = ProcessBuilder(
            ffprobeBinary,
            "-v", "error",
            "-select_streams", "v:0",
            "-show_entries", "stream=codec_name",
            "-of", "csv=p=0",
            segment.absolutePath,
        ).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().use { it.readText() }.trim().lowercase()
        proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
        if (!proc.isAlive) out.lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } else {
            proc.destroyForcibly()
            null
        }
    }.getOrNull()

    private suspend fun awaitPlaylist(dir: File, proc: Process?, timeoutMs: Long, minSegments: Int = 2): Boolean {
        if (proc == null) return false
        val playlist = File(dir, "index.m3u8")
        val deadline = clockMs() + timeoutMs
        while (clockMs() < deadline) {
            if (playlist.isFile && playlist.length() > 20) {
                val text = runCatching { playlist.readText() }.getOrDefault("")
                val segCount = EXTINF_RE.findAll(text).count()
                val ended = text.contains("#EXT-X-ENDLIST")
                if ((segCount >= minSegments || (ended && segCount >= 1)) &&
                    dir.listFiles()?.any { it.name.endsWith(".ts") && it.length() > 0 } == true
                ) {
                    return true
                }
            }
            if (!proc.isAlive) return false
            delay(150L)
        }
        return false
    }

    private fun cleanDir(dir: File) {
        dir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        private val ID_RE = Regex("^[0-9A-Za-z_-]{1,40}$")
        private val SESSION_RE = Regex("^[0-9a-f]{32}$")
        private val FILE_RE = Regex("^(index\\.m3u8|seg_[0-9]{5}\\.ts)$")
        private val EXTINF_RE = Regex("^#EXTINF:", RegexOption.MULTILINE)
        private val LIVE_HEVC_LABEL_RE = Regex("(?i)\\b(4K|UHD|2160P|HEVC)\\b|[⁴ᴷ]")

        fun validId(id: String): Boolean = ID_RE.matches(id)

        /** Première piste audio du fichier (si elle existe) : le choix d'avant. */
        const val DEFAULT_AUDIO_MAP = "0:a:0?"
        private const val PROBE_TIMEOUT_S = 8L

        /** Lignes `ffprobe … -of csv=p=0` (« 1,eng », « 2,fre », « 3 ») → (numéro, langue). */
        fun parseAudioProbe(out: String): List<Pair<Int, String?>> = out.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(',')
            val index = parts.firstOrNull()?.trim()?.toIntOrNull() ?: return@mapNotNull null
            index to parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
        }.toList()
    }
}
