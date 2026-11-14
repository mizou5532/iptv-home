package home.iptv.shared.text

/** Décomposition NFD puis retrait de toutes les marques (catégorie M), équivalent de `normalize('NFD').replace(/\p{M}/gu, '')`. */
internal expect fun stripDiacritics(s: String): String

private fun Char.isSpaceLike() = this == ' ' || isWhitespace() || this == '﻿'

/**
 * Nom normalisé pour la recherche, même règle que côté web : œ/æ remplacés (majuscules comprises),
 * NFD sans marques diacritiques, minuscules, espaces (dont insécables) réduits à un, bords coupés.
 */
fun searchNorm(s: String): String {
    val base = stripDiacritics(
        s.replace("œ", "oe").replace("Œ", "OE").replace("æ", "ae").replace("Æ", "AE"),
    ).lowercase()
    val sb = StringBuilder(base.length)
    var pendingSpace = false
    for (c in base) {
        if (c.isSpaceLike()) {
            pendingSpace = sb.isNotEmpty()
        } else {
            if (pendingSpace) sb.append(' ')
            pendingSpace = false
            sb.append(c)
        }
    }
    return sb.toString()
}
