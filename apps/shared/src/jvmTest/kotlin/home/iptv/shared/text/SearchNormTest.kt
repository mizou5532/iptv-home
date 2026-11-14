package home.iptv.shared.text

import kotlin.test.Test
import kotlin.test.assertEquals

class SearchNormTest {
    @Test fun accents_et_tiret() = assertEquals("tele-quebec hd", searchNorm("Télé-Québec HD"))
    @Test fun espaces_reduits_et_bords_coupes() = assertEquals("rds 2", searchNorm("  RDS   2 "))
    @Test fun cedille_et_majuscules() = assertEquals("ca sport", searchNorm("ÇA Sport"))
    @Test fun ligatures() = assertEquals("coeur aether", searchNorm("Cœur Æther"))
    @Test fun vide() = assertEquals("", searchNorm(""))
}
