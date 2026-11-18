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
    /** [reconnecting] : reprise automatique après une coupure (« Reconnexion… »). */
    data class Playing(val movie: MovieSummary, val reconnecting: Boolean = false) : MoviePlaybackState
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
    /** Langues du profil (page iPhone) et si elles ont été réglées ; défaut : Québec, France, anglais, sous-titres anglais. */
    private val languages: () -> Pair<home.iptv.shared.versions.LanguagePrefs, Boolean> = { home.iptv.shared.versions.LanguagePrefs() to false },
) {
    private val _state = MutableStateFlow<MoviePlaybackState>(MoviePlaybackState.Idle)
    val state: StateFlow<MoviePlaybackState> = _state

    init {
        // Tous les comptes IPTV pris (ou compte perdu) : message avec « Réessayer » au lieu d'un « Connexion… » sans fin.
        controller.onRefused("movie") { message ->
            if (_state.value is MoviePlaybackState.Playing) { save(); _state.value = MoviePlaybackState.Failed(message, true) }
        }
    }
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
    private var knownCategories = emptySet<String>()
    private var generation = 0
    private var job: Job? = null
    /** Reconnexions silencieuses faites pour ce film, et position d'où est partie la dernière. */
    private var recoveries = 0
    private var recoveredAt: Instant? = null
    /** Durée annoncée par le lecteur (fichier), 0 tant qu'inconnue : seule base fiable pour reconnaître une coupure. */
    private var playerDur = 0
    fun begin(request: PlayMovieRequest) {
        stop()
        // Rien du film précédent ne doit servir à celui-ci (« Réessayer » après un échec de chargement).
        played = false; pos = 0; dur = 0; playerDur = 0
        this.request = request
        val gen = ++generation
        val hidden = hiddenCategories() ?: return fail("Les réglages du profil ne sont pas encore chargés.", true)
        val cached = cachedCategory(request.movieId)?.takeIf { it.isNotBlank() }
        val known = request.categoryId?.takeIf { it.isNotBlank() } ?: cached
        knownCategories = listOfNotNull(known, cached).toSet()
        if (known == null) return fail("Film indisponible : parcours d'abord sa catégorie.", false)
        if (knownCategories.any { it in hidden }) return fail("Ce film est masqué.", false)
        _state.value = MoviePlaybackState.Loading
        job = scope.launch {
            val info = try { withContext(io) { loadInfo(request.movieId, known) } } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (gen != generation) return@launch
            val currentHidden = hiddenCategories()
            if (currentHidden == null || knownCategories.any { it in currentHidden } || info?.summary?.categoryId in currentHidden) return@launch fail("Ce film est indisponible pour ce profil.", false)
            val m = info?.summary ?: return@launch fail("Impossible de charger ce film. Réessaie.", true)
            val url = urlFor(m) ?: return@launch fail("Impossible de lire ce film. Réessaie.", true)
            knownCategories = knownCategories + m.categoryId
            movie = m.copy(categoryId = known)
            pos = when { request.restart -> 0; request.exact -> request.startS.coerceAtLeast(0); else -> resumePosition(request.startS, m.durationS) }
            recoveries = 0
            dur = m.durationS ?: 0
            played = false; wasPlaying = false; appliedTracks = false; lastSave = clock()
            _state.value = MoviePlaybackState.Playing(m)
            controller.open(url, pos * 1000L, label = "Film · ${home.iptv.shared.versions.versionTitle(m.name)}")
        }
    }
    fun onTick(positionS: Int, durationS: Int, playing: Boolean = true) {
        if (_state.value !is MoviePlaybackState.Playing) return
        // Masqué pendant la lecture : position gardée, lecteur fermé, et le message reste à l'écran (avant, `stop()`
        // repassait à Idle, ce qui refermait l'écran avant que le message ne s'affiche).
        if (!allowed()) { snapshot(); save(); generation++; job?.cancel(); job = null; _tracks.value = PlayerTracks.EMPTY; fail("Ce film est masqué.", false); return }
        pos = positionS.coerceAtLeast(0); if (durationS > 0) { dur = durationS; playerDur = durationS }
        if (playing) {
            played = true
            // Lecture stable depuis la dernière reconnexion : le crédit de reconnexions revient.
            val now = clock()
            if (recoveries > 0 && recoveredAt?.let { now - it >= RECOVERY_RESET_S.seconds } == true) recoveries = 0
            if (lastSave == null || now < lastSave!! || now - lastSave!! >= 30.seconds) save()
        } else if (wasPlaying) save()
        wasPlaying = playing
    }
    private fun snapshot() {
        val s = controller.active.value?.snapshot() ?: return
        if (s.ready && !s.failed) {
            pos = (s.positionMs.coerceAtLeast(0) / 1000).toInt()
            if (s.durationMs > 0) { dur = (s.durationMs / 1000).toInt(); playerDur = dur }
            if (s.playing) played = true
        }
    }
    fun onEnded() {
        if (_state.value !is MoviePlaybackState.Playing) return
        snapshot()
        // Fin loin de la durée connue : coupure du fournisseur, jamais « vu » ; on reprend où on était.
        // Plus de crédit : position réelle gardée, message avec « Réessayer » (jamais « vu »).
        if (isEarlyEnd(pos, playerDur)) {
            save()
            if (!recover()) { controller.close(); fail("Impossible de lire ce film. Vérifie la connexion puis réessaie.", true) }
            return
        }
        save(atEnd = true); controller.close(); _state.value = MoviePlaybackState.Finished
    }
    fun onFailed() {
        if (_state.value !is MoviePlaybackState.Playing) return
        snapshot(); save()
        if (recover()) return
        controller.close(); fail("Impossible de lire ce film. Vérifie la connexion puis réessaie.", true)
    }
    /** Même position exacte : une coupure à 95 % ne doit pas faire repartir le film du début. */
    fun retry() {
        val r = request ?: return
        // Position exacte seulement si CE film a vraiment été lu ; sinon la demande telle quelle.
        begin(if (movie != null && played) r.copy(startS = pos, restart = false, exact = true) else r)
    }
    /**
     * Coupure : rouvre le même film à la dernière position, au plus [MAX_SILENT_RECOVERIES] fois de suite, avec un
     * lecteur neuf (l'ancien est fermé d'abord). Si le film était en pause, il le reste. `false` quand le crédit est épuisé.
     */
    private fun recover(): Boolean {
        val m = movie ?: return false
        if (recoveries >= MAX_SILENT_RECOVERIES) return false
        val url = urlFor(m) ?: return false
        recoveries++
        recoveredAt = clock()
        val keepPaused = controller.active.value?.snapshot()?.let { !it.playing && !it.ended } == true
        appliedTracks = false; _tracks.value = PlayerTracks.EMPTY
        _state.value = MoviePlaybackState.Playing(m, reconnecting = true)
        controller.open(url, pos * 1000L, label = "Film · ${home.iptv.shared.versions.versionTitle(m.name)}")
        if (keepPaused) controller.active.value?.setPaused(true)
        return true
    }
    fun restart() { request?.copy(restart = true, startS = 0)?.let { begin(it) } }
    fun stop() {
        if (_state.value is MoviePlaybackState.Playing) { snapshot(); save() }
        generation++; job?.cancel(); job = null
        controller.close(); _tracks.value = PlayerTracks.EMPTY
        movie = null; _state.value = MoviePlaybackState.Idle
    }
    private fun allowed(): Boolean = hiddenCategories()?.let { hidden -> knownCategories.none { it in hidden } } == true
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
        // Langue audio et sous-titres : réglage de l'iPhone, sinon dernier choix retenu, sinon langues par défaut.
        val (langs, explicit) = languages()
        val choice = home.iptv.shared.versions.chooseTracks(langs, explicit, store.audioLang(profileId), store.subtitleLang(profileId),
            tracks.audio.map { trackLanguageCode(it.language) }, tracks.subtitles.map { trackLanguageCode(it.language) })
        choice.audio?.let { lang -> tracks.audio.firstOrNull { trackLanguageCode(it.language) == lang }?.let { player.selectAudio(it.id) } }
        when (val lang = choice.subtitles) {
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
