package home.iptv.tv.live.epg

import home.iptv.shared.xtream.EpgEntry
import home.iptv.shared.xtream.XtreamError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.assertSame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

private val BASE = Instant.parse("2026-06-01T12:00:00Z")

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.clockOf() = object : Clock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(BASE.toEpochMilliseconds() + testScheduler.currentTime)
}
private fun rel(sec: Long) = Instant.fromEpochSeconds(BASE.epochSeconds + sec)
private fun e(title: String, s: Long, f: Long) = EpgEntry(title, rel(s), rel(f))

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.repoOf(src: EpgSource, io: CoroutineDispatcher = StandardTestDispatcher(testScheduler)) =
    EpgRepository(src, backgroundScope, clockOf(), monotonicMs = { testScheduler.currentTime }, io = io)

private class FakeSource(
    val full: suspend (String) -> List<EpgEntry> = { emptyList() },
    val handler: suspend (String, Int) -> List<EpgEntry>,
) : EpgSource {
    val fullCalls = mutableListOf<String>()
    override suspend fun fullEpg(streamId: String): List<EpgEntry> { fullCalls += streamId; return full(streamId) }
    val calls = mutableListOf<String>()
    val limits = mutableListOf<Int>()
    var running = 0
    var maxRunning = 0
    override suspend fun shortEpg(streamId: String, limit: Int): List<EpgEntry> {
        calls += streamId; limits += limit
        running++; if (running > maxRunning) maxRunning = running
        try { return handler(streamId, limit) } finally { running-- }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class EpgRepositoryTest {
    private val simple: suspend (String, Int) -> List<EpgEntry> =
        { id, _ -> listOf(e("Actuel $id", -60, 3600), e("Suivant $id", 3600, 7200)) }

    @Test
    fun en_pause_aucune_demande_puis_reprise_de_la_source_observee() = runTest {
        val src = FakeSource(handler = simple)
        val repo = repoOf(src)
        val flow = repo.observe("1")
        runCurrent(); advanceTimeBy(400); runCurrent()
        assertEquals(1, src.calls.size)
        repo.pause()
        repo.observe("2")
        advanceTimeBy(10 * 60_000); runCurrent()
        assertEquals(1, src.calls.size) // ni suivi de "1" ni demande pour "2"
        repo.resume()
        advanceTimeBy(400); runCurrent()
        assertEquals(listOf("1", "2"), src.calls)
        assertEquals("Actuel 2", flow.value.current?.title)
        repo.close()
    }

    @Test
    fun attend_300_ms_avant_de_demander_et_demande_8_entrees() = runTest {
        val src = FakeSource(handler = simple)
        val repo = repoOf(src)
        val flow = repo.observe("1")
        runCurrent(); advanceTimeBy(299)
        assertEquals(0, src.calls.size)
        advanceTimeBy(2); runCurrent()
        assertEquals(listOf("1"), src.calls)
        assertEquals(listOf(8), src.limits)
        assertEquals("Actuel 1", flow.value.current?.title)
        assertEquals("Suivant 1", flow.value.next?.title)
        repo.close()
    }

    @Test
    fun changements_rapides_100_sources_une_seule_demande() = runTest {
        val src = FakeSource(handler = simple)
        val repo = repoOf(src)
        for (i in 1..100) { repo.observe("s$i"); advanceTimeBy(50) }
        advanceTimeBy(400); runCurrent()
        assertEquals(listOf("s100"), src.calls)
        repo.close()
    }

    @Test
    fun source_null_est_indisponible_sans_appel() = runTest {
        val src = FakeSource(handler = simple)
        val repo = repoOf(src)
        val flow = repo.observe(null)
        advanceTimeBy(1000); runCurrent()
        assertEquals(NowNext.EMPTY, flow.value)
        assertEquals(0, src.calls.size)
        repo.close()
    }

    @Test
    fun reponse_ancienne_n_ecrase_pas_la_source_courante() = runTest {
        val gateA = CompletableDeferred<Unit>()
        val src = FakeSource { id, n ->
            if (id == "A") withContext(NonCancellable) { gateA.await() }
            simple(id, n)
        }
        val repo = repoOf(src)
        val flow = repo.observe("A")
        advanceTimeBy(301); runCurrent()
        assertEquals(listOf("A"), src.calls)
        repo.observe("B")
        advanceTimeBy(301); runCurrent()
        assertEquals("Actuel B", flow.value.current?.title)
        gateA.complete(Unit); runCurrent()
        assertEquals("Actuel B", flow.value.current?.title)
        repo.close()
    }

    @Test
    fun cache_evite_la_deuxieme_demande_puis_expire_apres_5_min() = runTest {
        // Programme actuel tres long : seule la fraicheur de 5 min compte.
        val src = FakeSource { _, _ -> listOf(e("Long", -60, 36000)) }
        val repo = repoOf(src)
        repo.observe("1"); advanceTimeBy(301); runCurrent()
        repo.observe("2"); advanceTimeBy(301); runCurrent()
        repo.observe("1"); advanceTimeBy(301); runCurrent()
        assertEquals(listOf("1", "2"), src.calls)
        advanceTimeBy(5 * 60_000L); runCurrent()
        assertEquals(2, src.calls.count { it == "1" })
        repo.close()
    }

    @Test
    fun rafraichit_a_la_fin_du_programme_et_n_affiche_pas_de_titre_perime() = runTest {
        var n = 0
        val src = FakeSource { _, _ ->
            n++
            if (n == 1) listOf(e("Court", -10, 100), e("Apres", 100, 5000))
            else listOf(e("Apres", 100, 5000), e("Encore", 5000, 9000))
        }
        val repo = repoOf(src)
        val flow = repo.observe("1")
        advanceTimeBy(301); runCurrent()
        assertEquals("Court", flow.value.current?.title)
        advanceTimeBy(100_000); runCurrent()   // t = 100,3 s : Court est termine
        assertEquals("Apres", flow.value.current?.title)
        assertEquals(2, src.calls.size)
        repo.close()
    }

    @Test
    fun jamais_plus_de_2_appels_simultanes() = runTest {
        // Les appels ignorent l'annulation : ils restent en vol pendant que la selection change.
        val src = FakeSource { id, n -> withContext(NonCancellable) { delay(10_000) }; simple(id, n) }
        val repo = repoOf(src)
        val flow = repo.observe("s0")
        for (i in 1..4) { advanceTimeBy(301); runCurrent(); repo.observe("s$i") }
        advanceTimeBy(301); runCurrent()
        assertTrue(src.maxRunning <= 2, "max=${src.maxRunning}")
        advanceTimeBy(60_000); runCurrent()
        assertTrue(src.maxRunning <= 2, "max=${src.maxRunning}")
        assertEquals("Actuel s4", flow.value.current?.title)
        repo.close()
    }

    @Test
    fun lru_borne_a_100_sources() = runTest {
        val src = FakeSource { _, _ -> listOf(e("Long", -60, 36000)) }
        val repo = repoOf(src)
        for (i in 1..101) { repo.observe("s$i"); advanceTimeBy(301); runCurrent() }
        assertEquals(101, src.calls.size)
        repo.observe("s1"); advanceTimeBy(301); runCurrent()      // evincee : redemandee
        assertEquals(102, src.calls.size)
        repo.observe("s101"); advanceTimeBy(301); runCurrent()    // recente : en cache
        assertEquals(102, src.calls.size)
        assertTrue(repo.cacheSize() <= 100)
        repo.close()
    }

    @Test
    fun erreur_de_decodage_donne_indisponible_sans_planter() = runTest {
        val src = FakeSource { _, _ -> throw XtreamError.Unexpected(200) }
        val repo = repoOf(src)
        val flow = repo.observe("1")
        advanceTimeBy(301); runCurrent()
        assertEquals(NowNext.EMPTY, flow.value)
        assertEquals(listOf("1"), src.calls)
        repo.close()
    }

    @Test
    fun reessai_a_60_s_apres_une_erreur_puis_recuperation() = runTest {
        var n = 0
        val src = FakeSource { id, k -> if (n++ == 0) throw XtreamError.Unreachable else simple(id, k) }
        val repo = repoOf(src)
        val flow = repo.observe("1")
        advanceTimeBy(301); runCurrent()          // t = 301 ms : premier appel en echec
        assertEquals(1, src.calls.size)
        assertEquals(NowNext.EMPTY, flow.value)
        advanceTimeBy(59_000 - 1); runCurrent()   // 59 s apres l'echec
        assertEquals(1, src.calls.size)
        advanceTimeBy(1_001); runCurrent()        // 60 s apres l'echec
        assertEquals(2, src.calls.size)
        assertEquals("Actuel 1", flow.value.current?.title)
        assertEquals("Suivant 1", flow.value.next?.title)
        repo.close()
    }

    @Test
    fun reobserver_une_source_en_cache_publie_sans_etat_vide() = runTest {
        val src = FakeSource(handler = simple)
        val repo = repoOf(src)
        repo.observe("1"); advanceTimeBy(301); runCurrent()
        repo.observe("2"); advanceTimeBy(301); runCurrent()
        val flow = repo.observe("1")
        assertEquals("Actuel 1", flow.value.current?.title)   // immediat, sans avancer le temps
        repo.close()
    }

    @Test
    fun huit_entrees_dont_anciennes_retrouvent_maintenant_et_ensuite() = runTest {
        val src = FakeSource { _, _ ->
            listOf(
                e("O1", -50000, -40000), e("O2", -40000, -30000), e("O3", -30000, -20000), e("O4", -20000, -10000),
                e("O5", -10000, -5000), e("O6", -5000, -1000), e("Now", -1000, 1000), e("Next", 1000, 2000),
            )
        }
        val repo = repoOf(src)
        val flow = repo.observe("1")
        advanceTimeBy(301); runCurrent()
        assertEquals("Now", flow.value.current?.title)
        assertEquals("Next", flow.value.next?.title)
        repo.close()
    }

    @Test
    fun close_annule_les_collecteurs() = runTest {
        val src = FakeSource { id, n -> delay(10_000); simple(id, n) }
        val repo = repoOf(src)
        val flow = repo.observe("1")
        advanceTimeBy(500); runCurrent()
        repo.close()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(0, src.running)
        assertNull(flow.value.current)
        assertEquals(0, repo.cacheSize())
        val callsBefore = src.calls.size
        assertEquals(NowNext.EMPTY, repo.observe("2").value)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(callsBefore, src.calls.size)
    }

    @Test
    fun guide_court_sans_actuel_ni_suivant_passe_au_guide_complet() = runTest {
        // Panneau dont get_short_epg renvoie les premiers programmes de la journée (tous finis) : « indisponible ».
        val src = FakeSource(full = { listOf(e("Matin", -36000, -3600), e("Actuel", -3600, 1800), e("Ensuite", 1800, 5400)) }) { _, _ ->
            listOf(e("Matin", -36000, -18000), e("Midi", -18000, -3600))
        }
        val repo = repoOf(src)
        val flow = repo.observe("7")
        runCurrent(); advanceTimeBy(400); runCurrent()
        assertEquals("Actuel", flow.value.current?.title)
        assertEquals("Ensuite", flow.value.next?.title)
        assertEquals(listOf("7"), src.fullCalls)
        repo.close()
    }

    @Test
    fun les_demandes_du_guide_tournent_hors_du_fil_de_l_ecran() = runTest {
        // Le guide complet pèse des centaines de Ko : lecture et analyse ne doivent jamais tourner sur le fil principal.
        val io = StandardTestDispatcher(testScheduler)
        val seen = mutableListOf<ContinuationInterceptor?>()
        val src = FakeSource(full = { seen += currentCoroutineContext()[ContinuationInterceptor]; emptyList() }) { _, _ ->
            seen += currentCoroutineContext()[ContinuationInterceptor]; emptyList()
        }
        val repo = repoOf(src, io)
        repo.observe("1")
        runCurrent(); advanceTimeBy(400); runCurrent()
        assertEquals(2, seen.size)
        seen.forEach { assertSame(io, it) }
        repo.close()
    }

    @Test
    fun guide_complet_vide_n_est_pas_redemande_avant_une_heure() = runTest {
        // Chaîne sans aucun guide (fréquent) : le guide court est réessayé toutes les 5 min, pas le guide complet.
        val src = FakeSource { _, _ -> emptyList() }
        val repo = repoOf(src)
        repo.observe("1")
        runCurrent(); advanceTimeBy(400); runCurrent()
        assertEquals(listOf("1"), src.fullCalls)
        advanceTimeBy(30 * 60_000); runCurrent()
        assertTrue(src.calls.size >= 5, "le guide court reste réessayé : ${src.calls.size}")
        assertEquals(listOf("1"), src.fullCalls)
        advanceTimeBy(40 * 60_000); runCurrent()
        assertEquals(listOf("1", "1"), src.fullCalls)
        repo.close()
    }

    @Test
    fun guide_court_utile_ne_demande_pas_le_guide_complet() = runTest {
        val src = FakeSource(handler = simple)
        val repo = repoOf(src)
        repo.observe("1")
        runCurrent(); advanceTimeBy(400); runCurrent()
        assertTrue(src.fullCalls.isEmpty())
        repo.close()
    }

    @Test
    fun chaine_sans_guide_prend_celui_d_une_copie_du_meme_groupe() = runTest {
        // Copies regroupées : le fournisseur ne relie souvent le guide qu'à l'une d'elles.
        val src = FakeSource { id, _ -> if (id == "2") listOf(e("Match", -60, 3600), e("Après", 3600, 7200)) else emptyList() }
        val repo = EpgRepository(
            src, backgroundScope, clockOf(), guideIds = { id -> if (id == "1") listOf("1", "2") else listOf(id) },
            monotonicMs = { testScheduler.currentTime }, io = StandardTestDispatcher(testScheduler),
        )
        val flow = repo.observe("1")
        runCurrent(); advanceTimeBy(400); runCurrent()
        assertEquals("Match", flow.value.current?.title)
        assertEquals(listOf("1", "2"), src.calls)
        repo.close()
    }

    @Test
    fun programme_en_cours_aux_heures_perimees_ne_relance_pas_chaque_seconde() = runTest {
        val src = FakeSource(full = { listOf(EpgEntry("Série", rel(-10800), rel(-7200), nowPlaying = true), e("Film", -7200, -1800)) }) { _, _ -> emptyList() }
        val repo = repoOf(src)
        val flow = repo.observe("9")
        runCurrent(); advanceTimeBy(400); runCurrent()
        assertEquals("Série", flow.value.current?.title)
        advanceTimeBy(60_000); runCurrent()
        assertTrue(src.fullCalls.size <= 2, "demandes en boucle : ${src.fullCalls.size}")
        repo.close()
    }
}
