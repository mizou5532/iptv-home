package home.iptv.tv.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private class FakePlayer(val name: String, val log: MutableList<String>, val failOnStop: Boolean = false) : Player {
    override fun play(url: String) { log += "play $name $url" }
    override fun play(url: String, startMs: Long) { log += "play $name $url @$startMs" }
    override fun stop() { log += "stop $name"; if (failOnStop) error("boom") }
    override fun release() { log += "release $name" }
}

class PlayerControllerTest {
    @Test
    fun ouvrir_b_arrete_et_libere_a_avant_de_creer_b() {
        val log = mutableListOf<String>()
        val names = ArrayDeque(listOf("A", "B"))
        val c = PlayerController { val n = names.removeFirst(); log += "create $n"; FakePlayer(n, log) }
        c.open("ua")
        c.open("ub")
        assertEquals(
            listOf("create A", "play A ua", "stop A", "release A", "create B", "play B ub"),
            log,
        )
    }

    @Test
    fun ouvrir_avec_position_demarre_a_cette_position_et_sans_position_depuis_le_debut() {
        val log = mutableListOf<String>()
        val c = PlayerController { FakePlayer("A", log) }
        c.open("ua", 90_000)
        c.open("ub")
        assertEquals(listOf("play A ua @90000", "stop A", "release A", "play A ub"), log)
    }

    @Test
    fun fermer_arrete_et_libere_puis_plus_rien_d_actif() {
        val log = mutableListOf<String>()
        val c = PlayerController { FakePlayer("A", log) }
        c.open("ua")
        c.close()
        assertEquals(listOf("play A ua", "stop A", "release A"), log)
        assertNull(c.active.value)
        c.close() // idempotent
        assertEquals(3, log.size)
    }

    @Test
    fun le_lecteur_est_libere_meme_si_stop_echoue() {
        val log = mutableListOf<String>()
        val c = PlayerController { FakePlayer("A", log, failOnStop = true) }
        c.open("ua")
        assertFailsWith<IllegalStateException> { c.close() }
        assertEquals(listOf("play A ua", "stop A", "release A"), log)
        assertNull(c.active.value)
    }
}
