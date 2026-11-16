package home.iptv.tv.movies

import home.iptv.shared.progress.*
import home.iptv.tv.data.TrackPrefsStore
import home.iptv.tv.player.*
import home.iptv.tv.series.ProgressFlusher
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import kotlin.time.Instant
import kotlin.time.Duration.Companion.seconds

internal class MovieQueueStore : ProgressStore {
    var entries = emptyList<QueuedProgress>()
    override fun loadProgressQueue() = entries
    override fun saveProgressQueue(queue: List<QueuedProgress>) { entries = queue }
    override fun update(transform: (List<QueuedProgress>) -> List<QueuedProgress>) { entries = transform(entries) }
}
internal class MovieTestPlayer(val events: MutableList<String>) : Player {
    var position = 0L
    var duration = 1000000L
    override fun play(url: String) { events += "open" }
    override fun play(url: String, startMs: Long) { position = startMs; events += "open:$startMs" }
    override fun stop() { events += "stop" }
    override fun release() { events += "release" }
    override fun snapshot() = PlaybackSnapshot(positionMs = position, durationMs = duration, playing = true, ready = true)
}
internal val trackStore = object : TrackPrefsStore {
    override fun audioLang(profileId: String): String? = null
    override fun subtitleLang(profileId: String): String? = null
    override fun saveTrackPrefs(profileId: String, audioLang: String?, subtitleLang: String?) {}
}
@OptIn(ExperimentalCoroutinesApi::class)
private class PlaybackRig(test: TestScope, val summary: home.iptv.shared.xtream.MovieSummary = movie()) {
    val events = mutableListOf<String>()
    val players = mutableListOf<MovieTestPlayer>()
    val controller = PlayerController { MovieTestPlayer(events).also { players += it } }
    val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
    val store = MovieQueueStore()
    var now = Instant.parse("2026-11-15T10:00:00Z")
    val client = object : ProgressClient {
        override suspend fun record(p: String, entry: ProgressEntry): Boolean = throw java.io.IOException()
        override suspend fun continueWatching(p: String, max: Int) = emptyList<ProgressRow>()
        override suspend fun setWatched(p: String, items: List<WatchedItem>, watched: Boolean) {}
        override suspend fun setSeriesList(p: String, seriesId: String, on: Boolean) {}
        override suspend fun seriesProgress(p: String, seriesId: String) = emptyList<ProgressRow>()
    }
    val queue = ProgressQueue(client, store) { now }
    val playback = MoviePlayback("original", controller, queue, ProgressFlusher(queue, scope, StandardTestDispatcher(test.testScheduler)), trackStore, { now }, scope, { "fixture" }, { _, _ -> home.iptv.shared.xtream.MovieInfo(summary) }, { emptySet() }, StandardTestDispatcher(test.testScheduler), { "c" })
}

@OptIn(ExperimentalCoroutinesApi::class)
class MoviePlaybackTest {
    @Test fun end_marks_known_duration_watched_and_never_autoplays() = runTest {
        val f = PlaybackRig(this)
        f.playback.begin(PlayMovieRequest("m", "c")); runCurrent()
        f.players.last().duration = 1000000L
        f.playback.onEnded(); advanceTimeBy(60000); runCurrent()
        assertTrue(f.playback.state.value is MoviePlaybackState.Finished)
        assertNull(f.controller.active.value)
        assertEquals(1, f.players.size)
        assertEquals(1000, f.store.entries.single().entry.positionS)
        assertTrue(queuedMovieRows(f.store.entries, "original").single().watched)
        f.scope.cancel()
    }
    @Test fun unknown_duration_resume_and_stop_during_load_preserve_safety() = runTest {
        val f = PlaybackRig(this, movie().copy(durationS = null))
        f.playback.begin(PlayMovieRequest("m", "c", startS = 600)); runCurrent()
        assertEquals(600000L, f.players.last().position)
        f.playback.stop()
        f.playback.begin(PlayMovieRequest("m", "c")); f.playback.stop(); runCurrent()
        assertNull(f.controller.active.value)
        assertTrue(f.playback.state.value is MoviePlaybackState.Idle)
        assertEquals(1, f.players.size)
        f.scope.cancel()
    }
    @Test fun ninety_percent_and_less_than_thirty_seconds_resume_from_zero() = runTest {
        for (position in listOf(900, 980)) {
            val f = PlaybackRig(this)
            f.playback.begin(PlayMovieRequest("m", "c", startS = position)); runCurrent()
            assertEquals(0L, f.players.last().position)
            f.playback.stop(); f.scope.cancel()
        }
        val f = PlaybackRig(this, movie().copy(durationS = 200))
        f.playback.begin(PlayMovieRequest("m", "c", startS = 175)); runCurrent()
        assertEquals(0L, f.players.last().position)
        f.playback.stop(); f.scope.cancel()
    }
    @Test fun resume_periodic_pause_stop_original_profile_and_single_player() = runTest {
        val events = mutableListOf<String>(); val players = mutableListOf<MovieTestPlayer>()
        val controller = PlayerController { MovieTestPlayer(events).also { players += it } }
        controller.open("fixture-live")
        var now = Instant.parse("2026-11-15T10:00:00Z")
        val store = MovieQueueStore()
        val client = object : home.iptv.shared.progress.ProgressClient {
            override suspend fun record(p: String, entry: ProgressEntry): Boolean = throw java.io.IOException()
            override suspend fun continueWatching(p: String, max: Int) = emptyList<ProgressRow>()
            override suspend fun setWatched(p: String, items: List<WatchedItem>, watched: Boolean) {}
            override suspend fun setSeriesList(p: String, seriesId: String, on: Boolean) {}
            override suspend fun seriesProgress(p: String, seriesId: String) = emptyList<ProgressRow>()
        }
        val queue = ProgressQueue(client, store) { now }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val pb = MoviePlayback("original", controller, queue, ProgressFlusher(queue, scope, StandardTestDispatcher(testScheduler)), trackStore, { now }, scope, { "fixture-movie" }, { _, _ -> home.iptv.shared.xtream.MovieInfo(movie()) }, { emptySet() }, StandardTestDispatcher(testScheduler), { "c" })
        pb.begin(PlayMovieRequest("m", "c", startS = 300)); runCurrent()
        assertEquals(300000L, players.last().position)
        assertTrue(events.indexOf("release") < events.indexOf("open:300000"))
        now += 30.seconds; pb.onTick(330, 1000, true)
        assertEquals(330, store.entries.single().entry.positionS)
        pb.onTick(340, 1000, false)
        assertEquals(340, store.entries.single().entry.positionS)
        players.last().position = 350000
        pb.stop()
        assertEquals("original", store.entries.single().profileId)
        assertEquals("movie", store.entries.single().entry.itemType)
        assertEquals(350, store.entries.single().entry.positionS)
        assertNull(controller.active.value)
        scope.cancel()
    }
    @Test fun mask_changed_during_load_prevents_open_and_unknown_category_never_loads() = runTest {
        val events = mutableListOf<String>(); val controller = PlayerController { MovieTestPlayer(events) }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val store = MovieQueueStore(); val queue = ProgressQueue(object : ProgressClient {
            override suspend fun record(p: String, entry: ProgressEntry) = true
            override suspend fun continueWatching(p: String, max: Int) = emptyList<ProgressRow>()
            override suspend fun setWatched(p: String, items: List<WatchedItem>, watched: Boolean) {}
            override suspend fun setSeriesList(p: String, seriesId: String, on: Boolean) {}
            override suspend fun seriesProgress(p: String, seriesId: String) = emptyList<ProgressRow>()
        }, store) { Instant.fromEpochSeconds(1) }
        val gate = CompletableDeferred<Unit>(); var hidden: Set<String>? = emptySet(); var calls = 0; var cached: String? = null
        val pb = MoviePlayback("p", controller, queue, ProgressFlusher(queue, scope), trackStore, { Instant.fromEpochSeconds(1) }, scope, { "fixture" }, { _, _ -> calls++; gate.await(); home.iptv.shared.xtream.MovieInfo(movie()) }, { hidden }, StandardTestDispatcher(testScheduler), { cached })
        pb.begin(PlayMovieRequest("old", null)); runCurrent(); assertEquals(0, calls)
        cached = "c"; hidden = setOf("c")
        pb.begin(PlayMovieRequest("m", "allowed")); runCurrent()
        assertEquals(0, calls, "La catégorie masquée connue du cache gagne avant la requête")
        cached = null; hidden = emptySet()
        pb.begin(PlayMovieRequest("m", "c")); runCurrent(); hidden = setOf("c"); gate.complete(Unit); runCurrent()
        assertNull(controller.active.value)
        assertTrue(pb.state.value is MoviePlaybackState.Failed)
        scope.cancel()
    }
}
