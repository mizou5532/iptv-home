package home.iptv.tv.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Lecteur vidéo (Media3 en production, faux dans les tests). */
interface Player {
    fun play(url: String)

    /** Démarre à [startMs] (reprise d'un épisode). Par défaut, depuis le début. */
    fun play(url: String, startMs: Long) = play(url)
    fun stop()
    fun release()

    /** Position, durée, pause, fin et erreur du flux en cours (lecture des épisodes). Valeurs neutres par défaut. */
    fun snapshot(): PlaybackSnapshot = PlaybackSnapshot()

    /** Pistes audio et sous-titres disponibles ; vide tant que le flux n'est pas analysé. */
    fun tracks(): PlayerTracks = PlayerTracks.EMPTY
    fun selectAudio(id: String) {}

    /** `null` : aucun sous-titre. */
    fun selectSubtitle(id: String?) {}
    fun seekBy(deltaMs: Long) {}
    /** Aller à une position précise (barre de progression). */
    fun seekTo(positionMs: Long) {}
    fun setPaused(paused: Boolean) {}
}

/** Photo instantanée du lecteur. [playing] = lecture voulue et non terminée (une mise en mémoire tampon reste « playing »). */
data class PlaybackSnapshot(
    val positionMs: Long = 0,
    val durationMs: Long = -1,
    val playing: Boolean = false,
    val ended: Boolean = false,
    val failed: Boolean = false,
    val ready: Boolean = false,
)

/** Une piste audio ou de sous-titres. [language] : code tel que fourni par le fichier (peut être null). */
data class TrackOption(val id: String, val language: String?, val label: String, val selected: Boolean)

data class PlayerTracks(val audio: List<TrackOption>, val subtitles: List<TrackOption>) {
    companion object { val EMPTY = PlayerTracks(emptyList(), emptyList()) }
}

/** Garantit un seul lecteur à la fois (une seule connexion chez le fournisseur). */
class PlayerController(private val newPlayer: () -> Player) {
    private val _active = MutableStateFlow<Player?>(null)
    val active: StateFlow<Player?> = _active

    /** Arrête et libère le lecteur précédent AVANT d'en créer un autre. */
    fun open(url: String, startMs: Long = 0) {
        close()
        val p = newPlayer()
        _active.value = p
        if (startMs > 0) p.play(url, startMs) else p.play(url)
    }

    fun close() {
        val p = _active.value ?: return
        _active.value = null
        try {
            p.stop()
        } finally {
            p.release()
        }
    }
}
