package home.iptv.tv.live

import home.iptv.shared.text.categoryName
import home.iptv.shared.xtream.LiveStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveScreenModelTest {
    private fun stream(name: String, number: Int?) = LiveStream("1", name, "c", null, null, number)
    private val cat = LiveSection.Category("c", "Sport")

    @Test
    fun etiquette_sans_numero_ni_qualite_ni_categorie_repetee() {
        assertEquals("RDS", channelLabel(stream("RDS HD", 12)))
        assertEquals("HD", channelQuality(stream("RDS HD", 12)))
        assertEquals("RDS 2", channelLabel(stream("RDS 2", null)))
        assertNull(channelQuality(stream("RDS 2", null)))
        assertEquals("123", channelLabel(stream("AT&T: 123", 2), categoryName("US| AT&T ᴿᴬᵂ ⁶⁰ᶠᵖˢ")))
    }

    @Test
    fun separateur_du_fournisseur() {
        assertTrue(isSeparator("##### AT&T #####"))
        assertFalse(isSeparator("AT&T: 123"))
    }

    @Test
    fun sous_titre_singulier_pluriel_vide() {
        assertEquals("12 chaînes", sectionSubtitle(cat, 12))
        assertEquals("1 chaîne", sectionSubtitle(cat, 1))
        assertEquals("Vide", sectionSubtitle(cat, 0))
    }

    @Test
    fun libelle_pastille_et_message_vide_des_sections() {
        assertEquals("Favoris", sectionLabel(LiveSection.Favorites()))
        assertEquals("★", sectionBadge(LiveSection.Favorites()))
        assertEquals("Sport", sectionLabel(cat))
        assertNull(sectionBadge(cat))
        val news = LiveSection.Category("n", "US| NEWS ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ")
        assertEquals("NEWS", sectionLabel(news))
        assertEquals("US", sectionBadge(news))
        assertEquals("Aucune chaîne dans cette section.", emptySectionMessage(cat))
        assertEquals(
            "Ajoute des favoris depuis ton téléphone : page de configuration → Organiser.",
            emptySectionMessage(LiveSection.Favorites()),
        )
    }
}
