package home.iptv.shared.xtream

/** Identifiants Xtream. `toString()` masque l'identifiant et le mot de passe (jamais dans un journal). */
data class XtreamCreds(val server: String, val username: String, val password: String) {
    override fun toString(): String = "XtreamCreds(server=$server, username=***, password=***)"
}

enum class StreamKind(val path: String) {
    LIVE("live"),
    MOVIE("movie"),
    SERIES("series"),
}

private val PROTOCOLE = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

/** Ramène toute saisie d'adresse de serveur à la forme `protocole://hôte[:port]` en minuscules. */
fun normalizeServer(input: String): String {
    var s = input.trim()
    if (!PROTOCOLE.containsMatchIn(s)) s = "http://$s"
    val separateur = s.indexOf("://")
    val protocole = s.substring(0, separateur).lowercase()
    val reste = s.substring(separateur + 3)
    val autorite = reste.takeWhile { it != '/' && it != '?' && it != '#' }
    val hote = autorite.substringAfterLast('@')
    return "$protocole://${hote.lowercase()}"
}

fun apiUrl(creds: XtreamCreds, action: String? = null, params: Map<String, String> = emptyMap()): String {
    val parts = mutableListOf("username" to creds.username, "password" to creds.password)
    if (!action.isNullOrEmpty()) parts.add("action" to action)
    params.forEach { (k, v) -> parts.add(k to v) }
    val query = parts.joinToString("&") { (k, v) -> "${encodeComponent(k)}=${encodeComponent(v)}" }
    return "${normalizeServer(creds.server)}/player_api.php?$query"
}

fun streamUrl(creds: XtreamCreds, kind: StreamKind, id: String, ext: String): String {
    val u = encodeComponent(creds.username)
    val p = encodeComponent(creds.password)
    return "${normalizeServer(creds.server)}/${kind.path}/$u/$p/${encodeComponent(id)}.$ext"
}

private const val NON_ENCODES = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"

/** Encodage en pourcentage UTF-8 (comme `encodeURIComponent`) : espace en `%20`, jamais `+`. */
private fun encodeComponent(valeur: String): String {
    val sortie = StringBuilder()
    for (octet in valeur.encodeToByteArray()) {
        val c = (octet.toInt() and 0xFF)
        if (c < 0x80 && NON_ENCODES.indexOf(c.toChar()) >= 0) {
            sortie.append(c.toChar())
        } else {
            sortie.append('%')
            sortie.append("0123456789ABCDEF"[c shr 4])
            sortie.append("0123456789ABCDEF"[c and 0x0F])
        }
    }
    return sortie.toString()
}
