package home.iptv.shared.versions

import home.iptv.shared.progress.ProgressRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/** Étape B : rangées sans doublons, « Seulement mes langues », séparation manuelle, fiches TMDB, reprise entre versions. */
class VersionsStepBTest {
    private fun v(id: String, title: String, cat: String) = VersionInput(id, title, "c$id", cat, null)
    private val films = listOf(
        v("1", "FR - Oppenheimer (2023)", "FR - FILM 2023"), v("2", "EN - Oppenheimer (2023)", "EN - DRAMA"),
        v("3", "AR - Oppenheimer (2023)", "AR - MOVIES"), v("4", "QC - Oppenheimer (2023)", "QUÉBEC FILMS"),
        v("5", "FR - La casa de papel", "FRANCE SÉRIES"), v("6", "EN - Money Heist", "ENGLISH SERIES"),
    )
    private fun at(s: Long) = Instant.fromEpochSeconds(s)

    @Test fun separation_manuelle() {
        val g = groupVersions(films, splits = setOf("3"))
        assertEquals(listOf("1", "2", "4"), g.getValue("oppenheimer|2023").map { it.id })
        assertEquals(listOf("3"), g.getValue("#3|").map { it.id })
    }

    @Test fun titres_traduits_reunis_par_la_fiche_tmdb() {
        val tmdb = mapOf("5" to 71446, "6" to 71446, "1" to 872585)
        val g = mergeByTmdb(groupVersions(films)) { tmdb[it.id] }
        assertEquals(listOf("5", "6"), g.values.first { vs -> vs.any { it.id == "5" } }.map { it.id })
        assertEquals(2, g.size)
    }

    @Test fun seulement_mes_langues() {
        val all = groupVersions(films).getValue("oppenheimer|2023")
        val prefs = LanguagePrefs(order = listOf("qc", "en"), onlyMine = true)
        assertEquals(listOf("2", "4"), myVersions(all, prefs).map { it.id }, "France et arabe cachés : Québec et France sont séparés")
        assertEquals(all, myVersions(all, prefs.copy(onlyMine = false)))
        val netflix = groupVersions(listOf(v("9", "NF - Squid Game (2021)", "NETFLIX"))).values.first()
        assertEquals(netflix, myVersions(netflix, prefs), "plateforme sans langue connue : gardée")
    }

    @Test fun une_carte_par_titre_dans_une_rangee() {
        val index = VersionIndex(groupVersions(films))
        val row = listOf("2", "5", "1", "6", "3", "4")
        assertEquals(listOf("4", "5", "6"), collapseVersions(row, index, LanguagePrefs()) { it }, "meilleure version (Québec) à la place du premier")
        assertEquals(listOf("2", "5", "6"), collapseVersions(row, index, LanguagePrefs(order = listOf("en"))) { it })
        assertEquals(listOf("2", "1"), hideOtherLanguages(listOf("2", "3", "1"), index, LanguagePrefs(order = listOf("fr", "en"), onlyMine = true)) { it })
        assertEquals(listOf("x", "2"), collapseVersions(listOf("x", "2"), index, LanguagePrefs()) { it }, "inconnu de l'index : gardé")
        assertEquals(true, index.same("1", "4")); assertEquals(false, index.same("1", "5"))
    }

    @Test fun reprise_d_un_film_dans_une_autre_version() {
        val rows = listOf(
            ProgressRow("2", "movie", positionS = 1200, durationS = 10800, clientUpdatedAt = at(100)),
            ProgressRow("4", "movie", positionS = 600, durationS = 10800, clientUpdatedAt = at(50)),
        )
        assertEquals(1200, carriedMovieRow(rows, "1", setOf("1", "2", "4"))?.positionS)
        assertNull(carriedMovieRow(rows, "4", setOf("1", "2", "4")), "sa propre position d'abord")
        assertNull(carriedMovieRow(rows, "9", emptySet()))
    }

    @Test fun reprise_d_une_serie_dans_une_autre_version() {
        fun ep(id: String, series: String, s: Int, e: Int, pos: Int, t: Long, watched: Boolean = false) =
            ProgressRow(id, "episode", series, s, e, pos, 3000, watched, at(t))
        val own = mapOf("fr-1-2" to ep("fr-1-2", "fr", 1, 2, 100, 10))
        val others = listOf(ep("en-1-1", "en", 1, 1, 3000, 5, watched = true), ep("en-1-2", "en", 1, 2, 2000, 20), ep("qc-1-3", "qc", 1, 3, 400, 30))
        val slots = listOf(EpisodeSlot("fr-1-1", 1, 1), EpisodeSlot("fr-1-2", 1, 2), EpisodeSlot("fr-1-3", 1, 3), EpisodeSlot("fr-1-4", 1, 4))
        val out = carryEpisodeRows(own, others, slots)
        assertEquals(true, out.getValue("fr-1-1").watched)
        assertEquals("fr-1-1", out.getValue("fr-1-1").itemId)
        assertEquals(100, out.getValue("fr-1-2").positionS, "la ligne propre ne bouge pas")
        assertEquals(400, out.getValue("fr-1-3").positionS)
        assertNull(out["fr-1-4"])
    }
}
