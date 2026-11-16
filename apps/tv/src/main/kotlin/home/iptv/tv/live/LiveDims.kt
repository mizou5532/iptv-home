package home.iptv.tv.live

import androidx.compose.ui.unit.dp

/** Mesures de la mise en page du direct (les couleurs et tailles de texte viennent des jetons du thème). */
internal object LiveDims {
    val PageGutter = 56.dp
    val TopBarVertical = 22.dp
    val ColumnGap = 24.dp
    val Gap = 14.dp
    val RowGap = 8.dp
    val RowPadding = 14.dp
    /** Marge autour des listes pour que l'anneau et le grossissement ne soient pas rognés. */
    val FocusBleed = 8.dp
    /** Trois zones (style « Verre ») : catégories 22 %, chaînes 30 %, aperçu et guide 48 % de la largeur utile. */
    const val SectionsWeight = 0.22f
    const val ChannelsWeight = 0.30f
    const val PreviewWeight = 0.48f
    val SectionHeight = 52.dp
    val ChannelHeight = 62.dp
    val Logo = 42.dp
    val LogoInset = 4.dp
    const val LogoRequestPx = 96
    val Avatar = 34.dp
    val ChipH = 18.dp
    val ChipV = 8.dp
    val ButtonV = 11.dp
    val PlayingBar = 6.dp
    val ProgressHeight = 6.dp
    const val PanelWidthFraction = 0.4f
    val SelectionBar = 4.dp
    val SelectionBarHeight = 28.dp
    val Ring = 3.dp
    const val FocusScale = 1.03f
    const val FocusMs = 120
}
