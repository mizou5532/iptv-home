package home.iptv.server

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VersionMemoryStoreTest {
    @Test
    fun persiste_valide_et_isole_par_compte_iptv() {
        val dir = Files.createTempDirectory("vmem-test").toFile()
        try {
            val file = dir.resolve("version-memory.json")
            var acc = "compte-a"
            val store = VersionMemoryStore(file) { acc }

            assertTrue(store.remember("movie:10", "20"))
            assertTrue(store.remember("series:10", "30"))
            assertFalse(store.remember("series:10", "10")) // meme id refuse
            assertFalse(store.remember("live:10", "30")) // prefixe inconnu refuse
            assertFalse(store.remember("movie:bad key", "20"))

            assertEquals(mapOf("movie:10" to "20", "series:10" to "30"), store.snapshot().entries)

            // Rechargement depuis le disque avec le meme compte.
            val reloaded = VersionMemoryStore(file) { acc }
            assertEquals(mapOf("movie:10" to "20", "series:10" to "30"), reloaded.snapshot().entries)

            // Changement de fournisseur IPTV : les anciens numeros sont effaces.
            acc = "compte-b"
            assertEquals(emptyMap(), reloaded.snapshot().entries)
            assertTrue(reloaded.remember("series:77", "88"))
            assertEquals(mapOf("series:77" to "88"), reloaded.snapshot().entries)
        } finally {
            dir.deleteRecursively()
        }
    }
}
