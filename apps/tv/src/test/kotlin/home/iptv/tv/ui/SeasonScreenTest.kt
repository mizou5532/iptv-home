package home.iptv.tv.ui

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import home.iptv.shared.account.Day
import home.iptv.shared.account.Season
import home.iptv.shared.account.seasonGreeting
import home.iptv.shared.sync.Profile
import home.iptv.tv.startup.WhoIsWatchingScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Thèmes de saison : décor et mot du jour sur les deux thèmes de « Qui regarde ? », sans gêner le choix du profil.
 * Avec -Dseason.shots=<dossier>, enregistre une image par fête et par thème (contrôle visuel).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
class SeasonScreenTest {
    @get:Rule val rule = tvRule()
    private val profiles = listOf(
        Profile("p1", "Hamza", "#8FB4FF", "series", "sombre", 0),
        Profile("p2", "Maha", "#F4B6C8", "series", "clair", 1),
        Profile("p3", "Mahmoud", "#FFC46B", "sport", "sombre", 2),
    )

    @Test fun chaque_fete_sur_les_deux_themes() {
        val shots = System.getProperty("season.shots")?.let { java.io.File(it).apply { mkdirs() } }
        val current = androidx.compose.runtime.mutableStateOf(Season.NOEL to "doux")
        val picked = mutableListOf<String>()
        rule.setTvContent {
            val (season, theme) = current.value
            WhoIsWatchingScreen(profiles, offline = false, theme = theme, season = season,
                greeting = seasonGreeting(season, Day(2026, 12, 25))) { picked += it.id }
        }
        for (season in Season.entries) for (theme in listOf("doux", "aurore")) {
            current.value = season to theme
            rule.until(what = "${season.id} $theme") {
                rule.onAllNodes(hasTestTag("season-greeting")).fetchSemanticsNodes().isNotEmpty() &&
                    rule.onAllNodes(hasTestTag("season-layer")).fetchSemanticsNodes().isNotEmpty()
            }
            shots?.let { dir ->
                // Dessin direct de la vue (captureToImage attend un repos que les animations sans fin n'offrent jamais).
                var bmp: android.graphics.Bitmap? = null
                rule.runOnUiThread {
                    val v = rule.activity.window.decorView
                    bmp = android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888)
                        .also { v.draw(android.graphics.Canvas(it)) }
                }
                val out = bmp!!
                java.io.File(dir, "${season.id}-$theme.png").outputStream().use { out.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }

    @Test fun le_decor_ne_prend_pas_le_focus() {
        val picked = mutableListOf<String>()
        rule.setTvContent { WhoIsWatchingScreen(profiles, offline = false, theme = "aurore", season = Season.NOEL, greeting = "Joyeux Noël !") { picked += it.id } }
        rule.until(what = "profil focalisé") { rule.focusedTexts() != null }
        rule.ok(); rule.until { picked.isNotEmpty() }
        assertEquals("p1", picked.first())
    }

    @Test fun sans_fete_pas_de_decor() {
        rule.setTvContent { WhoIsWatchingScreen(profiles, offline = false) {} }
        rule.until(what = "titre") { rule.onAllNodes(hasText("Qui regarde ?", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, rule.onAllNodes(hasTestTag("season-layer")).fetchSemanticsNodes().size)
    }
}
