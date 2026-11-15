package home.iptv.tv.live.epg

import home.iptv.shared.xtream.EpgEntry
import home.iptv.shared.xtream.XtreamError
import kotlinx.coroutines.CompletableDeferred
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
private fun TestScope.repoOf(src: EpgSource) = EpgRepository(src, backgroundScope, clockOf()) { testScheduler.currentTime }

private class FakeSource(val handler: suspend (String, Int) -> List<EpgEntry>) : EpgSource {
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
        val src = FakeSource(simple)
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
        val src = FakeSource(simple)
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
        val src = FakeSource(simple)
        val repo = repoOf(src)
        for (i in 1..100) { repo.observe("s$i"); advanceTimeBy(50) }
        advanceTimeBy(400); runCurrent()
        assertEquals(listOf("s100"), src.calls)
        repo.close()
    }

    @Test
    fun source_null_est_indisponible_sans_appel() = runTest {
        val src = FakeSource(simple)
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
        val src = FakeSource(simple)
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
}
