package home.iptv.tv.diag

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Travail de fond en pause pendant qu'on navigue, jamais bloqué indéfiniment. */
@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundTest {
    @Test fun attend_que_la_telecommande_soit_calme() = runTest {
        UserActivity.touched(0)
        var done = false
        launch { UserActivity.awaitIdle(quietMs = 2_000, maxWaitMs = 30_000, now = { currentTime }); done = true }
        advanceTimeBy(1_000); runCurrent()
        assertFalse(done, "touche il y a 1 s : on attend")
        UserActivity.touched(1_000) // nouvelle touche
        advanceTimeBy(1_500); runCurrent()
        assertFalse(done, "1,5 s après la dernière touche : on attend encore")
        advanceTimeBy(600); runCurrent()
        assertTrue(done)
    }

    @Test fun jamais_bloque_plus_que_la_limite() = runTest {
        var done = false
        launch {
            UserActivity.awaitIdle(quietMs = 2_000, maxWaitMs = 10_000, now = { UserActivity.touched(currentTime); currentTime })
            done = true
        }
        advanceTimeBy(9_000); runCurrent()
        assertFalse(done)
        advanceTimeBy(1_200); runCurrent()
        assertTrue(done, "même si on navigue sans arrêt, le travail reprend après la limite")
    }

    @Test fun calme_depuis_longtemps_aucune_attente() = runTest {
        UserActivity.touched(-60_000)
        UserActivity.awaitIdle(now = { currentTime })
        assertEquals(0, currentTime)
    }

    @Test fun priorite_basse_rend_le_resultat() {
        assertEquals(42, lowPriority { 42 })
    }
}
