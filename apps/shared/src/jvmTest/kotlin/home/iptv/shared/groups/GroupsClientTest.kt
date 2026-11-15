package home.iptv.shared.groups

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class GroupsClientTest {
    private fun entry(id: String) = GroupPageEntry(id, "Sport", listOf(id), emptyList())
    @Test fun legacy_preferences_default_to_grouping_and_explicit_false_is_kept() {
        val old = """{"rev":0,"hidden":[],"favorites":[],"playlists":[],"order":null}"""
        assertTrue(kotlinx.serialization.json.Json.decodeFromString<home.iptv.shared.sync.ProfilePrefs>(old).groupCopies)
        assertFalse(kotlinx.serialization.json.Json.decodeFromString<home.iptv.shared.sync.ProfilePrefs>(old.dropLast(1) + ",\"group_copies\":false}").groupCopies)
    }
    @Test fun pages_are_complete_in_cursor_order() = runTest {
        val cursors = mutableListOf<String?>()
        val client = GroupsClient { _, after, _ ->
            cursors += after
            if (after == null) GroupPage(7, 1, listOf(entry("a")), "a") else GroupPage(7, 1, listOf(entry("b")))
        }
        assertEquals(listOf("a", "b"), client.snapshot("h").groups.map { it.id })
        assertEquals(listOf(null, "a"), cursors)
    }
    @Test fun revision_changes_restart_without_mixing_pages() = runTest {
        var first = true
        var starts = 0
        val client = GroupsClient { _, after, _ ->
            if (after == null) { starts++; GroupPage(if (first) 1 else 2, 1, listOf(entry("a")), "a") }
            else { first = false; GroupPage(2, 1, listOf(entry("b"))) }
        }
        assertEquals(GroupSnapshot(2, 1, listOf(ManualGroup("a", "Sport", listOf("a"), emptyList()), ManualGroup("b", "Sport", listOf("b"), emptyList()))), client.snapshot("h"))
        assertEquals(2, starts)
    }
    @Test fun unstable_revision_is_bounded_and_unknown_version_rejected() = runTest {
        var n = 0L
        val changing = GroupsClient { _, after, _ -> GroupPage(++n, 1, listOf(entry("a")), if (after == null) "a" else null) }
        assertFailsWith<IllegalStateException> { changing.snapshot("h") }
        assertEquals(6, n)
        assertFailsWith<IllegalArgumentException> { GroupsClient { _, _, _ -> GroupPage(1, 99, emptyList()) }.snapshot("h") }
    }
    @Test fun repeated_cursor_is_rejected() = runTest {
        assertFailsWith<IllegalStateException> {
            GroupsClient { _, _, _ -> GroupPage(1, 1, listOf(entry("a")), "a") }.snapshot("h")
        }
    }
}
