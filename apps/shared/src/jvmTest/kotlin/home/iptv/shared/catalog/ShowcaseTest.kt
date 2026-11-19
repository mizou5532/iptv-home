package home.iptv.shared.catalog

import home.iptv.shared.catalog.Showcase.Kind.MOVIES
import home.iptv.shared.catalog.Showcase.Kind.SERIES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Vitrine (0.41) : choix du propriétaire, avec de vrais noms de catégories du fournisseur. */
class ShowcaseTest {
    @Test fun films_en_vitrine() {
        for (n in listOf("FR - COMEDIE", "QC - FILMS VIEILLE", "EN - NEW RELEASE", "EN - KIDS", "FR - ENFANTS", "EN - MANGA/ANIME",
            "JP - JAPANESE FILM", "EN - IMDB TOP 250", "NETFLIX KIDS", "FR - LE DRENIER AJOUTEE")) assertTrue(Showcase.contains(MOVIES, n), n)
    }

    @Test fun films_hors_vitrine() {
        for (n in listOf("EN - DRAMA", "NETFLIX MOVIES", "AMAZON MOVIES", "FR - FILM ᴰᴼᴸᴮʸ ᴬᵁᴰᴵᴼ ⁴ᴷ ³⁸⁴⁰ᴾ", "EN - MOVIES ⁴ᴷ ³⁸⁴⁰ᴾ",
            "NETFLIX HEVC", "NETFLIX MOVIES DOLBY AUDIO", "MARVEL MOVIES 3840P (MULTI)", "أفلام آسيوية 2022")) assertFalse(Showcase.contains(MOVIES, n), n)
    }

    @Test fun series_en_vitrine() {
        for (n in listOf("FRANCE SÉRIES", "QUÉBEC SERIES", "ENGLISH SERIES", "NETFLIX  SERIES", "HBO MAX (ADULT SWIM)", "NETFLIX ADULT-SWIM",
            "NETFLIX ANIME", "FRANCE ANIME", "CRUNCHYROLL SERIES (MULTI-SUBS)", "ENGLISH KIDS", "NICKELODEON")) assertTrue(Showcase.contains(SERIES, n), n)
    }

    @Test fun series_hors_vitrine() {
        for (n in listOf("AMAZON SERIES", "NETFLIX REALITY", "NETFLIX SERIES ⁴ᴷ ³⁸⁴⁰ᴾ ᴰᵒˡᵇʸ ⱽᶦˢᶦᵒⁿ", "ENGLISH SERIES ᴰᴼᴸᴮʸ ᴬᵁᴰᴵᴼ",
            "FRANCE ANIMATION SERIES ⁴ᴷ ³⁸⁴⁰ᴾ", "DISNEY+ SERIES", "SKY")) assertFalse(Showcase.contains(SERIES, n), n)
    }

    @Test fun doublons_dans_l_index_des_versions() {
        val cats = mapOf("1" to "FR - COMEDIE", "2" to "FR - FILM ᴰᴼᴸᴮʸ ᴬᵁᴰᴵᴼ ⁴ᴷ ³⁸⁴⁰ᴾ", "3" to "EN - DRAMA", "4" to "NETFLIX HEVC")
        assertEquals(setOf("1"), Showcase.ids(MOVIES, cats))
        assertEquals(setOf("1", "2", "4"), Showcase.versionIds(MOVIES, cats))
    }

    @Test fun fournisseur_aux_noms_inconnus_tout_en_vitrine() {
        val cats = mapOf("a" to "Action", "b" to "Drame")
        assertEquals(setOf("a", "b"), Showcase.ids(MOVIES, cats))
        assertEquals(setOf("a", "b"), Showcase.ids(SERIES, cats))
    }
}
