package home.iptv.tv.movies

import home.iptv.shared.progress.*
import home.iptv.shared.sync.*
import home.iptv.shared.xtream.*
import home.iptv.tv.data.*
import home.iptv.tv.player.*
import home.iptv.tv.series.ProgressFlusher
import home.iptv.tv.series.resumePosition
import home.iptv.tv.series.trackLanguageCode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.time.Instant
import kotlin.time.Duration.Companion.seconds

sealed interface MoviePlaybackState {
    data object Idle : MoviePlaybackState
    data object Loading : MoviePlaybackState
    data class Playing(val movie: MovieSummary) : MoviePlaybackState
    data object Finished : MoviePlaybackState
    data class Failed(val message: String, val canRetry: Boolean) : MoviePlaybackState
}
typealias MoviePlaybackFactory = (Profile, StateFlow<ProfilePrefs?>) -> MoviePlayback

/** Lecture d'un film : aucune logique d'épisode/saison ni enchaînement automatique. */
class MoviePlayback(
    private val profileId: String,
    private val controller: PlayerController,
    private val queue: ProgressQueue,
    private val flusher: ProgressFlusher,
    private val store: TrackPrefsStore,
    private val clock: () -> Instant,
    private val scope: CoroutineScope,
    private val urlFor: (MovieSummary) -> String?,
    private val loadInfo: suspend (String, String) -> MovieInfo?,
    private val hiddenCategories: () -> Set<String>?,
    private val io: CoroutineDispatcher,
    private val cachedCategory: (String) -> String? = { null },
    private val coverFor: (String?) -> String? = { it },
) {
    private val _state = MutableStateFlow<MoviePlaybackState>(MoviePlaybackState.Idle)
    val state: StateFlow<MoviePlaybackState> = _state
    private val _tracks = MutableStateFlow(PlayerTracks.EMPTY)
    val tracks: StateFlow<PlayerTracks> = _tracks
    private var movie: MovieSummary? = null
    private var request: PlayMovieRequest? = null
    private var pos = 0
    private var dur = 0
    private var played = false
    private var wasPlaying = false
    private var lastSave: Instant? = null
    private var appliedTracks = false
    private var generation = 0
    private var job: Job? = null
    fun begin(request: PlayMovieRequest) {
        stop()
        this.request = request
        val gen = ++generation
        val hidden = hiddenCategories() ?: return fail("Les réglages du profil ne sont pas encore chargés.", true)
        val known = request.categoryId?.takeIf { it.isNotBlank() } ?: cachedCategory(request.movieId)?.takeIf { it.isNotBlank() }
        if (known == null) return fail("Film indisponible : parcours d'abord sa catégorie.", false)
        if (known in hidden) return fail("Ce film est masqué.", false)
        _state.value = MoviePlaybackState.Loading
        job = scope.launch {
            val info = try { withContext(io) { loadInfo(request.movieId, known) } } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (gen != generation) return@launch
            val currentHidden = hiddenCategories()
            if (currentHidden == null || known in currentHidden || info?.summary?.categoryId in currentHidden) return@launch fail("Ce film est indisponible pour ce profil.", false)
            val m = info?.summary ?: return@launch fail("Impossible de charger ce film. Réessaie.", true)
            val url = urlFor(m) ?: return@launch fail("Impossible de lire ce film. Réessaie.", true)
            movie = m.copy(categoryId = known)
            pos = if (request.restart) 0 else resumePosition(request.startS, m.durationS)
            dur = m.durationS ?: 0
            played = false; wasPlaying = false; appliedTracks = false; lastSave = clock()
            _state.value = MoviePlaybackState.Playing(m)
            controller.open(url, pos * 1000L)
        }
    }
    fun onTick(positionS: Int, durationS: Int, playing: Boolean = true) {
        if (_state.value !is MoviePlaybackState.Playing) return
        if (!allowed()) { stop(); fail("Ce film est masqué.", false); return }
        pos = positionS.coerceAtLeast(0); if (durationS > 0) dur = durationS
        if (playing) {
            played = true
            val now = clock()
            if (lastSave == null || now < lastSave!! || now - lastSave!! >= 30.seconds) save()
        } else if (wasPlaying) save()
        wasPlaying = playing
    }
    private fun snapshot() {
        val s = controller.active.value?.snapshot() ?: return
        if (s.ready && !s.failed) {
            pos = (s.positionMs.coerceAtLeast(0) / 1000).toInt()
            if (s.durationMs > 0) dur = (s.durationMs / 1000).toInt()
            if (s.playing) played = true
        }
    }
    fun onEnded() {
        if (_state.value !is MoviePlaybackState.Playing) return
        snapshot(); save(atEnd = true); controller.close(); _state.value = MoviePlaybackState.Finished
    }
    fun onFailed() { snapshot(); save(); controller.close(); fail("Impossible de lire ce film. Vérifie la connexion puis réessaie.", true) }
    fun retry() { request?.copy(startS = pos, restart = false)?.let { begin(it) } }
    fun restart() { request?.copy(restart = true, startS = 0)?.let { begin(it) } }
    fun stop() {
        if (_state.value is MoviePlaybackState.Playing) { snapshot(); save() }
        generation++; job?.cancel(); job = null
        controller.close(); _tracks.value = PlayerTracks.EMPTY
        movie = null; _state.value = MoviePlaybackState.Idle
    }
    private fun allowed(): Boolean = hiddenCategories()?.let { movie?.categoryId !in it && request?.categoryId !in it } == true
    private fun fail(message: String, retry: Boolean) { controller.close(); _state.value = MoviePlaybackState.Failed(message, retry) }
    private fun save(atEnd: Boolean = false) {
        val m = movie ?: return
        if (!played && !atEnd) return
        val position = if (atEnd && dur > 0) dur else if (dur > 0) pos.coerceAtMost(dur) else pos
        if (position <= 0) return
        queue.enqueue(profileId, ProgressEntry(itemId = m.id, seriesId = null, season = null, episode = null, itemType = "movie", positionS = position, durationS = dur, clientUpdatedAt = clock(), movieName = m.name, movieCover = coverFor(m.cover), movieCategory = m.categoryId))
        lastSave = clock(); flusher.request()
    }
    fun onTracks(tracks: PlayerTracks) {
        if (_state.value !is MoviePlaybackState.Playing) return
        _tracks.value = tracks
        if (appliedTracks || tracks == PlayerTracks.EMPTY) return
        appliedTracks = true
        val player = controller.active.value ?: return
        store.audioLang(profileId)?.let { lang -> tracks.audio.firstOrNull { trackLanguageCode(it.language) == lang }?.let { player.selectAudio(it.id) } }
        when (val lang = store.subtitleLang(profileId)) {
            null -> {}
            SUBTITLES_OFF -> player.selectSubtitle(null)
            else -> tracks.subtitles.firstOrNull { trackLanguageCode(it.language) == lang }?.let { player.selectSubtitle(it.id) }
        }
    }
    fun chooseAudio(option: TrackOption) {
        controller.active.value?.selectAudio(option.id)
        trackLanguageCode(option.language)?.let { store.saveTrackPrefs(profileId, it, store.subtitleLang(profileId)) }
    }
    fun chooseSubtitle(option: TrackOption?) {
        controller.active.value?.selectSubtitle(option?.id)
        val language = if (option == null) SUBTITLES_OFF else trackLanguageCode(option.language) ?: return
        store.saveTrackPrefs(profileId, store.audioLang(profileId), language)
    }
}
