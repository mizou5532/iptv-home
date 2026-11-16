package home.iptv.tv.ui

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import home.iptv.shared.catalog.CatalogDatabase
import home.iptv.shared.catalog.CatalogRepository
import home.iptv.shared.catalog.LiveSource
import home.iptv.shared.sync.Profile
import home.iptv.shared.xtream.LiveCategory
import home.iptv.shared.xtream.LiveStream
import home.iptv.tv.live.LiveBrowseViewModel
import home.iptv.tv.live.LiveRoute
import home.iptv.tv.player.Player
import home.iptv.tv.player.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal val PROFILE = Profile("p1", "Papa", "#3366ff", "live", "dark", 0)

/** Faux lecteur : jamais Media3 dans les tests. */
internal class FakePlayer(val log: MutableList<String>) : Player {
    override fun play(url: String) { log += "play" }
    override fun play(url: String, startMs: Long) { log += "play@$startMs" }
    override fun stop() { log += "stop" }
    override fun release() { log += "release" }
    /** Durée simulée (0 = inconnue, comme avant) : sert aux tests de la barre de progression. */
    var durationMs = 0L
    override fun snapshot() = if (durationMs > 0) home.iptv.tv.player.PlaybackSnapshot(positionMs = 60_000, durationMs = durationMs) else home.iptv.tv.player.PlaybackSnapshot()
    override fun seekTo(positionMs: Long) { log += "seek@$positionMs" }
    override fun setPaused(paused: Boolean) { log += "paused=$paused" }
}

internal fun liveRepo(cats: Int = 3, perCat: Int = 4): CatalogRepository {
    // Sous Robolectric, DriverManager ne trouve pas toujours le pilote SQLite tout seul.
    Class.forName("org.sqlite.JDBC")
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    CatalogDatabase.Schema.create(driver)
    val repo = CatalogRepository(CatalogDatabase(driver))
    val categories = (1..cats).map { LiveCategory("$it", "Categorie $it") }
    runBlocking {
        repo.refreshLive(object : LiveSource {
            override suspend fun liveCategories() = categories
            override suspend fun liveStreams(categoryId: String) =
                (1..perCat).map { n -> LiveStream("$categoryId$n", "Chaine $categoryId-$n", categoryId, null, null, n) }
        }, emptySet())
    }
    return repo
}

@RunWith(RobolectricTestRunner::class)

class LiveDpadTest {
    @get:Rule val rule = tvRule()
    private val scope = CoroutineScope(SupervisorJob() + testMain())
    private val log = mutableListOf<String>()

    @After fun tearDown() { scope.cancel() }

    private fun showLive() {
        val vm = LiveBrowseViewModel(liveRepo(), refresh = null, scope = scope, io = Dispatchers.IO)
        val player = PlayerController { FakePlayer(log) }
        rule.setTvContent {
            LiveRoute(vm, PROFILE, player, urlFor = { "fake://${it.id}" }, epg = null, onChangeProfile = {})
        }
        rule.until { rule.focusedTexts()?.any { it.startsWith("Categorie 1") } == true }
    }

    @Test
    fun droite_depuis_une_categorie_va_sur_une_chaine() {
        showLive()
        rule.until { rule.onAllNodesWithTextCount("Chaine 1-1") > 0 }
        rule.right()
        val focused = rule.focusedTexts()
        assertTrue(focused?.any { it.contains("Chaine 1-") } == true, "focus après Droite : $focused")
    }

    @Test
    fun bas_puis_droite_va_sur_une_chaine_de_la_nouvelle_categorie() {
        showLive()
        rule.down()
        rule.until { rule.onAllNodesWithTextCount("Chaine 2-1") > 0 }
        rule.right()
        val focused = rule.focusedTexts()
        assertTrue(focused?.any { it.contains("Chaine 2-") } == true, "focus après Bas, Droite : $focused")
    }

    @Test
    fun gauche_depuis_une_chaine_revient_aux_categories() {
        showLive()
        rule.until { rule.onAllNodesWithTextCount("Chaine 1-1") > 0 }
        rule.right()
        rule.left()
        val focused = rule.focusedTexts()
        assertTrue(focused?.any { it.startsWith("Categorie 1") } == true, "focus après Droite, Gauche : $focused")
    }

    @Test
    fun apercu_puis_plein_ecran_puis_retour_rend_le_focus_a_la_chaine() {
        showLive()
        rule.until { rule.onAllNodesWithTextCount("Chaine 1-1") > 0 }
        rule.right()
        rule.down()
        assertTrue(rule.focusedTexts()?.any { it.contains("Chaine 1-2") } == true, "focus : ${rule.focusedTexts()}")
        rule.ok()
        rule.until(what = "aperçu lancé") { log.contains("play") }
        rule.until(what = "boutons de l'aperçu") { rule.onAllNodesWithTextCount("Sources") > 0 }
        rule.right()
        assertTrue(rule.focusedTexts()?.any { it == "Sources" || it == "Arrêter" } == true, "Droite vers les boutons de l'aperçu : ${rule.focusedTexts()}")
        rule.left()
        assertTrue(rule.focusedTexts()?.any { it.contains("Chaine 1-") } == true, "Gauche revient aux chaînes : ${rule.focusedTexts()}")
        repeat(3) { if (rule.focusedTexts()?.any { it.contains("Chaine 1-2") } != true) rule.up() }
        rule.ok() // plein écran
        rule.until(what = "plein écran") { rule.onAllNodesWithTextCount("Categorie 1") == 0 }
        assertEquals(1, log.count { it == "play" }, "le passage en plein écran ne relance pas le flux")
        rule.back()
        rule.until(what = "retour à la liste, focus sur la chaîne") { rule.focusedTexts()?.any { it.contains("Chaine 1-2") } == true }
    }

    /** Le focus est sur une copie du panneau « Sources » (sa ligne dit « Catégorie · En lecture »). */
    private fun onSourceRow() = rule.focusedTexts()?.any { it.contains("· En lecture") } == true

    @Test
    fun panneau_sources_depuis_l_apercu_prend_le_focus_et_le_garde() {
        showLive()
        rule.until { rule.onAllNodesWithTextCount("Chaine 1-1") > 0 }
        rule.right()
        rule.ok()
        rule.until(what = "aperçu lancé") { log.contains("play") }
        rule.until(what = "boutons de l'aperçu") { rule.onAllNodesWithTextCount("Sources") > 0 }
        rule.right()
        repeat(2) { if (rule.focusedTexts()?.contains("Sources") != true) rule.left() }
        assertTrue(rule.focusedTexts()?.contains("Sources") == true, "focus sur le bouton Sources : ${rule.focusedTexts()}")
        rule.ok()
        rule.until(what = "le panneau prend le focus sur la copie en lecture") { onSourceRow() }
        // Les flèches restent dans le panneau : jamais sur une chaîne de la liste derrière, aucun nouveau flux.
        rule.down()
        assertTrue(rule.focusedTexts()?.any { it == "Arrêter" || it == "Fermer" } == true, "Bas dans le panneau : ${rule.focusedTexts()}")
        rule.left(); rule.left(); rule.up()
        assertTrue(onSourceRow(), "Haut revient à la copie : ${rule.focusedTexts()}")
        assertEquals(1, log.count { it == "play" })
        rule.back()
        rule.until(what = "Retour ferme le panneau et rend le focus au bouton Sources") { rule.focusedTexts()?.contains("Sources") == true }
    }

    @Test
    fun panneau_sources_en_plein_ecran_prend_le_focus_et_les_fleches_ne_changent_pas_de_chaine() {
        showLive()
        rule.until { rule.onAllNodesWithTextCount("Chaine 1-1") > 0 }
        rule.right()
        rule.ok()
        rule.until(what = "aperçu lancé") { log.contains("play") }
        rule.ok() // plein écran
        rule.until(what = "plein écran") { rule.onAllNodesWithTextCount("Categorie 1") == 0 }
        rule.ok() // commandes
        rule.until(what = "commandes, focus sur Sources") { rule.focusedTexts()?.contains("Sources") == true }
        rule.ok() // panneau
        rule.until(what = "le panneau prend le focus sur la copie en lecture") { onSourceRow() }
        rule.down(); rule.up(); rule.up()
        assertEquals(1, log.count { it == "play" }, "les flèches ne changent pas de chaîne sous le panneau")
        assertTrue(onSourceRow() || rule.focusedTexts()?.any { it == "Arrêter" || it == "Fermer" } == true, "focus resté dans le panneau : ${rule.focusedTexts()}")
        rule.back()
        rule.until(what = "panneau fermé") { rule.onAllNodesWithTextCount("· En lecture") == 0 }
        assertEquals(1, log.count { it == "play" })
    }

    @Test
    fun plein_ecran_un_seul_retour_ferme_les_commandes_puis_un_autre_revient_a_la_liste() {
        showLive()
        rule.until { rule.onAllNodesWithTextCount("Chaine 1-1") > 0 }
        rule.right()
        rule.ok()
        rule.until(what = "aperçu lancé") { log.contains("play") }
        rule.ok() // plein écran
        rule.until(what = "plein écran") { rule.onAllNodesWithTextCount("Categorie 1") == 0 }
        rule.ok() // commandes
        rule.until(what = "commandes, focus sur Sources") { rule.focusedTexts()?.contains("Sources") == true }
        rule.back()
        // Sans attendre : la minuterie des commandes ne doit pas masquer un Retour avalé.
        assertEquals(0, rule.onAllNodesWithTextCount("Arrêter"), "un seul Retour ferme les commandes (focus : ${rule.focusedTexts()})")
        rule.back()
        rule.until(what = "le Retour suivant revient à la liste") { rule.focusedTexts()?.any { it.contains("Chaine 1-1") } == true }
        assertEquals(1, log.count { it == "play" })
    }

    @Test
    fun ok_lance_l_apercu_puis_droite_reste_visible() {
        showLive()
        rule.until { rule.onAllNodesWithTextCount("Chaine 1-1") > 0 }
        rule.right()
        rule.ok()
        rule.until { log.contains("play") }
        rule.right()
        val focused = rule.focusedTexts()
        assertTrue(focused != null && focused.isNotEmpty(), "focus après aperçu puis Droite : $focused")
        assertEquals(1, log.count { it == "play" })
    }
}


internal fun TvRule.onAllNodesWithTextCount(text: String): Int =
    onAllNodes(androidx.compose.ui.test.hasText(text, substring = true)).fetchSemanticsNodes().size
