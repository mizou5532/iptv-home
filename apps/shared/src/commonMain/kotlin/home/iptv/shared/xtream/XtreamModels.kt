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

data class EpgEntry(val title: String, val start: Instant, val end: Instant)

/** Erreurs de l'API Xtream. Aucun message ne contient d'URL, d'identifiant ni de mot de passe. */
sealed class XtreamError(message: String) : Exception(message) {
    /** Réseau en panne ou délai dépassé. */
    object Unreachable : XtreamError("Fournisseur injoignable")

    /** Le fournisseur refuse les identifiants. */
    object Refused : XtreamError("Identifiants refusés")

    /** Réponse HTTP ou contenu inattendu. */
    data class Unexpected(val status: Int) : XtreamError("Réponse inattendue du fournisseur ($status)")
}
