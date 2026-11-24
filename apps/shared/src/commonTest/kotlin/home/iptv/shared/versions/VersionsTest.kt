package home.iptv.shared.versions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Noms réels du catalogue (17 nov. 2026). */
class VersionsTest {
    private fun v(id: String, title: String, cat: String, year: String? = null) = VersionInput(id, title, "c$id", cat, null, year)

    @Test fun langue_et_qualite_lues_dans_le_nom_et_la_categorie() {
        assertEquals(VersionTag(Lang.FR), versionTag("FR - Oppenheimer (2023)", "FR - FILM 2023"))
        assertEquals(VersionTag(Lang.EN), versionTag("EN - Oppenheimer (2023)", "EN - DRAMA"))
        assertEquals(VersionTag(Lang.QC), versionTag("QC - La Casa de Papel", "QUÉBEC SERIES"))
        assertEquals(VersionTag(Lang.FR, uhd = true, hdr = true), versionTag("4K-FR-HDR - Oppenheimer (2023)", "FR - FILM ⁴ᴷ ᴴᴰᴿ ᴰᴼᴸᴮʸ ᴬᵁᴰᴵᴼ"))
        assertEquals(VersionTag(null, source = "Netflix"), versionTag("NF - Squid Game (2021) (KR)", "NETFLIX ASIA"))
        assertEquals(VersionTag(Lang.EN, source = "Top Blu-ray"), versionTag("EN-TOP - 102. Oppenheimer (2023)", "EN - IMDB TOP 250"))
        assertEquals(Lang.FR, versionTag("FR - Breaking Bad", "FRANCE SÉRIES").lang)
        assertEquals(Lang.EN, versionTag("Breaking Bad", "ENGLISH SERIES").lang, "langue de la catégorie seulement")
        assertEquals(Lang.HI, versionTag("IN-KD - Squid Game in Conversation (2025)", "IN - KOREAN HINDI DABBLING").lang)
        assertEquals("Français (France) · 4K HDR", versionTag("4K-FR-HDR - Oppenheimer (2023)", "FR - FILM ⁴ᴷ ᴴᴰᴿ").label)
        assertEquals("Netflix · langues au lancement", versionTag("NF - Money Heist (ES)", "NETFLIX  SERIES").label)
    }

    @Test fun titres_nettoyes() {
        assertEquals("Oppenheimer", versionTitle("EN-TOP - 102. Oppenheimer (2023)"))
        assertEquals("Breaking Bad", versionTitle("4K-FR - Breaking Bad (2008) (US)"))
        assertEquals("El Camino : Un film \"Breaking Bad\"", versionTitle("FR - El Camino : Un film \"Breaking Bad\" (2019)"))
        assertEquals("2023", versionYear("FR - Oppenheimer (2023)")); assertEquals("2008", versionYear("FR - Breaking Bad", "2008"))
        assertNull(versionYear("NF - Money Heist: The Phenomenon"))
    }

    @Test fun regroupement_oppenheimer_et_breaking_bad() {
        val films = listOf(
            v("1", "4K-FR - Oppenheimer (2023)", "FR - FILM ᴰᴼᴸᴮʸ ᴬᵁᴰᴵᴼ ⁴ᴷ ³⁸⁴⁰ᴾ"), v("2", "4K-FR-HDR - Oppenheimer (2023)", "FR - FILM ⁴ᴷ ᴴᴰᴿ"),
            v("3", "EN - Oppenheimer  (2023)", "EN - NEW RELEASE"), v("4", "EN - Oppenheimer (2023)", "EN - DRAMA"),
            v("5", "FR - Oppenheimer (2023)", "FR - FILM 2023"), v("6", "TOP - Oppenheimer (2023)", "TOP MOVIES BLURAY (MULTI-SUBS)"),
            v("7", "EN - Oppenheimer: The Real Story  (2023)", "EN - DOCUMENTARIES"), v("8", "EN-TOP - 102. Oppenheimer (2023)", "EN - IMDB TOP 250"),
        )
        val g = groupVersions(films)
        assertEquals(setOf("1", "2", "3", "4", "5", "6", "8"), g["oppenheimer|2023"]!!.map { it.id }.toSet())
        assertEquals(listOf("7"), g["oppenheimer the real story|2023"]!!.map { it.id }, "le documentaire reste à part")
        val series = listOf(
            v("a", "4K-FR - Breaking Bad (2008) (US)", "FRANCE SERIES ⁴ᴷ ³⁸⁴⁰ᴾ", "2008"), v("b", "EN - Breaking Bad (2008)", "ENGLISH SERIES", "2008"),
            v("c", "EN - Breaking Bad (US)", "ENGLISH SERIES", "2008"), v("d", "FR - Breaking Bad", "FRANCE SÉRIES", "2008"),
        )
        assertEquals(4, groupVersions(series)["breaking bad|2008"]!!.size)
    }

    @Test fun remakes_separes_et_annee_manquante_rattachee() {
        val g = groupVersions(listOf(
            v("1", "EN - Dune (1984)", "EN - SCI-FI"), v("2", "EN - Dune (2021)", "EN - SCI-FI"), v("3", "FR - Dune (2021)", "FR - FILM 2021"),
            v("4", "NF - Money Heist: The Phenomenon", "NETFLIX MOVIES"), v("5", "EN - Money Heist: The Phenomenon  (2020)", "EN - DOCUMENTARIES"),
        ))
        assertEquals(listOf("1"), g["dune|1984"]!!.map { it.id }); assertEquals(listOf("2", "3"), g["dune|2021"]!!.map { it.id })
        assertNull(g["dune|"], "deux années : rien sans année")
        assertEquals(listOf("4", "5"), g["money heist the phenomenon|2020"]!!.map { it.id })
    }

    @Test fun meilleure_version_selon_les_langues_du_profil() {
        val vs = groupVersions(listOf(
            v("en", "EN - Oppenheimer (2023)", "EN - DRAMA"), v("fr", "FR - Oppenheimer (2023)", "FR - FILM 2023"),
            v("fr4k", "4K-FR - Oppenheimer (2023)", "FR - FILM ⁴ᴷ ³⁸⁴⁰ᴾ"), v("nf", "NF - Oppenheimer (2023)", "NETFLIX MOVIES"),
            v("qc", "QC - Oppenheimer (2023)", "QUÉBEC FILMS"),
        )).values.single()
        assertEquals("qc", bestVersion(vs)!!.id, "Québec d'abord par défaut")
        assertEquals("fr", bestVersion(vs.filter { it.id != "qc" })!!.id, "puis France, version standard avant la 4K")
        assertEquals("fr4k", bestVersion(vs.filter { it.id != "qc" }, LanguagePrefs(prefer4k = true))!!.id)
        assertEquals("en", bestVersion(vs, LanguagePrefs(order = listOf("en", "fr")))!!.id)
        assertEquals("nf", bestVersion(vs.filter { it.id == "nf" || it.id == "en" }, LanguagePrefs(order = listOf("es")))!!.id, "plateforme avant une langue non choisie")
        assertEquals(listOf("QC", "FR", "EN", "VO", "4K"), versionBadges(vs))
    }

    @Test fun piste_audio_preferee() {
        assertEquals("fr", preferredAudio(LanguagePrefs(), listOf("en", "fr", null)))
        assertEquals("en", preferredAudio(LanguagePrefs(), listOf("en", "es")))
        assertNull(preferredAudio(LanguagePrefs(order = listOf("qc")), listOf("en")))
        assertTrue(PREFERABLE_LANGS.first() == Lang.QC)
    }
}

class TrackChoiceTest {
    @Test fun reglage_iphone_puis_dernier_choix_puis_defaut() {
        // Sans réglage ni choix : français, sous-titres anglais.
        assertEquals(TrackChoice("fr", "en"), chooseTracks(LanguagePrefs(), false, null, null, listOf("en", "fr"), listOf("en", "fr")))
        // Sans réglage : le dernier choix à la télécommande reste.
        assertEquals(TrackChoice("en", "off"), chooseTracks(LanguagePrefs(), false, "en", "off", listOf("en", "fr"), listOf("en")))
        // Réglage de l'iPhone : il l'emporte sur l'ancien choix.
        assertEquals(TrackChoice("fr", "off"), chooseTracks(LanguagePrefs(subtitles = "off"), true, "en", "en", listOf("en", "fr"), listOf("en")))
        // Langue absente du fichier : on ne force rien.
        assertEquals(TrackChoice(null, null), chooseTracks(LanguagePrefs(order = listOf("es")), true, null, null, listOf("en"), listOf("fr")))
    }

    @Test fun langue_de_la_version_choisie_l_emporte_sur_le_profil() {
        // Bogue du 23 nov. : profil en anglais, version « FR » de South Park (pistes anglais + français) : l'audio doit être français.
        assertEquals(TrackChoice("fr", "off"), chooseTracks(LanguagePrefs(order = listOf("en"), subtitles = "off"), true, "en", null, listOf("en", "fr"), emptyList(), versionAudio = "fr"))
        // Aussi sans réglage iPhone, par-dessus le dernier choix retenu.
        assertEquals("fr", chooseTracks(LanguagePrefs(), false, "en", null, listOf("en", "fr"), emptyList(), versionAudio = "fr").audio)
        // Langue de la version absente du fichier : la règle habituelle (profil, puis dernier choix).
        assertEquals("en", chooseTracks(LanguagePrefs(order = listOf("en")), true, null, null, listOf("en", "fr"), emptyList(), versionAudio = "de").audio)
    }
}
