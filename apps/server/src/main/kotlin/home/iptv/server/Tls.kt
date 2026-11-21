package home.iptv.server

import io.ktor.network.tls.certificates.buildKeyStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64

const val KEY_ALIAS = "home"

/** Écrit un fichier secret avec les droits 600 dès sa création (jamais lisible par d'autres, même brièvement). */
private fun writeSecret(file: File, bytes: ByteArray) {
    val tmp = File(file.parentFile, file.name + ".tmp").toPath()
    Files.deleteIfExists(tmp)
    Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    Files.write(tmp, bytes)
    Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}

/** Mot de passe du magasin de clés, lu dans DATA_DIR/tls.pass. */
fun keyStorePassword(dir: File): CharArray = dir.resolve("tls.pass").readText().trim().toCharArray()

/** Magasin de clés TLS auto-signé, créé une fois (10 ans) dans DATA_DIR/tls.p12 puis réutilisé. */
fun loadOrCreateKeyStore(dir: File): KeyStore {
    dir.mkdirs()
    val p12 = dir.resolve("tls.p12")
    val passFile = dir.resolve("tls.pass")
    if (!p12.exists() || !passFile.exists()) {
        val octets = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val password = Base64.getUrlEncoder().withoutPadding().encodeToString(octets)
        val ks = buildKeyStore {
            certificate(KEY_ALIAS) {
                this.password = password
                daysValid = 3650
                keySizeInBits = 2048
            }
        }
        val out = ByteArrayOutputStream()
        ks.store(out, password.toCharArray())
        writeSecret(passFile, password.toByteArray())
        writeSecret(p12, out.toByteArray())
    }
    val ks = KeyStore.getInstance("PKCS12")
    p12.inputStream().use { ks.load(it, keyStorePassword(dir)) }
    return ks
}

/** Empreinte de la clé publique, au format de `CertificatePinner` d'OkHttp. */
fun spkiPin(cert: X509Certificate): String =
    "sha256/" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded))
