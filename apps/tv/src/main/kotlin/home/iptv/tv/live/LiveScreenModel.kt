package home.iptv.tv.live

import home.iptv.shared.text.CategoryName
import home.iptv.shared.text.categoryName
import home.iptv.shared.text.channelName
import home.iptv.shared.text.separatorTitle
import home.iptv.shared.xtream.LiveStream

/**
 * Nom affiché d'une chaîne : sans numéro du fournisseur (il ne correspond à rien), sans préfixe de pays, sans le nom
 * de la catégorie répété devant, sans mention de qualité (elle a sa propre étiquette, [channelQuality]).
 */
fun channelLabel(stream: LiveStream, category: CategoryName? = null): String = channelName(stream.name, category).title

/** Étiquette de qualité d'une chaîne (« FHD », « HD »…), ou rien. */
fun channelQuality(stream: LiveStream): String? = channelName(stream.name).quality

/** Vrai pour une ligne de séparation du fournisseur (« ##### AT&T ##### ») : un titre de groupe, jamais une chaîne. */
fun isSeparator(name: String): Boolean = separatorTitle(name) != null

/** Sous-titre d'une section dans la colonne de gauche. */
@Suppress("UNUSED_PARAMETER")
fun sectionSubtitle(section: LiveSection, count: Int): String = when (count) {
    0 -> "Vide"
    1 -> "1 chaîne"
    else -> "$count chaînes"
}

/** Titre affiché d'une section : pour une catégorie, sans préfixe de pays ni mention de qualité. */
fun sectionLabel(section: LiveSection): String =
    if (section is LiveSection.Category) categoryName(section.title).title else section.title

/** Pastille devant une section : le pays d'une catégorie (« US »), l'étoile des favoris, une note pour une playlist. */
fun sectionBadge(section: LiveSection): String? = when (section) {
    is LiveSection.Favorites -> "★"
    is LiveSection.Playlist -> "♪"
    is LiveSection.Category -> categoryName(section.title).country
}

/** Message sous le titre quand la section n'a aucune chaîne. */
fun emptySectionMessage(section: LiveSection?): String = when (section) {
    is LiveSection.Favorites -> "Ajoute des favoris depuis ton téléphone : page de configuration → Organiser."
    else -> "Aucune chaîne dans cette section."
}
