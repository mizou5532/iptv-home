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

    @Test fun detection_douce_chaine_morte_et_retablissement_auto_ou_lecture() {
        val events = mutableListOf<Pair<String, String>>()
        val r = CopyReliability({ saved }, { saved = it }, { now }, onRecord = { id, k -> events += id to k })
        // 2 echecs ne suffisent pas.
        r.failed("c1"); r.failed("c1")
        assertEquals(false, r.isLikelyDead(listOf("c1")))
        // 3e echec : la chaine est marquee comme probablement hors service.
        r.failed("c1")
        assertTrue(r.isLikelyDead(listOf("c1")))
        // Si une autre copie du groupe n'a pas echoue, la chaine n'est PAS hors service.
        assertEquals(false, r.isLikelyDead(listOf("c1", "c2")))
        // Apres ~24 h sans nouvel echec, auto-guerison (redevient testable sans blocage).
        now += 25 * 60 * 60_000L
        assertEquals(false, r.isLikelyDead(listOf("c1")))
        // Remis en panne puis reussi : disparait aussitot.
        repeat(3) { r.failed("c1") }
        assertTrue(r.isLikelyDead(listOf("c1")))
        r.worked("c1")
        assertEquals(false, r.isLikelyDead(listOf("c1")))
        assertTrue(events.contains("c1" to "ko") && events.contains("c1" to "ok"))
    }

    @Test fun fusion_depuis_serveur_maison_et_nettoyage() {
        val r = make()
        val before = r.version.value
        r.merge(mapOf("c9" to CopyScore(ok = 0.0, ko = 3.0, at = now)))
        assertTrue(r.version.value > before)
        assertTrue(r.isLikelyDead(listOf("c9")))
        // Une entree distante plus ancienne ne remplace pas un constat local plus recent.
        r.worked("c9")
        r.merge(mapOf("c9" to CopyScore(ok = 0.0, ko = 5.0, at = now - 10_000L)))
        assertEquals(false, r.isLikelyDead(listOf("c9")))
        r.clear()
        assertEquals(0.5, r.score("c9"), 1e-9)
    }
}
