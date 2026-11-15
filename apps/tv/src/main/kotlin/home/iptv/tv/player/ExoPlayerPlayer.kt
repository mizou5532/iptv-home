package home.iptv.tv.player

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.Log
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/** Lecteur Media3. Aucun journal : les adresses de flux contiennent les identifiants. */
class ExoPlayerPlayer(context: Context) : Player {
    private val exo: ExoPlayer

    /** Vrai quand la lecture a échoué (le message affiché ne contient jamais l'adresse). */
    var failed by mutableStateOf(false)
        private set

    /** Vrai une fois la première image prête (sert à enregistrer la dernière bonne copie, jamais avant). */
    var started by mutableStateOf(false)
        private set

    init {
        Log.setLogLevel(Log.LOG_LEVEL_OFF)
        // Beaucoup de serveurs Xtream redirigent les flux entre http et https.
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        exo = ExoPlayer.Builder(context.applicationContext)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()
        exo.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: PlaybackException) { failed = true }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == androidx.media3.common.Player.STATE_READY) started = true
            }
        })
    }

    override fun play(url: String) {
        failed = false
        started = false
        exo.setMediaItem(MediaItem.fromUri(url))
        exo.prepare()
        exo.playWhenReady = true
    }

    override fun stop() { exo.stop() }
    override fun release() { exo.release() }

    /** Une seule vue active à la fois : on rattache la même instance, sans l'arrêter. */
    fun attach(view: PlayerView) { if (view.player !== exo) view.player = exo }

    /** Détacher une vue ne stoppe jamais le lecteur ; une vue déjà reprise par une autre n'est pas touchée. */
    fun detach(view: PlayerView) { if (view.player === exo) view.player = null }
}
