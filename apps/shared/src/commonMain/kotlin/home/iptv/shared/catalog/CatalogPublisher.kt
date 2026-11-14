package home.iptv.shared.catalog

import home.iptv.shared.sync.PublishedCategory
import home.iptv.shared.sync.PublishedChannel
import home.iptv.shared.sync.SyncClient
import home.iptv.shared.text.searchNorm
import home.iptv.shared.xtream.LiveStream
import kotlin.coroutines.cancellation.CancellationException

data class PublishReport(
    val categories: Int,
    val publishedCategories: Int,
    val channels: Int,
    val failedCategories: List<String> = emptyList(),
)

/** FNV-1a 64 bits, en hexadécimal (16 caractères), sur `id|name|norm|number` une ligne par chaîne. */
fun fingerprint(streams: List<LiveStream>): String {
    var h = -3750763034362895579L // 0xcbf29ce484222325
    val prime = 0x100000001b3L
    val text = streams.joinToString("\n") { "${it.id}|${it.name}|${searchNorm(it.name)}|${it.number ?: ""}" }
    for (b in text.encodeToByteArray()) {
        h = (h xor (b.toLong() and 0xff)) * prime
    }
    return h.toULong().toString(16).padStart(16, '0')
}

/**
 * Publie la copie légère du direct (catégories et chaînes, sans icône ni identifiant EPG) vers Supabase.
 * Les chaînes d'une catégorie ne sont renvoyées que si leur empreinte diffère de celle du serveur ;
 * l'empreinte n'est écrite par le serveur qu'au dernier morceau.
 */
class CatalogPublisher(
    private val sync: SyncClient,
    private val repo: CatalogRepository,
    private val chunkSize: Int = 2000,
) {
    suspend fun publish(householdId: String, report: RefreshReport): PublishReport {
        val provider = report.providerCategories
        if (provider.isEmpty()) return PublishReport(0, 0, 0)

        val remote = sync.publishLiveCategories(
            householdId,
            provider.mapIndexed { i, c -> PublishedCategory(c.id, c.name, i) },
        )
        val cached = repo.categories().map { it.id }.toSet()
        var publishedCategories = 0
        var channels = 0
        val failed = mutableListOf<String>()
        for (cat in provider) {
            if (cat.id !in cached) continue
            try {
                val streams = repo.streams(cat.id)
                val fp = fingerprint(streams)
                if (remote[cat.id] == fp) continue
                val rows = streams.mapIndexed { i, s ->
                    PublishedChannel(s.id, s.name, searchNorm(s.name), s.number, i)
                }
                val chunks = rows.chunked(chunkSize).ifEmpty { listOf(emptyList()) }
                chunks.forEachIndexed { i, part ->
                    sync.publishLiveChannels(householdId, cat.id, fp, i, i == chunks.lastIndex, part)
                }
                publishedCategories++
                channels += rows.size
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += cat.id
            }
        }
        return PublishReport(provider.size, publishedCategories, channels, failed)
    }
}
