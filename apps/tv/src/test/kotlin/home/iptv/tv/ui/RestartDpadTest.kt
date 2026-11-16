package home.iptv.tv.ui

import home.iptv.tv.startup.WhoIsWatchingScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/** « Qui regarde ? » : Bas depuis les profils mène à « Redémarrer l'application ». */
@RunWith(RobolectricTestRunner::class)
class RestartDpadTest {
    @get:Rule val rule = tvRule()

    @Test fun bas_puis_ok_redemarre() {
        var restarts = 0
        rule.setTvContent { WhoIsWatchingScreen(listOf(PROFILE), offline = false, onRestart = { restarts++ }) {} }
        // Le focus est sur la vignette du profil (son nom est dessous, hors de la vignette).
        rule.until(what = "profil focalisé") { rule.focusedTexts() != null && rule.focusedTexts()?.none { it.contains("Redémarrer") } == true }
        rule.down()
        rule.until(what = "bouton focalisé") { rule.focusedTexts()?.any { it.contains("Redémarrer") } == true }
        rule.ok()
        rule.until { restarts == 1 }
        assertEquals(1, restarts)
    }
}
