package home.iptv.shared.progress

import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol

/** Only a public image address may leave the device: no userinfo, query or fragment. */
internal fun cleanSeriesCover(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    // Existing relative image paths remain valid, without parameters or fragments.
    if (raw.startsWith("/") && !raw.startsWith("//")) return raw.substringBefore('?').substringBefore('#')
    return runCatching {
        URLBuilder(raw).apply {
            require(protocol == URLProtocol.HTTP || protocol == URLProtocol.HTTPS)
            require(host.isNotBlank())
            user = null
            password = null
            parameters.clear()
            fragment = ""
            trailingQuery = false
        }.buildString()
    }.getOrNull()
}

/**
 * Une affiche hébergée chez le fournisseur ne garde que son chemin : l'adresse du serveur IPTV ne part jamais
 * dans la copie synchronisée. Autre hôte, chemin déjà relatif ou [server] inconnu : inchangée.
 */
fun coverWithoutServer(raw: String?, server: String?): String? {
    if (raw.isNullOrBlank() || server.isNullOrBlank()) return raw
    val base = runCatching { URLBuilder(server) }.getOrNull() ?: return raw
    val url = runCatching { URLBuilder(raw) }.getOrNull() ?: return raw
    if (!url.protocol.name.equals(base.protocol.name, true) || !url.host.equals(base.host, true) || url.port != base.port) return raw
    val path = url.encodedPathSegments.joinToString("/").let { if (it.startsWith("/")) it else "/$it" }
    return path.takeIf { it.length > 1 } ?: raw
}

/** Affiche prête à charger : un chemin relatif est recomposé avec l'adresse du serveur (connue sur la télé seulement). */
fun coverForDisplay(cover: String?, server: String?): String? {
    if (cover == null || server.isNullOrBlank()) return cover
    return if (cover.startsWith("/") && !cover.startsWith("//")) server.trimEnd('/') + cover else cover
}
