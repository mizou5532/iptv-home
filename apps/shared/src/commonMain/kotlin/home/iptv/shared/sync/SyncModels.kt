package home.iptv.shared.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class Profile(
    val id: String,
    val name: String,
    val color: String,
    val home: String,
    val theme: String,
    val position: Int,
    /** Réglages libres du profil (colonne `settings`), dont la série épinglée dans la bannière. */
    val settings: ProfileSettings = ProfileSettings(),
)

/** Réglages du foyer (`households.settings`) : thème de l'écran « Qui regarde ? » (`doux` par défaut, `aurore`). */
@Serializable
data class HouseholdSettings(
    @SerialName("welcome_theme") val welcomeTheme: String? = null,
    /** Catégories du fournisseur lues par l'onglet Sport, même masquées dans le Direct ; absent = choix par défaut. */
    @SerialName("sport_categories") val sportCategories: List<String>? = null,
    /** Thèmes de saison de « Qui regarde ? » ; absent = activés pour toutes les fêtes. */
    @SerialName("seasons") val seasons: home.iptv.shared.account.SeasonSettings? = null,
    /** Versions séparées à la main (« movie:123 », « series:9 ») : jamais regroupées avec les autres versions. */
    @SerialName("version_splits") val versionSplits: List<String> = emptyList(),
)

/** Mémoire du Sport de la maison : la chaîne [channelKey] diffuse (score > 0) ou non (< 0) les matchs de [teamKey]. */
@Serializable
data class SportVote(@SerialName("team_key") val teamKey: String, @SerialName("channel_key") val channelKey: String, val score: Int)

@Serializable
internal data class HouseholdSettingsRow(val id: String, val settings: HouseholdSettings = HouseholdSettings())

/**
 * Thème complet d'un profil (page iPhone, « Personnalisation »). [preset] : nuit, doux, minuit, foret, prune, charbon,
 * sable, ciel ou perso ([background] « #RRGGBB » choisi librement). [corners] : carres, arrondis (par défaut) ou ronds.
 */
@Serializable
data class ThemeLook(
    val preset: String? = null,
    val background: String? = null,
    val corners: String? = null,
)

/** Réglages d'un profil gardés dans `profiles.settings` (JSON) ; une clé inconnue est ignorée. */
@Serializable
data class ProfileSettings(
    /** Sections montrées sur la télé (« direct », « series », « films », « sport ») ; null ou vide = toutes. */
    @SerialName("sections") val sections: List<String>? = null,
    /** Série toujours affichée seule dans la bannière de l'onglet Séries (sans file ni défilement), ou null. */
    @SerialName("pinned_series") val pinnedSeries: String? = null,
    @SerialName("pinned_series_category") val pinnedSeriesCategory: String? = null,
    /** Version de la photo de profil (heure d'enregistrement) : change l'adresse lue, donc pas d'ancienne photo gardée. */
    @SerialName("avatar_v") val avatarVersion: Long? = null,
    /** false = photo retirée depuis la page iPhone (la lettre seule, sans rien télécharger). */
    @SerialName("avatar") val hasAvatar: Boolean? = null,
    /** Version de l'autocollant (heure d'enregistrement), même rôle que [avatarVersion]. */
    @SerialName("sticker_v") val stickerVersion: Long? = null,
    /** true = autocollant posé depuis la page iPhone (affiché dans un coin des accueils Séries et Films). */
    @SerialName("sticker") val hasSticker: Boolean? = null,
    /** Coin de l'autocollant : « bas-droite » (par défaut) ou « bas-gauche ». */
    @SerialName("sticker_corner") val stickerCorner: String? = null,
    /** Taille de l'autocollant : « petit », « moyen » (par défaut) ou « grand ». */
    @SerialName("sticker_size") val stickerSize: String? = null,
    /** true = fond d'écran posé depuis la page iPhone (derrière les accueils Séries et Films). */
    @SerialName("wallpaper") val hasWallpaper: Boolean? = null,
    /** Version du fond d'écran (heure d'enregistrement), même rôle que [avatarVersion]. */
    @SerialName("wallpaper_v") val wallpaperVersion: Long? = null,
    /** Voile de la couleur du thème posé sur le fond, en % (0 à 80) : textes lisibles sur une image chargée. */
    @SerialName("wallpaper_dim") val wallpaperDim: Int? = null,
    /** Équipes favorites (onglet Sport), choisies sur la page iPhone ; absent = Canadiens et Barça. */
    @SerialName("sport_teams") val sportTeams: List<SportTeam>? = null,
    /** Lecteur films et séries : « minimal » (par défaut, maquette 4) ou « carte » (maquette 3), choisi sur l'iPhone. */
    @SerialName("player_style") val playerStyle: String? = null,
    /** Réglages de l'onglet Sport (page iPhone, « Réglages du sport ») ; absent = réglages par défaut. */
    @SerialName("sport_view") val sportView: SportView? = null,
    /** Réglages des accueils Séries et Films (page iPhone, « Séries et films ») ; absent = réglages par défaut. */
    @SerialName("media_view") val mediaView: MediaView? = null,
    /** Thème complet choisi sur l'iPhone (fond, coins) ; absent = fond de la colonne `theme` (sombre ou clair). */
    @SerialName("look") val look: ThemeLook? = null,
    /** Langues préférées (versions et pistes audio/sous-titres) ; absent = Québec, France, anglais, sous-titres anglais. */
    @SerialName("languages") val languages: home.iptv.shared.versions.LanguagePrefs? = null,
) {
    /** Réglages Séries, avec l'ancienne série épinglée (`pinned_series`) reprise si rien n'est épinglé. */
    fun seriesView(): MediaKindView {
        val v = mediaView?.series ?: MediaKindView()
        val legacy = pinnedSeries?.takeIf { it.isNotBlank() }
        return if (v.bannerPinned.isEmpty() && legacy != null) v.copy(bannerPinned = listOf(PinnedTitle(legacy, pinnedSeriesCategory))) else v
    }
    fun moviesView(): MediaKindView = mediaView?.movies ?: MediaKindView()
}

/** Accueils Séries et Films d'un profil (`profiles.settings.media_view`). */
@Serializable
data class MediaView(val series: MediaKindView? = null, val movies: MediaKindView? = null)

/**
 * Accueil d'un type (séries ou films). Bannière : [bannerMode] « auto » (défile toutes les [bannerSeconds] s) ou
 * « fixe » (reste sur le premier titre) ; [bannerPinned] : titres choisis, seuls dans la bannière s'il y en a ;
 * sinon [bannerSources] dans l'ordre (null = ordre par défaut, voir [BannerSources]) et au plus [bannerMax] titres ;
 * [never] : titres à ne plus proposer. [rows] : rangées de l'accueil dans l'ordre, masquables (null = par défaut).
 */
@Serializable
data class MediaKindView(
    @SerialName("banner_mode") val bannerMode: String = "auto",
    @SerialName("banner_seconds") val bannerSeconds: Int = 10,
    @SerialName("banner_max") val bannerMax: Int = 5,
    @SerialName("banner_pinned") val bannerPinned: List<PinnedTitle> = emptyList(),
    @SerialName("banner_sources") val bannerSources: List<String>? = null,
    val never: List<String> = emptyList(),
    val rows: List<RowSetting>? = null,
) {
    val fixed: Boolean get() = bannerMode == "fixe"
    /** Délai entre deux titres, borné (3 à 60 s). */
    val rotateMs: Long get() = bannerSeconds.coerceIn(3, 60) * 1000L
    val maxTitles: Int get() = bannerMax.coerceIn(1, 10)
}

/** Un titre épinglé dans la bannière ; [category] aide à retrouver sa fiche. */
@Serializable
data class PinnedTitle(val id: String, val category: String? = null)

/** Une rangée de l'accueil : clé (« reprendre », « ma_liste », « nouveaux », « nouveautes », « categories ») et visibilité. */
@Serializable
data class RowSetting(val key: String, val on: Boolean = true)

/** Sources possibles de la bannière, dans l'ordre par défaut. */
object BannerSources {
    val SERIES = listOf("reprendre", "nouveaux", "suggestions", "ma_liste")
    val MOVIES = listOf("reprendre", "suggestions", "ma_liste", "nouveautes")
}

/** Rangées possibles de l'accueil, dans l'ordre par défaut. */
object HomeRows {
    val SERIES = listOf("reprendre", "ma_liste", "nouveaux", "categories")
    val MOVIES = listOf("reprendre", "ma_liste", "nouveautes", "categories")
}

/** Sources de la bannière à utiliser, dans l'ordre : celles choisies (connues seulement), sinon [defaults]. */
fun MediaKindView.sources(defaults: List<String>): List<String> =
    bannerSources?.filter { it in defaults }?.distinct() ?: defaults

/**
 * Rangées à afficher, dans l'ordre : celles réglées (connues seulement, masquées retirées), puis les rangées connues
 * absents du réglage (ajoutées plus tard dans l'app) à leur place par défaut, en fin de liste.
 */
fun MediaKindView.visibleRows(defaults: List<String>): List<String> {
    val set = rows ?: return defaults
    val known = set.filter { it.key in defaults }.distinctBy { it.key }
    val listed = known.mapTo(HashSet()) { it.key }
    return known.filter { it.on }.map { it.key } + defaults.filter { it !in listed }
}

/**
 * Affichage de l'onglet Sport d'un profil. [theme] : « equipes » (couleurs des deux équipes, par défaut), « sombre »
 * ou « profil » (couleur du profil). [order] : sports dans l'ordre voulu (« hockey », « football », « basketball »,
 * « soccer ») ; [hidden] : sports masqués (les matchs des équipes favorites restent). [mineFirst] : mes équipes en
 * tête de la bande. [featured] : « mes_equipes » (par défaut) ou « direct » (un match en direct à l'affiche d'abord).
 * [hints] : la ligne d'aide « OK : regarder · maintenir OK… ». [layout] : disposition de l'onglet, « affiche » (un
 * match en grand et la bande du bas, par défaut) ou « grille » (le match choisi en haut, tous les matchs en grille).
 */
@Serializable
data class SportView(
    val theme: String? = null,
    val order: List<String> = emptyList(),
    val hidden: List<String> = emptyList(),
    @SerialName("mine_first") val mineFirst: Boolean = true,
    val featured: String? = null,
    val hints: Boolean = true,
    /** Ordre des langues des chaînes proposées (« qc », « fr », « us », « ca ») ; absent = Québec, France, É.-U., Canada. */
    @SerialName("lang_order") val langOrder: List<String>? = null,
    val layout: String? = null,
    /** « Sans résultat » : aucun score ni classement à l'écran tant qu'on ne le demande pas (touche Info). */
    @SerialName("hide_scores") val hideScores: Boolean = false,
) {
    /** Disposition « grille » choisie sur l'iPhone ; toute autre valeur (ou rien) = l'affiche. */
    val grid: Boolean get() = layout == SPORT_LAYOUT_GRID
}

/** Valeur de [SportView.layout] pour la grille (maquette « 1 · Grille complète » du 18 nov.). */
const val SPORT_LAYOUT_GRID = "grille"

/**
 * Une équipe suivie. [league] = clé de compétition (« nhl », « nba », « nfl », « esp.1 »…) ; [id] = identifiant ESPN ;
 * [abbr] = sigle (LNH : sert à reconnaître l'équipe dans l'horaire officiel). [logo], [color] : pour l'affichage.
 */
@Serializable
data class SportTeam(
    val league: String,
    val id: String,
    val name: String,
    val abbr: String? = null,
    val logo: String? = null,
    val color: String? = null,
)

@Serializable
data class HiddenItem(
    @SerialName("item_type") val itemType: String,
    @SerialName("item_id") val itemId: String,
)

@Serializable
data class PlaylistPrefs(val id: String, val name: String, val items: List<String>)

@Serializable
data class ProfilePrefs(
    val rev: Long,
    val hidden: List<HiddenItem>,
    val favorites: List<String>,
    val playlists: List<PlaylistPrefs>,
    val order: List<String>?,
    @SerialName("group_copies") val groupCopies: Boolean = true,
    @SerialName("series_order") val seriesOrder: List<String>? = null,
    @SerialName("series_list") val seriesList: List<String> = emptyList(),
    @SerialName("movie_order") val movieOrder: List<String>? = null,
    @SerialName("movie_list") val movieList: List<String> = emptyList(),
)

@Serializable
data class PublishedCategory(val id: String, val name: String, val position: Int)

@Serializable
data class PublishedChannel(val id: String, val name: String, val norm: String, val number: Int?, val position: Int)

sealed class PairingState {
    data class Waiting(val code: String, val expiresAt: Instant) : PairingState()
    data class Paired(val householdId: String) : PairingState()
}
