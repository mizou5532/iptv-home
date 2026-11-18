package home.iptv.tv.nav

import kotlin.test.Test
import kotlin.test.assertEquals

class KeyRepeatGateTest {
    private val down = 20; private val right = 22

    @Test fun appuis_simples_toujours_acceptes_meme_tres_rapproches() {
        val g = KeyRepeatGate()
        assertEquals(listOf(true, true, true), listOf(g.allow(down, 0, 1000), g.allow(down, 0, 1010), g.allow(down, 0, 1020)))
    }

    @Test fun touche_maintenue_environ_10_deplacements_par_seconde() {
        val g = KeyRepeatGate()
        // Répétitions toutes les 50 ms pendant une seconde : un sur deux (11 au lieu de 21).
        val moves = (0..20).count { i -> g.allow(down, i, 1000L + i * 50) }
        assertEquals(11, moves)
    }

    @Test fun changer_de_fleche_passe_aussitot() {
        val g = KeyRepeatGate()
        g.allow(down, 0, 1000); g.allow(down, 1, 1050)
        assertEquals(true, g.allow(right, 3, 1060))
    }
}
