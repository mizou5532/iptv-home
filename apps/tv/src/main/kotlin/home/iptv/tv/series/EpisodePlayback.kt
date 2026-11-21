package home.iptv.tv.series

import home.iptv.shared.progress.ProgressEntry
import home.iptv.shared.progress.ProgressQueue
import home.iptv.shared.xtream.Episode
import home.iptv.shared.xtream.SeriesInfo
import home.iptv.tv.data.SUBTITLES_OFF
import home.iptv.tv.data.TrackPrefsStore
import home.iptv.tv.player.MAX_SILENT_RECOVERIES
import home.iptv.tv.player.PlayerController
import home.iptv.tv.player.RECOVERY_RESET_S
import home.iptv.tv.player.isEarlyEnd
import home.iptv.tv.player.PlayerTracks
import home.iptv.tv.player.TrackOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

sealed interface EpisodePlaybackState {
    data object Idle : EpisodePlaybackState
    /** La fiche de la série est en cours de lecture (rien n'est encore ouvert chez le fournisseur). */
    data object Loading : EpisodePlaybackState
    /** [reconnecting] : reprise automatique après une coupure (« Reconnexion… » au lieu de « Connexion… »). */
    data class Playing(val seriesName: String, val episode: Episode, val reconnecting: Boolean = false) : EpisodePlaybackState
    data class NextCountdown(val secondsLeft: Int, val next: Episode) : EpisodePlaybackState
    /** Fin de l'épisode sans enchaînement : dernier de la série ([seriesEnded]) ou enchaînement annulé. */
    data class Finished(val seriesEnded: Boolean) : EpisodePlaybackState
    /** Message en français, jamais d'adresse de flux ni d'identifiant. [canRetry] : « Réessayer » a un sens. */
    data class Failed(val message: String, val canRetry: Boolean) : EpisodePlaybackState
}

/**
 * Épisode suivant : le suivant de la même saison (numéro supérieur), sinon le premier de la saison d'après ; les
 * saisons sans épisode sont sautées. `null` après le dernier épisode de la dernière saison.
 */
fun nextEpisode(info: SeriesInfo, current: Episode): Episode? {
    val all = orderedEpisodes(info)
    val i = all.indexOfFirst { it.id == current.id }
    if (i >= 0) return all.getOrNull(i + 1)
    // Épisode absent de la fiche : le premier qui vient après lui dans l'ordre saison, numéro.
    return all.firstOrNull { it.season > current.season || (it.season == current.season && it.number > current.number) }
}

/**
 * Tous les épisodes à plat, dans l'ordre de lecture : saisons triées, épisodes dédupliqués par identifiant puis triés
 * de façon stable par numéro (deux « É5 » restent dans l'ordre du fournisseur). Source unique de « l'épisode suivant »
 * pour l'enchaînement et pour « Ensuite » de l'accueil.
 */
fun orderedEpisodes(info: SeriesInfo): List<Episode> =
    info.seasons.distinctBy { it.number }.sortedBy { it.number }
        .flatMap { it.episodes.distinctBy { e -> e.id }.sortedBy { e -> e.number } }

/** Reprise : la position enregistrée, sauf à 90 % ou à moins de 30 s de la fin (on repart alors de 0). */
fun resumePosition(positionS: Int?, durationS: Int?): Int {
    val p = positionS ?: return 0
    if (p <= 0) return 0
    val d = durationS ?: return p
    if (d <= 0) return p
    return if (p.toLong() * 100 >= d.toLong() * 90 || d - p <= 30) 0 else p
}

private val LANGUAGE_CODES = mapOf(
    "eng" to "en", "fre" to "fr", "fra" to "fr", "spa" to "es", "ger" to "de", "deu" to "de", "ita" to "it",
    "por" to "pt", "rus" to "ru", "ara" to "ar", "jpn" to "ja", "chi" to "zh", "zho" to "zh", "dut" to "nl",
    "nld" to "nl", "tur" to "tr", "pol" to "pl", "kor" to "ko", "hin" to "hi", "swe" to "sv", "nor" to "no",
    "dan" to "da", "fin" to "fi", "gre" to "el", "ell" to "el", "heb" to "he", "cze" to "cs", "ces" to "cs",
    "hun" to "hu", "rum" to "ro", "ron" to "ro", "ukr" to "uk",
)

/** Code de langue normalisé sur deux lettres quand on le connaît (« fre », « fra », « fr-CA » donnent « fr »), `null` si inconnue. */
fun trackLanguageCode(language: String?): String? {
    val base = language?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_').orEmpty()
    if (base.isEmpty() || base == "und" || base == "zxx") return null
    return LANGUAGE_CODES[base] ?: base
}

/** Titre d'une piste dans le panneau : langue (en français) et libellé du fichier, sans répétition. */
fun trackTitle(option: TrackOption, index: Int): String {
    val code = trackLanguageCode(option.language)
    val language = code?.let { Locale(it).getDisplayLanguage(Locale.FRENCH).takeIf { n -> n.isNotBlank() && n != it } }
        ?.replaceFirstChar { it.uppercase() }
    val label = option.label.trim().takeIf { it.isNotEmpty() }
    return when {
        language != null && label != null -> if (label.startsWith(language, ignoreCase = true)) label else "$language · $label"
        language != null -> language
        label != null -> label
        else -> "Piste ${index + 1}"
    }
}

/** Fabrique de la lecture d'épisodes d'un profil (ses préférences servent à « masqué gagne »). */
typealias EpisodePlaybackFactory = (home.iptv.shared.sync.Profile, StateFlow<home.iptv.shared.sync.ProfilePrefs?>) -> EpisodePlayback

private const val FAIL_PLAY = "Impossible de lire cet épisode. Vérifie la connexion internet de la télé, puis réessaie."

/**
 * Lecture d'un épisode : reprise, progression, épisode suivant, pistes. Un seul exemplaire par profil : tout ce qui
 * est enregistré l'est pour [profileId], même si l'écran change de profil juste après. Les méthodes publiques
 * s'appellent depuis le fil principal (le lecteur Media3 l'exige) ; l'envoi réseau passe par [flusher], hors du fil principal.
 *
 * - [loadInfo] : fiche de la série (cache du dépôt) ; [hiddenCategories] : catégories de séries masquées (`null` = préférences
 *   encore inconnues : rien n'est lu). Masqué gagne, à chaque lecture y compris l'enchaînement.
 * - [urlFor] : adresse de lecture (contient les identifiants : jamais journalisée ni affichée).
 */
class EpisodePlayback(
    private val profileId: String,
    private val controller: PlayerController,
    private val queue: ProgressQueue,
    private val flusher: ProgressFlusher,
    private val store: TrackPrefsStore,
    private val clock: () -> Instant,
    private val scope: CoroutineScope,
    private val urlFor: (Episode) -> String?,
    private val loadInfo: suspend (seriesId: String, categoryId: String?) -> SeriesInfo?,
    private val hiddenCategories: () -> Set<String>?,
    private val io: CoroutineDispatcher,
    private val countdownSeconds: Int = 10,
    private val saveEveryS: Int = 30,
    private val cachedCategory: (seriesId: String) -> String? = { null },
    private val coverFor: (String?) -> String? = { it },
    /** Langues du profil (page iPhone) et si elles ont été réglées ; défaut : Québec, France, anglais, sous-titres anglais. */
    private val languages: () -> Pair<home.iptv.shared.versions.LanguagePrefs, Boolean> = { home.iptv.shared.versions.LanguagePrefs() to false },
) {
    private val _state = MutableStateFlow<EpisodePlaybackState>(EpisodePlaybackState.Idle)
    val state: StateFlow<EpisodePlaybackState> = _state

    init {
        // Tous les comptes IPTV pris (ou compte perdu) : message avec « Réessayer » au lieu d'un « Connexion… » sans fin.
        controller.onRefused("episode") { message ->
            if (_state.value is EpisodePlaybackState.Playing) { save(); _state.value = EpisodePlaybackState.Failed(message, canRetry = true) }
        }
    }

    private val _tracks = MutableStateFlow(PlayerTracks.EMPTY)
    /** Pistes du flux en cours (panneau « Audio et sous-titres »). */
    val tracks: StateFlow<PlayerTracks> = _tracks

    private var series: SeriesInfo? = null
    private var current: Episode? = null
    private var category: String? = null
    private var lastRequest: PlayEpisodeRequest? = null
    private var lastPosS = 0
    private var lastDurS = 0
    private var lastSaveAt: Instant? = null
    private var wasPlaying = false
    /** Vrai dès qu'une seconde de lecture a eu lieu : sans lecture réelle, rien n'est enregistré (une reprise ouverte puis quittée ne touche pas la position). */
    private var played = false
    private var tracksApplied = false
    private var generation = 0
    private var loadJob: Job? = null
    private var countdownJob: Job? = null
    /** Reconnexions silencieuses faites pour l'épisode en cours, et position d'où est partie la dernière. */
    private var recoveries = 0
    private var recoveredAt: Instant? = null
    /** Durée annoncée par le lecteur (fichier), 0 tant qu'inconnue : seule base fiable pour reconnaître une coupure. */
    private var playerDurS = 0

    /** Position courante (en secondes), utile pour reprendre au même endroit sur une autre version. */
    fun currentPositionS(): Int = lastPosS

    /**
     * Cible exacte (saison, numéro d'épisode, position en s) pour « Essayer une autre version » : suit l'épisode
     * réellement en cours (y compris après un enchaînement automatique) et n'hérite jamais d'une série précédente.
     */
    fun resumeTarget(fallback: PlayEpisodeRequest): Triple<Int, Int, Int> {
        val e = current
        return if (e != null) Triple(e.season, e.number, lastPosS.coerceAtLeast(0))
        else Triple(fallback.season, fallback.number, if (fallback.restart) 0 else fallback.startS.coerceAtLeast(0))
    }

    /** Demande venue d'un écran : ferme tout lecteur, refuse si masquée, lit la fiche puis démarre. */
    fun begin(request: PlayEpisodeRequest) {
        val gen = beginNewPlayback()
        recoveries = 0
        lastRequest = request
        series = null; current = null
        lastPosS = 0; lastDurS = 0; playerDurS = 0; played = false; wasPlaying = false
        controller.close()
        _tracks.value = PlayerTracks.EMPTY
        val hidden = hiddenCategories() ?: return fail(PREFS_UNKNOWN, retry = true)
        if (request.categoryId != null && request.categoryId in hidden) return fail(MASKED, retry = false)
        _state.value = EpisodePlaybackState.Loading
        loadJob = scope.launch {
            val (info, masked) = try {
                withContext(io) {
                    // Catégorie inconnue de la demande : le cache local peut la connaître (masqué gagne, sans réseau).
                    val known = request.categoryId ?: cachedCategory(request.seriesId)
                    if (known != null && known in hidden) null to true else loadInfo(request.seriesId, known) to false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null to false
            }
            if (gen != generation) return@launch
            val allEpisodes = info?.seasons.orEmpty().flatMap { it.episodes }
            // Changement de version : l'identifiant d'épisode change d'une version à l'autre → on retrouve par saison et numéro.
            val episode = allEpisodes.firstOrNull { request.episodeId.isNotEmpty() && it.id == request.episodeId }
                ?: allEpisodes.firstOrNull { it.season == request.season && it.number == request.number }
            when {
                masked -> fail(MASKED, retry = false)
                info == null -> fail(NO_INFO, retry = true)
                episode == null -> fail(
                    if (request.episodeId.isEmpty()) "L'épisode S${request.season} É${request.number} n'existe pas dans cette version."
                    else "Cet épisode n'existe plus chez le fournisseur.",
                    retry = false,
                )
                else -> start(info, episode, request.startS.takeIf { !request.restart }, request.categoryId)
            }
        }
    }

    /**
     * Ouvre [episode] (fermeture du lecteur précédent d'abord). [resumeAtS] : position connue, corrigée par
     * [resumePosition] ; [recovering] : reconnexion automatique ; [exact] : la position est reprise telle quelle, même
     * près de la fin (reconnexion, « Réessayer » après une coupure).
     */
    fun start(
        series: SeriesInfo, episode: Episode, resumeAtS: Int?, categoryId: String? = null,
        recovering: Boolean = false, exact: Boolean = recovering,
    ) {
        if (_state.value is EpisodePlaybackState.Playing) save()
        if (!recovering) recoveries = 0
        beginNewPlayback()
        this.series = series
        this.current = episode
        // Une erreur d'ouverture ne doit pas laisser la position de l'épisode précédent : « Réessayer » repart d'ici.
        lastPosS = resumeAtS ?: 0
        played = false
        this.category = categoryId ?: series.summary.categoryId
        val hidden = hiddenCategories() ?: return fail(PREFS_UNKNOWN, retry = true)
        if (listOfNotNull(categoryId, series.summary.categoryId).any { it in hidden }) return fail(MASKED, retry = false)
        val url = urlFor(episode) ?: return fail(FAIL_PLAY, retry = true)
        val startS = if (exact) (resumeAtS ?: 0).coerceAtLeast(0) else resumePosition(resumeAtS, episode.durationS)
        lastPosS = startS
        lastDurS = episode.durationS ?: 0
        // Même fichier rouvert après une coupure : sa durée reste connue (une nouvelle coupure immédiate en est une).
        if (!recovering) playerDurS = 0 else if (playerDurS > 0) lastDurS = playerDurS
        lastSaveAt = clock()
        wasPlaying = false
        played = false
        tracksApplied = false
        _tracks.value = PlayerTracks.EMPTY
        _state.value = EpisodePlaybackState.Playing(series.summary.name, episode, reconnecting = recovering)
        controller.open(url, startS * 1000L, label = "Série · ${home.iptv.shared.versions.versionTitle(series.summary.name)} · S${episode.season} É${episode.number}")
    }

    /**
     * Appelé environ chaque seconde pendant la lecture. [playing] faux = en pause : la position est enregistrée au
     * passage en pause, puis à chaque reprise de 30 s de lecture.
     */
    fun onTick(posS: Int, durS: Int, playing: Boolean = true) {
        if (_state.value !is EpisodePlaybackState.Playing) return
        lastPosS = posS
        if (durS > 0) { lastDurS = durS; playerDurS = durS }
        if (!playing) {
            if (wasPlaying) save()
            wasPlaying = false
            return
        }
        wasPlaying = true
        played = true
        // Lecture repartie et stable depuis la dernière reconnexion : on retrouve tout le crédit de reconnexions.
        val now = clock()
        if (recoveries > 0 && recoveredAt?.let { now - it >= RECOVERY_RESET_S.seconds } == true) recoveries = 0
        val last = lastSaveAt
        if (last == null || now < last || now - last >= saveEveryS.seconds) save()
    }

    /** Fin du flux : position = durée (donc « vu »), puis décompte vers l'épisode suivant, ou fin de la série. */
    fun onEnded() {
        val s = _state.value as? EpisodePlaybackState.Playing ?: return
        val info = series ?: return
        // Fin loin de la durée du fichier (annoncée par le lecteur, pas par le fournisseur) : le fournisseur a coupé.
        // Jamais « vu » ni épisode suivant : on reprend où on était ; plus de crédit, message avec « Réessayer ».
        if (isEarlyEnd(lastPosS, playerDurS)) {
            if (recover()) return
            save()
            controller.close()
            _state.value = EpisodePlaybackState.Failed(FAIL_PLAY, canRetry = true)
            return
        }
        save(atEnd = true)
        val next = nextEpisode(info, s.episode)
        if (next == null) {
            controller.close()
            _state.value = EpisodePlaybackState.Finished(seriesEnded = true)
            return
        }
        countdownJob?.cancel()
        _state.value = EpisodePlaybackState.NextCountdown(countdownSeconds, next)
        countdownJob = scope.launch {
            for (left in countdownSeconds downTo 1) {
                _state.value = EpisodePlaybackState.NextCountdown(left, next)
                delay(1_000)
            }
            countdownJob = null // le décompte est fini : l'ouverture ne s'annule pas elle-même
            start(info, next, 0, category)
        }
    }

    /** Le lecteur a signalé une erreur : la position est gardée, le lecteur libéré, « Réessayer » reprend au même point. */
    fun onFailed() {
        if (_state.value !is EpisodePlaybackState.Playing) return
        save()
        if (recover()) return
        controller.close()
        _state.value = EpisodePlaybackState.Failed(FAIL_PLAY, canRetry = true)
    }

    fun retry() {
        val s = series
        val e = current
        // Même position exacte : une coupure à 95 % ne doit pas faire repartir l'épisode du début.
        if (s != null && e != null) start(s, e, lastPosS, category, exact = true) else lastRequest?.let { begin(it) }
    }

    /** « Annuler » pendant le décompte : on n'enchaîne pas. */
    fun cancelNext() {
        if (_state.value !is EpisodePlaybackState.NextCountdown) return
        countdownJob?.cancel(); countdownJob = null
        controller.close()
        _state.value = EpisodePlaybackState.Finished(seriesEnded = false)
    }

    /** « Lire maintenant » pendant le décompte. */
    fun playNextNow() {
        val s = _state.value as? EpisodePlaybackState.NextCountdown ?: return
        val info = series ?: return
        countdownJob?.cancel(); countdownJob = null
        start(info, s.next, 0, category)
    }

    /**
     * Sortie, onStop, changement de profil : la position est enregistrée pour ce profil, le lecteur fermé, le décompte et
     * toute ouverture en cours annulés. Rien ne reprend ensuite.
     */
    fun stop() {
        val s = _state.value
        if (s is EpisodePlaybackState.Playing) save()
        beginNewPlayback()
        controller.close()
        _tracks.value = PlayerTracks.EMPTY
        series = null; current = null; lastRequest = null
        lastPosS = 0; lastDurS = 0; playerDurS = 0; played = false; wasPlaying = false
        _state.value = EpisodePlaybackState.Idle
    }

    /** Les pistes du flux sont connues : la langue retenue pour ce profil est appliquée une seule fois par épisode. */
    fun onTracks(t: PlayerTracks) {
        if (_state.value !is EpisodePlaybackState.Playing) return
        _tracks.value = t
        if (tracksApplied || (t.audio.isEmpty() && t.subtitles.isEmpty())) return
        tracksApplied = true
        val player = controller.active.value ?: return
        applyTracks(t, player)
    }

    /** Langue audio et sous-titres : réglage de l'iPhone, sinon dernier choix retenu, sinon langues par défaut. */
    private fun applyTracks(t: PlayerTracks, player: home.iptv.tv.player.Player) {
        val (langs, explicit) = languages()
        val choice = home.iptv.shared.versions.chooseTracks(langs, explicit, store.audioLang(profileId), store.subtitleLang(profileId),
            t.audio.map { trackLanguageCode(it.language) }, t.subtitles.map { trackLanguageCode(it.language) })
        choice.audio?.let { lang ->
            t.audio.firstOrNull { trackLanguageCode(it.language) == lang }?.takeIf { !it.selected }?.let { player.selectAudio(it.id) }
        }
        when (val sub = choice.subtitles) {
            null -> {}
            SUBTITLES_OFF -> player.selectSubtitle(null)
            else -> t.subtitles.firstOrNull { trackLanguageCode(it.language) == sub }?.takeIf { !it.selected }
                ?.let { player.selectSubtitle(it.id) }
        }
    }

    fun chooseAudio(option: TrackOption) {
        controller.active.value?.selectAudio(option.id)
        val code = trackLanguageCode(option.language) ?: return // sans langue : choix valable pour ce film seulement
        store.saveTrackPrefs(profileId, code, store.subtitleLang(profileId))
    }

    /** `null` = « Aucun ». */
    fun chooseSubtitle(option: TrackOption?) {
        controller.active.value?.selectSubtitle(option?.id)
        val code = if (option == null) SUBTITLES_OFF else trackLanguageCode(option.language) ?: return
        store.saveTrackPrefs(profileId, store.audioLang(profileId), code)
    }

    /**
     * Coupure (erreur ou fin prématurée) : rouvre le même épisode à la dernière position, au plus
     * [MAX_SILENT_RECOVERIES] fois de suite. `false` quand le crédit est épuisé (l'appelant affiche alors son message).
     */
    private fun recover(): Boolean {
        val s = series ?: return false
        val e = current ?: return false
        if (recoveries >= MAX_SILENT_RECOVERIES) return false
        recoveries++
        recoveredAt = clock()
        // Un épisode en pause le reste (coupure pendant une longue pause : rien ne repart tout seul).
        val keepPaused = controller.active.value?.snapshot()?.let { !it.playing && !it.ended } == true
        // `start` enregistre et repart de cette position exacte, avec un lecteur neuf (l'ancien est fermé avant).
        start(s, e, lastPosS, category, recovering = true)
        if (keepPaused) controller.active.value?.setPaused(true)
        // Tentée : si `start` a refusé (masquée, réglages inconnus), son message précis est déjà affiché.
        return true
    }

    /** Annule toute ouverture ou décompte en cours ; une réponse tardive d'une demande plus ancienne est ignorée. */
    private fun beginNewPlayback(): Int {
        loadJob?.cancel(); loadJob = null
        countdownJob?.cancel(); countdownJob = null
        return ++generation
    }

    private fun fail(message: String, retry: Boolean) {
        controller.close()
        _state.value = EpisodePlaybackState.Failed(message, retry)
    }

    /** Met la position dans la file locale du profil (le plus récent gagne) et demande un envoi. Jamais une position à 0. */
    private fun save(atEnd: Boolean = false) {
        val s = series ?: return
        val e = current ?: return
        val duration = lastDurS.takeIf { it > 0 } ?: e.durationS ?: 0
        if (!atEnd && !played) return
        val position = if (atEnd && duration > 0) duration else if (duration > 0) lastPosS.coerceAtMost(duration) else lastPosS
        if (position <= 0) return
        queue.enqueue(
            profileId,
            ProgressEntry(
                itemId = e.id, seriesId = s.summary.id, season = e.season, episode = e.number,
                positionS = position, durationS = duration, clientUpdatedAt = clock(),
                seriesName = s.summary.name, seriesCover = coverFor(s.summary.cover), seriesCategory = category,
            ),
        )
        lastSaveAt = clock()
        flusher.request()
    }

    private companion object {
        const val MASKED = "Cette série est masquée : lecture impossible."
        const val PREFS_UNKNOWN = "Les réglages du profil ne sont pas encore chargés. Réessaie dans un instant."
        const val NO_INFO = "Impossible de charger la série. Vérifie la connexion internet de la télé, puis réessaie."
    }
}
