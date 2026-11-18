package home.iptv.tv.ui

import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import home.iptv.tv.player.ExoPlayerPlayer
import home.iptv.tv.player.VideoSurface
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Films, épisodes et Sport : la vidéo s'affiche dans une SurfaceView (HDR, cadence, moins de travail pour l'appareil). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
class VideoSurfaceTest {
    @get:Rule val rule = tvRule()

    private fun View.all(): List<View> = listOf(this) + ((this as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { g.getChildAt(it).all() } } ?: emptyList())

    @Test fun surface_view_ecran_allume_puis_retabli_en_quittant() {
        lateinit var player: ExoPlayerPlayer
        rule.runOnUiThread { player = ExoPlayerPlayer(rule.activity) }
        val shown = mutableStateOf(true)
        rule.setTvContent { if (shown.value) VideoSurface(player, Modifier.fillMaxSize()) }
        rule.waitForIdle()
        val root = rule.activity.window.decorView
        assertTrue(root.all().any { it is SurfaceView }, "une SurfaceView doit afficher la vidéo")
        assertTrue(root.all().any { it.keepScreenOn }, "écran maintenu allumé pendant la lecture")
        assertTrue(player.viewAttached)
        shown.value = false
        rule.waitForIdle()
        assertFalse(root.all().any { it is SurfaceView })
        assertFalse(root.all().any { it.keepScreenOn }, "l'écran peut de nouveau se mettre en veille")
        assertEquals(false, player.viewAttached)
        rule.runOnUiThread { player.release() }
    }
}
