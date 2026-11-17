package home.iptv.shared.sport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Garde-fou de vitesse de l'onglet Sport (17 nov. 2026) : la langue de chaque chaîne était recalculée pour chaque
 * match, 4,3 s pour 25 matchs et 4 400 chaînes sport sur un serveur rapide, bien plus sur la télé. Si ce calcul
 * redevient lent, ce test échoue.
 */
class SportSpeedTest {
    private val nets = listOf("RDS", "RDS 2", "TVA Sports", "TSN 1", "TSN 2", "Sportsnet East", "ESPN", "ESPN 2", "TNT", "beIN Sports 1", "Canal+ Sport", "DAZN 1")
    private val cats = listOf("CA| SPORTS", "CA| FRENCH SPORTS", "US| SPORTS ᴴᴰ", "FR| SPORT", "UK| SPORTS")
    private val visible = (0 until 4000).map { i -> SportChannel("g$i", "${nets[i % nets.size]} ${listOf("HD", "FHD", "RAW 60fps")[i % 3]} ${i / nets.size}", cats[i % cats.size], "s$i") }
    private val start = Instant.parse("2026-11-18T00:00:00Z")
    private val matches = (0 until 25).map { i ->
        Match("nba:$i", League.NBA, start + (i % 4).hours, MatchState.UPCOMING, "Toronto Raptors $i", "Boston Celtics $i", false, listOf("TSN", "ESPN"),
            homeShort = "Raptors $i", awayShort = "Celtics $i")
    }
    private val extra = (0 until 2200).map { i -> SportChannel("x:$i", "NBA: Raptors ${i % 40} vs Celtics ${i % 40} @ Nov 17 7:00 PM", "US| NBA PPV", "$i", direct = true) }

    private fun all(idx: SportChannelIndex, ex: SportExtraIndex) =
        matches.map { sportCandidates(it, emptyList(), idx, extraIdx = ex, regions = regionOrder(null), offsetSeconds = -5 * 3600) }

    @Test
    fun chaines_de_25_matchs_en_moins_d_une_seconde() {
        val idx = SportChannelIndex(visible)
        val ex = SportExtraIndex(extra)
        all(idx, ex) // premier passage : la machine virtuelle se met en route
        val t = System.nanoTime()
        val result = all(idx, ex)
        val ms = (System.nanoTime() - t) / 1_000_000
        assertEquals(25, result.size)
        assertTrue(result.all { it.all.size >= 4000 }, "le parcours garde toutes les chaînes sport")
        assertTrue(ms < 1_000, "calcul des chaînes trop lent : $ms ms pour 25 matchs (attendu : moins de 100 ms)")
    }

    @Test
    fun langue_gardee_egale_au_calcul() {
        visible.take(200).forEach { assertEquals(regionOf(it), it.region) }
        // Le parcours suit l'ordre des langues demandé, comme avant.
        val idx = SportChannelIndex(visible.take(200))
        val regions = regionOrder(listOf("fr", "qc"))
        val c = sportCandidates(matches.first(), emptyList(), idx, regions = regions)
        val browse = c.all.drop(c.likely.size)
        assertTrue(browse.size > 100)
        assertEquals(browse.map { regions.indexOf(it.region) }.sorted(), browse.map { regions.indexOf(it.region) })
    }
}
