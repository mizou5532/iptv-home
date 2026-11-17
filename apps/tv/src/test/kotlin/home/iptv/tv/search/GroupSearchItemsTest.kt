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
}
