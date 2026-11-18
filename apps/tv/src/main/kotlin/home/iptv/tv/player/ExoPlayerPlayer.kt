package home.iptv.tv.player

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/**
 * Lecteur Media3. Aucun journal : les adresses de flux contiennent les identifiants.
 * `UnstableApi` : réglage des journaux et fabrique de sources (stables en pratique, revérifier à chaque mise à jour de Media3).
 */
@androidx.annotation.OptIn(UnstableApi::class)
class ExoPlayerPlayer(context: Context) : Player {
    private val exo: ExoPlayer

    /** Vrai quand la lecture a échoué (le message affiché ne contient jamais l'adresse). */
    var failed by mutableStateOf(false)
        private set

    /** Vrai une fois la première image prête (sert à enregistrer la dernière bonne copie, jamais avant). */
    var started by mutableStateOf(false)
        private set

    /** Vrai quand le flux est arrivé au bout (fin d'un épisode). */
    var ended by mutableStateOf(false)
        private set

    /** Vrai quand le lecteur attend des données (« tampon ») : après le démarrage, une image figée qui dure. */
    var buffering by mutableStateOf(false)
        private set

    /** Diagnostic (calque caché) : état du lecteur, vue rattachée, 1re image sur CETTE vue, taille, dernier code d'erreur. */
    var diagState by mutableStateOf("inactif")
        private set
    var viewAttached by mutableStateOf(false)
        private set
    var firstFrameOnView by mutableStateOf(false)
        private set
    var videoSize by mutableStateOf<String?>(null)
        private set
    /** Nom du code Media3 (ex. ERROR_CODE_IO_BAD_HTTP_STATUS) : jamais le message, qui peut contenir l'adresse. */
    var lastErrorCode by mutableStateOf<String?>(null)
        private set

    /** Cadence de la vidéo en cours (images/s), connue quand le fichier est analysé ; `null` sinon (sert à adapter l'écran). */
    var videoFrameRate by mutableStateOf<Float?>(null)
        private set

    /** Pistes audio et sous-titres : se met à jour quand le fichier est analysé (jamais avant la première image prête). */
    var trackList by mutableStateOf(PlayerTracks.EMPTY)
        private set

    init {
        Log.setLogLevel(Log.LOG_LEVEL_OFF)
        // Beaucoup de serveurs Xtream redirigent les flux entre http et https.
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        // Décodeur de secours : si le décodeur matériel refuse un flux (profil vidéo inhabituel, fréquent en IPTV),
        // Media3 essaie le suivant au lieu d'échouer.
        val renderers = androidx.media3.exoplayer.DefaultRenderersFactory(context.applicationContext).setEnableDecoderFallback(true)
        exo = ExoPlayer.Builder(context.applicationContext, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()
        exo.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: PlaybackException) { failed = true; lastErrorCode = error.errorCodeName; home.iptv.tv.health.PlaybackSignals.failed() }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == androidx.media3.common.Player.STATE_READY) { if (!started) home.iptv.tv.health.PlaybackSignals.started(); started = true }
                ended = state == androidx.media3.common.Player.STATE_ENDED
                buffering = state == androidx.media3.common.Player.STATE_BUFFERING
                diagState = when (state) {
                    androidx.media3.common.Player.STATE_BUFFERING -> "tampon"
                    androidx.media3.common.Player.STATE_READY -> "prêt"
                    androidx.media3.common.Player.STATE_ENDED -> "fin"
                    else -> "inactif"
                }
            }
            override fun onRenderedFirstFrame() { firstFrameOnView = true }
            override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
                videoSize = if (size.width > 0 && size.height > 0) "${size.width}×${size.height}" else null
            }
            override fun onTracksChanged(tracks: Tracks) {
                trackList = describe(tracks)
                videoFrameRate = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
                    ?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it).frameRate } }
                    ?.takeIf { it > 0f }
            }
        })
    }

    override fun play(url: String) = play(url, 0L)

    override fun play(url: String, startMs: Long) {
        failed = false
        started = false
        ended = false
        buffering = false
        videoFrameRate = null
        trackList = PlayerTracks.EMPTY
        exo.setMediaItem(MediaItem.fromUri(url), startMs)
        exo.prepare()
        exo.playWhenReady = true
    }

    override fun stop() { exo.stop() }
    override fun release() { exo.release() }

    override fun snapshot() = PlaybackSnapshot(
        positionMs = exo.currentPosition.coerceAtLeast(0),
        durationMs = exo.duration.takeIf { it != C.TIME_UNSET } ?: -1,
        playing = exo.playWhenReady && exo.playbackState != androidx.media3.common.Player.STATE_ENDED,
        ended = ended, failed = failed, ready = started,
    )

    override fun tracks() = trackList
    override fun seekBy(deltaMs: Long) {
        val d = exo.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        exo.seekTo((exo.currentPosition + deltaMs).coerceIn(0, d))
    }
    override fun seekTo(positionMs: Long) {
        val d = exo.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        exo.seekTo(positionMs.coerceIn(0, d))
    }
    override fun setPaused(paused: Boolean) { exo.playWhenReady = !paused }

    /** Identifiant d'une piste : « type:groupe:piste » (positions dans la liste actuelle des groupes). */
    private fun describe(tracks: Tracks): PlayerTracks {
        fun options(type: Int, prefix: String): List<TrackOption> = tracks.groups.withIndex()
            .filter { it.value.type == type }
            .flatMap { (g, group) ->
                (0 until group.length).filter { group.isTrackSupported(it) }.map { t ->
                    val f = group.getTrackFormat(t)
                    TrackOption("$prefix:$g:$t", f.language?.takeIf { it != "und" }, f.label.orEmpty(), group.isTrackSelected(t))
                }
            }
        return PlayerTracks(options(C.TRACK_TYPE_AUDIO, "a"), options(C.TRACK_TYPE_TEXT, "t"))
    }

    private fun override(id: String): TrackSelectionOverride? {
        val parts = id.split(':')
        val group = parts.getOrNull(1)?.toIntOrNull()?.let { exo.currentTracks.groups.getOrNull(it) } ?: return null
        val track = parts.getOrNull(2)?.toIntOrNull()?.takeIf { it in 0 until group.length } ?: return null
        return TrackSelectionOverride(group.mediaTrackGroup, track)
    }

    override fun selectAudio(id: String) {
        val o = override(id) ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setOverrideForType(o).build()
    }

    override fun selectSubtitle(id: String?) {
        val builder = exo.trackSelectionParameters.buildUpon()
        if (id == null) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            val o = override(id) ?: return
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(o)
        }
        exo.trackSelectionParameters = builder.build()
    }

    /** Lecteur Media3 lui-même, pour la surface vidéo Compose ([VideoSurface]). */
    internal val media3: androidx.media3.common.Player get() = exo

    /** La surface vidéo Compose est à l'écran (diagnostic). */
    internal fun surfaceShown(shown: Boolean) { viewAttached = shown; if (shown) firstFrameOnView = false }

    /** Une seule vue active à la fois : on rattache la même instance, sans l'arrêter. */
    private var currentView: PlayerView? = null

    fun attach(view: PlayerView) {
        if (view.player !== exo) {
            view.player = exo
            // Nouvelle vue : sa première image reste à venir (onRenderedFirstFrame la signale).
            firstFrameOnView = false
        }
        currentView = view
        viewAttached = true
    }

    /** Détacher une vue ne stoppe jamais le lecteur ; une vue déjà reprise par une autre n'est pas touchée. */
    fun detach(view: PlayerView) {
        if (view.player === exo) view.player = null
        // L'ancienne vue peut être retirée après que la nouvelle a été rattachée : seule la vue courante compte.
        if (currentView === view) { currentView = null; viewAttached = false }
    }
}
