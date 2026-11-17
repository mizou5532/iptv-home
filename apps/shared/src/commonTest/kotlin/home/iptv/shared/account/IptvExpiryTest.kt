package home.iptv.shared.account

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IptvExpiryTest {
    private val montreal = -5 * 3600
    private val now = 1_795_000_000L // 18 nov. 2026, 11 h 06 UTC (6 h 06 à Montréal)
    private val day = 86_400L

    @Test fun dates_civiles() {
        assertEquals(Triple(1970, 1, 1), civilDate(0))
        assertEquals(Triple(2026, 11, 18), civilDate(now / day))
        assertEquals(Triple(2028, 2, 29), civilDate(21243))
        assertEquals("18 nov.", shortDate(now, montreal, now))
        assertEquals("19 mars 2027", shortDate(1_805_432_400L, montreal, now))
    }

    @Test fun message_seulement_dans_les_sept_derniers_jours() {
        assertNull(expiryNotice(null, now, montreal), "pas de date : illimité")
        assertNull(expiryNotice(now + 30 * day, now, montreal))
        assertEquals("L'abonnement IPTV se termine dans 5 jours (23 nov.). Pense à le renouveler.", expiryNotice(now + 5 * day, now, montreal))
        assertEquals("L'abonnement IPTV se termine demain (19 nov.). Pense à le renouveler.", expiryNotice(now + day, now, montreal))
        assertEquals("L'abonnement IPTV se termine aujourd'hui. Pense à le renouveler.", expiryNotice(now + 3600, now, montreal))
        assertEquals("L'abonnement IPTV est terminé (depuis le 16 nov.). Pense à le renouveler.", expiryNotice(now - 2 * day, now, montreal))
    }
}
