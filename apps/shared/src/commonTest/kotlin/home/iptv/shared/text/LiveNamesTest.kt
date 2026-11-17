package home.iptv.shared.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LiveNamesTest {
    @Test
    fun categorie_pays_et_qualite_en_petits_caracteres() {
        assertEquals(CategoryName("US", "NEWS"), categoryName("US| NEWS ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ"))
        assertEquals(CategoryName("US", "AT&T"), categoryName("US| AT&T ᴿᴬᵂ ⁶⁰ᶠᵖˢ"))
        assertEquals(CategoryName("US", "ENTERTAINMENT"), categoryName("US| ENTERTAINMENT"))
        assertEquals(CategoryName("CA", "SPORTS"), categoryName("|CA| SPORTS HD"))
        assertEquals(CategoryName("FR", "SPORT"), categoryName("[FR] SPORT FHD"))
    }

    @Test
    fun categorie_sans_prefixe_reconnu_reste_telle_quelle() {
        assertEquals(CategoryName(null, "Sport"), categoryName("Sport"))
        assertEquals(CategoryName(null, "NBA: Matchs"), categoryName("NBA: Matchs"))
        assertEquals(CategoryName(null, "RDS - Info"), categoryName("RDS - Info"))
        assertEquals(CategoryName(null, "HD"), categoryName("HD"))
        assertEquals(CategoryName(null, "4K"), categoryName(" 4K "))
    }

    @Test
    fun chaine_sans_categorie_repetee_ni_qualite() {
        val att = categoryName("US| AT&T ᴿᴬᵂ ⁶⁰ᶠᵖˢ")
        assertEquals(ChannelName("123 Music", null), channelName("AT&T: 123 Music", att))
        assertEquals(ChannelName("ESPN", "FHD"), channelName("US| ESPN FHD", att))
        assertEquals(ChannelName("ESPN", "HD"), channelName("US: ESPN ᴴᴰ", att))
        assertEquals(ChannelName("AT&T", null), channelName("AT&T", att))
        assertEquals(ChannelName("AT&T SportsNet", null), channelName("AT&T SportsNet", att))
    }

    @Test
    fun qualite_la_meilleure_citee() {
        assertEquals(ChannelName("RDS", "HD"), channelName("RDS HD"))
        assertEquals(ChannelName("RDS", "FHD"), channelName("RDS [FHD]"))
        assertEquals(ChannelName("RDS", "4K"), channelName("RDS UHD 4K"))
        assertEquals(ChannelName("RDS", "HD"), channelName("RDS ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ"))
        assertEquals(ChannelName("RDS", "RAW"), channelName("RDS RAW 60fps"))
        assertEquals(ChannelName("RDS 2", null), channelName("RDS 2"))
        assertEquals(ChannelName("TSN 4K Sports", null), channelName("TSN 4K Sports"))
        assertEquals(ChannelName("HD", null), channelName("HD"))
        assertEquals(ChannelName("CHD", null), channelName("CHD"))
    }

    @Test
    fun separateurs() {
        assertEquals("AT&T", separatorTitle("##### AT&T #####"))
        assertEquals("SPORTS", separatorTitle("===== US| SPORTS ᴴᴰ ====="))
        assertEquals("NEWS", separatorTitle("### NEWS"))
        assertEquals("AT&T", separatorTitle("US| ##### AT&T #####"))
        assertEquals("", separatorTitle("##########"))
        assertNull(separatorTitle("RDS HD"))
        assertNull(separatorTitle("C-SPAN"))
        assertNull(separatorTitle("-- RDS"))
    }
}
