package home.iptv.tv.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** Témoin : le banc d'essai lui-même déplace bien le focus à la télécommande. */
@OptIn(ExperimentalTvMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HarnessTest {
    @get:Rule val rule = tvRule()

    @Test
    fun droite_et_bas_deplacent_le_focus_entre_elements_cliquables() {
        rule.setTvContent {
            val first = remember { FocusRequester() }
            LaunchedEffect(Unit) { first.requestFocus() }
            Column {
                Row {
                    Text("A", Modifier.size(100.dp).focusRequester(first).clickable {})
                    Text("B", Modifier.size(100.dp).clickable {})
                }
                Text("C", Modifier.size(100.dp).clickable {})
            }
        }
        assertEquals(listOf("A"), rule.focusedTexts())
        rule.right()
        assertEquals(listOf("B"), rule.focusedTexts())
        rule.left()
        rule.down()
        assertEquals(listOf("C"), rule.focusedTexts())
    }
}
