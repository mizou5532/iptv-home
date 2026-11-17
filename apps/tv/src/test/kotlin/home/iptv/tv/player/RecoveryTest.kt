package home.iptv.tv.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class SeekPlayer(var position: Long, val duration: Long) : Player {
    val seeks = mutableListOf<Long>()
    override fun play(url: String) {}
    override fun stop() {}
    override fun release() {}
    override fun snapshot() = PlaybackSnapshot(positionMs = position, durationMs = duration, playing = true, ready = true)
    override fun seekBy(deltaMs: Long) { error("plus de saut appui par appui") }
    override fun seekTo(positionMs: Long) { seeks += positionMs; position = positionMs }
}

class RecoveryTest {
    @Test
    fun une_fin_loin_de_la_duree_est_une_coupure_pas_une_vraie_fin() {
        assertTrue(isEarlyEnd(1200, 2400))
        assertTrue(isEarlyEnd(0, 2400))
        assertFalse(isEarlyEnd(2390, 2400), "vraie fin")
        assertFalse(isEarlyEnd(2400 - EARLY_END_MARGIN_S, 2400), "a la marge pile : vraie fin")
        assertFalse(isEarlyEnd(100, 0), "duree inconnue : on ne peut pas savoir")
        assertFalse(isEarlyEnd(100, -1))
    }

    @Test
    fun appuis_rapides_regroupes_en_un_seul_saut_chez_le_fournisseur() {
        val p = SeekPlayer(position = 60_000, duration = 3_600_000)
        val b = SeekBatch()
        repeat(9) { b.nudge(p, 10_000) } // touche droite maintenue
        assertEquals(150_000L, b.targetMs)
        assertEquals(90_000L, b.deltaMs)
        assertTrue(p.seeks.isEmpty(), "rien n'est demande pendant la rafale")
        b.commit(p)
        assertEquals(listOf(150_000L), p.seeks, "un seul saut")
        assertNull(b.targetMs)
        assertEquals(0L, b.deltaMs)
    }

    @Test
    fun la_cible_reste_dans_le_film_et_un_aller_retour_s_annule() {
        val p = SeekPlayer(position = 15_000, duration = 100_000)
        val b = SeekBatch()
        b.nudge(p, -10_000); b.nudge(p, -10_000)
        assertEquals(0L, b.targetMs, "pas avant le debut")
        assertEquals(-15_000L, b.deltaMs, "ecart reel")
        b.clear()
        p.position = 95_000
        b.nudge(p, 10_000)
        assertEquals(100_000L, b.targetMs, "pas apres la fin")
        b.nudge(p, -10_000)
        assertEquals(90_000L, b.targetMs)
        b.commit(p); b.commit(p)
        assertEquals(listOf(90_000L), p.seeks, "un commit sans attente ne fait rien")
    }

    @Test
    fun sans_lecteur_rien_ne_se_passe() {
        val b = SeekBatch()
        b.nudge(null, 10_000)
        assertNull(b.targetMs)
        b.commit(null)
    }

    @Test
    fun horloge_lisible() {
        assertEquals("0:00", clockText(0))
        assertEquals("1:15", clockText(75))
        assertEquals("1:02:05", clockText(3725))
        assertEquals("0:00", clockText(-5))
    }
}
