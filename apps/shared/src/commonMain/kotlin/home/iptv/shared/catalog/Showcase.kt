package home.iptv.shared.catalog

/**
 * Vitrine (0.41, demande du propriétaire : « charger une partie ; les films plus niches n'apparaissent pas dans
 * l'écran mais je les trouve par la recherche »).
 *
 * Seules les catégories de la vitrine ont leurs rangées à l'accueil Films et Séries, comptent dans l'index des
 * versions et dans l'index de recherche gardé en mémoire. Les autres restent sur la télé (titres seulement lus à la
 * demande) : la recherche les trouve quand même. Ma liste et Reprendre montrent toujours leurs titres, quelle que soit
 * leur catégorie.
 *
 * Choix du propriétaire (19 nov. 2026) : français et Québec, nouveautés, enfants, animés japonais, animation pour
 * adultes en anglais (Simpson, Family Guy, South Park : « adult swim »), séries anglaises, Netflix. Les doublons 4K,
 * Dolby et HEVC ne sont pas en vitrine (ce sont des versions de titres déjà en vitrine ; trouvables par la recherche).
 *
 * Si aucune catégorie d'un fournisseur ne correspond (noms différents), toutes sont en vitrine : rien ne disparaît.
 */
object Showcase {
    enum class Kind { MOVIES, SERIES }

    /** Catégorie [name] en vitrine pour [kind]. */
    fun contains(kind: Kind, name: String): Boolean {
        val n = norm(name)
        if (n.isBlank() || DUPLICATE.containsMatchIn(name) || DUPLICATE_WORDS.containsMatchIn(n)) return false
        if (ALWAYS.containsMatchIn(n)) return true
        return when (kind) {
            Kind.MOVIES -> MOVIES.containsMatchIn(n)
            Kind.SERIES -> SERIES.containsMatchIn(n)
        }
    }

    /** Catégorie de doublons (4K, Dolby, HEVC) : hors vitrine, mais gardée dans l'index des versions (versions de secours). */
    fun isDuplicate(name: String): Boolean = DUPLICATE.containsMatchIn(name) || DUPLICATE_WORDS.containsMatchIn(norm(name))

    /** Catégories de l'index des versions : la vitrine et ses doublons (4K, Dolby…), pour garder les versions de secours. */
    fun versionIds(kind: Kind, categories: Map<String, String>): Set<String> {
        val shown = ids(kind, categories)
        return shown + categories.filter { (_, name) -> isDuplicate(name) }.keys
    }

    /**
     * Identifiants en vitrine parmi [categories] (identifiant → nom). Aucune correspondance : toutes (repli prudent pour
     * un fournisseur aux noms inconnus).
     */
    fun ids(kind: Kind, categories: Map<String, String>): Set<String> {
        val chosen = categories.filter { (_, name) -> contains(kind, name) }.keys
        return if (chosen.isEmpty()) categories.keys else chosen
    }

    private fun norm(s: String): String = s.uppercase()
        .replace('É', 'E').replace('È', 'E').replace('Ê', 'E').replace('À', 'A').replace('Ç', 'C').replace('Ô', 'O')
        .replace(Regex("\\s+"), " ").trim()

    /** Versions en double (exposants « ⁴ᴷ », « ᴰᴼᴸᴮʸ »…) : jamais en vitrine. */
    private val DUPLICATE = Regex("[⁴³ᴷᴰᴼᴸᴮʸᴴᴿⱽᵒˡᵇʸᶦˢ]")
    private val DUPLICATE_WORDS = Regex("\\b(4K|3840P|2160P|UHD|HEVC|DOLBY|HDR)\\b")

    /** Les deux sections : enfants, animés, animation pour adultes. */
    private val ALWAYS = Regex(
        "\\b(KIDS|ENFANTS|NICKELODEON|ANIME|ANIMI|ANIMES|MANGA|CRUNCHYROLL|ADULT[ -]SWIM)\\b|^JP - |JAPAN",
    )

    /** Films : français et Québec, nouveautés anglaises, classiques (IMDB). */
    private val MOVIES = Regex("^(FR|QC) - |\\bNEW RELEASE\\b|\\bIMDB TOP\\b")

    /** Séries : France et Québec, séries anglaises, Netflix. */
    private val SERIES = Regex("^(FRANCE|QUEBEC)\\b|^ENGLISH SERIES$|^NETFLIX SERIES$")
}
