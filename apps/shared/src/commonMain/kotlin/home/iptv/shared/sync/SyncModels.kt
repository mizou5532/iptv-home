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

sealed class PairingState {
    data class Waiting(val code: String, val expiresAt: Instant) : PairingState()
    data class Paired(val householdId: String) : PairingState()
}
