package home.iptv.shared.account

import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Mêmes cas que la page iPhone (fixtures/seasons.json). */
class SeasonsTest {
    private val fixture = Json.parseToJsonElement(File("../fixtures/seasons.json").takeIf { it.exists() }
        ?.readText() ?: File("../../fixtures/seasons.json").readText()).jsonObject

    @Test fun dates_communes_avec_la_page() {
        for (c in fixture["cases"]!!.jsonArray) {
            val (date, expected) = c.jsonArray.let { it[0].jsonPrimitive.content to it[1].jsonPrimitive.contentOrNull }
            assertEquals(expected, seasonAt(Day.parse(date))?.id, date)
        }
    }

    @Test fun jours_et_reglages() {
        val d = Day(2027, 2, 28)
        assertEquals(d, Day(2027, 2, 26).plus(2)); assertEquals(Day(2027, 3, 1), d.plus(1)); assertEquals(Day(2028, 3, 1), Day(2028, 2, 28).plus(2))
        val noel = Day(2026, 12, 25)
        assertEquals(Season.NOEL, activeSeason(noel, null), "activé par défaut")
        assertNull(activeSeason(noel, SeasonSettings(on = false)))
        assertNull(activeSeason(noel, SeasonSettings(off = listOf("noel"))))
        assertEquals(Season.HALLOWEEN, activeSeason(Day(2026, 11, 17), SeasonSettings(on = false, force = "halloween")), "essai forcé")
        assertEquals("Joyeux Noël !", seasonGreeting(Season.NOEL, noel))
        assertEquals("Joyeuses fêtes !", seasonGreeting(Season.NOEL, Day(2026, 12, 5)))
        assertEquals("Bonne année 2027 !", seasonGreeting(Season.NOUVEL_AN, Day(2026, 12, 31)))
        assertEquals("Bonne année 2027 !", seasonGreeting(Season.NOUVEL_AN, Day(2027, 1, 2)))
    }
}
