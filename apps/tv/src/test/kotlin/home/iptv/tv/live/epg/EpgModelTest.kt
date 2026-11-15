package home.iptv.tv.live.epg

import home.iptv.shared.xtream.EpgEntry
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

private val T0 = Instant.parse("2026-06-01T12:00:00Z")
private fun at(sec: Long) = Instant.fromEpochSeconds(T0.epochSeconds + sec)
private fun e(title: String, s: Long, f: Long) = EpgEntry(title, at(s), at(f))

class EpgModelTest {
    @Test
    fun maintenant_a_borne_fin_exclue() {
        val list = listOf(e("A", -3600, 0), e("B", 0, 3600), e("C", 3600, 7200))
        // Pile a la fin de A : A est termine, B commence (debut inclus).
        val r = nowNext(list, at(0))
        assertEquals("B", r.current?.title)
        assertEquals("C", r.next?.title)
        assertEquals(0f, r.progress)
        // Une seconde avant la fin de B.
        val r2 = nowNext(list, at(3599))
        assertEquals("B", r2.current?.title)
        // Pile a la fin de C : plus rien d'actuel.
        val r3 = nowNext(list, at(7200))
        assertNull(r3.current); assertNull(r3.next); assertNull(r3.progress)
    }

    @Test
    fun progression_bornee_et_proportionnelle() {
        val r = nowNext(listOf(e("A", 0, 4000)), at(1000))
        assertEquals(0.25f, r.progress)
    }

    @Test
    fun episodes_perimes_ignores() {
        val list = listOf(e("Vieux1", -9000, -7200), e("Vieux2", -7200, -3600), e("Actuel", -60, 600), e("Suivant", 600, 1200))
        val r = nowNext(list, at(0))
        assertEquals("Actuel", r.current?.title)
        assertEquals("Suivant", r.next?.title)
        // Rien d'actuel : seul le futur est propose comme suivant, jamais un titre perime.
        val r2 = nowNext(listOf(e("Vieux", -9000, -7200), e("Futur", 300, 900)), at(0))
        assertNull(r2.current); assertNull(r2.progress)
        assertEquals("Futur", r2.next?.title)
    }

    @Test
    fun intervalle_negatif_ignore() {
        val list = listOf(e("Inverse", 600, -600), e("Vide", 0, 0), e("Bon", -10, 100))
        val r = nowNext(list, at(0))
        assertEquals("Bon", r.current?.title)
        assertNull(r.next)
    }

    @Test
    fun ordre_quelconque_trie_par_debut() {
        val list = listOf(e("C", 1200, 1800), e("B", 600, 1200), e("A", -10, 600))
        val r = nowNext(list, at(0))
        assertEquals("A", r.current?.title)
        assertEquals("B", r.next?.title)
    }

    @Test
    fun horaires_changement_heure() {
        val toronto = ZoneId.of("America/Toronto")
        // 2026-03-08 : 02:00 devient 03:00. 01:30 EST = 06:30Z ; 03:30 EDT = 07:30Z : une heure reelle.
        val s = Instant.parse("2026-03-08T06:30:00Z")
        val f = Instant.parse("2026-03-08T07:30:00Z")
        val entry = EpgEntry("Nuit", s, f)
        assertEquals("01:30", formatLocalTime(s, toronto))
        assertEquals("03:30", formatLocalTime(f, toronto))
        val r = nowNext(listOf(entry), Instant.parse("2026-03-08T06:45:00Z"))
        assertEquals(0.25f, r.progress)
        // Retour a l'heure normale : 2026-11-01, 01:30 EDT puis 01:30 EST sont deux instants distincts.
        assertEquals("01:30", formatLocalTime(Instant.parse("2026-11-01T05:30:00Z"), toronto))
        assertEquals("01:30", formatLocalTime(Instant.parse("2026-11-01T06:30:00Z"), toronto))
    }

    @Test
    fun le_drapeau_en_cours_du_panneau_sert_quand_les_heures_ne_couvrent_pas_maintenant() {
        // Panneau dont les heures sont décalées (fuseau mal appliqué) : aucun intervalle ne contient maintenant,
        // mais get_simple_data_table marque le programme en cours. Sans lui : « Programme indisponible » deux fois.
        val list = listOf(
            EpgEntry("Avant", at(-14400), at(-10800)),
            EpgEntry("Série", at(-10800), at(-7200), nowPlaying = true),
            EpgEntry("Cinéma", at(-7200), at(-1800)),
        )
        val r = nowNext(list, at(0))
        assertEquals("Série", r.current?.title)
        assertEquals("Cinéma", r.next?.title)
        assertNull(r.progress, "pas de barre d'avancement sur des heures incohérentes")
    }

    @Test
    fun les_heures_priment_sur_le_drapeau_quand_elles_sont_coherentes() {
        val list = listOf(EpgEntry("Ancien", at(-7200), at(-3600), nowPlaying = true), e("Actuel", -3600, 3600), e("Suivant", 3600, 7200))
        val r = nowNext(list, at(0))
        assertEquals("Actuel", r.current?.title)
        assertEquals("Suivant", r.next?.title)
    }
}
