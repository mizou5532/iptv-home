package home.iptv.tv.diag

import home.iptv.tv.diag.StartupOrder.Step
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Démarrage dans l'ordre du propriétaire (0.41) : Séries, Films, Sport, Direct, puis la recherche ; jamais bloqué. */
@OptIn(ExperimentalCoroutinesApi::class)
class StartupOrderTest {
    @Test fun chaque_etape_attend_les_precedentes() = runTest {
        val order = StartupOrder(now = { testScheduler.currentTime }, stepCapMs = 20_000)
        val log = mutableListOf<String>()
        for (step in listOf(Step.DIRECT, Step.SPORT, Step.MOVIES, Step.SEARCH)) launch { order.turn(step); log += step.name }
        runCurrent()
        assertEquals(emptyList(), log)
        order.done(Step.SERIES); runCurrent()
        assertEquals(listOf("MOVIES"), log)
        order.done(Step.MOVIES); runCurrent()
        assertEquals(listOf("MOVIES", "SPORT"), log)
        order.done(Step.SPORT); runCurrent()
        assertEquals(listOf("MOVIES", "SPORT", "DIRECT"), log)
        order.done(Step.DIRECT); runCurrent()
        assertEquals(listOf("MOVIES", "SPORT", "DIRECT", "SEARCH"), log)
    }

    @Test fun une_etape_qui_ne_vient_pas_ne_bloque_rien() = runTest {
        val order = StartupOrder(now = { testScheduler.currentTime }, stepCapMs = 20_000)
        val log = mutableListOf<String>()
        launch { order.turn(Step.DIRECT); log += "DIRECT" }
        advanceTimeBy(59_000); runCurrent()
        assertEquals(emptyList(), log)
        advanceTimeBy(1_001); runCurrent()
        assertEquals(listOf("DIRECT"), log, "au plus 3 × 20 s après le lancement")
    }

    @Test fun series_ne_attend_jamais() = runTest {
        val order = StartupOrder(now = { testScheduler.currentTime })
        var ran = false
        launch { order.turn(Step.SERIES); ran = true }
        runCurrent()
        assertEquals(true, ran)
    }
}
