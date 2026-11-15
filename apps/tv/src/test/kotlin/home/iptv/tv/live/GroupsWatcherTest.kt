package home.iptv.tv.live

import home.iptv.shared.groups.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class GroupsWatcherTest {
    @Test fun local_then_complete_snapshot_and_revision_probe_only_on_prefs_change() = runTest {
        val store = PrefsStore()
        val old = GroupSnapshot(1, 1, emptyList())
        store.saveGroupSnapshot("h", old)
        var calls = 0
        val w = GroupsWatcher(GroupsClient { _, _, _ -> calls++; GroupPage(2, 1, emptyList()) }, store, { "h" }, StandardTestDispatcher(testScheduler))
        assertEquals(old, w.snapshot.value)
        w.refresh(5)
        assertEquals(2, w.snapshot.value?.rev)
        assertEquals(w.snapshot.value, store.groupSnapshot("h"))
        w.refresh(5)
        assertEquals(2, calls) // probe + snapshot, pas de nouveau chargement
        w.refresh(6)
        assertEquals(3, calls) // preference sans changement des groupes : sonde courte seulement
    }
    @Test fun offline_partial_or_unknown_version_never_replace_valid_cache() = runTest {
        val store = PrefsStore()
        val old = GroupSnapshot(1, 1, emptyList())
        store.saveGroupSnapshot("h", old)
        var mode = 0
        val w = GroupsWatcher(GroupsClient { _, after, lim ->
            when {
                mode == 0 -> error("hors ligne")
                mode == 1 -> GroupPage(2, 99, emptyList())
                after != null -> error("page interrompue")
                else -> GroupPage(2, 1, listOf(GroupPageEntry("a", "Sport", listOf("a"), emptyList())), "a")
            }
        }, store, { "h" }, StandardTestDispatcher(testScheduler))
        repeat(3) { mode = it; w.refresh(2); assertEquals(old, w.snapshot.value); assertEquals(old, store.groupSnapshot("h")) }
    }
    @Test fun household_changed_during_response_discards_old_result_and_releases_memory() = runTest {
        var h: String? = "a"
        val store = PrefsStore()
        store.saveGroupSnapshot("a", GroupSnapshot(1, 1, emptyList()))
        val gate = CompletableDeferred<Unit>()
        val w = GroupsWatcher(GroupsClient { _, _, _ -> gate.await(); GroupPage(2, 1, emptyList()) }, store, { h }, StandardTestDispatcher(testScheduler))
        val task = launch { w.refresh(2) }
        runCurrent(); h = "b"; gate.complete(Unit); task.join()
        assertNull(w.snapshot.value)
        assertNull(store.groupSnapshot("b"))
        w.refresh(3)
        assertEquals(2, store.groupSnapshot("b")?.rev)
        h = null; w.refresh(3)
        assertNull(w.snapshot.value)
    }
}
