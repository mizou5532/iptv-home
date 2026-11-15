package home.iptv.tv.live

/** Écran affiché : la liste avec l'aperçu, ou la vidéo en plein écran. */
enum class LiveMode { Browse, Fullscreen }

/** Panneau ouvert par-dessus l'écran ; Retour le ferme avant toute autre chose. */
enum class LivePanel { None, Sources }

/** Pourquoi une lecture demandée n'a pas pu démarrer. */
enum class Refusal { Unavailable, NoCredentials }

/** Une ligne de groupe telle que la voit l'état : identifiant du groupe et titre affiché. */
data class RowRef(val id: String, val title: String)

/** Ce que l'hôte doit exécuter après une transition ; le réducteur ne touche jamais au lecteur. */
sealed interface LiveEffect {
    /** Fermer le flux courant puis ouvrir ce groupe (copie [sourceId] si choisie, sinon la meilleure disponible). */
    data class Play(val groupId: String, val sourceId: String? = null) : LiveEffect

    /** Fermer le flux courant. */
    data object Stop : LiveEffect
}

sealed interface LiveEvent {
    /** Le focus passe sur une ligne : jamais de lecture. */
    data class Focus(val rowId: String) : LiveEvent
    /** OK sur une ligne de la liste, depuis la section [sectionKey]. */
    data class Ok(val row: RowRef, val sectionKey: String) : LiveEvent
    /** OK en plein écran : affiche les commandes. */
    data object FullscreenOk : LiveEvent
    data object HideControls : LiveEvent
    data object Back : LiveEvent
    /** La liste a rendu le focus à la ligne [LivePlaybackState.returnFocusRowId]. */
    data object FocusRestored : LiveEvent
    /** Haut (-1) / bas (+1) en plein écran dans l'ordre visible de la section d'origine. */
    data class Step(val delta: Int, val ordered: List<RowRef>) : LiveEvent
    data object OpenSources : LiveEvent
    data object CloseSources : LiveEvent
    data class ChooseSource(val sourceId: String) : LiveEvent
    data object Stop : LiveEvent
    /** Le lecteur signale une erreur de lecture sur la copie ouverte. */
    data object PlayFailed : LiveEvent
    /** L'hôte a ouvert le flux de cette copie. */
    data class Started(val groupId: String, val sourceId: String) : LiveEvent
    data class Refused(val groupId: String, val reason: Refusal) : LiveEvent
    /** Le lecteur a été libéré de l'extérieur (app en arrière-plan). */
    data object PlayerReleased : LiveEvent
    /** La copie en lecture n'est plus visible : masqué gagne. */
    data object SourceHidden : LiveEvent
    /** Le groupe de la copie en lecture a changé d'identifiant (correction) : la lecture continue. */
    data class Regrouped(val row: RowRef) : LiveEvent
    /** La section d'origine a disparu. */
    data class OriginChanged(val sectionKey: String) : LiveEvent
}

/**
 * Navigation du direct, pure : aucun lecteur, aucune coroutine. [effect] est l'effet demandé par la transition qui
 * a produit cet état ; l'hôte l'exécute, et les transitions suivantes le remettent à `null`.
 */
data class LivePlaybackState(
    val mode: LiveMode = LiveMode.Browse,
    /** Groupe parcouru (focus). */
    val selectedRowId: String? = null,
    val playingGroupId: String? = null,
    /** Copie dont le flux est ouvert ; `null` tant que la lecture démarre. */
    val playingSourceId: String? = null,
    val playingTitle: String? = null,
    val originSectionKey: String? = null,
    val panel: LivePanel = LivePanel.None,
    val controlsVisible: Boolean = false,
    /** La copie ouverte a échoué : OK ou le choix de cette copie la relance. */
    val failed: Boolean = false,
    /** Ligne à refocaliser dans la liste après un retour du plein écran, jusqu'à [LiveEvent.FocusRestored]. */
    val returnFocusRowId: String? = null,
    val notice: String? = null,
    val effect: LiveEffect? = null,
)

const val NOTICE_HIDDEN = "La source en lecture n'est plus disponible : la lecture est arrêtée."
const val NOTICE_UNAVAILABLE = "Cette chaîne n'est plus disponible."
const val NOTICE_NO_CREDENTIALS = "Identifiants IPTV manquants — ouvre la page de configuration."

private fun LivePlaybackState.idle(notice: String? = null, effect: LiveEffect? = null) = copy(
    mode = LiveMode.Browse, playingGroupId = null, playingSourceId = null, playingTitle = null,
    panel = LivePanel.None, controlsVisible = false, failed = false, notice = notice, effect = effect,
    returnFocusRowId = if (mode == LiveMode.Fullscreen) playingGroupId ?: selectedRowId else returnFocusRowId,
)

private fun LivePlaybackState.startPlaying(row: RowRef, fx: LiveEffect.Play, mode: LiveMode) = copy(
    mode = mode, selectedRowId = row.id, playingGroupId = row.id, playingSourceId = null, playingTitle = row.title,
    panel = LivePanel.None, controlsVisible = false, failed = false, notice = null, effect = fx,
)

/**
 * Transition pure. Règles : le focus ne lance rien ; OK sur le groupe en lecture passe en plein écran sans nouvelle
 * lecture ; OK ailleurs remplace la lecture ; Retour ferme le panneau, puis les commandes, puis le plein écran ;
 * haut/bas suit la section d'origine sans boucler ; masqué ou refusé arrête sans jamais changer de source.
 */
fun reduce(state: LivePlaybackState, event: LiveEvent): LivePlaybackState {
    val s = state.copy(effect = null)
    return when (event) {
        is LiveEvent.Focus -> s.copy(selectedRowId = event.rowId)
        is LiveEvent.Ok ->
            if (s.mode == LiveMode.Browse && s.playingGroupId == event.row.id && s.playingSourceId != null && s.failed)
                s.copy(originSectionKey = event.sectionKey).startPlaying(event.row, LiveEffect.Play(event.row.id, s.playingSourceId), LiveMode.Browse)
            else if (s.mode == LiveMode.Browse && s.playingGroupId == event.row.id && s.playingSourceId != null)
                s.copy(mode = LiveMode.Fullscreen, selectedRowId = event.row.id, originSectionKey = event.sectionKey, panel = LivePanel.None, notice = null, returnFocusRowId = null)
            else if (s.playingGroupId == event.row.id && s.mode == LiveMode.Browse) s // démarrage en cours : pas de second flux
            else s.copy(originSectionKey = event.sectionKey).startPlaying(event.row, LiveEffect.Play(event.row.id), LiveMode.Browse)
        LiveEvent.FullscreenOk -> when {
            s.mode != LiveMode.Fullscreen -> s
            // Erreur de lecture : OK relance la même copie (Retour mène à la liste et aux Sources).
            s.failed && s.playingGroupId != null && s.playingSourceId != null ->
                s.startPlaying(RowRef(s.playingGroupId, s.playingTitle ?: ""), LiveEffect.Play(s.playingGroupId, s.playingSourceId), LiveMode.Fullscreen)
            else -> s.copy(controlsVisible = true)
        }
        LiveEvent.HideControls -> s.copy(controlsVisible = false)
        LiveEvent.Back -> when {
            s.panel != LivePanel.None -> s.copy(panel = LivePanel.None)
            s.controlsVisible -> s.copy(controlsVisible = false)
            s.mode == LiveMode.Fullscreen -> s.copy(mode = LiveMode.Browse, returnFocusRowId = s.playingGroupId ?: s.selectedRowId)
            else -> s
        }
        LiveEvent.FocusRestored -> s.copy(returnFocusRowId = null)
        is LiveEvent.Step -> {
            if (s.mode != LiveMode.Fullscreen || s.panel != LivePanel.None || s.playingGroupId == null) return s
            val index = event.ordered.indexOfFirst { it.id == s.playingGroupId }
            val target = when {
                event.ordered.isEmpty() -> null
                index >= 0 -> event.ordered.getOrNull(index + event.delta)
                event.delta > 0 -> event.ordered.first()
                else -> event.ordered.last()
            }
            if (target == null) s else s.startPlaying(target, LiveEffect.Play(target.id), LiveMode.Fullscreen)
        }
        // Les commandes du plein écran se referment : rien de focalisable ne reste sous le panneau.
        LiveEvent.OpenSources -> if (s.playingGroupId != null) s.copy(panel = LivePanel.Sources, controlsVisible = false) else s
        LiveEvent.CloseSources -> s.copy(panel = LivePanel.None)
        is LiveEvent.ChooseSource -> {
            val group = s.playingGroupId
            when {
                group == null -> s
                event.sourceId == s.playingSourceId && !s.failed -> s.copy(panel = LivePanel.None)
                else -> s.copy(panel = LivePanel.None, controlsVisible = false, failed = false, playingSourceId = null, notice = null, effect = LiveEffect.Play(group, event.sourceId))
            }
        }
        LiveEvent.Stop -> if (s.playingGroupId == null) s.copy(panel = LivePanel.None, mode = LiveMode.Browse)
            // Le bouton Arrêter disparaît : le focus revient sur la ligne courante de la liste.
            else s.idle(effect = LiveEffect.Stop).let { if (s.mode == LiveMode.Browse) it.copy(returnFocusRowId = s.selectedRowId) else it }
        LiveEvent.PlayFailed -> if (s.playingSourceId != null) s.copy(failed = true) else s
        is LiveEvent.Started ->
            if (s.playingGroupId == event.groupId && s.playingSourceId == null) s.copy(playingSourceId = event.sourceId, failed = false) else s
        is LiveEvent.Refused ->
            if (s.playingGroupId != event.groupId) s
            else s.idle(if (event.reason == Refusal.NoCredentials) NOTICE_NO_CREDENTIALS else NOTICE_UNAVAILABLE, LiveEffect.Stop)
        LiveEvent.PlayerReleased -> if (s.playingGroupId == null) s else s.idle()
        LiveEvent.SourceHidden -> if (s.playingGroupId == null) s else s.idle(NOTICE_HIDDEN, LiveEffect.Stop)
        is LiveEvent.Regrouped ->
            if (s.playingGroupId == null) s
            else s.copy(playingGroupId = event.row.id, playingTitle = event.row.title,
                selectedRowId = if (s.selectedRowId == s.playingGroupId) event.row.id else s.selectedRowId)
        is LiveEvent.OriginChanged -> s.copy(originSectionKey = event.sectionKey)
    }
}
