package home.iptv.tv.live

import home.iptv.shared.sync.ProfilePrefs
import home.iptv.shared.xtream.LiveCategory
import home.iptv.shared.xtream.LiveStream

sealed interface LiveSection {
    val key: String
    val title: String

    data class Favorites(override val title: String = "Favoris") : LiveSection {
        override val key = "fav"
    }

    data class Playlist(val id: String, override val title: String) : LiveSection {
        override val key = "pl:$id"
    }

    data class Category(val id: String, override val title: String) : LiveSection {
        override val key = "cat:$id"
    }
}

private const val HIDDEN_CATEGORY = "live_category"
private const val HIDDEN_CHANNEL = "channel"

private fun ProfilePrefs.hiddenIds(type: String): Set<String> =
    hidden.filter { it.itemType == type }.mapTo(HashSet()) { it.itemId }

/**
 * Favoris (s'il y en a), puis les playlists dans l'ordre, puis les catégories : ordre du profil d'abord, puis les
 * catégories qu'il ne cite pas dans l'ordre du fournisseur. Les catégories masquées (pour tous ou pour ce profil)
 * disparaissent. Sans préférences, ou avec un ordre vide : ordre du fournisseur.
 */
fun buildSections(categories: List<LiveCategory>, prefs: ProfilePrefs?): List<LiveSection> {
    val hiddenCategories = prefs?.hiddenIds(HIDDEN_CATEGORY).orEmpty()
    val byId = categories.associateBy { it.id }
    val pinned = prefs?.order.orEmpty().distinct().mapNotNull { byId[it] }
    val pinnedIds = pinned.mapTo(HashSet()) { it.id }
    val ordered = (pinned + categories.filter { it.id !in pinnedIds }).filter { it.id !in hiddenCategories }
    return buildList {
        if (prefs != null && prefs.favorites.isNotEmpty()) add(LiveSection.Favorites())
        prefs?.playlists?.forEach { add(LiveSection.Playlist(it.id, it.name)) }
        ordered.forEach { add(LiveSection.Category(it.id, it.name)) }
    }
}

/** Retire les chaînes masquées, et celles d'une catégorie masquée : « masqué gagne », même dans un favori. */
fun visibleStreams(streams: List<LiveStream>, prefs: ProfilePrefs?): List<LiveStream> {
    if (prefs == null || prefs.hidden.isEmpty()) return streams
    val channels = prefs.hiddenIds(HIDDEN_CHANNEL)
    val categories = prefs.hiddenIds(HIDDEN_CATEGORY)
    return streams.filter { it.id !in channels && it.categoryId !in categories }
}
