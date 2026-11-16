package home.iptv.tv.feature

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.launch

/**
 * Défilement « rangée par rangée » des accueils Séries et Films (essai TCL 0.9.2 : bannière et rangées à moitié
 * coupées). La liste verticale ne défile plus « juste assez » pour montrer l'élément choisi ; c'est la rangée qui
 * reçoit le focus qui se cale en haut de l'écran (la bannière, première rangée, ramène tout en haut). Les rangées
 * horizontales gardent leur défilement normal.
 *
 * Usage : [RowSnapList] autour de la LazyColumn, et chaque rangée dans [SnapRow] avec son rang dans la liste.
 */
@OptIn(ExperimentalFoundationApi::class)
private val NoVerticalAutoScroll = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

@OptIn(ExperimentalFoundationApi::class)
class RowSnap internal constructor(internal val state: LazyListState, internal val default: BringIntoViewSpec)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RowSnapList(state: LazyListState, content: @Composable (RowSnap) -> Unit) {
    val snap = RowSnap(state, LocalBringIntoViewSpec.current)
    CompositionLocalProvider(LocalBringIntoViewSpec provides NoVerticalAutoScroll) { content(snap) }
}

/** Une rangée de la liste : quand le focus y entre, elle vient en haut ([index] = son rang dans la LazyColumn). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SnapRow(snap: RowSnap, index: Int, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    CompositionLocalProvider(LocalBringIntoViewSpec provides snap.default) {
        Box(Modifier.onFocusChanged { if (it.hasFocus) scope.launch { snap.state.animateScrollToItem(index) } }) { content() }
    }
}
