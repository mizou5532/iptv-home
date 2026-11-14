package home.iptv.tv.live

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private val A = RowRef("g:a", "Chaine A")
private val B = RowRef("g:b", "Chaine B")
private val C = RowRef("g:c", "Chaine C")
private val ORDER = listOf(A, B, C)

private fun run(vararg events: LiveEvent, from: LivePlaybackState = LivePlaybackState()): LivePlaybackState =
    events.fold(from) { s, e -> reduce(s, e) }

private fun playingA() = run(LiveEvent.Ok(A, "cat:1"), LiveEvent.Started("g:a", "s1"))

class LivePlaybackStateTest {
    @Test
    fun le_focus_seul_ne_lance_rien() {
        val s = run(LiveEvent.Focus("g:a"), LiveEvent.Focus("g:b"))
        assertEquals("g:b", s.selectedRowId)
        assertNull(s.playingGroupId)
        assertNull(s.effect)
        assertEquals(LiveMode.Browse, s.mode)
    }

    @Test
    fun premier_ok_lance_l_apercu() {
        val s = run(LiveEvent.Ok(A, "cat:1"))
        assertEquals(LiveEffect.Play("g:a"), s.effect)
        assertEquals("g:a", s.playingGroupId)
        assertEquals("Chaine A", s.playingTitle)
        assertEquals("cat:1", s.originSectionKey)
        assertEquals(LiveMode.Browse, s.mode)
        assertNull(s.playingSourceId)
    }

    @Test
    fun deuxieme_ok_sur_le_meme_groupe_passe_en_plein_ecran_sans_nouvelle_lecture() {
        val s = reduce(playingA(), LiveEvent.Ok(A, "cat:1"))
        assertEquals(LiveMode.Fullscreen, s.mode)
        assertNull(s.effect)
        assertEquals("s1", s.playingSourceId)
    }

    @Test
    fun retour_du_plein_ecran_revient_a_l_apercu_sans_nouvelle_lecture() {
        val full = reduce(playingA(), LiveEvent.Ok(A, "cat:1"))
        val s = reduce(full, LiveEvent.Back)
        assertEquals(LiveMode.Browse, s.mode)
        assertNull(s.effect)
        assertEquals("g:a", s.playingGroupId)
        assertEquals("s1", s.playingSourceId)
    }

    @Test
    fun ok_sur_un_autre_groupe_remplace_la_lecture() {
        val s = reduce(playingA(), LiveEvent.Ok(B, "cat:1"))
        assertEquals(LiveEffect.Play("g:b"), s.effect)
        assertEquals("g:b", s.playingGroupId)
        assertNull(s.playingSourceId)
        assertEquals(LiveMode.Browse, s.mode)
    }

    @Test
    fun retour_en_navigation_ne_change_rien_et_garde_la_lecture() {
        val before = playingA()
        assertEquals(before, reduce(before, LiveEvent.Back))
    }

    @Test
    fun retour_du_plein_ecran_demande_le_focus_sur_la_chaine_en_lecture() {
        val full = reduce(playingA(), LiveEvent.Ok(A, "cat:1"))
        val back = reduce(full, LiveEvent.Back)
        assertEquals("g:a", back.returnFocusRowId)
        assertNull(reduce(back, LiveEvent.FocusRestored).returnFocusRowId)
        // Arreter depuis le plein ecran rend aussi le focus a la liste.
        assertEquals("g:a", reduce(full, LiveEvent.Stop).returnFocusRowId)
        // Un simple retour de l'apercu ne demande rien.
        assertNull(reduce(playingA(), LiveEvent.Back).returnFocusRowId)
    }

    @Test
    fun retour_ferme_le_panneau_avant_de_quitter_le_plein_ecran() {
        val full = run(LiveEvent.Ok(A, "cat:1"), LiveEvent.Started("g:a", "s1"), LiveEvent.Ok(A, "cat:1"), LiveEvent.OpenSources)
        assertEquals(LivePanel.Sources, full.panel)
        val s1 = reduce(full, LiveEvent.Back)
        assertEquals(LivePanel.None, s1.panel)
        assertEquals(LiveMode.Fullscreen, s1.mode)
        assertEquals(LiveMode.Browse, reduce(s1, LiveEvent.Back).mode)
    }

    @Test
    fun retour_ferme_d_abord_les_commandes_du_plein_ecran() {
        val s = run(LiveEvent.FullscreenOk, from = reduce(playingA(), LiveEvent.Ok(A, "cat:1")))
        assertEquals(true, s.controlsVisible)
        val s1 = reduce(s, LiveEvent.Back)
        assertEquals(false, s1.controlsVisible)
        assertEquals(LiveMode.Fullscreen, s1.mode)
    }

    @Test
    fun les_sources_ne_s_ouvrent_que_pour_une_lecture() {
        assertEquals(LivePanel.None, run(LiveEvent.OpenSources).panel)
        assertEquals(LivePanel.Sources, reduce(playingA(), LiveEvent.OpenSources).panel)
    }

    @Test
    fun changer_de_categorie_ne_touche_pas_a_la_lecture() {
        // Le parcours d'une autre liste n'envoie que des Focus : la lecture et l'origine restent.
        val s = run(LiveEvent.Focus("g:x"), LiveEvent.Focus("g:y"), from = playingA())
        assertEquals("g:a", s.playingGroupId)
        assertEquals("s1", s.playingSourceId)
        assertEquals("cat:1", s.originSectionKey)
        assertNull(s.effect)
    }

    @Test
    fun bas_en_plein_ecran_passe_au_groupe_suivant_et_reste_en_plein_ecran() {
        val full = reduce(playingA(), LiveEvent.Ok(A, "cat:1"))
        val s = reduce(full, LiveEvent.Step(1, ORDER))
        assertEquals(LiveEffect.Play("g:b"), s.effect)
        assertEquals("g:b", s.playingGroupId)
        assertEquals("g:b", s.selectedRowId)
        assertEquals(LiveMode.Fullscreen, s.mode)
        assertEquals("cat:1", s.originSectionKey)
    }

    @Test
    fun haut_et_bas_ne_bouclent_pas() {
        val full = reduce(playingA(), LiveEvent.Ok(A, "cat:1"))
        val top = reduce(full, LiveEvent.Step(-1, ORDER))
        assertEquals(full, top)
        val last = run(LiveEvent.Ok(C, "cat:1"), LiveEvent.Started("g:c", "s3"), LiveEvent.Ok(C, "cat:1"))
        assertEquals(last, reduce(last, LiveEvent.Step(1, ORDER)))
    }

    @Test
    fun hors_plein_ecran_haut_bas_est_ignore() {
        val before = playingA()
        assertEquals(before, reduce(before, LiveEvent.Step(1, ORDER)))
    }

    @Test
    fun groupe_absent_de_la_section_bas_prend_le_premier_et_haut_le_dernier() {
        val gone = RowRef("g:z", "Z")
        val full = run(LiveEvent.Ok(gone, "cat:1"), LiveEvent.Started("g:z", "sz"), LiveEvent.Ok(gone, "cat:1"))
        assertEquals("g:a", reduce(full, LiveEvent.Step(1, ORDER)).playingGroupId)
        assertEquals("g:c", reduce(full, LiveEvent.Step(-1, ORDER)).playingGroupId)
    }

    @Test
    fun choisir_une_autre_source_relance_ce_groupe_avec_cette_copie() {
        val s = reduce(reduce(playingA(), LiveEvent.OpenSources), LiveEvent.ChooseSource("s2"))
        assertEquals(LiveEffect.Play("g:a", "s2"), s.effect)
        assertEquals(LivePanel.None, s.panel)
        assertEquals("g:a", s.playingGroupId)
        assertNull(s.playingSourceId)
    }

    @Test
    fun choisir_la_source_active_ferme_seulement_le_panneau() {
        val s = reduce(reduce(playingA(), LiveEvent.OpenSources), LiveEvent.ChooseSource("s1"))
        assertNull(s.effect)
        assertEquals(LivePanel.None, s.panel)
        assertEquals("s1", s.playingSourceId)
    }

    @Test
    fun arreter_ferme_le_flux_et_revient_a_la_liste() {
        val full = reduce(playingA(), LiveEvent.Ok(A, "cat:1"))
        val s = reduce(reduce(full, LiveEvent.OpenSources), LiveEvent.Stop)
        assertEquals(LiveEffect.Stop, s.effect)
        assertNull(s.playingGroupId)
        assertNull(s.playingSourceId)
        assertEquals(LiveMode.Browse, s.mode)
        assertEquals(LivePanel.None, s.panel)
    }

    @Test
    fun masque_gagne_arrete_sans_changer_de_source() {
        val s = reduce(reduce(playingA(), LiveEvent.Ok(A, "cat:1")), LiveEvent.SourceHidden)
        assertEquals(LiveEffect.Stop, s.effect)
        assertNull(s.playingGroupId)
        assertEquals(LiveMode.Browse, s.mode)
        assertEquals(true, s.notice != null)
    }

    @Test
    fun un_groupe_renomme_pendant_la_lecture_garde_la_lecture() {
        val s = reduce(playingA(), LiveEvent.Regrouped(RowRef("manual:m", "Mon sport")))
        assertNull(s.effect)
        assertEquals("manual:m", s.playingGroupId)
        assertEquals("Mon sport", s.playingTitle)
        assertEquals("s1", s.playingSourceId)
    }

    @Test
    fun une_reponse_perimee_est_ignoree() {
        val s = run(LiveEvent.Ok(A, "cat:1"), LiveEvent.Ok(B, "cat:1"))
        assertEquals(s.copy(effect = null), reduce(s, LiveEvent.Started("g:a", "s1")).copy(effect = null))
        assertEquals(s.copy(effect = null), reduce(s, LiveEvent.Refused("g:a", Refusal.Unavailable)).copy(effect = null))
        assertEquals("g:b", reduce(s, LiveEvent.Started("g:b", "s9")).playingGroupId)
        assertEquals("s9", reduce(s, LiveEvent.Started("g:b", "s9")).playingSourceId)
    }

    @Test
    fun un_refus_arrete_et_explique() {
        val s = reduce(run(LiveEvent.Ok(A, "cat:1")), LiveEvent.Refused("g:a", Refusal.NoCredentials))
        assertNull(s.playingGroupId)
        assertEquals(LiveEffect.Stop, s.effect)
        assertEquals(true, s.notice?.contains("Identifiants") == true)
    }

    @Test
    fun lecteur_libere_de_l_exterieur_remet_a_zero_sans_effet() {
        val s = reduce(reduce(playingA(), LiveEvent.Ok(A, "cat:1")), LiveEvent.PlayerReleased)
        assertNull(s.playingGroupId)
        assertNull(s.effect)
        assertEquals(LiveMode.Browse, s.mode)
    }

    @Test
    fun section_d_origine_disparue_est_remplacee() {
        assertEquals("fav", reduce(playingA(), LiveEvent.OriginChanged("fav")).originSectionKey)
    }
}
