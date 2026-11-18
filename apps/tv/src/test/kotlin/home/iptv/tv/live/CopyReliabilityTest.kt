package home.iptv.tv.live

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CopyReliabilityTest {
    private var now = 1_000_000_000L
    private var saved: String? = null
    private fun make() = CopyReliability({ saved }, { saved = it }, { now })

    @Test fun la_copie_qui_coupe_passe_apres_les_autres_et_la_derniere_bonne_reste_devant() {
        val r = make()
        repeat(3) { r.failed("hd") }
        repeat(2) { r.worked("fhd") }
        assertEquals(listOf("fhd", "sd", "hd"), r.rank(listOf("hd", "fhd", "sd")))
        // Mêmes chances (inconnues) : l'ordre reçu est gardé.
        assertEquals(listOf("x", "y"), r.rank(listOf("x", "y")))
    }

    @Test fun garde_sur_la_tele_et_relu_au_redemarrage() {
        val r = make()
        repeat(3) { r.failed("hd") }
        assertTrue(make().score("hd") < 0.3)
    }

    @Test fun une_copie_reparee_reprend_sa_place_en_quelques_jours() {
        val r = make()
        repeat(4) { r.failed("hd") }
        now += 12 * 24 * 60 * 60_000L // 4 demi-vies : les échecs ne pèsent presque plus
        r.worked("hd")
        assertTrue(r.score("hd") > 0.6, "${r.score("hd")}")
    }

    @Test fun reconnexion_compte_comme_un_demi_echec_et_texte_illisible_ignore() {
        saved = "pas du json"
        val r = make()
        r.stalled("a")
        assertEquals((0 + 1.0) / (0.5 + 2), r.score("a"), 1e-9)
    }
}
