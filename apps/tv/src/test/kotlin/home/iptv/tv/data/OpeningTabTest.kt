package home.iptv.tv.data

import kotlin.test.Test
import kotlin.test.assertEquals

class OpeningTabTest {
    @Test fun valeurs_du_compte() {
        assertEquals(HomeTab.DIRECT, openingTab("direct"))
        assertEquals(HomeTab.SERIES, openingTab("series"))
        assertEquals(HomeTab.MOVIES, openingTab("films"))
        assertEquals(HomeTab.SPORT, openingTab("sport"))
    }

    @Test fun valeur_ancienne_ou_absente_ouvre_le_direct() {
        assertEquals(HomeTab.DIRECT, openingTab("mixte"))
        assertEquals(HomeTab.DIRECT, openingTab(null))
        assertEquals(HomeTab.DIRECT, openingTab(""))
        assertEquals(HomeTab.SPORT, openingTab(" Sport "))
    }
}
