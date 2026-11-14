package home.iptv.tv.live

import kotlin.test.Test
import kotlin.test.assertEquals

class BackActionTest {
    @Test
    fun retour_ramene_aux_sections_seulement_si_elles_existent_et_n_ont_pas_le_focus() {
        assertEquals(BackAction.FocusSections, backAction(sectionsCount = 3, sectionsFocused = false))
        assertEquals(BackAction.Exit, backAction(sectionsCount = 3, sectionsFocused = true))
        assertEquals(BackAction.Exit, backAction(sectionsCount = 0, sectionsFocused = false))
        assertEquals(BackAction.Exit, backAction(sectionsCount = 0, sectionsFocused = true))
    }
}
