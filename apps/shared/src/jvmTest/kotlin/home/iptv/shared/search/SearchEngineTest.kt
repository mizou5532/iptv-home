package home.iptv.shared.search

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime

class SearchEngineTest {
    private fun index(vararg titles: String) = SearchIndex(titles.mapIndexed { i, title -> SearchItem("$i", title, setOf("cat")) })
    @Test fun accents_et_casse() = assertEquals(1, index("Les Canadiens de Montréal").search("MONTREAL").size)
    @Test fun mot_partiel() = assertEquals(1, index("Canadiens").search("canadi").size)
    @Test fun ordre_des_mots() = assertEquals(1, index("Les Canadiens de Montréal").search("montreal canadiens").size)
    @Test fun une_faute_sur_mot_moyen() {
        assertEquals(1, index("Canadiens").search("canadeins").size)
        assertEquals(1, index("FC Barcelona").search("barcelonr").size)
    }
    @Test fun pas_de_faute_sous_quatre_lettres() = assertTrue(index("RDS").search("rbs").isEmpty())
    @Test fun deux_fautes_sur_mot_long() {
        assertEquals(1, index("Montreal").search("montrael").size)
        assertEquals(1, index("Barcelona").search("barxeloza").size)
        assertTrue(index("abcdefg").search("abxdefz").isEmpty())
    }
    @Test fun dernier_mot_en_cours_avec_faute() = assertEquals(1, index("Canadiens").search("canade").size)
    @Test fun surnoms() {
        for (q in listOf("habs", "ch", "canadien")) assertEquals(1, index("Canadiens").search(q).size)
        for (q in listOf("barca", "BARÇA", "fcb")) assertEquals(2, index("FC Barcelona", "Barcelone").search(q).size)
        for ((q, title) in listOf("psg" to "Paris", "mtl" to "Montréal", "qc" to "Québec", "nhl" to "LNH", "lnh" to "NHL")) assertEquals(1, index(title).search(q).size)
    }
    @Test fun classement() = assertEquals(listOf("RDS", "RDS 2", "Super RDS Info"), index("RDS 2", "RDS", "Super RDS Info").search("rds").map { it.title })
    @Test fun separateurs() {
        assertEquals(1, index("FR| SPORT HD").search("sport").size)
        assertEquals(listOf("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k"), searchWords("À|b:c-d.e/f(g)[h],i  j\tk"))
        assertEquals(listOf("RDS 2", "RDS|Info"), index("RDS|Info", "RDS 2").search("rds").map { it.title })
    }
    @Test fun doublons_par_id() {
        val engine = SearchIndex(listOf(SearchItem("x", "RDS", setOf("a")), SearchItem("x", "RDS", setOf("b"))))
        assertEquals(1, engine.size)
        assertEquals(1, engine.search("rds").size)
        assertEquals(setOf("a", "b"), engine.search("rds").single().categoryIds)
    }
    @Test fun limite() {
        val engine = index(*Array(200) { "Sport $it" })
        assertEquals(60, engine.search("sport").size)
        assertEquals(60, engine.search("sport", 200).size)
        assertEquals(3, engine.search("sport", 3).size)
        assertTrue(engine.search("sport", 0).isEmpty())
    }
    @Test fun requete_vide() { for (q in listOf("", "   ", "|:/")) assertTrue(index("RDS").search(q).isEmpty()) }
    @Test fun stable() { val engine = index("Sport B", "Sport A", "Sport C"); assertEquals(engine.search("sport"), engine.search("sport")) }
    @Test fun chaque_mot_et_premiere_lettre_fautive() {
        assertEquals(1, index("Canadiens Montréal").search("xanadiens montreal").size)
        assertTrue(index("Canadiens Montréal").search("canadiens paris").isEmpty())
        assertTrue(index("Canadiens Montréal").search("canade montreal").isEmpty())
        assertEquals(1, index("Supercanadiens").search("canadiens").size)
    }
    @Test fun performance_50000_titres() {
        val random = Random(571)
        val roots = listOf("montreal", "canadiens", "barcelona", "aventure", "mystere", "documentaire", "science", "nature", "histoire", "cinema", "sport", "quebec", "paris", "voyage", "ocean", "foret", "musique", "culture", "etoiles", "planete")
        val vocabulary = List(6000) { roots[it % roots.size] + (1..random.nextInt(2, 7)).map { ('a'..'z').random(random) }.joinToString("") }
        val engine = index(*Array(50000) { "${roots[random.nextInt(roots.size)]} ${vocabulary[random.nextInt(vocabulary.size)]} ${vocabulary[random.nextInt(vocabulary.size)]}" })
        val queries = listOf("canadi", "montreal", "habs", "barca", "aventure", "mystre", "documentair", "scence", "nature", "histoire", "cinema", "sport", "qc", "psg", "voyge", "ocean", "foret", "musique", "culture eto", "xanadiens")
        repeat(3) { engine.search(queries[it]) }
        for (query in queries) {
            val elapsed = measureTime { engine.search(query) }
            println("SEARCH_PERF $query ${elapsed.inWholeMicroseconds / 1000.0} ms")
            assertTrue(elapsed.inWholeNanoseconds < 100_000_000, "$query: $elapsed")
        }
    }
}
