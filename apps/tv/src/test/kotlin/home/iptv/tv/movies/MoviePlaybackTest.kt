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
    var playingNow = true
    var pausedSet: Boolean? = null
    override fun play(url: String) { events += "open" }
    override fun play(url: String, startMs: Long) { position = startMs; events += "open:$startMs" }
    override fun stop() { events += "stop" }
    override fun release() { events += "release" }
    override fun snapshot() = PlaybackSnapshot(positionMs = position, durationMs = duration, playing = playingNow, ready = true)
    override fun setPaused(paused: Boolean) { pausedSet = paused; playingNow = !paused }
    override fun selectAudio(id: String) { events += "audio:$id" }
}
internal val trackStore = object : TrackPrefsStore {
    override fun audioLang(profileId: String): String? = null
    override fun subtitleLang(profileId: String): String? = null
    override fun saveTrackPrefs(profileId: String, audioLang: String?, subtitleLang: String?) {}
}
@OptIn(ExperimentalCoroutinesApi::class)
private class PlaybackRig(test: TestScope, val summary: home.iptv.shared.xtream.MovieSummary = movie()) {
    var hidden: Set<String>? = emptySet()
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
    val playback = MoviePlayback("original", controller, queue, ProgressFlusher(queue, scope, StandardTestDispatcher(test.testScheduler)), trackStore, { now }, scope, { "fixture" }, { _, _ -> home.iptv.shared.xtream.MovieInfo(summary) }, { hidden }, StandardTestDispatcher(test.testScheduler), { "c" })
}

@OptIn(ExperimentalCoroutinesApi::class)
class MoviePlaybackTest {
    @Test fun version_francaise_choisie_joue_en_francais_meme_pour_un_profil_en_anglais() = runTest {
        // Bogue du 23 nov. : profil réglé en anglais, version « FR » du film choisie sur la fiche (pistes anglais + français).
        val events = mutableListOf<String>()
        val controller = PlayerController { MovieTestPlayer(events) }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val queue = ProgressQueue(object : ProgressClient {
            override suspend fun record(p: String, entry: ProgressEntry): Boolean = true
            override suspend fun continueWatching(p: String, max: Int) = emptyList<ProgressRow>()
            override suspend fun setWatched(p: String, items: List<WatchedItem>, watched: Boolean) {}
            override suspend fun setSeriesList(p: String, seriesId: String, on: Boolean) {}
            override suspend fun seriesProgress(p: String, seriesId: String) = emptyList<ProgressRow>()
        }, MovieQueueStore()) { Instant.fromEpochSeconds(1) }
        val pb = MoviePlayback("p", controller, queue, ProgressFlusher(queue, scope, StandardTestDispatcher(testScheduler)), trackStore, { Instant.fromEpochSeconds(1) }, scope,
            { "fixture" }, { _, _ -> home.iptv.shared.xtream.MovieInfo(movie("m", "c")) }, { emptySet() }, StandardTestDispatcher(testScheduler), { "c" },
            languages = { home.iptv.shared.versions.LanguagePrefs(order = listOf("en")) to true },
            versionAudio = { id, title -> if (id == "m" && title == "Film m") "fr" else null })
        pb.begin(PlayMovieRequest("m", "c")); runCurrent()
        pb.onTracks(PlayerTracks(listOf(TrackOption("a:en", "eng", "English", true), TrackOption("a:fr", "fre", "Français", false)), emptyList()))
        assertTrue("audio:a:fr" in events, "piste française attendue : $events")
        assertFalse("audio:a:en" in events)
        scope.cancel()
    }

    @Test fun end_marks_known_duration_watched_and_never_autoplays() = runTest {
        val f = PlaybackRig(this)
        f.playback.begin(PlayMovieRequest("m", "c")); runCurrent()
        f.players.last().duration = 1000000L
        f.players.last().position = 995000L // vraie fin : près de la durée
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

    @Test fun coupure_du_fournisseur_au_milieu_reprend_a_la_position_sans_marquer_vu() = runTest {
        val f = PlaybackRig(this)
        f.playback.begin(PlayMovieRequest("m", "c")); runCurrent()
        f.players.last().position = 300000L
        f.playback.onTick(300, 1000, true)
        f.playback.onEnded()
        assertEquals(MoviePlaybackState.Playing(movie(), reconnecting = true), f.playback.state.value)
        assertEquals(2, f.players.size)
        assertEquals("open:300000", f.events.last())
        assertTrue(f.events.lastIndexOf("release") < f.events.lastIndexOf("open:300000"), "fermeture avant reouverture")
        assertEquals(300, f.store.entries.single().entry.positionS, "pas « vu »")
        f.scope.cancel()
    }
    @Test fun deux_reconnexions_silencieuses_puis_message_et_reessayer_a_la_position_exacte() = runTest {
        val f = PlaybackRig(this)
        f.playback.begin(PlayMovieRequest("m", "c")); runCurrent()
        f.players.last().position = 950000L // 95 % : la reprise normale repartirait de zero
        f.playback.onTick(950, 1000, true)
        f.playback.onFailed(); f.players.last().position = 950000L
        f.playback.onFailed(); f.players.last().position = 950000L
        assertEquals(3, f.players.size)
        f.playback.onFailed()
        val failed = f.playback.state.value as MoviePlaybackState.Failed
        assertTrue(failed.canRetry)
        assertNull(f.controller.active.value)
        f.playback.retry(); runCurrent()
        assertEquals("open:950000", f.events.last())
        assertEquals(MoviePlaybackState.Playing(movie()), f.playback.state.value)
        f.scope.cancel()
    }
    @Test fun reconnexion_pendant_une_pause_garde_la_pause() = runTest {
        val f = PlaybackRig(this)
        f.playback.begin(PlayMovieRequest("m", "c")); runCurrent()
        f.players.last().position = 200000L
        f.players.last().playingNow = false
        f.playback.onFailed()
        assertEquals(true, f.players.last().pausedSet)
        f.scope.cancel()
    }
    @Test fun masque_pendant_la_lecture_affiche_le_message_au_lieu_de_refermer_l_ecran() = runTest {
        val f = PlaybackRig(this)
        f.playback.begin(PlayMovieRequest("m", "c")); runCurrent()
        f.playback.onTick(100, 1000, true)
        f.hidden = setOf("c")
        f.playback.onTick(101, 1000, true)
        assertEquals(MoviePlaybackState.Failed("Ce film est masqué.", false), f.playback.state.value)
        assertNull(f.controller.active.value)
        f.scope.cancel()
    }
    @Test fun coupure_sans_credit_message_et_jamais_vu() = runTest {
        val f = PlaybackRig(this)
        f.playback.begin(PlayMovieRequest("m", "c")); runCurrent()
        repeat(3) { f.players.last().position = 300000L; f.playback.onTick(300, 1000, true); f.playback.onEnded() }
        assertEquals(MoviePlaybackState.Failed("Impossible de lire ce film. Vérifie la connexion puis réessaie.", true), f.playback.state.value)
        assertEquals(300, f.store.entries.single().entry.positionS, "jamais « vu »")
        assertNull(f.controller.active.value)
        f.scope.cancel()
    }
    @Test fun reessayer_un_autre_film_ne_reprend_pas_a_la_position_du_precedent() = runTest {
        val f = PlaybackRig(this)
        f.playback.begin(PlayMovieRequest("m", "c")); runCurrent()
        f.players.last().position = 900000L; f.playback.onTick(900, 1000, true)
        f.playback.stop()
        f.hidden = null // reglages pas encore charges : echec avant tout chargement
        f.playback.begin(PlayMovieRequest("autre", "c", startS = 120))
        assertTrue(f.playback.state.value is MoviePlaybackState.Failed)
        f.hidden = emptySet()
        f.playback.retry(); runCurrent()
        assertEquals("open:120000", f.events.last(), "la demande d'origine, pas 900 s du film precedent")
        f.scope.cancel()
    }
}
