package home.iptv.tv.search

import home.iptv.shared.search.SearchIndex
import home.iptv.shared.search.SearchItem
import home.iptv.shared.versions.Lang
import kotlin.test.Test
import kotlin.test.assertEquals

/** Recherche « oppenheimer » : une seule ligne, toutes les versions dedans (noms réels du catalogue). */
class GroupSearchItemsTest {
    @Test fun une_ligne_par_titre_avec_ses_versions() {
        val items = listOf(
            SearchItem("1", "FR - Oppenheimer (2023)", setOf("fr")), SearchItem("2", "EN - Oppenheimer (2023)", setOf("en")),
            SearchItem("3", "4K-FR-HDR - Oppenheimer (2023)", setOf("fr4k")), SearchItem("4", "QC - Oppenheimer (2023)", setOf("qc")),
            SearchItem("5", "EN - Oppenheimer: The Real Story  (2023)", setOf("doc")), SearchItem("6", "EN - Barbie (2023)", setOf("en")),
        )
        val names = mapOf("fr" to "FR - FILM 2023", "en" to "EN - DRAMA", "fr4k" to "FR - FILM ⁴ᴷ ᴴᴰᴿ", "qc" to "QUÉBEC FILMS", "doc" to "EN - DOCUMENTARIES")
        val (grouped, results) = groupSearchItems(items, names)
        val found = SearchIndex(grouped).search("oppenheimer")
        assertEquals(listOf("Oppenheimer", "Oppenheimer: The Real Story"), found.map { it.title })
        val main = results[found.first().id]!!
        assertEquals(setOf("1", "2", "3", "4"), main.versions.map { it.id }.toSet())
        assertEquals(setOf(Lang.FR, Lang.EN, Lang.QC), main.versions.mapNotNull { it.tag.lang }.toSet())
        assertEquals("6", results.values.single { it.title == "Barbie" }.id, "titre seul : identifiant inchangé")
    }

    @Test fun seulement_mes_langues_separations_et_fiches_tmdb() {
        val items = listOf(
            SearchItem("1", "FR - Oppenheimer (2023)", setOf("fr")), SearchItem("2", "EN - Oppenheimer (2023)", setOf("en")),
            SearchItem("3", "AR - Oppenheimer (2023)", setOf("ar")), SearchItem("7", "AR - Wadjda (2012)", setOf("ar")),
            SearchItem("8", "FR - La casa de papel", setOf("fr")), SearchItem("9", "EN - Money Heist", setOf("en")),
        )
        val names = mapOf("fr" to "FR - FILMS", "en" to "EN - DRAMA", "ar" to "AR - MOVIES")
        val only = home.iptv.shared.versions.LanguagePrefs(order = listOf("fr", "en"), onlyMine = true)
        val (grouped, results) = groupSearchItems(items, names, only)
        assertEquals(listOf("1", "2"), results[grouped.first { it.title == "Oppenheimer" }.id]!!.versions.map { it.id })
        assertEquals(false, grouped.any { it.title == "Wadjda" }, "titre seulement en arabe : caché")
        val indexes = home.iptv.shared.versions.VersionIndexes(
            items = { items }, categoryNames = { names },
            tmdb = { mapOf("movie|FR - La casa de papel|" to 71446, "movie|EN - Money Heist|" to 71446) },
            splits = { setOf("movie:2") },
        )
        val (g2, r2) = groupSearchItems(items, names, home.iptv.shared.versions.LanguagePrefs()) { indexes.group(home.iptv.shared.meta.MediaKind.MOVIE, it) }
        assertEquals(listOf("1", "3"), r2[g2.first { it.title == "Oppenheimer" && it.id.startsWith("v:") }.id]!!.versions.map { it.id }, "version séparée à part")
        assertEquals(listOf("8", "9"), r2.values.single { it.versions.any { v -> v.id == "8" } }.versions.map { it.id }, "titres traduits réunis")
        assertEquals(listOf("1", "3"), indexes.index(home.iptv.shared.meta.MediaKind.MOVIE).versionsOf("3").map { it.id })
    }
}
