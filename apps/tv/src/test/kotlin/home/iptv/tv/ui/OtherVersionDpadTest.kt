package home.iptv.tv.ui

import home.iptv.shared.progress.*
import home.iptv.shared.versions.Lang
import home.iptv.shared.versions.Version
import home.iptv.shared.versions.VersionTag
import home.iptv.tv.feature.MapVersionMemory
import home.iptv.tv.movies.*
import home.iptv.tv.player.PlayerController
import home.iptv.tv.series.ProgressFlusher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * « Essayer une autre version » (0.41, idée du propriétaire) : un film qui ne démarre pas propose la version suivante,
 * avant « Réessayer », à la télécommande ; la version qui marche est ensuite retenue à la place de celle en panne.
 */
@RunWith(RobolectricTestRunner::class)
class OtherVersionDpadTest {
    @get:Rule val rule = tvRule()
    private val scope = CoroutineScope(SupervisorJob() + testMain())
    @After fun tearDown() { scope.cancel() }

    private val log = mutableListOf<String>()
    private val controller = PlayerController { FakePlayer(log) }
    private val queue = ProgressQueue(object : ProgressClient {
        override suspend fun record(p: String, entry: ProgressEntry): Boolean = true
        override suspend fun continueWatching(p: String, max: Int) = emptyList<ProgressRow>()
        override suspend fun setWatched(p: String, items: List<WatchedItem>, watched: Boolean) {}
        override suspend fun setSeriesList(p: String, seriesId: String, on: Boolean) {}
        override suspend fun seriesProgress(p: String, seriesId: String) = emptyList<ProgressRow>()
    }, MovieQueueStore()) { Instant.fromEpochSeconds(1) }
    // Catégorie masquée : la lecture échoue tout de suite (comme une version en panne).
    private val playback = MoviePlayback("p", controller, queue, ProgressFlusher(queue, scope), trackStore, { Instant.fromEpochSeconds(1) }, scope,
        { "fixture" }, { id, _ -> home.iptv.shared.xtream.MovieInfo(movie(id, "c")) }, { setOf("c") }, kotlinx.coroutines.Dispatchers.IO, { "c" })

    @Test fun version_en_panne_propose_la_suivante() {
        val other = Version("m4k", "c4k", "Film m", VersionTag(Lang.EN, uhd = true), null, null)
        var tried: Version? = null
        rule.setTvContent {
            MoviePlayerScreen(PlayMovieRequest("m", "c"), playback, controller, listOf(other), onTryOther = { tried = it }) {}
        }
        rule.until(timeoutMs = 10_000, what = "bouton autre version") { rule.onAllNodesWithTextCount("Essayer une autre version (Anglais · 4K)") > 0 }
        rule.until(what = "focus sur le bouton") { rule.focusedTexts()?.any { it.startsWith("Essayer une autre version") } == true }
        rule.ok()
        rule.until(what = "version essayée") { tried != null }
        assertEquals("m4k", tried?.id)
    }

    @Test fun sans_autre_version_reessayer_comme_avant() {
        rule.setTvContent { MoviePlayerScreen(PlayMovieRequest("m", "c"), playback, controller) {} }
        rule.until(timeoutMs = 10_000, what = "écran d'erreur") { rule.onAllNodesWithTextCount("Retour à la fiche") > 0 }
        assertEquals(0, rule.onAllNodes(androidx.compose.ui.test.hasText("Essayer une autre version", substring = true)).fetchSemanticsNodes().size)
    }

    @Test fun version_retenue_suivie_sans_boucle() {
        val m = MapVersionMemory()
        assertEquals("a", m.resolve("a"))
        m.remember("a", "b")
        assertEquals("b", m.resolve("a"))
        m.remember("b", "c")
        assertEquals("c", m.resolve("a"), "de proche en proche")
        assertEquals("b", m.resolve("a") { it != "c" }, "version disparue : on s'arrête avant")
        m.remember("c", "a") // a remarche : plus de boucle
        assertEquals("a", m.resolve("a"))
        assertEquals("a", m.resolve("c"))
    }
}
