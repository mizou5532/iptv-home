package home.iptv.shared.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class Profile(
    val id: String,
    val name: String,
    val color: String,
    val home: String,
    val theme: String,
    val position: Int,
    /** Réglages libres du profil (colonne `settings`), dont la série épinglée dans la bannière. */
    val settings: ProfileSettings = ProfileSettings(),
)

/** Réglages d'un profil gardés dans `profiles.settings` (JSON) ; une clé inconnue est ignorée. */
@Serializable
data class ProfileSettings(
    /** Série toujours affichée seule dans la bannière de l'onglet Séries (sans file ni défilement), ou null. */
    @SerialName("pinned_series") val pinnedSeries: String? = null,
    @SerialName("pinned_series_category") val pinnedSeriesCategory: String? = null,
    /** Version de la photo de profil (heure d'enregistrement) : change l'adresse lue, donc pas d'ancienne photo gardée. */
    @SerialName("avatar_v") val avatarVersion: Long? = null,
    /** false = photo retirée depuis la page iPhone (la lettre seule, sans rien télécharger). */
    @SerialName("avatar") val hasAvatar: Boolean? = null,
)

@Serializable
data class HiddenItem(
    @SerialName("item_type") val itemType: String,
    @SerialName("item_id") val itemId: String,
)

@Serializable
data class PlaylistPrefs(val id: String, val name: String, val items: List<String>)

@Serializable
data class ProfilePrefs(
    val rev: Long,
    val hidden: List<HiddenItem>,
    val favorites: List<String>,
    val playlists: List<PlaylistPrefs>,
    val order: List<String>?,
    @SerialName("group_copies") val groupCopies: Boolean = true,
    @SerialName("series_order") val seriesOrder: List<String>? = null,
    @SerialName("series_list") val seriesList: List<String> = emptyList(),
    @SerialName("movie_order") val movieOrder: List<String>? = null,
    @SerialName("movie_list") val movieList: List<String> = emptyList(),
)

@Serializable
data class PublishedCategory(val id: String, val name: String, val position: Int)

@Serializable
data class PublishedChannel(val id: String, val name: String, val norm: String, val number: Int?, val position: Int)

sealed class PairingState {
    data class Waiting(val code: String, val expiresAt: Instant) : PairingState()
    data class Paired(val householdId: String) : PairingState()
}
