package home.iptv.shared.search

import kotlin.test.Test
import kotlin.test.assertEquals

class PersonSearchTest {
    private val items = listOf(
        SearchItem("1", "FR - Training Day (2001)", setOf("c")),
        SearchItem("2", "EN - Flight (2012)", setOf("c")),
        SearchItem("3", "FR - Flight (1990)", setOf("c")),
        SearchItem("4", "4K-FR - Equalizer (2014)", setOf("c")),
        SearchItem("5", "Gladiator (2000)", setOf("c")),
    )

    @Test fun titres_du_catalogue_de_la_personne_titre_francais_ou_original_annee_a_un_an_pres() {
        val credits = listOf(
            PersonCredit("movie", "Training Day", year = 2001),
            PersonCredit("movie", "Equalizer", "The Equalizer", 2014),
            PersonCredit("movie", "Flight", year = 2012),
            PersonCredit("series", "Gladiator", year = 2000),
        )
        assertEquals(listOf("1", "4", "2"), matchPersonCredits(items, credits, "movie").map { it.id })
    }

    @Test fun reponse_du_serveur_lue_et_reponse_illisible_ignoree() {
        val r = parsePersonCredits("""{"person":{"name":"Denzel Washington","department":"Acting"},"credits":[{"kind":"movie","title":"Flight","original_title":"Flight","year":2012,"tmdb_id":87492,"role":"acteur"}]}""")
        assertEquals("Denzel Washington", r?.person?.name)
        assertEquals(2012, r?.credits?.single()?.year)
        assertEquals(null, parsePersonCredits("pas du json"))
    }
}
