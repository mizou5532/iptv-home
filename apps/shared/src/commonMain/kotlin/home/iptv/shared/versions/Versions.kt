package home.iptv.shared.versions

import home.iptv.shared.text.searchNorm
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Versions d'un même film ou d'une même série chez le fournisseur (« FR - Oppenheimer (2023) », « EN - Oppenheimer
 * (2023) », « 4K-FR-HDR - Oppenheimer (2023) »…) : langue et qualité lues dans le nom et la catégorie, regroupement
 * par titre nettoyé et année, choix de la meilleure version selon les langues préférées du profil.
 */
enum class Lang(val code: String, val label: String, val short: String, val audio: String?) {
    QC("qc", "Français (Québec)", "QC", "fr"),
    FR("fr", "Français (France)", "FR", "fr"),
    EN("en", "Anglais", "EN", "en"),
    ES("es", "Espagnol", "ES", "es"),
    IT("it", "Italien", "IT", "it"),
    DE("de", "Allemand", "DE", "de"),
    PT("pt", "Portugais", "PT", "pt"),
    AR("ar", "Arabe", "AR", "ar"),
    TR("tr", "Turc", "TR", "tr"),
    HI("hi", "Hindi", "HI", "hi"),
    JA("ja", "Japonais", "JP", "ja"),
    KO("ko", "Coréen", "KR", "ko"),
    ZH("zh", "Chinois", "CN", "zh"),
    NL("nl", "Néerlandais", "NL", "nl"),
    PL("pl", "Polonais", "PL", "pl"),
    RU("ru", "Russe", "RU", "ru"),
    EL("el", "Grec", "GR", "el");

    companion object { fun of(code: String?) = entries.firstOrNull { it.code == code } }
}

/** Langues que l'on peut mettre dans l'ordre sur la page iPhone (les autres restent reconnues et affichées). */
val PREFERABLE_LANGS = listOf(Lang.QC, Lang.FR, Lang.EN, Lang.ES, Lang.AR, Lang.IT, Lang.DE, Lang.PT)

/** Plateformes reconnues dans le début du nom ; leurs versions ont souvent plusieurs langues dans le fichier. */
private val SOURCES = mapOf(
    "NF" to "Netflix", "AMZ" to "Prime Video", "D+" to "Disney+", "A+" to "Apple TV+", "PRMT" to "Paramount+",
    "P+" to "Paramount+", "MAX" to "Max", "PCOK" to "Peacock", "SKY" to "Sky", "OSN+" to "OSN+", "UNV" to "Universal",
    "DSC+" to "Discovery+", "NICK" to "Nickelodeon", "VP" to "Video Premium", "TOP" to "Top Blu-ray",
)

private val LANG_TOKENS = mapOf(
    "QC" to Lang.QC, "FR" to Lang.FR, "VF" to Lang.FR, "VFF" to Lang.FR, "VFQ" to Lang.QC, "EN" to Lang.EN, "ES" to Lang.ES,
    "IT" to Lang.IT, "DE" to Lang.DE, "PT" to Lang.PT, "BR" to Lang.PT, "AR" to Lang.AR, "TR" to Lang.TR, "IN" to Lang.HI,
    "HI" to Lang.HI, "JP" to Lang.JA, "KR" to Lang.KO, "CN" to Lang.ZH, "NL" to Lang.NL, "PL" to Lang.PL, "RU" to Lang.RU,
    "GR" to Lang.EL,
)

/** Mots de catégorie qui donnent la langue quand le nom ne la dit pas (« QUÉBEC SERIES », « FRANCE SÉRIES »…). */
private val CATEGORY_WORDS = listOf(
    Regex("\\b(QU[EÉ]BEC|QC)\\b") to Lang.QC,
    Regex("^\\s*(FR|FRANCE|FRENCH|FRAN[CÇ]AIS)\\b") to Lang.FR,
    Regex("^\\s*(EN|ENGLISH)\\b") to Lang.EN,
    Regex("^\\s*(ES|SPANISH|ESPA[NÑ]OL)\\b") to Lang.ES,
    Regex("^\\s*(AR|ARABIC)\\b") to Lang.AR,
    Regex("^\\s*(DE|GERMAN|DEUTSCH)\\b") to Lang.DE,
    Regex("^\\s*(IT|ITALIAN)\\b") to Lang.IT,
)

/** Début du nom « 4K-FR-HDR - », « EN-TOP - », « NF - » : des étiquettes séparées par des tirets, puis « - ». */
private val PREFIX = Regex("^\\s*([A-Za-z0-9+]{1,6}(?:-[A-Za-z0-9+]{1,6}){0,3})\\s+-\\s+")
private val YEAR = Regex("\\((19\\d\\d|20\\d\\d)\\)")
private val TRAILING_TAGS = Regex("(\\s*\\((?:19|20)\\d\\d\\)|\\s*\\([A-Z]{2}\\)|\\s*\\[[^\\]]{1,12}\\])+\\s*$")
private val RANK = Regex("^\\s*\\d{1,3}\\.\\s+")
private val QUALITY_WORDS = Regex("\\b(4K|UHD|HDR|HDR10|DV|FHD|1080P|720P|MULTI|VOSTFR|VOST|VF|VFF|VFQ)\\b", RegexOption.IGNORE_CASE)

/** Ce que l'on sait d'une version sans la lancer. [lang] null = inconnue (souvent plusieurs langues, ex. Netflix). */
data class VersionTag(val lang: Lang?, val uhd: Boolean = false, val hdr: Boolean = false, val source: String? = null) {
    /** « Français (Québec) », « Anglais · 4K HDR », « Netflix · langues au lancement ». */
    val label: String get() {
        val quality = listOfNotNull("4K".takeIf { uhd }, "HDR".takeIf { hdr }).joinToString(" ")
        val base = lang?.label ?: source?.let { "$it · langues au lancement" } ?: "Langue inconnue"
        return listOfNotNull(base, quality.ifEmpty { null }).joinToString(" · ")
    }
    /** Étiquette courte des résultats (« QC », « FR », « EN », « 4K », « NF »). */
    val short: String get() = lang?.short ?: when (source) { "Netflix" -> "NF"; null -> "?"; else -> "VO" }
}

fun versionTag(title: String, categoryName: String? = null): VersionTag {
    val tokens = PREFIX.find(title)?.groupValues?.get(1)?.uppercase()?.split('-').orEmpty()
    var lang = tokens.firstNotNullOfOrNull { LANG_TOKENS[it] }
    val source = tokens.firstNotNullOfOrNull { SOURCES[it] }
    val cat = categoryName?.uppercase().orEmpty()
    if (lang == null) lang = CATEGORY_WORDS.firstOrNull { it.first.containsMatchIn(cat) }?.second
    // Une catégorie « QUÉBEC » l'emporte sur un « FR » générique (les doublages québécois sont souvent rangés en FR-).
    if (lang == Lang.FR && CATEGORY_WORDS.first().first.containsMatchIn(cat)) lang = Lang.QC
    val uhd = "4K" in tokens || Regex("\\b(4K|UHD|2160P|³⁸⁴⁰ᴾ|⁴ᴷ)").containsMatchIn(cat) || Regex("\\b4K\\b").containsMatchIn(title.substringAfter(" - "))
    val hdr = "HDR" in tokens || "ᴴᴰᴿ" in cat || Regex("\\bHDR\\b").containsMatchIn(cat)
    return VersionTag(lang, uhd, hdr, source)
}

/** Titre sans étiquettes : « Oppenheimer », « Breaking Bad », « Money Heist ( La Casa De Papel ) ». */
fun versionTitle(title: String): String {
    var t = title.replace(PREFIX, "")
    t = t.replace(RANK, "")
    t = t.replace(TRAILING_TAGS, "")
    return t.replace(Regex("\\s+"), " ").trim().ifEmpty { title.trim() }
}

fun versionYear(title: String, year: String? = null): String? =
    year?.trim()?.takeIf { Regex("^(19|20)\\d\\d$").matches(it) } ?: YEAR.findAll(title).lastOrNull()?.groupValues?.get(1)

/** Clé de regroupement (sans l'année, réglée par [groupVersions]). */
fun versionCore(title: String): String = searchNorm(versionTitle(title).replace(QUALITY_WORDS, " "))
    .replace(Regex("[^a-z0-9]+"), " ").trim()

/** Une version du catalogue (un identifiant du fournisseur). */
data class Version(
    val id: String, val categoryId: String, val title: String, val tag: VersionTag,
    val cover: String? = null, val year: String? = null,
)

data class VersionInput(val id: String, val title: String, val categoryId: String, val categoryName: String?, val cover: String?, val year: String? = null)

/**
 * Regroupe par titre nettoyé ; dans un même titre, des années connues différentes restent séparées (remakes) et une
 * version sans année rejoint l'année s'il n'y en a qu'une. Clé : « titre|année » (année vide si inconnue).
 */
fun groupVersions(items: List<VersionInput>, splits: Set<String> = emptySet()): Map<String, List<Version>> {
    val out = LinkedHashMap<String, MutableList<Version>>()
    // Versions séparées à la main (« Ce n'est pas le même titre ») : chacune seule, jamais regroupée.
    val (alone, rest) = items.partition { it.id in splits }
    alone.forEach { out["#${it.id}|"] = mutableListOf(it.toVersion()) }
    rest.groupBy { versionCore(it.title) }.forEach { (core, list) ->
        if (core.isEmpty()) { list.forEach { out["#${it.id}|"] = mutableListOf(it.toVersion()) }; return@forEach }
        val withYear = list.map { it to versionYear(it.title, it.year) }
        val years = withYear.mapNotNull { it.second }.distinct()
        withYear.forEach { (item, year) ->
            val y = year ?: years.singleOrNull() ?: ""
            out.getOrPut("$core|$y") { mutableListOf() }.add(item.toVersion(year ?: years.singleOrNull()))
        }
    }
    return out
}

private fun VersionInput.toVersion(y: String? = versionYear(title, year)) =
    Version(id, categoryId, title, versionTag(title, categoryName), cover, y)

/** Langues préférées d'un profil (page iPhone, « Langues ») ; par défaut Québec, France, anglais, sous-titres anglais. */
@Serializable
data class LanguagePrefs(
    val order: List<String> = listOf("qc", "fr", "en"),
    /** Code de langue des sous-titres (« en », « fr »…) ou « off ». */
    val subtitles: String = "en",
    @SerialName("prefer_4k") val prefer4k: Boolean = false,
    /** « Seulement mes langues » : les versions dans une langue connue hors de [order] sont cachées. */
    @SerialName("only_mine") val onlyMine: Boolean = false,
)

/**
 * Versions montrées au profil : avec « Seulement mes langues », celles dans une langue reconnue absente de la liste
 * sont retirées (les versions de plateforme sans langue connue restent : elles ont souvent plusieurs langues).
 */
fun myVersions(versions: List<Version>, prefs: LanguagePrefs): List<Version> =
    if (!prefs.onlyMine) versions else versions.filter { v -> v.tag.lang.let { it == null || it.code in prefs.order } }

/**
 * Réunit les groupes dont une version a la même fiche TMDB (titres traduits : « La casa de papel » et « Money Heist »).
 * [tmdbOf] donne la fiche connue d'une version (cache local seulement) ; la clé du premier groupe est gardée.
 */
fun mergeByTmdb(groups: Map<String, List<Version>>, tmdbOf: (Version) -> Int?): Map<String, List<Version>> {
    val owner = HashMap<Int, String>()
    val target = HashMap<String, String>()
    for ((key, versions) in groups) {
        if (key.startsWith("#")) continue
        val ids = versions.mapNotNull(tmdbOf).distinct()
        val existing = ids.firstNotNullOfOrNull { owner[it] }
        val into = existing ?: key
        ids.forEach { owner.putIfAbsent(it, into) }
        if (existing != null) target[key] = existing
    }
    if (target.isEmpty()) return groups
    val out = LinkedHashMap<String, MutableList<Version>>()
    for ((key, versions) in groups) out.getOrPut(target[key] ?: key) { mutableListOf() }.addAll(versions)
    return out
}

/** Accès rapide aux versions d'un titre par identifiant de version. */
class VersionIndex(val groups: Map<String, List<Version>>) {
    private val keyOf: Map<String, String> = HashMap<String, String>().also { m -> groups.forEach { (k, vs) -> vs.forEach { m[it.id] = k } } }
    /** Toutes les versions du titre de [id] (lui compris) ; vide si inconnu. */
    fun versionsOf(id: String): List<Version> = keyOf[id]?.let { groups[it] }.orEmpty()
    fun keyOf(id: String): String? = keyOf[id]
    /** Deux identifiants désignent le même titre. */
    fun same(a: String, b: String): Boolean = a == b || keyOf[a]?.let { it == keyOf[b] } == true
    companion object { val EMPTY = VersionIndex(emptyMap()) }
}

/**
 * Une carte par titre dans une rangée (Ma liste, Ajouts récents…) : la première rencontrée garde sa place, remplacée
 * par la meilleure version du profil quand elle est dans la rangée. [idOf] donne l'identifiant de version d'une carte.
 */
fun <T> collapseVersions(items: List<T>, index: VersionIndex, prefs: LanguagePrefs, idOf: (T) -> String): List<T> {
    if (index.groups.isEmpty()) return items
    val byKey = LinkedHashMap<String, MutableList<T>>()
    items.forEach { byKey.getOrPut(index.keyOf(idOf(it)) ?: "#" + idOf(it)) { mutableListOf() }.add(it) }
    return byKey.map { (key, cards) ->
        if (cards.size == 1) cards[0] else {
            val versions = index.groups[key].orEmpty().filter { v -> cards.any { idOf(it) == v.id } }
            val best = bestVersion(versions, prefs)
            cards.firstOrNull { idOf(it) == best?.id } ?: cards[0]
        }
    }
}

/** Retire d'une rangée les versions cachées par « Seulement mes langues » (titres connus de l'index seulement). */
fun <T> hideOtherLanguages(items: List<T>, index: VersionIndex, prefs: LanguagePrefs, idOf: (T) -> String): List<T> {
    if (!prefs.onlyMine) return items
    return items.filter { item ->
        val v = index.versionsOf(idOf(item)).firstOrNull { it.id == idOf(item) } ?: return@filter true
        myVersions(listOf(v), prefs).isNotEmpty()
    }
}

/**
 * Meilleure version pour [prefs] : la première langue de la liste disponible ; puis les versions sans langue connue
 * (plateformes, souvent plusieurs langues) ; puis les autres langues. À langue égale : 4K si demandé, sinon la
 * version standard (plus légère, plus sûre) ; puis l'ordre du catalogue.
 */
fun bestVersion(versions: List<Version>, prefs: LanguagePrefs = LanguagePrefs()): Version? {
    val order = prefs.order.mapNotNull { Lang.of(it) }
    fun rank(v: Version): Int = v.tag.lang?.let { l -> order.indexOf(l).takeIf { it >= 0 } } ?: if (v.tag.lang == null) order.size else order.size + 1
    return versions.withIndex().minWithOrNull(compareBy<IndexedValue<Version>>(
        { rank(it.value) },
        { if (it.value.tag.uhd == prefs.prefer4k) 0 else 1 },
        { if (it.value.tag.hdr && !prefs.prefer4k) 1 else 0 },
        { it.index },
    ))?.value
}

/** Langues distinctes d'un groupe, dans l'ordre de préférence du profil (étiquettes des résultats). */
fun versionBadges(versions: List<Version>, prefs: LanguagePrefs = LanguagePrefs()): List<String> {
    val order = prefs.order.mapNotNull { Lang.of(it) }
    val langs = versions.mapNotNull { it.tag.lang }.distinct()
        .sortedBy { l -> order.indexOf(l).let { if (it < 0) 100 + l.ordinal else it } }
    val badges = langs.map { it.short }.toMutableList()
    if (versions.any { it.tag.lang == null }) badges += "VO"
    if (versions.any { it.tag.uhd }) badges += "4K"
    return badges
}

/** Langue audio à choisir dans le fichier : première langue préférée présente parmi [available] (codes « fr », « en »…). */
fun preferredAudio(prefs: LanguagePrefs, available: List<String?>): String? =
    prefs.order.mapNotNull { Lang.of(it)?.audio }.distinct().firstOrNull { it in available }

/** Pistes à choisir au lancement : code audio (null = laisser celle du fichier) et sous-titres (code, « off » ou null). */
data class TrackChoice(val audio: String?, val subtitles: String?)

/**
 * Langues réglées sur l'iPhone ([explicit]) : elles l'emportent sur le dernier choix fait à la télécommande. Sans
 * réglage : le dernier choix retenu, sinon les langues par défaut (Québec, France, anglais ; sous-titres anglais).
 * [audioCodes] et [subtitleCodes] : langues présentes dans le fichier (codes « fr », « en »…).
 */
fun chooseTracks(
    prefs: LanguagePrefs, explicit: Boolean, rememberedAudio: String?, rememberedSubtitles: String?,
    audioCodes: List<String?>, subtitleCodes: List<String?>,
): TrackChoice {
    val wantedAudio = preferredAudio(prefs, audioCodes)
    val audio = if (explicit) wantedAudio ?: rememberedAudio?.takeIf { it in audioCodes } else rememberedAudio?.takeIf { it in audioCodes } ?: wantedAudio
    val wantedSub = prefs.subtitles.takeIf { it == "off" || it in subtitleCodes }
    val subs = if (explicit) wantedSub else rememberedSubtitles ?: wantedSub
    return TrackChoice(audio, subs)
}

/**
 * Reprise d'une version à l'autre (film) : sans position pour [id], la plus récente des autres versions du même titre
 * ([siblings]) sert de point de départ. Rend null quand [id] a déjà sa propre ligne ou qu'aucune autre n'existe.
 */
fun carriedMovieRow(rows: List<home.iptv.shared.progress.ProgressRow>, id: String, siblings: Set<String>): home.iptv.shared.progress.ProgressRow? {
    val movies = rows.filter { it.itemType == "movie" }
    if (movies.any { it.itemId == id }) return null
    return movies.filter { it.itemId in siblings && it.itemId != id }.maxByOrNull { it.clientUpdatedAt }
}

/** Un épisode de la version ouverte : identifiant, saison, numéro. */
data class EpisodeSlot(val id: String, val season: Int, val number: Int)

/**
 * Reprise d'une version à l'autre (série) : un épisode sans ligne prend celle du même épisode (saison et numéro) d'une
 * autre version ([others] : lignes des séries sœurs) ; la plus récente gagne. Les lignes propres ([own]) ne bougent pas.
 */
fun carryEpisodeRows(
    own: Map<String, home.iptv.shared.progress.ProgressRow>,
    others: List<home.iptv.shared.progress.ProgressRow>,
    episodes: List<EpisodeSlot>,
): Map<String, home.iptv.shared.progress.ProgressRow> {
    if (others.isEmpty()) return own
    val bySlot = others.filter { it.itemType == "episode" && it.season != null && it.episode != null }
        .groupBy { it.season!! to it.episode!! }.mapValues { (_, r) -> r.maxBy { it.clientUpdatedAt } }
    if (bySlot.isEmpty()) return own
    val out = HashMap(own)
    episodes.forEach { e -> if (e.id !in out) bySlot[e.season to e.number]?.let { out[e.id] = it.copy(itemId = e.id) } }
    return out
}
