package home.iptv.tv.health

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderWatchTest {
    private var outage: Outage? = Outage.PROVIDER_DOWN
    private var checks = 0
    private fun TestScope.watch() = ProviderWatch(backgroundScope, { checks++; outage }, clockMs = { testScheduler.currentTime })

    @Test fun trois_echecs_de_suite_fournisseur_muet_message_clair() = runTest {
        val w = watch()
        w.failure(); w.failure(); runCurrent()
        assertNull(w.alert.value); assertEquals(0, checks)
        w.failure(); runCurrent()
        assertEquals(OUTAGE_PROVIDER, w.alert.value)
        advanceTimeBy(15_001); runCurrent()
        assertNull(w.alert.value, "le message s'efface seul")
    }

    @Test fun une_video_qui_marche_entre_deux_remet_le_compte_a_zero() = runTest {
        val w = watch()
        w.failure(); w.failure(); w.success(); w.failure(); runCurrent()
        assertEquals(0, checks)
    }

    @Test fun echecs_espaces_de_plus_de_deux_minutes_ne_comptent_pas_ensemble() = runTest {
        val w = watch()
        w.failure(); advanceTimeBy(70_000); w.failure(); advanceTimeBy(70_000); w.failure(); runCurrent()
        assertEquals(0, checks)
    }

    @Test fun plus_d_internet_et_chaine_seule_en_panne() = runTest {
        outage = Outage.NO_INTERNET
        val w = watch()
        repeat(3) { w.failure() }; runCurrent()
        assertEquals(OUTAGE_INTERNET, w.alert.value)
        outage = null
        val w2 = watch()
        repeat(3) { w2.failure() }; runCurrent()
        assertNull(w2.alert.value, "fournisseur et internet en marche : rien (problème d'une chaîne)")
    }

    @Test fun au_plus_un_message_toutes_les_cinq_minutes() = runTest {
        val w = watch()
        repeat(3) { w.failure() }; runCurrent()
        advanceTimeBy(20_000); runCurrent()
        repeat(3) { w.failure() }; runCurrent()
        assertEquals(1, checks)
        advanceTimeBy(5 * 60_000); repeat(3) { w.failure() }; runCurrent()
        assertEquals(2, checks)
    }

    @Test fun video_qui_demarre_pendant_la_verification_aucun_message() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val w = ProviderWatch(backgroundScope, { gate.await(); Outage.PROVIDER_DOWN }, clockMs = { testScheduler.currentTime })
        repeat(3) { w.failure() }; runCurrent()
        w.success(); gate.complete(Unit); runCurrent()
        assertNull(w.alert.value)
    }

    @Test fun chaines_mortes_sans_panne_pas_de_verification_a_chaque_echec() = runTest {
        outage = null
        val w = watch()
        repeat(3) { w.failure() }; runCurrent()
        w.failure(); w.failure(); runCurrent()
        assertEquals(1, checks)
    }
}
