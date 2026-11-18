package home.iptv.tv.health

import home.iptv.shared.sync.ExtraAccount
import home.iptv.shared.xtream.UserInfo
import home.iptv.shared.xtream.XtreamError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** Vert, orange, rouge, ou gris (pas encore connu / pas applicable). */
enum class Level { OK, WARN, BAD, UNKNOWN }

/** Une ligne du bilan : « Internet · 42 Mb/s, assez pour la 4K ». */
data class HealthLine(val label: String, val value: String, val level: Level)

/** Ce que le bilan mesure ; chaque mesure lève une exception si elle échoue. */
interface HealthProbes {
    /** Temps (ms) pour joindre internet (un petit fichier public). */
    suspend fun internetMs(): Long
    /** Débit de téléchargement (Mb/s), mesuré sur quelques mégaoctets. */
    suspend fun downloadMbps(): Double
    /** Temps (ms) pour joindre le serveur de la maison (réglages, profils). */
    suspend fun homeServerMs(): Long
    /** Ce que le fournisseur dit du compte principal, et le temps de réponse (ms). */
    suspend fun provider(): Pair<UserInfo, Long>
    suspend fun extraAccounts(): List<ExtraAccount>
    /** État de la dernière mise à jour des chaînes du Direct (texte du diagnostic). */
    fun catalog(): String
    fun version(): String
    /** Dernière progression retrouvée après un changement de fournisseur ; `null` si jamais. */
    fun progressRemap(): String? = null
}

private const val PROBE_TIMEOUT_MS = 12_000L

/**
 * Bilan de santé de l'app (demande du propriétaire) : internet, débit, serveur de la maison, fournisseur IPTV, comptes,
 * catalogue et version, en phrases simples. Les mesures tournent en même temps ; aucune ne bloque plus de 12 s. Jamais
 * d'adresse ni d'identifiant dans le texte.
 */
class HealthCheck(private val probes: HealthProbes, private val nowS: () -> Long = { System.currentTimeMillis() / 1000 }) {
    suspend fun run(): List<HealthLine> = coroutineScope {
        val internet = async { attempt { probes.internetMs() } }
        val speed = async { attempt { probes.downloadMbps() } }
        val home = async { attempt { probes.homeServerMs() } }
        val provider = async { attemptProvider() }
        val extras = async { attempt { probes.extraAccounts() } }
        val net = internet.await()
        listOf(
            internetLine(net),
            speedLine(speed.await(), net != null),
            homeLine(home.await(), net != null),
            providerLine(provider.await(), net != null),
        ) + providerDetails(provider.await()) + extrasLines(extras.await()) + listOf(
            HealthLine("Chaînes du Direct", probes.catalog().ifBlank { "pas encore mises à jour depuis l'ouverture" }, Level.UNKNOWN),
            HealthLine("Version de l'app", probes.version(), Level.UNKNOWN),
        ) + listOfNotNull(probes.progressRemap()?.let { HealthLine("Progression (nouveau fournisseur)", it, Level.OK) })
    }

    private sealed interface ProviderResult {
        data class Ok(val info: UserInfo, val ms: Long) : ProviderResult
        data object Refused : ProviderResult
        data object Down : ProviderResult
    }

    private suspend fun attemptProvider(): ProviderResult = try {
        withTimeoutOrNull(PROBE_TIMEOUT_MS) { probes.provider() }?.let { (i, ms) -> ProviderResult.Ok(i, ms) } ?: ProviderResult.Down
    } catch (e: CancellationException) { throw e } catch (e: XtreamError.Refused) { ProviderResult.Refused } catch (e: Exception) { ProviderResult.Down }

    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        withTimeoutOrNull(PROBE_TIMEOUT_MS) { block() }
    } catch (e: CancellationException) { throw e } catch (e: Exception) { null }

    private fun internetLine(ms: Long?) = if (ms == null) HealthLine("Internet", "Pas de connexion : vérifie le Wi-Fi ou le modem", Level.BAD)
        else HealthLine("Internet", if (ms < 800) "Connecté" else "Connecté, mais lent à répondre ($ms ms)", if (ms < 800) Level.OK else Level.WARN)

    private fun speedLine(mbps: Double?, online: Boolean): HealthLine = when {
        !online -> HealthLine("Vitesse", "—", Level.UNKNOWN)
        mbps == null -> HealthLine("Vitesse", "Mesure impossible pour l'instant", Level.UNKNOWN)
        mbps >= 25 -> HealthLine("Vitesse", "${mbps.toInt()} Mb/s : assez pour la 4K", Level.OK)
        mbps >= 10 -> HealthLine("Vitesse", "${mbps.toInt()} Mb/s : bien pour la HD, juste pour la 4K", Level.WARN)
        else -> HealthLine("Vitesse", "${"%.1f".format(java.util.Locale.FRANCE, mbps)} Mb/s : faible, la vidéo peut s'arrêter", Level.BAD)
    }

    private fun homeLine(ms: Long?, online: Boolean) = when {
        ms != null -> HealthLine("Serveur de la maison", "Joignable (réglages, profils, progression)", Level.OK)
        !online -> HealthLine("Serveur de la maison", "—", Level.UNKNOWN)
        else -> HealthLine("Serveur de la maison", "Ne répond pas : les réglages de l'iPhone arriveront plus tard", Level.WARN)
    }

    private fun providerLine(p: ProviderResult, online: Boolean): HealthLine = when (p) {
        is ProviderResult.Ok -> {
            val active = p.info.status?.equals("Active", ignoreCase = true) != false && p.info.expiresAt?.let { it > nowS() } != false
            if (active) HealthLine("Fournisseur IPTV", if (p.ms < 2000) "Répond normalement" else "Répond lentement (${p.ms / 1000} s)", if (p.ms < 2000) Level.OK else Level.WARN)
            else HealthLine("Fournisseur IPTV", "Abonnement inactif ou expiré", Level.BAD)
        }
        ProviderResult.Refused -> HealthLine("Fournisseur IPTV", "Refuse le compte principal (identifiants changés ?)", Level.BAD)
        ProviderResult.Down -> if (online) HealthLine("Fournisseur IPTV", "Ne répond pas : panne chez le fournisseur, ce n'est pas ta télé", Level.BAD)
            else HealthLine("Fournisseur IPTV", "—", Level.UNKNOWN)
    }

    private fun providerDetails(p: ProviderResult): List<HealthLine> {
        val info = (p as? ProviderResult.Ok)?.info ?: return emptyList()
        val out = mutableListOf<HealthLine>()
        info.expiresAt?.let { end ->
            val days = ((end - nowS()) / 86_400).toInt()
            val date = java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale.CANADA_FRENCH).format(java.util.Date(end * 1000))
            out += HealthLine("Abonnement", if (days >= 0) "Jusqu'au $date (dans $days jour${if (days > 1) "s" else ""})" else "Terminé le $date",
                when { days < 0 -> Level.BAD; days <= 7 -> Level.BAD; days <= 30 -> Level.WARN; else -> Level.OK })
        }
        info.activeConnections?.let { a ->
            val max = info.maxConnections?.takeIf { it > 0 }
            out += HealthLine("Vidéos en cours", if (max != null) "$a sur $max permise${if (max > 1) "s" else ""}" else "$a",
                if (max != null && a >= max) Level.WARN else Level.OK)
        }
        info.message?.let { out += HealthLine("Message du fournisseur", it, Level.UNKNOWN) }
        return out
    }

    private fun extrasLines(list: List<ExtraAccount>?): List<HealthLine> {
        if (list.isNullOrEmpty()) return emptyList()
        return list.map { a ->
            when (a.status) {
                "ok" -> HealthLine("Compte ${a.position}", "Vérifié, prêt à servir", Level.OK)
                "refuse" -> HealthLine("Compte ${a.position}", "Refusé : voir la page iPhone (Réglages)", Level.BAD)
                else -> HealthLine("Compte ${a.position}", "En attente de vérification", Level.WARN)
            }
        }
    }
}
