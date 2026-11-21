package home.iptv.tv.feature

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Défilement « rangée par rangée » des accueils Séries et Films (essai TCL 0.9.2 : bannière et rangées à moitié
 * coupées). La liste verticale ne défile plus « juste assez » pour montrer l'élément choisi ; c'est la rangée qui
 * reçoit le focus qui se cale en haut de l'écran (la bannière, première rangée, ramène tout en haut). Les rangées
 * horizontales gardent leur défilement normal.
 *
 * En défilement rapide (flèche maintenue), bascule en saut direct `scrollToItem` (évite d'empiler 15 animations)
 * et signale [LocalFastScroll] = vrai pour différer les nouveaux décodages d'affiches jusqu'à l'arrêt (~120 ms).
 */
@OptIn(ExperimentalFoundationApi::class)
private val NoVerticalAutoScroll = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

/** Vrai pendant un défilement vertical rapide dans Films/Séries : diffère les nouveaux chargements hors cache. */
val LocalFastScroll = compositionLocalOf { false }

private const val RAPID_HOP_MS = 220L
private const val FAST_SCROLL_SETTLE_MS = 120L

@OptIn(ExperimentalFoundationApi::class)
class RowSnap internal constructor(
    internal val state: LazyListState,
    internal val default: BringIntoViewSpec,
    internal val onRapid: () -> Unit = {},
) {
    internal var scrollJob: Job? = null
    internal var lastHopMs: Long = -RAPID_HOP_MS
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RowSnapList(state: LazyListState, content: @Composable (RowSnap) -> Unit) {
    val defaultSpec = LocalBringIntoViewSpec.current
    var rapidSignal by remember(state) { mutableStateOf(0) }
    var fastScroll by remember(state) { mutableStateOf(false) }

    // Active LocalFastScroll uniquement lors des sauts rapides consécutifs (flèche maintenue), sans retarder un appui simple.
    LaunchedEffect(state, rapidSignal) {
        if (rapidSignal > 0) {
            fastScroll = true
            delay(FAST_SCROLL_SETTLE_MS)
            fastScroll = false
        }
    }

    val snap = remember(state, defaultSpec) {
        RowSnap(state, defaultSpec) {
            fastScroll = true
            rapidSignal++
        }
    }
    CompositionLocalProvider(
        LocalBringIntoViewSpec provides NoVerticalAutoScroll,
        LocalFastScroll provides fastScroll,
    ) {
        content(snap)
    }
}

/** Une rangée de la liste : quand le focus y entre, elle vient en haut ([index] = son rang dans la LazyColumn). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SnapRow(snap: RowSnap, index: Int, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    CompositionLocalProvider(LocalBringIntoViewSpec provides snap.default) {
        Box(
            Modifier.onFocusChanged {
                if (it.hasFocus) {
                    val now = android.os.SystemClock.uptimeMillis()
                    val rapid = now - snap.lastHopMs < RAPID_HOP_MS
                    snap.lastHopMs = now
                    if (rapid) snap.onRapid()
                    snap.scrollJob?.cancel()
                    snap.scrollJob = scope.launch {
                        if (rapid) snap.state.scrollToItem(index) else snap.state.animateScrollToItem(index)
                    }
                }
            },
        ) { content() }
    }
}
