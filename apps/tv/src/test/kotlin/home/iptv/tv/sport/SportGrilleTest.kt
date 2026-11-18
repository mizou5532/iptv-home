package home.iptv.tv.sport

import home.iptv.shared.sport.League
import home.iptv.shared.sport.Match
import home.iptv.shared.sport.MatchState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Instant

/** Filtres et états de la disposition « grille » de l'onglet Sport. */
class SportGrilleTest {
    private val t0 = Instant.parse("2026-11-18T00:00:00Z")
    private fun m(id: String, league: League, state: MatchState = MatchState.UPCOMING, detail: String? = null) =
        Match(id, league, t0, state, "Locaux $id", "Visiteurs $id", followed = false, detail = detail)

    private val list = listOf(
        m("1", League.NHL, MatchState.LIVE), m("2", League.NHL), m("3", League.NFL),
        m("4", League.LIGA, MatchState.LIVE), m("5", League.EPL), m("6", League.NHL, MatchState.FINAL),
    )

    @Test fun filtres_tout_direct_puis_les_sports_presents_dans_l_ordre() {
        val chips = gridChips(list)
        assertEquals(listOf("tout" to 6, "direct" to 2, "hockey" to 3, "football" to 1, "soccer" to 2), chips.map { it.key to it.count })
        assertEquals(listOf("Tout", "En direct", "LNH", "NFL", "Soccer"), chips.map { it.label })
    }

    @Test fun aucun_filtre_quand_ils_montrent_tous_la_meme_chose() {
        assertEquals(emptyList<GridChip>(), gridChips(emptyList()))
        assertEquals(emptyList<GridChip>(), gridChips(listOf(m("1", League.NHL), m("2", League.NHL))))
        assertEquals(emptyList<GridChip>(), gridChips(listOf(m("1", League.NHL, MatchState.LIVE), m("2", League.NHL, MatchState.LIVE))))
        assertEquals(listOf("tout", "direct", "hockey"), gridChips(listOf(m("1", League.NHL, MatchState.LIVE), m("2", League.NHL))).map { it.key })
    }

    @Test fun filtrer_la_grille() {
        assertEquals(list, gridFilter(list, "tout"))
        assertEquals(listOf("1", "4"), gridFilter(list, "direct").map { it.id })
        assertEquals(listOf("1", "2", "6"), gridFilter(list, "hockey").map { it.id })
        assertEquals(listOf("4", "5"), gridFilter(list, "soccer").map { it.id })
        assertEquals(emptyList<Match>(), gridFilter(list, "basketball"))
    }

    @Test fun etat_des_tuiles() {
        assertEquals("2e pér. 12:41", gridStatus(m("1", League.NHL, MatchState.LIVE, detail = "2e pér. 12:41"), today = true))
        assertEquals("En direct", gridStatus(m("1", League.NHL, MatchState.LIVE), today = true))
        assertEquals("Terminé", gridStatus(m("1", League.NHL, MatchState.FINAL), today = true))
        val later = gridStatus(m("1", League.NHL), today = true)
        assertFalse(later.contains("LNH"), "l'heure seule, sans la compétition : $later")
        assertFalse(gridStatus(m("1", League.NHL), today = false).contains("LNH"))
    }
}
