package home.iptv.shared.catalog

import home.iptv.shared.progress.coverWithoutServer
import home.iptv.shared.text.searchNorm
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Un titre du catalogue (série ou film) tel qu'il part vers le serveur (`publish_catalog_titles`) : de quoi chercher
 * ([norm], même règle que la recherche de la page) et afficher sur l'iPhone. Jamais l'adresse du fournisseur :
 * une affiche hébergée chez lui ne garde que son chemin.
 */
@Serializable
data class CatalogTitleRow(
    val id: String,
    val name: String,
    val norm: String,
    val cover: String? = null,
    @SerialName("cat") val category: String? = null,
    val year: String? = null,
    val rating: String? = null,
)

/** Lots envoyés au serveur (il en accepte au plus 3 000 par appel). */
const val CATALOG_TITLES_BATCH = 2_000

/**
 * Titres prêts à publier : un par identifiant (la première copie gardée), sans nom vide. [server] : adresse du
 * fournisseur, retirée des affiches.
 */
fun catalogTitleRows(items: List<CatalogTitleSource>, server: String?): List<CatalogTitleRow> =
    items.asSequence()
        .filter { it.id.isNotBlank() && it.name.isNotBlank() }
        .distinctBy { it.id }
        .map { s ->
            CatalogTitleRow(
                id = s.id, name = s.name.trim().take(300), norm = searchNorm(s.name).take(300),
                cover = coverWithoutServer(s.cover, server)?.takeIf { it.length <= 500 },
                category = s.category?.takeIf { it.isNotBlank() },
                year = s.year?.trim()?.take(10)?.takeIf { it.isNotEmpty() },
                rating = s.rating?.trim()?.replace(',', '.')?.takeIf { r -> r.toDoubleOrNull()?.let { it.isFinite() && it >= 0 } == true },
            )
        }.toList()

/** Ce qu'il faut d'un titre du cache pour le publier. */
data class CatalogTitleSource(val id: String, val name: String, val cover: String?, val category: String?, val year: String?, val rating: String?)
