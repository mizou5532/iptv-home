package home.iptv.tv.player

import home.iptv.shared.account.AccountProbe
import home.iptv.shared.account.AccountVerdict
import home.iptv.shared.account.ExtraAccountsCheck
import home.iptv.shared.account.RECHECK_EVERY_S
import home.iptv.shared.sync.AccountClaim
import home.iptv.shared.sync.ExtraAccount
import home.iptv.shared.sync.Occupant
import home.iptv.shared.xtream.LiveCategory
import home.iptv.shared.xtream.LiveStream
import home.iptv.shared.xtream.StreamKind
import home.iptv.shared.xtream.UserInfo
import home.iptv.shared.xtream.XtreamCreds
import home.iptv.shared.xtream.XtreamError
import home.iptv.shared.xtream.streamUrl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val MAIN = XtreamCreds("http://serveur.test", "principal", "mdp")
private val SECOND = XtreamCreds("http://serveur.test", "second", "mdp2")

private class LogPlayer(val log: MutableList<String>) : Player {
    override fun play(url: String) { log += "play $url" }
    override fun stop() {}
    override fun release() {}
}

@OptIn(ExperimentalCoroutinesApi::class)
class AccountLeasesTest {
    private val calls = mutableListOf<String>()
    private var answer: () -> AccountClaim = { AccountClaim.Granted("s1", 2) }

    private fun TestScope.leases() = AccountLeases(
        scope = backgroundScope,
        claim = { label, takeover -> calls += "claim $label${takeover?.let { " / $it" } ?: ""}"; answer() },
        heartbeat = { id, label -> calls += "heartbeat $id $label"; true },
        release = { id -> calls += "release $id" },
        account = { p -> if (p == 2) SECOND else null },
        clockMs = { testScheduler.currentTime },
    )

    private fun TestScope.controller(l: AccountLeases, log: MutableList<String>) =
        PlayerController(l, { MAIN }, backgroundScope) { LogPlayer(log) }

    private fun live(c: XtreamCreds, id: String) = streamUrl(c, StreamKind.LIVE, id, "ts")

    @Test
    fun la_video_passe_par_le_compte_reserve_et_le_garde_en_changeant_de_chaine() = runTest {
        val log = mutableListOf<String>()
        val c = controller(leases(), log)
        c.open(live(MAIN, "1")); runCurrent()
        c.open(live(MAIN, "2")); runCurrent()
        assertEquals(listOf("play ${live(SECOND, "1")}", "play ${live(SECOND, "2")}"), log)
        assertEquals(1, calls.count { it.startsWith("claim") }, "une seule demande au serveur pour deux chaînes")
    }

    @Test
    fun compte_principal_adresse_inchangee_et_serveur_injoignable_compte_principal() = runTest {
        val log = mutableListOf<String>()
        answer = { AccountClaim.Granted("s1", 1) }
        controller(leases(), log).open(live(MAIN, "1")); runCurrent()
        answer = { throw java.io.IOException("hors ligne") }
        val log2 = mutableListOf<String>()
        controller(leases(), log2).open(live(MAIN, "1")); runCurrent()
        assertEquals(listOf("play ${live(MAIN, "1")}"), log)
        assertEquals(listOf("play ${live(MAIN, "1")}"), log2)
    }

    @Test
    fun tous_les_comptes_pris_rien_n_est_lu_message_et_pas_de_nouvelle_demande_tout_de_suite() = runTest {
        val log = mutableListOf<String>()
        answer = { AccountClaim.Busy(listOf(Occupant("Télé du salon", "Maha", ""))) }
        val c = controller(leases(), log)
        c.open(live(MAIN, "1")); runCurrent()
        assertTrue(log.isEmpty())
        assertNull(c.active.value)
        assertEquals("Le compte IPTV est déjà utilisé : Maha sur Télé du salon.", c.busy.value)
        c.open(live(MAIN, "2")); runCurrent()
        assertEquals(1, calls.count { it.startsWith("claim") })
        advanceTimeBy(9_000); runCurrent()
        answer = { AccountClaim.Granted("s2", 2) }
        c.open(live(MAIN, "3")); runCurrent()
        assertEquals(listOf("play ${live(SECOND, "3")}"), log)
        assertNull(c.busy.value)
    }

    @Test
    fun signe_de_vie_puis_compte_rendu_5_s_apres_la_fin_mais_pas_au_changement_de_chaine() = runTest {
        val c = controller(leases(), mutableListOf())
        c.open(live(MAIN, "1")); runCurrent()
        advanceTimeBy(15_001); runCurrent()
        assertTrue(calls.any { it.startsWith("heartbeat s1") })
        c.close(); advanceTimeBy(2_000); runCurrent()
        c.open(live(MAIN, "2")); runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertTrue("release s1" !in calls, "rouvert pendant le délai : compte gardé")
        c.close(); advanceTimeBy(5_001); runCurrent()
        assertTrue("release s1" in calls)
    }

    @Test
    fun app_en_arriere_plan_compte_rendu_tout_de_suite() = runTest {
        val c = controller(leases(), mutableListOf())
        c.open(live(MAIN, "1")); runCurrent()
        c.closeAndRelease(); runCurrent()
        assertTrue("release s1" in calls)
    }

    @Test
    fun video_fermee_pendant_la_demande_le_compte_obtenu_est_rendu() = runTest {
        val gate = CompletableDeferred<Unit>()
        answer = { AccountClaim.Granted("s1", 2) }
        val l = AccountLeases(backgroundScope, claim = { _, _ -> gate.await(); answer() }, heartbeat = { _, _ -> true },
            release = { id -> calls += "release $id" }, account = { SECOND }, clockMs = { testScheduler.currentTime })
        val log = mutableListOf<String>()
        val c = controller(l, log)
        c.open(live(MAIN, "1")); runCurrent()
        c.close(); runCurrent()
        gate.complete(Unit); runCurrent()
        advanceTimeBy(5_001); runCurrent()
        assertTrue(log.isEmpty())
        assertTrue("release s1" in calls)
    }

    @Test
    fun fermeture_puis_chaine_suivante_aussitot_le_compte_n_est_jamais_rendu() = runTest {
        val c = controller(leases(), mutableListOf())
        c.open(live(MAIN, "1")); runCurrent()
        // Changement de chaîne direct (fermeture puis ouverture sans que rien d'autre ne tourne entre les deux).
        c.close(); c.open(live(MAIN, "2"))
        advanceTimeBy(10_000); runCurrent()
        assertTrue("release s1" !in calls)
    }

    @Test
    fun reservation_perdue_meme_compte_rendu_la_video_continue_sinon_elle_s_arrete_avec_un_message() = runTest {
        var alive = true
        val l = AccountLeases(backgroundScope, claim = { _, _ -> calls += "claim"; answer() }, heartbeat = { _, _ -> alive },
            release = { id -> calls += "release $id" }, account = { SECOND }, clockMs = { testScheduler.currentTime })
        val refused = mutableListOf<String>()
        val c = controller(l, mutableListOf())
        c.onRefused("test") { refused += it }
        c.open(live(MAIN, "1")); runCurrent()
        alive = false; answer = { AccountClaim.Granted("s9", 2) }
        advanceTimeBy(15_001); runCurrent()
        assertTrue(c.active.value != null, "même compte rendu : la vidéo continue")
        answer = { AccountClaim.Busy(listOf(Occupant("Salon", "Maha", ""))) }
        advanceTimeBy(15_001); runCurrent()
        assertNull(c.active.value)
        assertEquals(listOf("Le compte IPTV est déjà utilisé : Maha sur Salon."), refused)
    }

    @Test
    fun compte_donne_mais_inconnu_de_la_tele_rien_n_est_lu_sur_le_principal() = runTest {
        val l = AccountLeases(backgroundScope, claim = { _, _ -> AccountClaim.Granted("s1", 3) }, heartbeat = { _, _ -> true },
            release = { id -> calls += "release $id" }, account = { null }, clockMs = { testScheduler.currentTime })
        val log = mutableListOf<String>()
        val c = controller(l, log)
        c.open(live(MAIN, "1")); runCurrent()
        assertTrue(log.isEmpty())
        assertTrue("release s1" in calls)
    }

    @Test
    fun quelqu_un_regarde_choix_prendre_la_main_attend_que_l_autre_s_arrete_puis_lit() = runTest {
        val log = mutableListOf<String>()
        var done = false
        answer = { AccountClaim.Busy(listOf(Occupant("Télé du salon", "Maha", "Série · Lost · S1 É2", "s7"))) }
        val l = AccountLeases(backgroundScope, claim = { label, takeover -> calls += "claim $label / $takeover"; answer() },
            heartbeat = { _, _ -> true }, release = {}, account = { SECOND }, takeoverDone = { done },
            clockMs = { testScheduler.currentTime })
        val c = controller(l, log)
        c.open(live(MAIN, "1"), label = "Direct · TSN"); runCurrent()
        assertTrue(log.isEmpty(), "rien n'est lu en attendant le choix")
        assertEquals("Maha regarde « Série · Lost · S1 É2 » sur Télé du salon.", occupantLine(c.conflict.value!!.occupants.single()))
        assertTrue(c.waiting.value)
        answer = { AccountClaim.Granted("s8", 1, replaced = "s7") }
        c.takeOver("s7"); runCurrent()
        assertNull(c.conflict.value)
        assertEquals("claim Direct · TSN / s7", calls.last())
        advanceTimeBy(2_500); runCurrent()
        assertTrue(log.isEmpty(), "l'autre télé n'a pas encore arrêté : on attend")
        done = true
        advanceTimeBy(1_001); runCurrent()
        assertEquals(listOf("play ${live(MAIN, "1")}"), log)
        assertTrue(!c.waiting.value)
    }

    @Test
    fun prendre_la_main_l_autre_tele_ne_repond_pas_on_lit_apres_8_s_au_plus() = runTest {
        val log = mutableListOf<String>()
        answer = { AccountClaim.Busy(listOf(Occupant("Salon", null, "", "s7"))) }
        val l = AccountLeases(backgroundScope, claim = { _, _ -> answer() }, heartbeat = { _, _ -> true }, release = {},
            account = { SECOND }, takeoverDone = { false }, clockMs = { testScheduler.currentTime })
        val c = controller(l, log)
        c.open(live(MAIN, "1")); runCurrent()
        answer = { AccountClaim.Granted("s8", 1, replaced = "s7") }
        c.takeOver("s7"); advanceTimeBy(8_100); runCurrent()
        assertEquals(1, log.size)
    }

    @Test
    fun laisser_regarder_rien_n_est_lu_message_et_ecrans_prevenus() = runTest {
        val log = mutableListOf<String>()
        answer = { AccountClaim.Busy(listOf(Occupant("Télé du salon", "Maha", "Film · Dune", "s7"))) }
        val c = controller(leases(), log)
        val refused = mutableListOf<String>()
        c.onRefused("test") { refused += it }
        c.open(live(MAIN, "1")); runCurrent()
        c.leave(); runCurrent()
        assertTrue(log.isEmpty())
        assertNull(c.active.value)
        assertNull(c.conflict.value)
        assertEquals(listOf("D’accord : Maha continue de regarder."), refused)
    }

    @Test
    fun choix_en_attente_puis_on_quitte_la_video_le_choix_disparait() = runTest {
        answer = { AccountClaim.Busy(listOf(Occupant("Salon", "Maha", "", "s7"))) }
        val c = controller(leases(), mutableListOf())
        c.open(live(MAIN, "1")); runCurrent()
        c.close()
        assertNull(c.conflict.value)
        c.takeOver("s7"); runCurrent()
        assertTrue(calls.none { it.contains("/ s7") }, "aucune prise de main après avoir quitté")
    }

    @Test
    fun une_autre_tele_a_pris_la_main_la_video_s_arrete_avec_qui_sans_redemander() = runTest {
        var alive = true
        val l = AccountLeases(backgroundScope, claim = { _, _ -> calls += "claim"; AccountClaim.Granted("s1", 1) },
            heartbeat = { _, _ -> alive }, release = {}, account = { SECOND },
            takenOver = { id -> if (id == "s1") home.iptv.shared.sync.TakenBy("Télé de la chambre", "Hamza") else null },
            clockMs = { testScheduler.currentTime }, heartbeatMs = 5_000)
        val refused = mutableListOf<String>()
        val c = controller(l, mutableListOf())
        c.onRefused("test") { refused += it }
        c.open(live(MAIN, "1")); runCurrent()
        alive = false
        advanceTimeBy(5_001); runCurrent()
        assertNull(c.active.value)
        assertEquals(listOf("Hamza a pris la main depuis Télé de la chambre : ta vidéo est arrêtée."), refused)
        assertEquals(1, calls.count { it == "claim" })
    }

    @Test
    fun le_signe_de_vie_dit_ce_qui_est_regarde_apres_un_changement_de_chaine() = runTest {
        answer = { AccountClaim.Granted("s1", 1) }
        val c = controller(leases(), mutableListOf())
        c.open(live(MAIN, "1"), label = "Direct · TSN 1"); runCurrent()
        c.open(live(MAIN, "2"), label = "Direct · RDS"); runCurrent()
        advanceTimeBy(15_001); runCurrent()
        assertTrue("heartbeat s1 Direct · RDS" in calls, calls.toString())
    }

    @Test
    fun message_occupe() {
        assertEquals("Les 2 comptes IPTV sont déjà utilisés : Maha sur Salon, Fire Stick.",
            busyMessage(listOf(Occupant("Salon", "Maha", ""), Occupant("Fire Stick", null, ""))))
        assertEquals("Le compte IPTV est déjà utilisé.", busyMessage(emptyList()))
    }
}

private class FakeProbe(
    val refused: Set<String> = emptySet(),
    val status: String = "Active",
    val cats: Map<String, List<String>> = emptyMap(),
    val streams: Map<String, List<String>> = emptyMap(),
    val movies: Map<String, Set<String>> = emptyMap(),
    val series: Map<String, Set<String>> = emptyMap(),
) : AccountProbe {
    override suspend fun movieCategoryIds(creds: XtreamCreds) = movies[creds.username] ?: setOf("m1")
    override suspend fun seriesCategoryIds(creds: XtreamCreds) = series[creds.username] ?: setOf("s1")
    val asked = mutableListOf<String>()
    override suspend fun userInfo(creds: XtreamCreds): UserInfo {
        if (creds.username in refused) throw XtreamError.Refused
        return UserInfo(true, 1, emptyList(), null, status)
    }
    override suspend fun liveCategories(creds: XtreamCreds) =
        (cats[creds.username] ?: listOf("1", "2")).map { LiveCategory(it, "Cat $it") }
    override suspend fun liveStreams(creds: XtreamCreds, categoryId: String): List<LiveStream> {
        asked += "${creds.username}:$categoryId"
        return (streams[creds.username] ?: listOf("10", "11")).map { LiveStream(it, "Ch $it", categoryId, null, null, null) }
    }
}

class ExtraAccountsCheckTest {
    private fun acc(name: String, status: String = "a_verifier") = ExtraAccount("id-$name", 2, name, "p", status)

    private fun check(probe: AccountProbe, accounts: List<ExtraAccount>, reports: MutableMap<String, AccountVerdict>, hidden: Set<String> = emptySet()) =
        ExtraAccountsCheck(probe, { MAIN }, { accounts }, { hidden }, { id, v -> reports[id] = v })

    @Test
    fun meme_catalogue_ok_et_refus_avec_la_raison() = runTest {
        val reports = mutableMapOf<String, AccountVerdict>()
        val probe = FakeProbe(refused = setOf("faux"), cats = mapOf("autre" to listOf("1", "9")), streams = mapOf("diff" to listOf("10")))
        check(probe, listOf(acc("bon"), acc("faux"), acc("autre"), acc("diff"), acc("principal"), acc("deja", "ok").copy(checkedAt = System.currentTimeMillis() / 1000)), reports).runPending()
        assertEquals(mapOf(
            "id-bon" to AccountVerdict(true, null, null),
            "id-faux" to AccountVerdict(false, "identifiants", null),
            "id-autre" to AccountVerdict(false, "catalogue", null),
            "id-diff" to AccountVerdict(false, "catalogue", null),
            "id-principal" to AccountVerdict(false, "meme_compte", null),
        ), reports)
    }

    @Test
    fun compte_avec_son_propre_lien_verifie_a_son_adresse() = runTest {
        // Le fournisseur donne un lien par abonnement (26 nov.) : le compte est interrogé à SON adresse, pas à celle du principal.
        val servers = mutableListOf<String>()
        val probe = object : AccountProbe by FakeProbe() {
            override suspend fun userInfo(creds: XtreamCreds): UserInfo { servers += "${creds.username}@${creds.server}"; return UserInfo(true, 1, emptyList(), null, "Active") }
        }
        val reports = mutableMapOf<String, AccountVerdict>()
        check(probe, listOf(acc("lien2").copy(server = "http://autre-lien.test:8080"), acc("sans")), reports).runPending()
        assertEquals(listOf("lien2@http://autre-lien.test:8080", "sans@${MAIN.server}"), servers)
        assertEquals(AccountVerdict(true, null, null), reports["id-lien2"])
    }

    @Test
    fun memes_chaines_mais_pas_les_memes_films_ou_series() = runTest {
        val reports = mutableMapOf<String, AccountVerdict>()
        val probe = FakeProbe(movies = mapOf("f" to setOf("m2"), "fs" to setOf("m2")), series = mapOf("s" to setOf("s2"), "fs" to setOf("s2")))
        check(probe, listOf(acc("f"), acc("s"), acc("fs")), reports).runPending()
        assertEquals(listOf("films", "series", "films_series"), listOf("id-f", "id-s", "id-fs").map { reports[it]?.reason })
    }

    @Test
    fun reverifie_une_fois_par_jour_les_comptes_deja_verifies() = runTest {
        val reports = mutableMapOf<String, AccountVerdict>()
        val now = 10_000_000L
        val list = listOf(
            ExtraAccount("id-frais", 2, "frais", "p", "ok", checkedAt = now - 3600),
            ExtraAccount("id-vieux", 3, "vieux", "p", "ok", checkedAt = now - RECHECK_EVERY_S),
            ExtraAccount("id-refuse", 4, "refuse", "p", "refuse", checkedAt = now - RECHECK_EVERY_S - 1),
        )
        ExtraAccountsCheck(FakeProbe(), { MAIN }, { list }, { emptySet() }, { id, v -> reports[id] = v }, nowS = { now }).runPending()
        assertEquals(setOf("id-vieux", "id-refuse"), reports.keys)
    }

    @Test
    fun abonnement_expire() = runTest {
        val reports = mutableMapOf<String, AccountVerdict>()
        check(FakeProbe(status = "Expired"), listOf(acc("vieux")), reports).runPending()
        assertEquals(AccountVerdict(false, "expire", null), reports["id-vieux"])
    }

    @Test
    fun la_categorie_comparee_n_est_jamais_une_masquee_pour_tout_le_monde() = runTest {
        val probe = FakeProbe()
        check(probe, listOf(acc("bon")), mutableMapOf(), hidden = setOf("1")).runPending()
        assertEquals(listOf("principal:2", "bon:2"), probe.asked)
    }

    @Test
    fun fournisseur_injoignable_rien_n_est_rapporte() = runTest {
        val reports = mutableMapOf<String, AccountVerdict>()
        val probe = object : AccountProbe by FakeProbe() {
            override suspend fun userInfo(creds: XtreamCreds): UserInfo = throw XtreamError.Unreachable
        }
        check(probe, listOf(acc("bon")), reports).runPending()
        assertTrue(reports.isEmpty())
    }
}
