package home.iptv.tv.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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

/**
 * Garantit un seul lecteur à la fois (une seule connexion chez le fournisseur). Avec [leases] (plusieurs comptes IPTV
 * dans le foyer), chaque vidéo passe par le compte réservé pour cette télé : [mainCreds] est le compte des adresses
 * reçues, remplacé par le compte réservé ; si tous les comptes sont pris, rien n'est lu et [busy] donne le message.
 */
class PlayerController(
    private val leases: AccountLeases? = null,
    private val mainCreds: () -> home.iptv.shared.xtream.XtreamCreds? = { null },
    private val scope: kotlinx.coroutines.CoroutineScope? = null,
    private val newPlayer: () -> Player,
) {
    private val _active = MutableStateFlow<Player?>(null)
    val active: StateFlow<Player?> = _active
    private val _busy = MutableStateFlow<String?>(null)
    /** « Les 2 comptes IPTV sont déjà utilisés : … » après une vidéo refusée ; effacé à la vidéo suivante. */
    val busy: StateFlow<String?> = _busy
    private val _refusals = MutableStateFlow(0L)
    /** Augmente à chaque vidéo refusée (tous les comptes pris, ou compte perdu) : les écrans de film et d'épisode le suivent. */
    val refusals: StateFlow<Long> = _refusals

    /** Écrans qui doivent savoir qu'une vidéo a été refusée (films, épisodes), un par sorte : le dernier créé remplace l'ancien. */
    private val refusalListeners = java.util.concurrent.ConcurrentHashMap<String, (String) -> Unit>()
    fun onRefused(kind: String, listener: (String) -> Unit) { refusalListeners[kind] = listener }

    /** Vidéo refusée : lecteur fermé, message affiché, écrans prévenus. */
    private fun refuse(message: String) {
        _busy.value = message
        close()
        _refusals.value++
        refusalListeners.values.forEach { it(message) }
    }

    /** Arrête et libère le lecteur précédent AVANT d'en créer un autre. */
    fun open(url: String, startMs: Long = 0) {
        close(keepAccount = true)
        _busy.value = null
        val p = newPlayer()
        _active.value = p
        val l = leases
        val launcher = scope
        if (l == null || launcher == null) { start(p, url, startMs); return }
        // Compte déjà réservé (changement de chaîne) : sans attendre.
        l.current()?.let { start(p, rewrite(url, it.creds), startMs); return }
        launcher.launch {
            val result = l.acquire()
            if (_active.value !== p) {
                // Vidéo fermée ou remplacée pendant la demande : le compte obtenu ne doit pas rester pris.
                if (_active.value == null) l.stopped()
                return@launch
            }
            when (result) {
                is LeaseResult.Use -> start(p, rewrite(url, result.creds), startMs)
                is LeaseResult.Busy -> refuse(busyMessage(result.occupants))
            }
        }
    }

    private fun start(p: Player, url: String, startMs: Long) {
        if (startMs > 0) p.play(url, startMs) else p.play(url)
    }

    /** Adresse avec le compte réservé ; adresse d'origine pour le compte principal ou une adresse inconnue. */
    private fun rewrite(url: String, creds: home.iptv.shared.xtream.XtreamCreds?): String {
        val main = mainCreds() ?: return url
        if (creds == null) return url
        return home.iptv.shared.xtream.withAccount(url, main, creds) ?: url
    }

    init {
        // Réservation perdue pendant une vidéo (longue coupure, compte repris par une autre télé) : la vidéo s'arrête
        // plutôt que de partager le compte (le fournisseur couperait l'une des deux).
        leases?.onLost = { message -> if (_active.value != null) refuse(message) }
    }

    fun close() = close(keepAccount = false)

    private fun close(keepAccount: Boolean) {
        val p = _active.value ?: return
        _active.value = null
        try {
            p.stop()
        } finally {
            p.release()
            if (!keepAccount) leases?.stopped()
        }
    }

    /** L'app passe en arrière-plan : lecteur fermé et compte rendu tout de suite. */
    fun closeAndRelease() {
        close()
        leases?.releaseNow()
    }
}
