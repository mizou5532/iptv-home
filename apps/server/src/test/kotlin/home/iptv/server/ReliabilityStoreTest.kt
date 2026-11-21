package home.iptv.server

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReliabilityStoreTest {
    @Test
    fun persiste_decroit_valide_et_isole_par_compte_iptv() {
        val dir = Files.createTempDirectory("rel-test").toFile()
        try {
            val file = dir.resolve("copy-reliability.json")
            var acc = "compte-1"
            var now = 1_000_000_000L
            val store = ReliabilityStore(file, { acc }, { now })

            assertTrue(store.record("ch1", "ko"))
            assertTrue(store.record("ch1", "ko"))
            assertTrue(store.record("ch1", "stall"))
            assertTrue(store.record("ch2", "ok"))
            assertFalse(store.record("ch1", "invalid"))
            assertFalse(store.record("bad id", "ok"))

            val snap = store.snapshot()
            assertEquals(2.5, snap.scores["ch1"]!!.ko, 1e-9)
            assertEquals(1.0, snap.scores["ch2"]!!.ok, 1e-9)

            // Rechargement depuis le disque avec le meme compte.
            val reloaded = ReliabilityStore(file, { acc }, { now })
            assertEquals(2.5, reloaded.snapshot().scores["ch1"]!!.ko, 1e-9)

            // Changement de fournisseur : repars a zero.
            acc = "compte-2"
            assertEquals(emptyMap(), reloaded.snapshot().scores)
        } finally {
            dir.deleteRecursively()
        }
    }
}
