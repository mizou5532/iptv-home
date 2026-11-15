package home.iptv.tv.live

import home.iptv.shared.groups.GroupSource
import home.iptv.shared.groups.LiveGroup
import home.iptv.tv.player.Player
import home.iptv.tv.player.PlayerController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun src(id: String) = GroupSource(id, "c", "Nom original $id", 0)
private fun row(id: String, vararg sources: String) =
    GroupRow(LiveGroup("g:$id", "Chaine $id", (sources.toList().ifEmpty { listOf(id) }).map(::src)), sources.firstOrNull() ?: id)

private class Fake(val name: String, val log: MutableList<String>) : Player {
    override fun play(url: String) { log += "play $name $url" }
    override fun stop() { log += "stop $name" }
    override fun release() { log += "release $name" }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class Rig(scope: TestScope, zap: Long = 0) {
    val log = mutableListOf<String>()
    private var n = 0
    val controller = PlayerController { Fake("P${++n}", log) }
    var sections: MutableMap<String, List<GroupRow>?> = mutableMapOf()
    var fallback: String? = "fav"
    var groups: MutableMap<String, LiveGroup?> = mutableMapOf()
    var refuse: Refusal? = null
    var gate: CompletableDeferred<Unit>? = null
    val resolved = mutableListOf<String>()
    val resolvedRows = mutableListOf<GroupRow>()
    val host = LivePlaybackHost(
        controller, scope.backgroundScope,
        resolve = { r, src ->
            resolved += "${r.group.id}/${src ?: "auto"}"
            resolvedRows += r
            // Resolution qui ignore l'annulation : seule la garde de l'hote l'empeche de rouvrir le flux.
            gate?.let { g -> withContext(NonCancellable) { g.await() } }
            refuse?.let { Resolved.Refused(it) } ?: Resolved.Ready(src ?: r.anchorSourceId, "u-${src ?: r.anchorSourceId}")
        },
        rowsOf = { sections[it] },
        fallbackKey = { fallback },
        groupOf = { groups[it] },
        zapDebounceMs = zap,
    )
    val plays get() = log.count { it.startsWith("play") }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LiveNavigationTest {
    private val a = row("a", "a1", "a2")
    private val b = row("b")
    private val c = row("c")

    @Test
    fun une_copie_ajoutee_au_groupe_en_lecture_est_resolue_sur_le_groupe_courant() = runTest {
        val r = Rig(this)
        r.host.ok(a, "cat:1"); runCurrent()
        // Meme identifiant de groupe, une copie de plus (ajoutee depuis l'iPhone).
        val grown = LiveGroup(a.group.id, a.group.title, a.group.sources + src("a3"))
        r.groups["a1"] = grown
        r.host.syncPlaying(); runCurrent()
        r.host.chooseSource("a3"); runCurrent()
        assertEquals(listOf("a1", "a2", "a3"), r.resolvedRows.last().group.sources.map { it.id })
        assertEquals("play P2 u-a3", r.log.last())
    }

    @Test
    fun le_focus_seul_ne_cree_aucun_lecteur() = runTest {
        val r = Rig(this)
        r.host.focus(a.group.id); r.host.focus(b.group.id); runCurrent()
        assertEquals(emptyList(), r.log)
        assertEquals(b.group.id, r.host.state.value.selectedRowId)
    }

    @Test
    fun premier_ok_lance_une_fois_puis_plein_ecran_et_retour_sans_nouvelle_lecture() = runTest {
        val r = Rig(this)
        r.host.ok(a, "cat:1"); runCurrent()
        assertEquals(listOf("play P1 u-a1"), r.log)
        r.host.ok(a, "cat:1"); runCurrent()
        assertEquals(LiveMode.Fullscreen, r.host.state.value.mode)
        assertTrue(r.host.back())
        assertEquals(LiveMode.Browse, r.host.state.value.mode)
        runCurrent()
        assertEquals(listOf("play P1 u-a1"), r.log) // ni arret ni nouvelle ouverture
        assertEquals(1, r.resolved.size)
    }

    @Test
    fun ok_sur_un_autre_groupe_ferme_l_ancien_flux_avant_d_ouvrir_le_nouveau() = runTest {
        val r = Rig(this)
        r.host.ok(a, "cat:1"); runCurrent()
        r.host.ok(b, "cat:1")
        // Fermeture immediate, avant meme la resolution de la nouvelle copie.
        assertEquals(listOf("play P1 u-a1", "stop P1", "release P1"), r.log)
        runCurrent()
        assertEquals(listOf("play P1 u-a1", "stop P1", "release P1", "play P2 u-b"), r.log)
    }

    @Test
    fun deux_ok_rapproches_n_ouvrent_que_le_dernier() = runTest {
        val r = Rig(this)
        r.host.ok(a, "cat:1"); r.host.ok(b, "cat:1"); runCurrent()
        assertEquals(listOf("play P1 u-b"), r.log)
    }

    @Test
    fun retour_sans_panneau_ni_plein_ecran_n_est_pas_consomme() = runTest {
        val r = Rig(this)
        r.host.ok(a, "cat:1"); runCurrent()
        assertFalse(r.host.back())
        assertEquals("g:a", r.host.state.value.playingGroupId)
        assertEquals(1, r.plays)
    }

    @Test
    fun retour_ferme_le_panneau_sources_puis_le_plein_ecran() = runTest {
        val r = Rig(this)
        r.host.ok(a, "cat:1"); runCurrent(); r.host.ok(a, "cat:1")
        r.host.openSources()
        assertTrue(r.host.back())
        assertEquals(LivePanel.None, r.host.state.value.panel)
        assertEquals(LiveMode.Fullscreen, r.host.state.value.mode)
        assertTrue(r.host.back())
        assertEquals(LiveMode.Browse, r.host.state.value.mode)
        assertEquals(1, r.plays)
    }

    @Test
    fun changer_de_categorie_garde_la_lecture() = runTest {
        val r = Rig(this)
        r.host.ok(a, "cat:1"); runCurrent()
        r.host.focus("g:autre"); r.host.focus("g:encore"); runCurrent()
        assertEquals(listOf("play P1 u-a1"), r.log)
        assertEquals("g:a", r.host.state.value.playingGroupId)
        assertEquals("cat:1", r.host.state.value.originSectionKey)
    }

    @Test
    fun haut_bas_suit_la_section_d_origine_sans_boucler() = runTest {
        val r = Rig(this)
        r.sections["cat:1"] = listOf(a, b, c)
        r.host.ok(a, "cat:1"); runCurrent(); r.host.ok(a, "cat:1")
        r.host.step(-1); runCurrent()
        assertEquals(1, r.plays) // deja en haut
        r.host.step(1); runCurrent()
        assertEquals("g:b", r.host.state.value.playingGroupId)
        r.host.step(1); runCurrent()
        r.host.step(1); runCurrent()
        assertEquals("g:c", r.host.state.value.playingGroupId)
        assertEquals(3, r.plays)
        assertEquals(LiveMode.Fullscreen, r.host.state.value.mode)
        assertEquals(listOf("g:a/auto", "g:b/auto", "g:c/auto"), r.resolved)
    }

    @Test
    fun appuis_rapides_haut_bas_sont_traites_dans_l_ordre() = runTest {
        val r = Rig(this)
        r.sections["cat:1"] = listOf(a, b, c)
        r.host.ok(a, "cat:1"); runCurrent(); r.host.ok(a, "cat:1")
        r.host.step(1); r.host.step(1); runCurrent()
        assertEquals("g:c", r.host.state.value.playingGroupId)
    }

    @Test
    fun section_d_origine_disparue_bascule_sur_les_favoris_ou_la_premiere_categorie() = runTest {
        val r = Rig(this)
        r.sections["cat:1"] = null
        r.sections["fav"] = listOf(b, a, c)
        r.host.ok(a, "cat:1"); runCurrent(); r.host.ok(a, "cat:1")
        r.host.step(1); runCurrent()
        assertEquals("fav", r.host.state.value.originSectionKey)
        assertEquals("g:c", r.host.state.value.playingGroupId)
    }

    @Test
    fun choisir_une_source_ferme_l_ancienne_et_ouvre_la_copie_choisie() = runTest {
        val r = Rig(this)
        r.host.ok(a, "cat:1"); runCurrent()
        r.host.openSources(); r.host.chooseSource("a2"); runCurrent()
        assertEquals(listOf("play P1 u-a1", "stop P1", "release P1", "play P2 u-a2"), r.log)
        assertEquals("a2", r.host.state.value.playingSourceId)
        assertEquals(LivePanel.None, r.host.state.value.panel)
    }

    @Test
    fun arreter_libere_le_lecteur_et_la_liste_reste_sans_lecture() = runTest {
        val r = Rig(this)
        r.host.ok(a, "cat:1"); runCurrent(); r.host.ok(a, "cat:1")
        r.host.stop(); runCurrent()
        assertEquals(listOf("play P1 u-a1", "stop P1", "release P1"), r.log)
        assertNull(r.host.state.value.playingGroupId)
        assertEquals(LiveMode.Browse, r.host.state.value.mode)
    }

    @Test
    fun masque_gagne_arrete_la_lecture_sans_ouvrir_une_autre_copie() = runTest {
        val r = Rig(this)
        r.host.ok(a, "cat:1"); runCurrent()
        r.groups["a1"] = null // la copie en lecture est masquee, a2 reste visible
        r.host.syncPlaying()
        assertEquals(listOf("play P1 u-a1", "stop P1", "release P1"), r.log)
        assertNull(r.host.state.value.playingGroupId)
        assertEquals(NOTICE_HIDDEN, r.host.state.value.notice)
    }

    @Test
    fun un_groupe_modifie_pendant_la_lecture_ne_redemarre_pas() = runTest {
        val r = Rig(this)
        r.host.ok(a, "cat:1"); runCurrent()
        val manual = LiveGroup("manual:m", "Mon sport", listOf(src("a1"), src("a2"), src("z")))
        r.groups["a1"] = manual
        r.host.syncPlaying()
        assertEquals(listOf("play P1 u-a1"), r.log)
        assertEquals("manual:m", r.host.state.value.playingGroupId)
        // Le choix d'une autre copie part maintenant du nouveau groupe.
        r.host.chooseSource("z"); runCurrent()
        assertEquals("manual:m/z", r.resolved.last())
    }

    @Test
    fun une_copie_toujours_visible_ne_change_rien() = runTest {
        val r = Rig(this)
        r.host.ok(a, "cat:1"); runCurrent()
        r.groups["a1"] = a.group
        r.host.syncPlaying()
        assertEquals(1, r.log.size)
        assertEquals("a1", r.host.state.value.playingSourceId)
    }

    @Test
    fun une_lecture_refusee_n_ouvre_aucun_flux_et_explique() = runTest {
        val r = Rig(this)
        r.refuse = Refusal.NoCredentials
        r.host.ok(a, "cat:1"); runCurrent()
        assertEquals(emptyList(), r.log)
        assertEquals(NOTICE_NO_CREDENTIALS, r.host.state.value.notice)
        assertNull(r.host.state.value.playingGroupId)
    }

    @Test
    fun une_resolution_tardive_apres_arret_n_ouvre_rien() = runTest {
        val r = Rig(this)
        r.host.ok(a, "cat:1"); r.host.stop(); runCurrent()
        assertEquals(0, r.plays)
    }

    @Test
    fun quitter_le_direct_ferme_le_flux() = runTest {
        val r = Rig(this)
        r.host.ok(a, "cat:1"); runCurrent()
        r.host.shutdown()
        assertEquals(listOf("play P1 u-a1", "stop P1", "release P1"), r.log)
        assertNull(r.controller.active.value)
    }

    @Test
    fun lecteur_libere_par_l_activite_remet_la_navigation_a_zero_sans_fermer_deux_fois() = runTest {
        val r = Rig(this)
        r.host.ok(a, "cat:1"); runCurrent()
        r.controller.close()
        r.host.playerReleased()
        assertNull(r.host.state.value.playingGroupId)
        assertEquals(3, r.log.size)
    }

    @Test
    fun onstop_pendant_la_resolution_ne_rouvre_jamais_le_flux() = runTest {
        val r = Rig(this)
        r.gate = CompletableDeferred()
        r.host.ok(a, "cat:1"); runCurrent()
        assertEquals(1, r.resolved.size)
        r.controller.close() // ce que fait onStop
        r.host.suspend()
        r.gate!!.complete(Unit); runCurrent()
        assertEquals(0, r.plays)
        assertNull(r.host.state.value.playingGroupId)
    }

    @Test
    fun resolution_tardive_apres_remplacement_ne_rouvre_pas_l_ancienne_chaine() = runTest {
        val r = Rig(this)
        r.gate = CompletableDeferred()
        r.host.ok(a, "cat:1"); runCurrent()
        r.host.ok(b, "cat:1"); runCurrent()
        r.gate!!.complete(Unit); runCurrent()
        assertEquals(listOf("play P1 u-b"), r.log)
    }

    @Test
    fun zapping_attend_une_selection_stable_avant_d_ouvrir() = runTest {
        val r = Rig(this, zap = 250)
        r.sections["cat:1"] = listOf(a, b, c)
        r.host.ok(a, "cat:1"); runCurrent(); r.host.ok(a, "cat:1")
        r.host.step(1); runCurrent()
        r.host.step(1); runCurrent()
        // Ancien flux ferme, rien d'ouvert tant que la selection bouge.
        assertEquals(listOf("play P1 u-a1", "stop P1", "release P1"), r.log)
        assertEquals("g:c", r.host.state.value.playingGroupId)
        advanceTimeBy(251); runCurrent()
        assertEquals(listOf("play P1 u-a1", "stop P1", "release P1", "play P2 u-c"), r.log)
        assertEquals(listOf("g:a/auto", "g:c/auto"), r.resolved)
    }

    @Test
    fun apres_une_erreur_ok_rejoue_la_meme_copie() = runTest {
        val r = Rig(this)
        r.host.ok(a, "cat:1"); runCurrent()
        r.host.playFailed()
        r.host.ok(a, "cat:1"); runCurrent()
        assertEquals(listOf("play P1 u-a1", "stop P1", "release P1", "play P2 u-a1"), r.log)
        assertEquals(LiveMode.Browse, r.host.state.value.mode)
    }
}
