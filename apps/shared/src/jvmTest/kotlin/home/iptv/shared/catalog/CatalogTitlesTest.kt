package home.iptv.shared.catalog

import kotlin.test.Test
import kotlin.test.assertEquals

class CatalogTitlesTest {
    @Test fun titres_prets_sans_adresse_du_fournisseur() {
        val rows = catalogTitleRows(listOf(
            CatalogTitleSource("1", " Œuvre Épique ", "http://serveur.test:8080/images/1.jpg", "c1", "2021", "7,5"),
            CatalogTitleSource("1", "Doublon", null, "c2", null, null),
            CatalogTitleSource("2", "Autre", "https://image.tmdb.org/t/p/w500/a.jpg", "", " ", "abc"),
            CatalogTitleSource("", "Sans id", null, null, null, null),
            CatalogTitleSource("3", "  ", null, null, null, null),
        ), "http://serveur.test:8080")
        assertEquals(listOf("1", "2"), rows.map { it.id })
        assertEquals(CatalogTitleRow("1", "Œuvre Épique", "oeuvre epique", "/images/1.jpg", "c1", "2021", "7.5"), rows[0])
        assertEquals(CatalogTitleRow("2", "Autre", "autre", "https://image.tmdb.org/t/p/w500/a.jpg", null, null, null), rows[1])
    }
}
