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
)

@Serializable
data class PublishedCategory(val id: String, val name: String, val position: Int)

@Serializable
data class PublishedChannel(val id: String, val name: String, val norm: String, val number: Int?, val position: Int)

sealed class PairingState {
    data class Waiting(val code: String, val expiresAt: Instant) : PairingState()
    data class Paired(val householdId: String) : PairingState()
}
