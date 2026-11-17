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
    "ᴀAʙBᴄCᴅDᴇEꜰFɢGʜHɪIᴊJᴋKʟLᴍMɴNᴏOᴘPʀRꜱSᴛTᴜUᴠVᴡWʏYᴢZ⁽(⁾)".chunked(2).forEach { put(it[0], it[1]) }
}

private val QUALITY_WORD = "4k|uhd|fhd|hd|sd|raw|hevc|h\\.?265|\\d{2,3}\\s?fps|1080p|720p|2160p"
private val trailingQuality = Regex("(?:[\\s\\-|/]+|^)(?:$QUALITY_WORD)$|\\s*[\\[(](?:$QUALITY_WORD)[\\])]$", RegexOption.IGNORE_CASE)
/** Le nom entier n'est qu'une mention de qualité (« FHD ») : on ne retire jamais tout. */
private val technicalOnly = Regex("^(?:$QUALITY_WORD)$", RegexOption.IGNORE_CASE)
private val looseEnds = Regex("^[\\s\\-|/:]+|[\\s\\-|/:]+$")
private val spaces = Regex("\\s+")
private val countryPrefix = Regex("^\\s*(?:\\|\\s*([A-Z]{2,4})\\s*\\||\\[\\s*([A-Z]{2,4})\\s*]|([A-Z]{2,4})\\s*\\|)\\s*(?=\\S)")
/** « US: ESPN » : deux lettres puis deux-points, seulement pour un vrai code de pays (« NBA: » n'en est pas un). */
private val countryColon = Regex("^\\s*([A-Z]{2})(?:\\s+(?:EN|FR|ES|DE|IT|PT|AR|NL))?\\s*:\\s*(?=\\S)")
private val KNOWN_COUNTRIES = setOf(
    "US", "CA", "UK", "GB", "FR", "BE", "CH", "LU", "ES", "PT", "IT", "DE", "AT", "NL", "IE", "MX", "AR", "BR", "CL", "CO",
    "AU", "NZ", "QC", "MA", "DZ", "TN", "TR", "IN", "PK", "PL", "RO", "GR", "SE", "NO", "DK", "FI", "RU", "AE", "SA", "EG",
    "HU", "SI", "MK", "KR", "JP", "LT", "LV", "AZ", "AM", "ID", "CY", "IS", "LA", "IL", "IR", "HR", "BG", "CZ", "RS", "PH", "AL",
)
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

/**
 * Préfixe de pays ou de région en tête de [name]. [strict] : seulement un vrai code de pays (noms de chaînes, où
 * « VIP| », « GOLD| » ou « TUBI| » ne sont pas des pays) ; sinon tout préfixe de 2 à 4 capitales (catégories : « ASIA| »).
 */
private fun takeCountry(name: String, strict: Boolean = false): Pair<String?, String> {
    countryPrefix.find(name)?.let { m ->
        val code = m.groupValues.drop(1).first { it.isNotEmpty() }
        if (!strict || code in KNOWN_COUNTRIES) return code to name.substring(m.range.last + 1)
    }
    countryColon.find(name)?.takeIf { it.groupValues[1] in KNOWN_COUNTRIES }?.let { m ->
        return m.groupValues[1] to name.substring(m.range.last + 1)
    }
    return null to name
}

/** Pays écrit au début d'un nom de chaîne ou de catégorie (« US| », « [FR] », « CA: »), sinon null. */
fun countryOf(raw: String): String? = takeCountry(splitTiny(raw.replace(UNICODE_SPACES, " ")).text).first

/** Pays écrit au début d'un nom de CHAÎNE : seulement un vrai code de pays (« VIP| ESPN » n'en a pas). */
fun channelCountry(raw: String): String? = takeCountry(splitTiny(raw.replace(UNICODE_SPACES, " ")).text, strict = true).first

/** Mots ajoutés aux copies de secours, retirés pour regrouper (jamais un chiffre seul : « RDS 2 » est une autre chaîne). */
private val copyNoise = Regex("(?:[\\s\\-|/]+)(?:backup|bkp|alt|alternate|alternatif|multi)$|\\s*[\\[(](?:backup|bkp|alt|alternate|alternatif|multi)[\\])]$", RegexOption.IGNORE_CASE)

/**
 * Cœur du nom d'une chaîne pour regrouper ses copies : nom nettoyé ([channelName]) sans les mots de secours
 * (« Backup », « Alt »…). Les espaces spéciaux sont d'abord ramenés à des espaces simples (même règle sur la page).
 */
fun groupCore(raw: String): String {
    var title = channelName(raw.replace(UNICODE_SPACES, " ")).title
    // Étiquette de flux devant le nom (« AT&T: AMC », « PRIME: … », « TV: … ») : ce n'est pas un pays, on la retire
    // pour regrouper avec « US: AMC HD », sauf s'il ne resterait qu'une qualité.
    feedLabel.find(title)?.let { m ->
        val rest = title.substring(m.range.last + 1)
        if (rest.any { it.isLetter() } && !technicalOnly.matches(stripQuality(rest, StringBuilder()))) title = rest
    }
    while (true) {
        val next = stripQuality(tidy(copyNoise.replace(title, "")), StringBuilder()).ifEmpty { title }
        if (next == title) return title
        title = next
    }
}

private val feedLabel = Regex("^[A-Za-z0-9&+/!'.]{2,8}\\s*:\\s+(?=\\S)")

private val UNICODE_SPACES = Regex("[\\t-\\r \\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]")

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
    var (country, afterCountry) = takeCountry(parts.text)
    // « RDS | FHD » : « RDS » n'est pas un préfixe si le reste n'est qu'une qualité.
    if (country != null && technicalOnly.matches(stripQuality(afterCountry, StringBuilder()))) { country = null; afterCountry = parts.text }
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
