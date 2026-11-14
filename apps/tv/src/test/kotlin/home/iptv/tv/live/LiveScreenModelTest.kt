package home.iptv.tv.live

import home.iptv.shared.xtream.LiveStream
import kotlin.test.Test
import kotlin.test.assertEquals

class LiveScreenModelTest {
    private fun stream(name: String, number: Int?) = LiveStream("1", name, "c", null, null, number)
    private val cat = LiveSection.Category("c", "Sport")

    @Test
    fun etiquette_avec_et_sans_numero() {
        assertEquals("12 · RDS HD", channelLabel(stream("RDS HD", 12)))
        assertEquals("RDS HD", channelLabel(stream("RDS HD", null)))
    }

    @Test
    fun sous_titre_singulier_pluriel_vide() {
        assertEquals("12 chaînes", sectionSubtitle(cat, 12))
        assertEquals("1 chaîne", sectionSubtitle(cat, 1))
        assertEquals("Vide", sectionSubtitle(cat, 0))
    }

    @Test
    fun libelle_et_message_vide_des_sections() {
        assertEquals("\u2605 Favoris", sectionLabel(LiveSection.Favorites()))
        assertEquals("Sport", sectionLabel(cat))
        assertEquals("Aucune cha\u00EEne dans cette section.", emptySectionMessage(cat))
        assertEquals(
            "Ajoute des favoris depuis ton t\u00E9l\u00E9phone : page de configuration \u2192 Organiser.",
            emptySectionMessage(LiveSection.Favorites()),
        )
    }
}
