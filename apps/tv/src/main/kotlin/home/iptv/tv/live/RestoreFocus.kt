package home.iptv.tv.live

import home.iptv.shared.xtream.LiveStream

/** Que faire du focus quand on revient de la lecture ? */
sealed interface RestoreAction {
    /** Rien à restaurer (premier affichage ou déjà consommé). */
    data object None : RestoreAction

    /** Refocaliser la chaîne à cet index. */
    data class Channel(val index: Int) : RestoreAction

    /** La chaîne n'est plus dans la liste : on ne perd pas le focus, on prend la première catégorie. */
    data object FirstCategory : RestoreAction
}

fun restoreAction(restoreId: String?, streams: List<LiveStream>): RestoreAction {
    if (restoreId == null) return RestoreAction.None
    val index = streams.indexOfFirst { it.id == restoreId }
    return if (index >= 0) RestoreAction.Channel(index) else RestoreAction.FirstCategory
}
