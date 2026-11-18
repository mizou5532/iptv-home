package home.iptv.tv.live

import home.iptv.shared.groups.LiveGroup
import home.iptv.tv.player.PlayerController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Une copie qui n'a donné aucune image après ce délai est considérée en panne (bascule automatique). */
const val START_TIMEOUT_MS = 12_000L

/** Une copie qui se fige de nouveau moins de 60 s après sa reconnexion n'est pas rouverte : on bascule. */
const val RECONNECT_WINDOW_MS = 60_000L

/** Résultat de la résolution d'une copie à lire. */
sealed interface Resolved {
    data class Ready(val sourceId: String, val url: String) : Resolved {
        /** L'adresse contient les identifiants : jamais dans un journal ni un message. */
        override fun toString() = "Ready(sourceId=$sourceId)"
    }
    data class Refused(val reason: Refusal) : Resolved
}

/**
 * Exécute les effets du réducteur [reduce] à travers le [PlayerController] existant (seul propriétaire du lecteur).
 * Un nouvel effet de lecture ferme d'abord le flux courant, puis ouvre le nouveau : une seule connexion chez le
 * fournisseur. Passer de l'aperçu au plein écran ne produit aucun effet, donc aucune reconnexion.
 *
 * - [resolve] : copie concrète à lire (masqué gagne, dernière bonne copie encore visible) et son adresse.
 * - [rowsOf] : lignes visibles d'une section dans l'ordre affiché, `null` si elle a disparu.
 * - [fallbackKey] : section de repli (favoris sinon première catégorie).
 * - [groupOf] : groupe visible actuel d'une copie, `null` si elle est masquée ou retirée.
 */
class LivePlaybackHost(
    private val player: PlayerController,
    private val scope: CoroutineScope,
    private val resolve: suspend (row: GroupRow, sourceId: String?) -> Resolved,
    private val rowsOf: suspend (sectionKey: String) -> List<GroupRow>?,
    private val fallbackKey: () -> String?,
    private val groupOf: suspend (sourceId: String) -> LiveGroup?,
    /** Zapping haut/bas : attente d'une sélection stable avant d'ouvrir le flux. */
    private val zapDebounceMs: Long = 250,
    /** Horloge (ms) : une copie qui se fige de nouveau peu après sa reconnexion bascule directement. */
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    /** Fiabilité des copies : ordre des copies essayées après une coupure, et bilan de chaque coupure. */
    private val reliability: CopyReliability? = null,
) {
    private val _state = MutableStateFlow(LivePlaybackState())
    val state: StateFlow<LivePlaybackState> = _state

    private val known = HashMap<String, GroupRow>()
    private var playJob: Job? = null
    private var zapping = false
    private val moves = Mutex()
    /** Adresse de la copie ouverte (pour la rouvrir telle quelle) ; jamais journalisée. */
    private var opened: Pair<String, String>? = null
    /** Dernière reconnexion par copie (ms), pour la lecture en cours. */
    private val reconnectedAt = HashMap<String, Long>()

    fun focus(rowId: String) = dispatch(LiveEvent.Focus(rowId))

    /** OK sur une ligne de la section [sectionKey]. */
    fun ok(row: GroupRow, sectionKey: String) {
        known[row.group.id] = row
        dispatch(LiveEvent.Ok(RowRef(row.group.id, row.group.title), sectionKey))
    }

    /** OK en plein écran : affiche les commandes. */
    fun fullscreenOk() = dispatch(LiveEvent.FullscreenOk)
    fun hideControls() = dispatch(LiveEvent.HideControls)
    fun focusRestored() = dispatch(LiveEvent.FocusRestored)

    /** Retour ; `true` si l'état a changé (sinon l'appelant applique le Retour habituel de la liste). */
    fun back(): Boolean {
        val before = _state.value
        dispatch(LiveEvent.Back)
        return _state.value.copy(effect = null) != before.copy(effect = null)
    }

    /** Haut (-1) / bas (+1) en plein écran ; les appuis rapprochés sont traités dans l'ordre. */
    fun step(delta: Int) {
        scope.launch {
            moves.withLock {
                val s = _state.value
                if (s.mode != LiveMode.Fullscreen) return@withLock
                // Les lignes de séparation du fournisseur (« ##### … ##### ») ne sont pas des chaînes : on les saute.
                val rows = originRows(s)?.filterNot { isSeparator(it.group.title) } ?: return@withLock
                rows.forEach { known[it.group.id] = it }
                zapping = true
                try {
                    dispatch(LiveEvent.Step(delta, rows.map { RowRef(it.group.id, it.group.title) }))
                } finally { zapping = false }
            }
        }
    }

    private suspend fun originRows(s: LivePlaybackState): List<GroupRow>? {
        val origin = s.originSectionKey
        rowsOf(origin ?: return null)?.let { return it }
        // Section disparue : favoris s'il y en a, sinon première catégorie.
        val fallback = fallbackKey() ?: return null
        dispatch(LiveEvent.OriginChanged(fallback))
        return rowsOf(fallback)
    }

    fun openSources() = dispatch(LiveEvent.OpenSources)
    fun closeSources() = dispatch(LiveEvent.CloseSources)
    fun chooseSource(sourceId: String) = dispatch(LiveEvent.ChooseSource(sourceId))
    fun stop() = dispatch(LiveEvent.Stop)

    /** Erreur de lecture signalée par le lecteur : bascule vers une autre copie, ou échec si toutes ont été essayées. */
    fun playFailed() = copyFailed(_state.value.playingSourceId)

    /**
     * La copie [sourceId] ne répond pas (erreur du lecteur, ou aucune image après [START_TIMEOUT_MS]) : on essaie la
     * copie suivante du même groupe, chacune une seule fois, une connexion à la fois. Plus aucune : échec (OK relance).
     * Sans effet si la lecture a changé entre-temps (autre chaîne, autre copie, Arrêter).
     */
    fun copyFailed(sourceId: String?) {
        if (sourceId == null) return
        scope.launch {
            moves.withLock {
                val s = _state.value
                if (s.playingSourceId != sourceId || s.failed || s.playingGroupId == null) return@withLock
                val copies = try { groupOf(sourceId)?.sources?.map { it.id } } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                    ?: listOf(sourceId)
                if (_state.value.playingSourceId != sourceId) return@withLock
                val tried = s.tried + sourceId
                reliability?.failed(sourceId)
                // Copie suivante : la plus fiable des copies pas encore essayées (ordre du fournisseur à égalité).
                val left = copies.filter { it !in tried }
                val next = (reliability?.rank(left) ?: left).firstOrNull()
                if (next == null) dispatch(LiveEvent.PlayFailed)
                else dispatch(LiveEvent.Failover(next, tried, tried.size + 1, maxOf(copies.size, tried.size + 1)))
            }
        }
    }

    /**
     * La copie [sourceId] a démarré puis s'est figée (le lecteur attend des données depuis [home.iptv.tv.player.LIVE_STALL_MS]) :
     * on la rouvre une fois (« Reconnexion… »), une seule connexion à la fois. Figée de nouveau moins de
     * [RECONNECT_WINDOW_MS] après : bascule vers une autre copie comme pour une erreur. Sans effet si la lecture a changé.
     */
    fun stalled(sourceId: String) {
        scope.launch {
            var failover = false
            moves.withLock {
                val s = _state.value
                if (s.playingSourceId != sourceId || s.failed) return@withLock
                val url = opened?.takeIf { it.first == sourceId }?.second
                val now = clockMs()
                val last = reconnectedAt[sourceId]
                if (url == null || (last != null && now - last < RECONNECT_WINDOW_MS)) { failover = true; return@withLock }
                reconnectedAt[sourceId] = now
                reliability?.stalled(sourceId)
                dispatch(LiveEvent.Reconnecting)
                player.open(url, label = known[s.playingGroupId]?.group?.title?.let { "Direct · $it" } ?: "Direct")
            }
            if (failover) copyFailed(sourceId)
        }
    }

    /** La copie en lecture donne une image. */
    fun playing() = dispatch(LiveEvent.Playing)

    /** Quitter le direct ou changer de profil : le flux est fermé quoi qu'il arrive (sans effet si déjà fermé). */
    fun shutdown() = suspend()

    /** onStop : arrêt sans condition, une résolution en cours ne doit jamais rouvrir le flux en arrière-plan. */
    fun suspend() {
        dispatch(LiveEvent.Stop)
        playJob?.cancel(); playJob = null
        opened = null
        player.close()
    }

    /** Le lecteur a été libéré de l'extérieur alors qu'une lecture était ouverte. */
    fun playerReleased() = dispatch(LiveEvent.PlayerReleased)

    /** La section d'origine a disparu pendant la navigation. */
    fun originGone() { fallbackKey()?.let { dispatch(LiveEvent.OriginChanged(it)) } }

    /**
     * À rappeler quand les groupes changent : si la copie en lecture est masquée ou retirée, la lecture s'arrête
     * (jamais de changement de source automatique) ; si seul son groupe a changé, la lecture continue.
     */
    suspend fun syncPlaying() {
        val s = _state.value
        val source = s.playingSourceId ?: return
        val group = groupOf(source)
        // L'état a pu changer pendant la vérification (autre chaîne lancée) : on ne touche à rien.
        if (_state.value.playingSourceId != source) return
        if (group == null) {
            dispatch(LiveEvent.SourceHidden)
        } else if (group.id != s.playingGroupId) {
            val row = known[s.playingGroupId]?.let { GroupRow(group, it.anchorSourceId) }
            if (row != null) known[group.id] = row
            dispatch(LiveEvent.Regrouped(RowRef(group.id, group.title)))
        } else {
            // Meme groupe, copies modifiees (ajout depuis l'iPhone) : « Sources » doit choisir parmi les copies actuelles.
            known[group.id]?.takeIf { it.group != group }?.let { known[group.id] = GroupRow(group, it.anchorSourceId) }
        }
    }

    private fun dispatch(event: LiveEvent) {
        val next = reduce(_state.value, event)
        _state.value = next
        when (val fx = next.effect) {
            is LiveEffect.Play -> play(fx)
            LiveEffect.Stop -> { playJob?.cancel(); playJob = null; opened = null; reconnectedAt.clear(); player.close() }
            null -> {}
        }
    }

    private fun play(fx: LiveEffect.Play) {
        playJob?.cancel()
        // Fermeture avant remplacement : jamais deux flux, même le temps de résoudre la copie.
        opened = null
        reconnectedAt.clear()
        player.close()
        val row = known[fx.groupId] ?: return dispatch(LiveEvent.Refused(fx.groupId, Refusal.Unavailable))
        val wait = if (zapping) zapDebounceMs else 0L
        playJob = scope.launch {
            if (wait > 0) delay(wait)
            val resolved = try {
                resolve(row, fx.sourceId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Resolved.Refused(Refusal.Unavailable)
            }
            when (resolved) {
                is Resolved.Refused -> dispatch(LiveEvent.Refused(fx.groupId, resolved.reason))
                is Resolved.Ready -> {
                    // Remplacée entre-temps (autre OK, Arrêter) : cette copie ne doit jamais s'ouvrir.
                    if (_state.value.playingGroupId != fx.groupId) return@launch
                    player.open(resolved.url, label = "Direct · ${row.group.title}")
                    opened = resolved.sourceId to resolved.url
                    dispatch(LiveEvent.Started(fx.groupId, resolved.sourceId))
                }
            }
        }
    }
}
