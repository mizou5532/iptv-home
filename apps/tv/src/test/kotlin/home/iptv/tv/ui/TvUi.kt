package home.iptv.tv.ui

import android.view.KeyEvent
import kotlinx.coroutines.android.asCoroutineDispatcher
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import home.iptv.tv.theme.IptvTheme

typealias TvRule = AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>

fun tvRule(): TvRule = createAndroidComposeRule<ComponentActivity>()

/**
 * Contenu tel qu'à la télé : thème de l'app et mode « télécommande » (non tactile), sans lequel Compose ne rend
 * pas les éléments cliquables focalisables.
 */
fun TvRule.setTvContent(content: @Composable () -> Unit) {
    setContent {
        val modes = LocalInputModeManager.current
        SideEffect { if (modes.inputMode != InputMode.Keyboard) modes.requestInputMode(InputMode.Keyboard) }
        IptvTheme { content() }
    }
    runOnIdle { activity.window.decorView.rootView.requestFocus() }
    waitForIdle()
}

/** Un appui (bas puis haut) sur une touche de la télécommande, par le vrai chemin Android (fenêtre de l'activité). */
fun TvRule.press(keyCode: Int) {
    runOnUiThread {
        val view = activity.window.decorView
        view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }
    waitForIdle()
}

fun TvRule.right() = press(KeyEvent.KEYCODE_DPAD_RIGHT)
fun TvRule.left() = press(KeyEvent.KEYCODE_DPAD_LEFT)
fun TvRule.up() = press(KeyEvent.KEYCODE_DPAD_UP)
fun TvRule.down() = press(KeyEvent.KEYCODE_DPAD_DOWN)
fun TvRule.ok() = press(KeyEvent.KEYCODE_DPAD_CENTER)
fun TvRule.back() = press(KeyEvent.KEYCODE_BACK)

/** Textes du nœud qui a le focus (fusionnés), ou `null` si rien n'a le focus dans Compose. */
fun TvRule.focusedTexts(): List<String>? {
    val nodes = onAllNodes(isFocused()).fetchSemanticsNodes()
    val node: SemanticsNode = nodes.firstOrNull() ?: return null
    return node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty() +
        node.config.getOrNull(SemanticsProperties.TestTag)?.let { listOf("#$it") }.orEmpty()
}

/** Attend (horloge réelle, ViewModels sur des fils) qu'une condition devienne vraie. */
fun TvRule.until(timeoutMs: Long = 5_000, what: String = "", detail: () -> String = { "" }, condition: () -> Boolean) {
    try {
        waitUntil(timeoutMs) { condition() }
    } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
        throw AssertionError("attente dépassée : $what ${detail()} (focus : ${focusedTexts()})", e)
    }
}

/**
 * Fil principal de CE test. `Dispatchers.Main` reste lié au fil principal du premier test de la machine virtuelle :
 * sous Robolectric, un test suivant voyait parfois ses reprises sur Main ne jamais s'exécuter.
 */
fun testMain(): kotlinx.coroutines.CoroutineDispatcher =
    android.os.Handler(android.os.Looper.getMainLooper()).asCoroutineDispatcher()
