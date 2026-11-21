package home.iptv.shared.home

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Version du protocole télé <-> serveur maison, envoyée dans l'en-tête `X-Home-Protocol` ; refus net si différente. */
const val HOME_PROTOCOL = 1

@Serializable
data class SnapStream(
    val id: String,
    val categoryId: String,
    val name: String,
    val icon: String?,
    val epgChannelId: String?,
    val number: Int?,
    val position: Int,
    val autoKey: String?,
    val title: String,
)

@Serializable
data class SnapCategory(val id: String, val name: String, val position: Int)

/**
 * Le Direct tel que le serveur maison le prépare. [rev] : empreinte SHA-256 (hexadécimal) du contenu ;
 * [account] : empreinte du compte IPTV ([accountFingerprint]) ; [hidden] : catégories masquées pour tout le monde.
 */
@Serializable
data class LiveSnapshot(
    val rev: String,
    val account: String,
    val builtAt: Long,
    val categories: List<SnapCategory>,
    val streams: List<SnapStream>,
    val hidden: List<String>,
)

@Serializable
data class SnapEpg(val title: String, val startMs: Long, val endMs: Long, val nowPlaying: Boolean)

@Serializable
data class EpgReply(val entries: List<SnapEpg>)

/** Jeton d'accès à un serveur maison : ne s'affiche jamais, même par erreur dans un journal. */
class HomeToken(val value: String) {
    override fun toString() = "HomeToken(***)"
}

@Serializable
private class RevInput(val categories: List<SnapCategory>, val streams: List<SnapStream>, val hidden: List<String>)

private val revJson = Json { encodeDefaults = true }

/** Empreinte stable du contenu : le même contenu donne la même, un texte changé en donne une autre. */
fun liveSnapshotRev(categories: List<SnapCategory>, streams: List<SnapStream>, hidden: List<String>): String =
    sha256Hex(revJson.encodeToString(RevInput.serializer(), RevInput(categories, streams, hidden)).encodeToByteArray())

/** Empreinte du compte IPTV (serveur + nom d'utilisateur), jamais les identifiants eux-mêmes. */
fun accountFingerprint(server: String, username: String): String =
    sha256Hex((server.trim().lowercase() + "|" + username.trim()).encodeToByteArray()).take(32)

/** SHA-256 en Kotlin pur (code commun, pas d'API JVM) ; résultat en hexadécimal minuscule. */
internal fun sha256Hex(data: ByteArray): String {
    val k = intArrayOf(
        0x428a2f98, 0x71374491, -0x4a3f0431, -0x164a245b, 0x3956c25b, 0x59f111f1, -0x6dc07d5c, -0x54e3a12b,
        -0x27f85568, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, -0x7f214e02, -0x6423f959, -0x3e640e8c,
        -0x1b64963f, -0x1041b87a, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        -0x67c1aeae, -0x57ce3993, -0x4ffcd838, -0x40a68039, -0x391ff40d, -0x2a586eb9, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, -0x7e3d36d2, -0x6d8dd37b,
        -0x5d40175f, -0x57e599b5, -0x3db47490, -0x3893ae5d, -0x2e6d17e7, -0x2966f9dc, -0xbf1ca7b, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, -0x7b3787ec, -0x7338fdf8, -0x6f410006, -0x5baf9315, -0x41065c09, -0x398e870e,
    )
    val h = intArrayOf(
        0x6a09e667, -0x4498517b, 0x3c6ef372, -0x5ab00ac6, 0x510e527f, -0x64fa9774, 0x1f83d9ab, 0x5be0cd19,
    )
    val bitLen = data.size.toLong() * 8
    val padded = ByteArray(((data.size + 9 + 63) / 64) * 64)
    data.copyInto(padded)
    padded[data.size] = 0x80.toByte()
    for (i in 0 until 8) padded[padded.size - 1 - i] = (bitLen ushr (8 * i)).toByte()
    val w = IntArray(64)
    for (block in 0 until padded.size / 64) {
        for (t in 0 until 16) {
            val o = block * 64 + t * 4
            w[t] = ((padded[o].toInt() and 0xff) shl 24) or ((padded[o + 1].toInt() and 0xff) shl 16) or
                ((padded[o + 2].toInt() and 0xff) shl 8) or (padded[o + 3].toInt() and 0xff)
        }
        for (t in 16 until 64) {
            val s0 = w[t - 15].rotateRight(7) xor w[t - 15].rotateRight(18) xor (w[t - 15] ushr 3)
            val s1 = w[t - 2].rotateRight(17) xor w[t - 2].rotateRight(19) xor (w[t - 2] ushr 10)
            w[t] = w[t - 16] + s0 + w[t - 7] + s1
        }
        var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
        var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
        for (t in 0 until 64) {
            val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val ch = (e and f) xor (e.inv() and g)
            val t1 = hh + s1 + ch + k[t] + w[t]
            val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val t2 = s0 + maj
            hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh
    }
    val hex = "0123456789abcdef"
    val sb = StringBuilder()
    for (x in h) for (i in 3 downTo 0) {
        val byte = (x ushr (8 * i)) and 0xff
        sb.append(hex[byte ushr 4]).append(hex[byte and 15])
    }
    return sb.toString()
}
