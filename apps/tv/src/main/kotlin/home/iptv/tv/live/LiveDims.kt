package home.iptv.tv.live

import androidx.compose.ui.unit.dp

/** Mesures de la mise en page du direct (les couleurs et tailles de texte viennent des jetons du thème). */
internal object LiveDims {
    val PageGutter = 48.dp
    val TopBarVertical = 24.dp
    val ColumnGap = 32.dp
    val Gap = 16.dp
    val RowGap = 8.dp
    val RowPadding = 16.dp
    /** Marge autour des listes pour que l'anneau et le grossissement ne soient pas rognés. */
    val FocusBleed = 8.dp
    const val SectionsWeight = 0.32f
    val SectionHeight = 64.dp
    val ChannelHeight = 72.dp
    val Logo = 48.dp
    val LogoInset = 4.dp
    const val LogoRequestPx = 96
    val Avatar = 40.dp
    val ChipH = 20.dp
    val ChipV = 10.dp
    val SelectionBar = 4.dp
    val SelectionBarHeight = 28.dp
    val Ring = 3.dp
    const val FocusScale = 1.03f
    const val FocusMs = 120
}
