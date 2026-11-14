package home.iptv.shared.text

import kotlin.test.Test
import kotlin.test.assertEquals

class SearchNormTest {
    @Test fun accents_et_tiret() = assertEquals("tele-quebec hd", searchNorm("Télé-Québec HD"))
    @Test fun espaces_reduits_et_bords_coupes() = assertEquals("rds 2", searchNorm("  RDS   2 "))
    @Test fun cedille_et_majuscules() = assertEquals("ca sport", searchNorm("ÇA Sport"))
    @Test fun ligatures() = assertEquals("coeur aether", searchNorm("Cœur Æther"))
    @Test fun vide() = assertEquals("", searchNorm(""))
    @Test fun diacritiques_non_francais() {
        assertEquals("sport ct", searchNorm("Šport Čt"))
        assertEquals("zena tv", searchNorm("Žena TV"))
    }
    @Test fun espace_insecable() = assertEquals("a b", searchNorm("A\u00A0B"))
    @Test fun espaces_unicode_aux_bords() = assertEquals("a b", searchNorm("\u00A0A\u2003\u00A0B\u3000"))
}
