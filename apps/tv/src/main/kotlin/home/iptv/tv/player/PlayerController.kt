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

    private val _conflict = MutableStateFlow<PlaybackConflict?>(null)
    /**
     * Tout est pris : qui regarde quoi, en attendant le choix « Prendre la main » ([takeOver]) ou « Laisser regarder »
     * ([leave]). Rien n'est lu pendant ce temps.
     */
    val conflict: StateFlow<PlaybackConflict?> = _conflict
    private val _waiting = MutableStateFlow(false)
    /** Vidéo ouverte mais pas encore lancée (demande de compte, choix « Prendre la main ») : les délais « pas d'image » attendent. */
    val waiting: StateFlow<Boolean> = _waiting
    private class Pending(val player: Player, val url: String, val startMs: Long, val label: String)
    @Volatile private var pending: Pending? = null

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

    /** Arrête et libère le lecteur précédent AVANT d'en créer un autre. [label] : ce qui est regardé (« Série · Lost »). */
    fun open(url: String, startMs: Long = 0, label: String = "") {
        close(keepAccount = true)
        _busy.value = null
        val p = newPlayer()
        _active.value = p
        val l = leases
        val launcher = scope
        if (l == null || launcher == null) { start(p, url, startMs); return }
        // Compte déjà réservé (changement de chaîne) : sans attendre.
        l.current(label)?.let { start(p, rewrite(url, it.creds), startMs); return }
        _waiting.value = true
        launcher.launch {
            val result = l.acquire(label)
            if (_active.value !== p) {
                // Vidéo fermée ou remplacée pendant la demande : le compte obtenu ne doit pas rester pris.
                if (_active.value == null) l.stopped()
                return@launch
            }
            when (result) {
                is LeaseResult.Use -> start(p, rewrite(url, result.creds), startMs)
                is LeaseResult.Busy ->
                    if (result.occupants.any { it.streamId != null }) {
                        pending = Pending(p, url, startMs, label)
                        _conflict.value = PlaybackConflict(result.occupants.filter { it.streamId != null })
                    } else refuse(busyMessage(result.occupants))
            }
        }
    }

    /** « Prendre la main » sur la télé qui regarde avec la réservation [streamId] : sa vidéo s'arrête, la nôtre démarre. */
    fun takeOver(streamId: String) {
        val pd = pending ?: return
        pending = null
        _conflict.value = null
        val l = leases ?: return
        val launcher = scope ?: return
        if (_active.value !== pd.player) return
        launcher.launch {
            val result = l.acquire(pd.label, takeover = streamId)
            if (_active.value !== pd.player) {
                if (_active.value == null) l.stopped()
                return@launch
            }
            when (result) {
                is LeaseResult.Use -> start(pd.player, rewrite(pd.url, result.creds), pd.startMs)
                is LeaseResult.Busy -> refuse(busyMessage(result.occupants))
            }
        }
    }

    /** « Laisser regarder » : rien n'est lu, l'autre télé continue. */
    fun leave() {
        val pd = pending
        val c = _conflict.value
        pending = null
        _conflict.value = null
        if (pd != null && _active.value === pd.player) refuse(leftMessage(c?.occupants.orEmpty()))
    }

    private fun start(p: Player, url: String, startMs: Long) {
        _waiting.value = false
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
        // Choix « Prendre la main » en attente : sans objet si la vidéo est fermée ou remplacée.
        pending = null
        _conflict.value = null
        _waiting.value = false
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

/** Tout est pris : qui regarde quoi ([Occupant.streamId] : sa réservation). */
data class PlaybackConflict(val occupants: List<home.iptv.shared.sync.Occupant>)

/** « Maha regarde Série · Lost sur Télé du salon. » */
fun occupantLine(o: home.iptv.shared.sync.Occupant): String {
    val who = o.profileName?.takeIf { it.isNotBlank() } ?: "Quelqu’un"
    val what = o.label.takeIf { it.isNotBlank() }?.let { " « $it »" } ?: ""
    val where = o.deviceName.takeIf { it.isNotBlank() }?.let { " sur $it" } ?: " sur une autre télé"
    return "$who regarde$what$where."
}

/** Après « Laisser regarder ». */
fun leftMessage(occupants: List<home.iptv.shared.sync.Occupant>): String {
    val o = occupants.singleOrNull() ?: return "D’accord : les autres continuent de regarder."
    val who = o.profileName?.takeIf { it.isNotBlank() } ?: "l’autre télé"
    return "D’accord : $who continue de regarder."
}
