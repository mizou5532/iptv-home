package home.iptv.shared.home

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class HomeProtocolTest {
    private val cats = listOf(SnapCategory("1", "Sports", 0), SnapCategory("2", "Films", 1))
    private val streams = listOf(
        SnapStream("10", "1", "TSN 1", "http://i/x.png", "tsn1.ca", 5, 0, "tsn1", "TSN 1"),
        SnapStream("11", "2", "Cinéma", null, null, null, 1, null, "Cinéma"),
    )

    private fun snapshot(c: List<SnapCategory> = cats, s: List<SnapStream> = streams) =
        LiveSnapshot(liveSnapshotRev(c, s, listOf("2")), "abc", 1234L, c, s, listOf("2"))

    @Test
    fun snapshotRoundTrip() {
        val snap = snapshot()
        assertEquals(snap, Json.decodeFromString<LiveSnapshot>(Json.encodeToString(LiveSnapshot.serializer(), snap)))
        val epg = EpgReply(listOf(SnapEpg("Match", 1L, 2L, true)))
        assertEquals(epg, Json.decodeFromString<EpgReply>(Json.encodeToString(EpgReply.serializer(), epg)))
    }

    @Test
    fun snapshotRevIsStableForSameContent() {
        assertEquals(snapshot().rev, snapshot().rev)
        assertEquals(64, snapshot().rev.length)
        val renamed = streams.map { if (it.id == "10") it.copy(name = "TSN 2") else it }
        assertNotEquals(snapshot().rev, snapshot(s = renamed).rev)
    }

    @Test
    fun sha256MatchesKnownVectors() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex("abc".encodeToByteArray()))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256Hex(ByteArray(0)))
        // Deux blocs (plus de 55 octets).
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            sha256Hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()),
        )
    }

    @Test
    fun accountFingerprintKeepsLegacyOutput() {
        // Valeur calculée avec l'ancien code d'IptvApp (MessageDigest SHA-256, 32 premiers caractères).
        assertEquals("507eaea34a6c1622f2d33403a5b8bbf4", accountFingerprint("  HTTP://Exemple.invalid:8080 ", " utilisateur-test "))
    }

    @Test
    fun homeTokenNeverPrintsItsValue() {
        assertEquals("HomeToken(***)", HomeToken("secret-fake").toString())
    }
}
