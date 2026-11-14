package home.iptv.shared.xtream

import kotlin.time.Instant

data class UserInfo(val auth: Boolean, val maxConnections: Int?, val allowedOutputFormats: List<String>)

data class LiveCategory(val id: String, val name: String)

data class LiveStream(
    val id: String,
    val name: String,
    val categoryId: String,
    val icon: String?,
    val epgChannelId: String?,
    val number: Int?,
)

data class EpgEntry(val title: String, val start: Instant, val end: Instant)

/** Erreurs de l'API Xtream. Aucun message ne contient d'URL, d'identifiant ni de mot de passe. */
sealed class XtreamError(message: String) : Exception(message) {
    /** Réseau en panne ou délai dépassé. */
    object Unreachable : XtreamError("Fournisseur injoignable")

    /** Le fournisseur refuse les identifiants. */
    object Refused : XtreamError("Identifiants refusés")

    /** Réponse HTTP ou contenu inattendu. */
    data class Unexpected(val status: Int) : XtreamError("Réponse inattendue du fournisseur ($status)")
}
