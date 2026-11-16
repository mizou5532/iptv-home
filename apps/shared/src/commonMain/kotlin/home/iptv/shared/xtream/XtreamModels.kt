package home.iptv.shared.xtream

import kotlin.time.Instant

data class UserInfo(val auth: Boolean, val maxConnections: Int?, val allowedOutputFormats: List<String>)

data class LiveCategory(val id: String, val name: String)

data class LiveStream(
    val id: String,
    val name: String,
    val categoryId: String,
    val icon: String?,
    val epgChannelId: String?,
    val number: Int?,
)

data class SeriesCategory(val id: String, val name: String)

/** Nom affiché d'une série que le fournisseur liste sans nom (elle reste ouvrable). */
const val UNTITLED_SERIES = "Série sans titre"

/** Nom affiché d'une catégorie de séries sans nom (elle reste une rangée). */
const val UNNAMED_CATEGORY = "Catégorie sans nom"

data class SeriesSummary(
    val id: String,
    val categoryId: String,
    val name: String,
    val cover: String?,
    val year: String?,
    val genre: String?,
    val plot: String?,
    val rating: String?,
)

data class Episode(
    val id: String,
    val season: Int,
    val number: Int,
    val title: String,
    val durationS: Int?,
    val extension: String,
    val cover: String?,
)

/** Une saison peut n'avoir aucun épisode (fiche incomplète du fournisseur) : elle est conservée vide. */
data class Season(val number: Int, val name: String, val episodes: List<Episode>)

data class SeriesInfo(val summary: SeriesSummary, val seasons: List<Season>)

data class MovieCategory(val id: String, val name: String)

/** Nom affiché d'un film que le fournisseur liste sans nom (il reste lisible). */
const val UNTITLED_MOVIE = "Film sans titre"

/**
 * Un film. [addedAt] : date d'ajout chez le fournisseur en secondes Unix, `null` si absente ou invalide (jamais une
 * fausse nouveauté). [extension] sert à composer l'adresse de lecture ; [durationS] `null` si inconnue.
 */
data class MovieSummary(
    val id: String,
    val categoryId: String,
    val name: String,
    val cover: String?,
    val year: String?,
    val genre: String?,
    val plot: String?,
    val rating: String?,
    val addedAt: Long?,
    val extension: String,
    val durationS: Int?,
)

data class MovieInfo(val summary: MovieSummary)

/** Un programme du guide. [nowPlaying] : le panneau le marque « en cours » (get_simple_data_table seulement). */
data class EpgEntry(val title: String, val start: Instant, val end: Instant, val nowPlaying: Boolean = false)

/** Erreurs de l'API Xtream. Aucun message ne contient d'URL, d'identifiant ni de mot de passe. */
sealed class XtreamError(message: String) : Exception(message) {
    /** Réseau en panne ou délai dépassé. */
    object Unreachable : XtreamError("Fournisseur injoignable")

    /** Le fournisseur refuse les identifiants. */
    object Refused : XtreamError("Identifiants refusés")

    /** Réponse HTTP ou contenu inattendu. */
    data class Unexpected(val status: Int) : XtreamError("Réponse inattendue du fournisseur ($status)")
}
