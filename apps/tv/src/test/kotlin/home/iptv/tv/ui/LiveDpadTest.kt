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
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal val PROFILE = Profile("p1", "Papa", "#3366ff", "live", "dark", 0)

/** Faux lecteur : jamais Media3 dans les tests. */
internal class FakePlayer(val log: MutableList<String>) : Player {
    override fun play(url: String) { log += "play" }
    override fun play(url: String, startMs: Long) { log += "play@$startMs" }
    override fun stop() { log += "stop" }
    override fun release() { log += "release" }
}

internal fun liveRepo(cats: Int = 3, perCat: Int = 4): CatalogRepository {
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
@Config(sdk = [35])
class LiveDpadTest {
    @get:Rule val rule = tvRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
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
        assertTrue(focused?.any { it.startsWith("Chaine 1-") } == true, "focus après Droite : $focused")
    }

    @Test
    fun bas_puis_droite_va_sur_une_chaine_de_la_nouvelle_categorie() {
        showLive()
        rule.down()
        rule.until { rule.onAllNodesWithTextCount("Chaine 2-1") > 0 }
        rule.right()
        val focused = rule.focusedTexts()
        assertTrue(focused?.any { it.startsWith("Chaine 2-") } == true, "focus après Bas, Droite : $focused")
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
