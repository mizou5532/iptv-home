package home.iptv.tv.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Lecteur vidéo (Media3 en production, faux dans les tests). */
interface Player {
    fun play(url: String)
    fun stop()
    fun release()
}

/** Garantit un seul lecteur à la fois (une seule connexion chez le fournisseur). */
class PlayerController(private val newPlayer: () -> Player) {
    private val _active = MutableStateFlow<Player?>(null)
    val active: StateFlow<Player?> = _active

    /** Arrête et libère le lecteur précédent AVANT d'en créer un autre. */
    fun open(url: String) {
        close()
        val p = newPlayer()
        _active.value = p
        p.play(url)
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
