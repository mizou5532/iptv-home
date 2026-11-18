package home.iptv.shared.xtream

import kotlin.time.Instant

data class UserInfo(
    val auth: Boolean, val maxConnections: Int?, val allowedOutputFormats: List<String>,
    /** Fin de l'abonnement (secondes depuis 1970) ; null = le fournisseur n'en donne pas (illimité). */
    val expiresAt: Long? = null,
    /** État écrit par le fournisseur (« Active », « Expired »…). */
    val status: String? = null,
    /** Vidéos ouvertes en ce moment sur ce compte (tous appareils confondus) ; `null` si le fournisseur ne le dit pas. */
    val activeConnections: Int? = null,
    /** Message du fournisseur (annonce, maintenance) ; `null` s'il n'y en a pas. */
    val message: String? = null,
)

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
    /** Grande image de fond (paysage) donnée par le fournisseur (`backdrop_path`), ou null. */
    val backdrop: String? = null,
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
    /** Grande image de fond (paysage), donnée surtout par la fiche (`get_vod_info`), ou null. */
    val backdrop: String? = null,
)

data class MovieInfo(val summary: MovieSummary, val tech: MediaTech? = null)

/**
 * Qualité réelle d'un fichier, telle que le fournisseur l'a analysée (`info.video` / `info.audio` de la fiche) :
 * définition, HDR, codec vidéo, son. `null` partout quand le fournisseur ne la donne pas.
 */
data class MediaTech(
    val width: Int? = null, val height: Int? = null,
    /** « HDR10 », « HLG », « Dolby Vision » ou `null` (SDR ou inconnu). */
    val hdr: String? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val audioChannels: Int? = null,
) {
    /** « 4K · HDR10 · HEVC · 5.1 Dolby Digital Plus » ; `null` s'il n'y a rien à dire. */
    fun label(): String? {
        val w = width ?: 0; val h = height ?: 0
        val res = when {
            w >= 3200 || h >= 1800 -> "4K"
            w >= 1800 || h >= 1000 -> "Full HD"
            w >= 1200 || h >= 700 -> "HD"
            w > 0 || h > 0 -> "SD"
            else -> null
        }
        val video = when (videoCodec?.lowercase()) {
            "hevc", "h265" -> "HEVC"; "h264", "avc" -> "H.264"; "av1" -> "AV1"; "vp9" -> "VP9"; "mpeg2video" -> "MPEG-2"; else -> null
        }
        val channels = when (audioChannels) { null, 0 -> null; 1 -> "Mono"; 2 -> "Stéréo"; 6 -> "5.1"; 8 -> "7.1"; else -> "$audioChannels canaux" }
        val codec = when (audioCodec?.lowercase()) {
            "eac3" -> "Dolby Digital Plus"; "ac3" -> "Dolby Digital"; "truehd" -> "Dolby TrueHD"; "dts" -> "DTS"; "aac" -> "AAC"; "mp3" -> "MP3"; "opus" -> "Opus"; "flac" -> "FLAC"; else -> null
        }
        val sound = listOfNotNull(channels, codec).joinToString(" ").ifBlank { null }
        return listOfNotNull(res, hdr, video, sound).joinToString(" · ").ifBlank { null }
    }

    /** Forme courte gardée sur la télé (fiche en cache). */
    fun encode(): String = listOf(width, height, hdr, videoCodec, audioCodec, audioChannels).joinToString("|") { it?.toString().orEmpty() }

    companion object {
        fun decode(s: String?): MediaTech? {
            val p = s?.split('|')?.takeIf { it.size == 6 } ?: return null
            fun str(i: Int) = p[i].ifBlank { null }
            return MediaTech(str(0)?.toIntOrNull(), str(1)?.toIntOrNull(), str(2), str(3), str(4), str(5)?.toIntOrNull())
        }
    }
}

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
