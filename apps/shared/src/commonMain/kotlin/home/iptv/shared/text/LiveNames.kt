package home.iptv.shared.text

/**
 * Noms du direct tels qu'on les affiche. Le fournisseur envoie « US| NEWS ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ », « AT&T: 123 » ou
 * « ##### AT&T ##### » : ici on en tire un pays, un titre propre, une qualité et les séparateurs. Affichage
 * seulement : les identifiants, le regroupement des copies et la recherche gardent les noms d'origine.
 */
data class CategoryName(val country: String?, val title: String)

data class ChannelName(val title: String, val quality: String?)

/** Petites lettres en exposant et petites capitales que le fournisseur emploie pour la qualité. */
private val TINY: Map<Char, Char> = buildMap {
    "ᴬAᴮBᴰDᴱEᴳGᴴHᴵIᴶJᴷKᴸLᴹMᴺNᴼOᴾPᴿRᵀTᵁUⱽVᵂW".chunked(2).forEach { put(it[0], it[1]) }
    "ᵃaᵇbᶜcᵈdᵉeᶠfᵍgʰhⁱiʲjᵏkˡlᵐmⁿnᵒoᵖpʳrˢsᵗtᵘuᵛvʷwˣxʸyᶻz".chunked(2).forEach { put(it[0], it[1]) }
    "⁰0¹1²2³3⁴4⁵5⁶6⁷7⁸8⁹9".chunked(2).forEach { put(it[0], it[1]) }
    "ᴀAʙBᴄCᴅDᴇEꜰFɢGʜHɪIᴊJᴋKʟLᴍMɴNᴏOᴘPʀRꜱSᴛTᴜUᴠVᴡWʏYᴢZ".chunked(2).forEach { put(it[0], it[1]) }
}

private val QUALITY_WORD = "4k|uhd|fhd|hd|sd|raw|hevc|h\\.?265|\\d{2,3}\\s?fps|1080p?|720p?|2160p?"
private val trailingQuality = Regex("(?:[\\s\\-|/]+|^)(?:$QUALITY_WORD)$|\\s*[\\[(](?:$QUALITY_WORD)[\\])]$", RegexOption.IGNORE_CASE)
private val looseEnds = Regex("^[\\s\\-|/:]+|[\\s\\-|/:]+$")
private val spaces = Regex("\\s+")
private val countryPrefix = Regex("^\\s*(?:\\|\\s*([A-Z]{2,3})\\s*\\||\\[\\s*([A-Z]{2,3})\\s*]|([A-Z]{2,3})\\s*\\|)\\s*(?=\\S)")
private val separator = Regex("^\\s*[#=*~•_\\-]{2,}\\s*(.*?)\\s*[#=*~•_\\-]{2,}\\s*$")
private val separatorOpen = Regex("^\\s*[#=*~•]{3,}\\s*(.*?)\\s*$")

private class Parts(val text: String, val tech: String)

/** Sépare le texte lisible des mentions en petits caractères (rendues en lettres ordinaires dans [Parts.tech]). */
private fun splitTiny(name: String): Parts {
    val text = StringBuilder(name.length)
    val tech = StringBuilder()
    for (c in name) {
        val plain = TINY[c]
        if (plain != null) { tech.append(plain); text.append(' ') } else {
            text.append(c)
            if (c == ' ' || c == '/') tech.append(' ')
        }
    }
    return Parts(text.toString(), tech.toString())
}

private fun tidy(s: String) = spaces.replace(looseEnds.replace(s, ""), " ")

/** Retire les mentions de qualité en fin de nom ; les mots retirés sont ajoutés à [found]. */
private fun stripQuality(name: String, found: StringBuilder): String {
    var title = tidy(name)
    while (true) {
        val m = trailingQuality.find(title) ?: return title
        val rest = tidy(title.removeRange(m.range))
        if (rest.isEmpty()) return title
        found.append(' ').append(m.value)
        title = rest
    }
}

/** La meilleure définition citée (« 4K », « FHD », « HD », « SD »), sinon « RAW », sinon rien. */
private fun qualityTag(words: String): String? {
    val w = words.lowercase()
    fun has(re: String) = Regex("(?:^|[^a-z0-9])(?:$re)(?:[^a-z0-9]|$)").containsMatchIn(w)
    return when {
        has("4k|uhd|2160p?") -> "4K"
        has("fhd|1080p?") -> "FHD"
        has("hd|720p?") -> "HD"
        has("sd") -> "SD"
        has("raw") -> "RAW"
        else -> null
    }
}

private fun takeCountry(name: String): Pair<String?, String> {
    val m = countryPrefix.find(name) ?: return null to name
    val code = m.groupValues.drop(1).first { it.isNotEmpty() }
    return code to name.substring(m.range.last + 1)
}

/** « US| NEWS ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ » → pays « US », titre « NEWS ». Sans préfixe reconnu : pas de pays. */
fun categoryName(raw: String): CategoryName {
    val parts = splitTiny(raw)
    val (country, rest) = takeCountry(parts.text)
    val title = stripQuality(rest, StringBuilder())
    return CategoryName(country, title.ifEmpty { tidy(raw) })
}

/**
 * Nom d'une chaîne dans une liste : sans préfixe de pays, sans le nom de sa catégorie répété devant (« AT&T: 123 »
 * dans « AT&T » → « 123 »), sans mention de qualité ; celle-ci revient à part ([ChannelName.quality]).
 */
fun channelName(raw: String, category: CategoryName? = null): ChannelName {
    val parts = splitTiny(raw)
    val found = StringBuilder(parts.tech)
    val (country, afterCountry) = takeCountry(parts.text)
    var rest = tidy(afterCountry)
    val heads = listOfNotNull(category?.title, category?.country.takeIf { country == null })
    for (head in heads) {
        if (head.isBlank() || !rest.startsWith(head, ignoreCase = true)) continue
        val after = rest.substring(head.length)
        val cut = after.trimStart()
        if (cut.isNotEmpty() && cut[0] in ":|-" && tidy(cut.substring(1)).isNotEmpty()) { rest = tidy(cut.substring(1)); break }
    }
    val title = stripQuality(rest, found)
    return ChannelName(title.ifEmpty { tidy(raw) }, qualityTag(found.toString()))
}

/** Titre d'une ligne de séparation (« ##### AT&T ##### » → « AT&T », peut être vide) ; `null` pour une vraie chaîne. */
fun separatorTitle(raw: String): String? {
    val name = takeCountry(raw).second
    val m = separator.matchEntire(name) ?: separatorOpen.matchEntire(name) ?: return null
    val inner = m.groupValues[1].trim { it.isWhitespace() || it in "#=*~•_-" }
    return categoryName(inner).title.takeIf { inner.isNotEmpty() } ?: ""
}
