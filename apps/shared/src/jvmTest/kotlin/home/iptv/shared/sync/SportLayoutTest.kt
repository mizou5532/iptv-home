package home.iptv.shared.sync

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Disposition de l'onglet Sport choisie sur l'iPhone (`profiles.settings.sport_view.layout`). */
class SportLayoutTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun view(text: String) = json.decodeFromString(ProfileSettings.serializer(), text).sportView

    @Test fun grille_lue_depuis_les_reglages_du_profil() {
        val v = view("""{"sport_view":{"theme":"sombre","layout":"grille"}}""")
        assertEquals("grille", v?.layout)
        assertTrue(v?.grid == true)
        assertEquals("sombre", v?.theme)
    }

    @Test fun affiche_par_defaut_ou_valeur_inconnue() {
        assertFalse(SportView().grid)
        assertFalse(view("""{"sport_view":{"layout":"affiche"}}""")?.grid == true)
        assertFalse(view("""{"sport_view":{"layout":"mosaique"}}""")?.grid == true)
        assertFalse(view("""{"sport_view":{"hints":false}}""")?.grid == true)
        assertEquals(null, view("""{}"""))
    }
}
