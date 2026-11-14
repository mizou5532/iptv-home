package home.iptv.tv.live

enum class BackAction { FocusSections, Exit }

/** Retour : ramener le focus sur les sections seulement s'il y en a et qu'il n'y est pas déjà ; sinon on quitte. */
fun backAction(sectionsCount: Int, sectionsFocused: Boolean): BackAction =
    if (sectionsCount > 0 && !sectionsFocused) BackAction.FocusSections else BackAction.Exit
