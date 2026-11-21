package home.iptv.tv.health

import home.iptv.shared.sync.ExtraAccount
import home.iptv.shared.xtream.UserInfo
import home.iptv.shared.xtream.XtreamError
import home.iptv.tv.home.HomeState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private const val NOW = 1_800_000_000L

private open class Probes(
    val internet: () -> Long = { 120 }, val speed: () -> Double = { 48.0 }, val home: () -> Long = { 90 },
    val prov: () -> Pair<UserInfo, Long> = { UserInfo(true, 1, emptyList(), NOW + 40 * 86_400, "Active", activeConnections = 1) to 300 },
    val extras: List<ExtraAccount> = emptyList(),
    val homeBox: HomeState = HomeState.Present("0.43.0"),
) : HealthProbes {
    override suspend fun internetMs() = internet()
    override suspend fun downloadMbps() = speed()
    override suspend fun homeServerMs() = home()
    override suspend fun provider() = prov()
    override suspend fun extraAccounts() = extras
    override fun catalog() = "terminée, 105 catégories"
    override fun version() = "0.36.0"
    override fun homeCatalogServer() = homeBox
}

class HealthCheckTest {
    private suspend fun run(p: HealthProbes) = HealthCheck(p) { NOW }.run().associate { it.label to (it.level to it.value) }

    @Test fun tout_va_bien() = runTest {
        val r = run(Probes(extras = listOf(ExtraAccount("a", 2, "u", "p", "ok"))))
        assertEquals(Level.OK to "Connecté", r["Internet"])
        assertEquals(Level.OK to "48 Mb/s : assez pour la 4K", r["Vitesse"])
        assertEquals(Level.OK, r["Fournisseur IPTV"]!!.first)
        assertEquals(Level.OK, r["Abonnement"]!!.first)
        kotlin.test.assertTrue(r["Abonnement"]!!.second.matches(Regex("Jusqu'au \\d+ février 2027 \\(dans 40 jours\\)")), r["Abonnement"]!!.second)
        assertEquals(Level.WARN to "1 sur 1 permise", r["Vidéos en cours"])
        assertEquals(Level.OK to "Vérifié, prêt à servir", r["Compte 2"])
        assertEquals(Level.OK to "présent (0.43.0)", r["Serveur maison"])
        assertEquals("0.36.0", r["Version de l'app"]!!.second)
    }

    @Test fun serveur_maison_present_ou_absent() = runTest {
        assertEquals(Level.OK to "présent (0.43.0)", run(Probes(homeBox = HomeState.Present("0.43.0")))["Serveur maison"])
        assertEquals(Level.OK to "présent", run(Probes(homeBox = HomeState.Present(null)))["Serveur maison"])
        assertEquals(Level.UNKNOWN to "absent (la télé utilise son secours)", run(Probes(homeBox = HomeState.Absent))["Serveur maison"])
        assertEquals(Level.UNKNOWN to "absent (pas encore interrogé)", run(Probes(homeBox = HomeState.Unknown))["Serveur maison"])
    }

    @Test fun panne_du_fournisseur_alors_qu_internet_marche() = runTest {
        val r = run(Probes(prov = { throw XtreamError.Unreachable }))
        assertEquals(Level.BAD to "Ne répond pas : panne chez le fournisseur, ce n'est pas ta télé", r["Fournisseur IPTV"])
        assertEquals(Level.OK, r["Internet"]!!.first)
    }

    @Test fun pas_d_internet_le_reste_n_est_pas_accuse() = runTest {
        val down: () -> Nothing = { throw java.io.IOException("x") }
        val r = run(Probes(internet = down, speed = down, home = down, prov = { throw XtreamError.Unreachable }))
        assertEquals(Level.BAD, r["Internet"]!!.first)
        assertEquals(Level.UNKNOWN, r["Fournisseur IPTV"]!!.first)
        assertEquals(Level.UNKNOWN, r["Serveur de la maison"]!!.first)
    }

    @Test fun compte_refuse_expire_et_debit_faible() = runTest {
        assertEquals(Level.BAD, run(Probes(prov = { throw XtreamError.Refused }))["Fournisseur IPTV"]!!.first)
        val r = run(Probes(speed = { 6.4 }, prov = { UserInfo(true, 1, emptyList(), NOW - 86_400, "Expired") to 200 }))
        assertEquals(Level.BAD to "Abonnement inactif ou expiré", r["Fournisseur IPTV"])
        assertEquals(Level.BAD, r["Vitesse"]!!.first)
    }
}
