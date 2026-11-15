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
