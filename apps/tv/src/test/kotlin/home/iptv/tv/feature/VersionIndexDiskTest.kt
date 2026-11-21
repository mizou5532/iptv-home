package home.iptv.tv.feature

import home.iptv.shared.meta.MediaKind
import home.iptv.shared.search.SearchItem
import home.iptv.shared.versions.Version
import home.iptv.shared.versions.VersionIndexDisk
import home.iptv.shared.versions.VersionIndexes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Index des versions gardé sur la télé d'un lancement à l'autre (0.40). */
class VersionIndexDiskTest {
    private val dir = kotlin.io.path.createTempDirectory("versions").toFile()

    private class CountingDisk(val inner: VersionIndexDisk) : VersionIndexDisk {
        var hits = 0; var saves = 0
        override fun load(kind: MediaKind, fingerprint: String) = inner.load(kind, fingerprint)?.also { hits++ }
        override fun save(kind: MediaKind, fingerprint: String, groups: Map<String, List<Version>>) { saves++; inner.save(kind, fingerprint, groups) }
    }

    private val titles = mutableListOf(
        SearchItem("1", "FR - Oppenheimer (2023)", setOf("c1"), "https://img/1.jpg"),
        SearchItem("2", "4K-EN-HDR - Oppenheimer (2023)", setOf("c2"), null),
        SearchItem("3", "QC - Dune", setOf("c1"), null),
    )
    private var splits = emptySet<String>()
    private fun indexes(disk: VersionIndexDisk) = VersionIndexes(
        items = { titles.toList() }, categoryNames = { mapOf("c1" to "FR | FILMS", "c2" to "4K") }, splits = { splits }, disk = disk)

    @Test fun relu_tel_quel_au_lancement_suivant_si_rien_n_a_change() {
        val disk = CountingDisk(FileVersionIndexDisk(dir))
        val first = indexes(disk).index(MediaKind.MOVIE)
        assertEquals(1, disk.saves); assertEquals(0, disk.hits)
        // Nouveau lancement (nouvel objet), même catalogue : relu, rien recalculé ni réécrit.
        val second = indexes(disk).index(MediaKind.MOVIE)
        assertEquals(1, disk.hits); assertEquals(1, disk.saves)
        assertEquals(first.groups, second.groups)
        assertTrue(second.same("1", "2"), "les deux Oppenheimer restent réunis")
        assertEquals(first.versionsOf("1").map { it.tag }, second.versionsOf("1").map { it.tag })
        assertEquals("https://img/1.jpg", second.versionsOf("1").first { it.id == "1" }.cover)
    }

    @Test fun recalcule_si_le_catalogue_ou_une_separation_change() {
        val disk = CountingDisk(FileVersionIndexDisk(dir))
        indexes(disk).index(MediaKind.MOVIE)
        titles += SearchItem("4", "EN - Dune", setOf("c2"), null)
        indexes(disk).index(MediaKind.MOVIE)
        assertEquals(2, disk.saves, "catalogue changé : recalculé")
        splits = setOf("movie:2")
        val split = indexes(disk).index(MediaKind.MOVIE)
        assertEquals(3, disk.saves, "séparation : recalculé")
        assertTrue(!split.same("1", "2"))
    }

    @Test fun films_et_series_separes_fichier_abime_ignore() {
        val real = FileVersionIndexDisk(dir)
        indexes(real).index(MediaKind.MOVIE)
        assertNull(real.load(MediaKind.SERIES, "x"))
        java.io.File(dir, "versions-movie.txt").writeText("n'importe quoi")
        assertNull(real.load(MediaKind.MOVIE, "abc"))
        // Recalculé et réécrit proprement.
        val again = indexes(real).index(MediaKind.MOVIE)
        assertTrue(again.same("1", "2"))
    }

    @Test fun un_seul_calcul_meme_demande_en_meme_temps() {
        val disk = CountingDisk(FileVersionIndexDisk(dir))
        val v = indexes(disk)
        val threads = List(4) { Thread { v.index(MediaKind.MOVIE) } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        assertEquals(1, disk.saves, "jamais deux fois le même calcul")
    }
}
