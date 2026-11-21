package home.iptv.tv.feature

import home.iptv.shared.meta.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Test

class VersionMemoryTest {
    @Test
    fun separe_les_identifiants_films_et_series_et_garde_compatibilite_films() {
        val pushed = mutableListOf<Pair<String, String>>()
        val mem = MapVersionMemory { k, v -> pushed += k to v }
        mem.remember(MediaKind.MOVIE, "100", "200")
        mem.remember(MediaKind.SERIES, "100", "900")
        assertEquals("200", mem.resolve(MediaKind.MOVIE, "100"))
        assertEquals("200", mem.resolve("100")) // raccourci historique films
        assertEquals("900", mem.resolve(MediaKind.SERIES, "100"))
        assertEquals(listOf("movie:100" to "200", "series:100" to "900"), pushed)
    }

    @Test
    fun suit_chaine_sans_boucle_et_retombe_si_version_absente() {
        val mem = MapVersionMemory()
        mem.remember(MediaKind.SERIES, "1", "2")
        mem.remember(MediaKind.SERIES, "2", "3")
        assertEquals("3", mem.resolve(MediaKind.SERIES, "1"))
        assertEquals("2", mem.resolve(MediaKind.SERIES, "1") { it != "3" })
        // Retour vers 1 ne cree pas de boucle.
        mem.remember(MediaKind.SERIES, "3", "1")
        assertEquals("1", mem.resolve(MediaKind.SERIES, "3"))
        assertEquals("1", mem.resolve(MediaKind.SERIES, "1"))
    }

    @Test
    fun fusion_depuis_serveur_maison_et_nettoyage() {
        val mem = MapVersionMemory()
        mem.merge(mapOf(
            "movie:10" to "11",
            "series:20" to "21",
            "invalide:30" to "31",
            "series:40" to "40",
        ))
        assertEquals("11", mem.resolve(MediaKind.MOVIE, "10"))
        assertEquals("21", mem.resolve(MediaKind.SERIES, "20"))
        assertEquals("40", mem.resolve(MediaKind.SERIES, "40"))
        mem.clear()
        assertEquals("10", mem.resolve(MediaKind.MOVIE, "10"))
        assertEquals("20", mem.resolve(MediaKind.SERIES, "20"))
    }
}
