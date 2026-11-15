package home.iptv.tv.data

import home.iptv.shared.progress.ProgressEntry
import home.iptv.shared.progress.QueuedProgress
import kotlin.time.Instant
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** In-memory implementation of the Android preferences interface; no disk or real credentials. */
private class PreferencesFixture {
    val values = mutableMapOf<String, String?>()
    var applies = 0
    var commits = 0
    var commitResult = true
    private val pending = mutableMapOf<String, String?>()
    private lateinit var editor: SharedPreferences.Editor
    val prefs: SharedPreferences
    init {
        editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
            when (method.name) {
                "putString" -> { pending[args!![0] as String] = args[1] as String?; editor }
                "apply" -> { values.putAll(pending); pending.clear(); applies++; null }
                "commit" -> { values.putAll(pending); pending.clear(); commits++; commitResult }
                else -> error("Unexpected editor method ${method.name}")
            }
        } as SharedPreferences.Editor
        prefs = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString" -> values[args!![0]] ?: args[1]
                "edit" -> editor
                else -> error("Unexpected preferences method ${method.name}")
            }
        } as SharedPreferences
    }
    fun store() = AndroidLocalStore(object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
    })
}

class ProgressPersistenceTest {
    @Test
    fun queueRoundTripsThroughPreferencesAcrossStoreInstances() {
        val fixture = PreferencesFixture()
        val entry = QueuedProgress("p1", ProgressEntry("e1", null, null, null, 42, 100,
            Instant.parse("2026-11-15T10:00:00.123Z")), attempts = 3)
        fixture.store().saveProgressQueue(listOf(entry))
        assertTrue(fixture.store().persistNow())
        assertEquals(listOf(entry), fixture.store().loadProgressQueue())
        fixture.store().update { it + entry.copy(profileId = "p2") }
        assertEquals(listOf("p1", "p2"), fixture.store().loadProgressQueue().map { it.profileId })
    }

    @Test
    fun tabConstantsRoundTripPerProfile() {
        val fixture = PreferencesFixture()
        val store = fixture.store()
        store.saveLastTab("p1", HomeTab.SERIES)
        store.saveLastTab("p2", HomeTab.DIRECT)
        assertEquals(HomeTab.SERIES, fixture.store().lastTab("p1"))
        assertEquals(HomeTab.DIRECT, fixture.store().lastTab("p2"))
        assertEquals(null, fixture.store().lastTab("p3"))
    }

    @Test
    fun unsupportedSavedTabIsIgnored() {
        val fixture = PreferencesFixture()
        fixture.values["last_tab:p1"] = "unsupported"
        assertEquals(null, fixture.store().lastTab("p1"))
    }

    @Test
    fun tabSelectionUsesConstantsInsteadOfFreeString() {
        val setter = LocalStore::class.java.methods.single { it.name == "saveLastTab" }
        assertTrue(setter.parameterTypes[1].isEnum)
    }

    @Test
    fun routineQueueWritesUseApply() {
        val fixture = PreferencesFixture()
        fixture.store().saveProgressQueue(emptyList())
        assertEquals(1, fixture.applies)
        assertEquals(0, fixture.commits)
    }

    @Test
    fun explicitPersistenceIsExposedForOnStop() {
        val fixture = PreferencesFixture()
        val store: LocalStore = fixture.store()
        store.saveProgressQueue(emptyList())
        assertTrue(store.persistNow())
        assertEquals(1, fixture.commits)
        fixture.commitResult = false
        assertEquals(false, store.persistNow())
        assertEquals(2, fixture.commits)
    }

    @Test
    fun unreadableJsonIsPreservedBeforeQueueReplacement() {
        val fixture = PreferencesFixture()
        fixture.values["progress_queue"] = "[broken"
        val store = fixture.store()
        assertEquals(emptyList(), store.loadProgressQueue())
        store.saveProgressQueue(emptyList())
        assertEquals("[broken", fixture.values["progress_queue_corrupt"])
        assertEquals("[]", fixture.values["progress_queue"])
    }
}
