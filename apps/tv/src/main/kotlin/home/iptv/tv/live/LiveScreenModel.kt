package home.iptv.tv.live

import home.iptv.shared.xtream.LiveStream

/** « 12 · RDS HD » si la chaîne a un numéro, sinon son nom. */
fun channelLabel(stream: LiveStream): String =
    stream.number?.let { "$it · ${stream.name}" } ?: stream.name

/** Sous-titre d'une section dans la colonne de gauche. */
@Suppress("UNUSED_PARAMETER")
fun sectionSubtitle(section: LiveSection, count: Int): String = when (count) {
    0 -> "Vide"
    1 -> "1 chaîne"
    else -> "$count chaînes"
}

/** Titre affiché d'une section (une étoile devant les favoris). */
fun sectionLabel(section: LiveSection): String =
    if (section is LiveSection.Favorites) "★ ${section.title}" else section.title

/** Message sous le titre quand la section n'a aucune chaîne. */
fun emptySectionMessage(section: LiveSection?): String = when (section) {
    is LiveSection.Favorites -> "Ajoute des favoris depuis ton téléphone : page de configuration → Organiser."
    else -> "Aucune chaîne dans cette section."
}
