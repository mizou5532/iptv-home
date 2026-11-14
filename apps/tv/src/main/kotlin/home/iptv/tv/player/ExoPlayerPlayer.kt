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

    init {
        Log.setLogLevel(Log.LOG_LEVEL_OFF)
        // Beaucoup de serveurs Xtream redirigent les flux entre http et https.
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        exo = ExoPlayer.Builder(context.applicationContext)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()
        exo.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: PlaybackException) { failed = true }
        })
    }

    override fun play(url: String) {
        failed = false
        exo.setMediaItem(MediaItem.fromUri(url))
        exo.prepare()
        exo.playWhenReady = true
    }

    override fun stop() { exo.stop() }
    override fun release() { exo.release() }

    fun attach(view: PlayerView) { view.player = exo }
}
