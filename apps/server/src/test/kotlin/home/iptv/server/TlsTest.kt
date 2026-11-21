package home.iptv.server

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.cert.X509Certificate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TlsTest {
    private fun cert(ks: java.security.KeyStore) = ks.getCertificate("home") as X509Certificate

    @Test
    fun keyStoreReusedAcrossStarts() {
        val dir = Files.createTempDirectory("tls").toFile()
        val pin1 = spkiPin(cert(loadOrCreateKeyStore(dir)))
        val pin2 = spkiPin(cert(loadOrCreateKeyStore(dir)))
        assertEquals(pin1, pin2)
        for (nom in listOf("tls.p12", "tls.pass")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve(nom).toPath())))
        }
    }

    @Test
    fun pinFormatMatchesOkHttp() {
        val c = cert(loadOrCreateKeyStore(Files.createTempDirectory("tls").toFile()))
        val pin = spkiPin(c)
        assertTrue(pin.startsWith("sha256/"))
        assertEquals(okhttp3.CertificatePinner.pin(c), pin)
    }
}
