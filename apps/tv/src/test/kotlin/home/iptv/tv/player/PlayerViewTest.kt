package home.iptv.tv.player

import android.view.TextureView
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Essai TCL 0.4.0 : plein écran noir (son présent) après l'aperçu, jusqu'à ce que quelque chose soit dessiné par-dessus.
 * La vue vidéo doit être une TextureView (image dans la hiérarchie des vues, sans « trou » de SurfaceView à recalculer).
 */
@RunWith(RobolectricTestRunner::class)
class PlayerViewTest {
    @Test
    fun la_vue_video_est_une_texture_sans_commandes_et_garde_l_ecran_allume() {
        val view = newPlayerView(ApplicationProvider.getApplicationContext())
        assertTrue(view.videoSurfaceView is TextureView, "vue vidéo : ${view.videoSurfaceView?.javaClass?.simpleName}")
        assertFalse(view.useController)
        assertTrue(view.keepScreenOn)
    }
}
