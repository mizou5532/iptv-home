package home.iptv.tv.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import home.iptv.tv.data.HomeTab
import home.iptv.tv.nav.SectionBar
import home.iptv.tv.update.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Bouton « Mise à jour » de la barre du haut, à la télécommande. */
@RunWith(RobolectricTestRunner::class)
class UpdateDpadTest {
    @get:Rule val rule = tvRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dir: File = Files.createTempDirectory("maj").toFile()
    @Volatile private var remoteCode = 6
    @Volatile private var allowed = true
    @Volatile private var installed: File? = null
    @Volatile private var permissionOpened = 0
    private val platform = object : UpdatePlatform {
        override val installedVersionCode = 6L
        override suspend fun fetchVersion() = """{"versionCode":$remoteCode,"versionName":"0.5.1"}"""
        override suspend fun download(target: File, progress: (Int) -> Unit) { target.writeText("apk"); progress(100) }
        override fun canInstall() = allowed
        override fun openInstallPermission() { permissionOpened++ }
        override fun install(apk: File) { installed = apk }
        override val workDir = File(dir, "u")
    }
    private val updater = Updater(platform, scope)
    @After fun tearDown() { scope.cancel() }

    private fun focusedHas(text: String) = rule.focusedTexts()?.any { it.contains(text) } == true
    private fun show() = rule.setTvContent {
        val first = remember { FocusRequester() }
        CompositionLocalProvider(LocalUpdater provides updater) { UpdateHost { SectionBar(HomeTab.DIRECT, {}, first) } }
    }

    @Test fun a_jour_pas_de_bouton() {
        show(); updater.check(force = true)
        Thread.sleep(200); rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithTextCount("Mise à jour"))
    }

    @Test fun nouvelle_version_bouton_panneau_puis_installation() {
        remoteCode = 7; show(); updater.check(force = true)
        rule.until(what = "bouton affiché") { rule.onAllNodesWithTextCount("Mise à jour") > 0 }
        repeat(4) { if (!focusedHas("Mise à jour")) rule.right() }
        assertTrue(focusedHas("Mise à jour"), "${rule.focusedTexts()}")
        rule.ok()
        rule.until(what = "panneau ouvert, focus sur Mettre à jour") { focusedHas("Mettre à jour") }
        assertTrue(rule.onAllNodesWithTextCount("Nouvelle version 0.5.1 disponible.") > 0)
        rule.ok()
        rule.until(what = "installation lancée") { installed != null }
        rule.until(what = "texte d'installation") { rule.onAllNodesWithTextCount("Installation de la version 0.5.1…") > 0 }
    }

    @Test fun sans_autorisation_le_panneau_ouvre_le_reglage_et_plus_tard_ferme() {
        remoteCode = 7; allowed = false; show(); updater.check(force = true)
        rule.until { rule.onAllNodesWithTextCount("Mise à jour") > 0 }
        repeat(4) { if (!focusedHas("Mise à jour")) rule.right() }
        rule.ok(); rule.until { focusedHas("Mettre à jour") }; rule.ok()
        rule.until(what = "explication de l'autorisation") { focusedHas("Ouvrir le réglage") }
        rule.ok(); rule.until { permissionOpened == 1 }
        rule.right(); assertTrue(focusedHas("Plus tard"), "${rule.focusedTexts()}")
        rule.ok(); rule.until(what = "panneau fermé") { rule.onAllNodesWithTextCount("Ouvrir le réglage") == 0 }
        rule.until(what = "focus rendu au bouton") { focusedHas("Mise à jour") }
        assertTrue(installed == null)
    }
}
