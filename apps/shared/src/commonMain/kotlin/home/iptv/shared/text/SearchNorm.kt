package home.iptv.shared.text

private const val FROM = "àâäáãåçéèêëíìîïñóòôöõúùûüýÿ"
private const val TO = "aaaaaaceeeeiiiinooooouuuuyy"

/**
 * Nom normalisé pour la recherche : minuscules, lettres accentuées latines sans accent (œ → oe, æ → ae),
 * espaces consécutifs réduits à un, bords coupés. Même règle que côté web (mêmes vecteurs de test).
 * `java.text.Normalizer` n'existe pas en code commun : table de correspondance explicite.
 */
fun searchNorm(s: String): String {
    val sb = StringBuilder(s.length)
    for (c in s.lowercase()) {
        when {
            c == 'œ' -> sb.append("oe")
            c == 'æ' -> sb.append("ae")
            else -> {
                val i = FROM.indexOf(c)
                sb.append(if (i >= 0) TO[i] else c)
            }
        }
    }
    return sb.toString().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
}
