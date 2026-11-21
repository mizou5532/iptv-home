package home.iptv.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConfigTest {
    private val complet = mapOf("SUPABASE_URL" to "https://x.supabase.co", "SUPABASE_KEY" to "sb_publishable_test", "LAN_URL" to "https://192.168.2.81:8443")

    @Test
    fun defaultsApplied() {
        val c = Config.fromEnv(complet)
        assertEquals(8443, c.port)
        assertEquals("/data", c.dataDir.path)
    }

    @Test
    fun missingVariableExplained() {
        val e = assertFailsWith<IllegalStateException> { Config.fromEnv(complet - "SUPABASE_KEY") }
        assertTrue("SUPABASE_KEY" in e.message!!)
        assertTrue("manquante" in e.message!!)
    }
}
