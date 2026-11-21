package home.iptv.tv.home

import okhttp3.CertificatePinner
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** L'empreinte du certificat présenté ne correspond pas à celle publiée : se distingue des autres erreurs TLS. */
class PinMismatchException : CertificateException("empreinte du certificat différente de celle attendue")

/**
 * Ne fait confiance qu'à UN certificat : celui dont l'empreinte de clé publique (`sha256/<base64>`, format
 * `CertificatePinner`) vaut [pin]. Jamais de repli sur les autorités du système : le certificat du serveur maison est
 * auto-signé.
 */
internal class PinnedTrustManager(private val pin: String) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw PinMismatchException()
        // L'empreinte seule fait foi (pas de contrôle de date ni de chaîne : auto-signé).
        if (CertificatePinner.pin(leaf) != pin) throw PinMismatchException()
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("pas d'authentification du client")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * Copie de [base] qui n'accepte que le certificat d'empreinte [pin]. Le certificat est celui d'une adresse IP du
 * réseau local (pas de SAN) : la vérification du nom d'hôte est donc remplacée par l'empreinte, déjà contrôlée par
 * le gestionnaire de confiance pendant la poignée de main.
 */
fun pinnedClient(base: OkHttpClient, pin: String): OkHttpClient {
    val trust = PinnedTrustManager(pin)
    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
    return base.newBuilder()
        .sslSocketFactory(context.socketFactory, trust)
        .hostnameVerifier { _, _ -> true }
        // Jamais de http en clair ni de redirection : le jeton ne doit partir que vers le serveur épinglé.
        .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
}
