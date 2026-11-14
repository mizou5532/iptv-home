package home.iptv.tv.live

import home.iptv.shared.groups.*
import kotlin.test.*

class GroupRowsTest {
    private val a = GroupSource("a", "A", "Sport HD", 0)
    private val b = GroupSource("b", "B", "Sport FHD", 1)
    private val c = GroupSource("c", "A", "Cinema", 2)
    private val snapshot = GroupSnapshot(0, 1, emptyList())

    @Test fun hidden_favorite_never_returns_through_visible_copy() {
        assertEquals(emptyList(), buildGroupRows(listOf(a), listOf(b, c), snapshot, true))
    }
    @Test fun first_present_source_anchors_favorites_order() {
        assertEquals(listOf("b", "c"), buildGroupRows(listOf(b, a, c), listOf(a, b, c), snapshot, true).map { it.anchorSourceId })
    }
    @Test fun category_offers_visible_copy_from_other_category() {
        assertEquals(listOf("a", "b"), buildGroupRows(listOf(a), listOf(a, b, c), snapshot, true).single().group.sources.map { it.id })
    }
    @Test fun hidden_other_category_is_excluded() {
        assertEquals(listOf(a), buildGroupRows(listOf(a), listOf(a, c), snapshot, true).single().group.sources)
    }
    @Test fun disabled_returns_separate_entries_in_section_order() {
        val rows = buildGroupRows(listOf(b, a, c), listOf(a, b, c), snapshot, false)
        assertEquals(listOf("b", "a", "c"), rows.map { it.anchorSourceId })
        assertTrue(rows.all { it.group.sources.size == 1 })
    }
}
