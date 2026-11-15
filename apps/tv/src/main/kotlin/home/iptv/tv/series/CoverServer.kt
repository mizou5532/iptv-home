package home.iptv.tv.series

import androidx.compose.runtime.staticCompositionLocalOf
import home.iptv.shared.progress.coverForDisplay

/** Adresse du serveur du fournisseur (lue au moment de l'affichage) : recompose les affiches enregistrées sans leur hôte. */
val LocalCoverServer = staticCompositionLocalOf<() -> String?> { { null } }

internal fun resolveCover(cover: String?, server: () -> String?): String? =
    cover?.takeIf { it.isNotBlank() }?.let { coverForDisplay(it, if (it.startsWith("/")) server() else null) }
