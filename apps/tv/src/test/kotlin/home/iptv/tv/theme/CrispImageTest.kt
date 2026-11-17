package home.iptv.tv.theme

import kotlin.test.Test
import kotlin.test.assertEquals

/** Tailles de réduction des logos et photos (jamais agrandis, tenir dans le cadre ou le couvrir). */
class CrispImageTest {
    @Test fun logo_tient_dans_le_cadre() = assertEquals(60 to 40, scaledSize(500, 333, 60, 60, cover = false))
    @Test fun photo_couvre_le_cadre() = assertEquals(90 to 60, scaledSize(1024, 683, 60, 60, cover = true))
    @Test fun jamais_agrandie() = assertEquals(40 to 40, scaledSize(40, 40, 120, 120, cover = false))
}
