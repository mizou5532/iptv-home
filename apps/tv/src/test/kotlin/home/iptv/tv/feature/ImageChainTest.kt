package home.iptv.tv.feature

import androidx.compose.foundation.text.BasicText
import home.iptv.tv.ui.setTvContent
import home.iptv.tv.ui.tvRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/** Affiche en panne (lien cassé chez le fournisseur, « FR - South Park », 24 nov.) : on passe à l'image suivante. */
@RunWith(RobolectricTestRunner::class)
class ImageChainTest {
    @get:Rule val rule = tvRule()

    @Test fun une_image_qui_ne_charge_pas_laisse_la_place_a_la_suivante() {
        var chain: ImageChain? = null
        rule.setTvContent {
            val c = rememberImageChain(listOf("https://fournisseur.invalide/a.jpg", null, "https://image.tmdb.org/b.jpg"))
            chain = c
            BasicText(c.url ?: "aucune")
        }
        assertEquals("https://fournisseur.invalide/a.jpg", chain!!.url)
        rule.runOnIdle { chain!!.failed() }
        rule.waitForIdle()
        assertEquals("https://image.tmdb.org/b.jpg", chain!!.url, "la case vide est remplacée par l'affiche officielle")
        rule.runOnIdle { chain!!.failed() } // plus rien après : on garde la dernière (texte du titre en dessous)
        rule.waitForIdle()
        assertEquals("https://image.tmdb.org/b.jpg", chain!!.url)
    }
}
