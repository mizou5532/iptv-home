package home.iptv.tv.live

import androidx.compose.ui.unit.dp

/** Mesures de la mise en page du direct (les couleurs et tailles de texte viennent des jetons du thème). */
internal object LiveDims {
    val PageGutter = 56.dp
    /** Marge des trois zones du direct : la moitié de celle de la barre du haut, pour occuper l'écran d'un bord à l'autre. */
    val BodyGutter = 28.dp
    val TopBarVertical = 22.dp
    val ColumnGap = 20.dp
    val Gap = 14.dp
    val RowGap = 8.dp
    val RowPadding = 14.dp
    /** Marge autour des listes pour que l'anneau et le grossissement ne soient pas rognés. */
    val FocusBleed = 8.dp
    /** Trois zones : catégories 21 %, chaînes 38 % (noms entiers et programme en cours), aperçu et guide 41 %. */
    const val SectionsWeight = 0.21f
    const val ChannelsWeight = 0.38f
    const val PreviewWeight = 0.41f
    val SectionHeight = 44.dp
    val ChannelHeight = 64.dp
    val Logo = 46.dp
    /** Pastille de pays d'une catégorie (« US », « CA », étoile des favoris). */
    val BadgeWidth = 30.dp
    val BadgeHeight = 20.dp
    val RowProgressHeight = 3.dp
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
