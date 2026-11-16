package home.iptv.tv.update

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class UpdaterTest {
    private val dir: File = Files.createTempDirectory("maj").toFile()
    private val payload = "nouvelle version".toByteArray()
    private fun sha(bytes: ByteArray) = File(dir, "x").also { it.writeBytes(bytes) }.let { sha256Hex(it).also { _ -> it.delete() } }

    private inner class Fake(var versionJson: String, var installed: Long = 6) : UpdatePlatform {
        var allowed = true; var installedApk: File? = null; var fetches = 0; var opened = 0
        var bytes = payload; var failDownload = false
        override val installedVersionCode get() = installed
        override suspend fun fetchVersion(): String { fetches++; return versionJson }
        override suspend fun download(target: File, progress: (Int) -> Unit) {
            if (failDownload) throw java.io.IOException("coupure")
            progress(50); target.writeBytes(bytes); progress(100)
        }
        override fun canInstall() = allowed
        override fun openInstallPermission() { opened++ }
        override fun install(apk: File) { installedApk = apk }
        override val workDir = File(dir, "updates")
    }
    private fun json(code: Int, sha: String? = null, size: Long? = null) =
        """{"versionCode":$code,"versionName":"0.$code"${sha?.let { ",\"apkSha256\":\"$it\"" } ?: ""}${size?.let { ",\"apkSize\":$it" } ?: ""}}"""

    @Test fun lecture_tolerante_de_version_json() {
        assertEquals(RemoteVersion(7, "0.5.1", "ab", 10), parseRemoteVersion("""{"versionCode":7,"versionName":"0.5.1","apkSha256":"ab","apkSize":10,"autre":1}"""))
        assertEquals(RemoteVersion(6, "0.5.0"), parseRemoteVersion("""{"versionCode":6,"versionName":"0.5.0"}"""))
        assertNull(parseRemoteVersion("pas du json"))
        assertNull(parseRemoteVersion("""{"versionName":"0.5.1"}"""))
        assertNull(parseRemoteVersion("""{"versionCode":0,"versionName":"0"}"""))
        assertNull(parseRemoteVersion("[]"))
    }

    @Test fun meme_version_ou_plus_ancienne_rien_a_proposer() = runTest {
        val u = Updater(Fake(json(6)), this); u.check(); advanceUntilIdle()
        assertEquals(UpdateState.None, u.state.value)
        val v = Updater(Fake(json(5)), this); v.check(); advanceUntilIdle()
        assertEquals(UpdateState.None, v.state.value)
    }

    @Test fun reseau_coupe_a_la_verification_rien_ne_s_affiche() = runTest {
        val f = object : UpdatePlatform by Fake(json(7)) { override suspend fun fetchVersion(): String = throw java.io.IOException("hors ligne") }
        val u = Updater(f, this); u.check(); advanceUntilIdle()
        assertEquals(UpdateState.None, u.state.value)
    }

    @Test fun nouvelle_version_telechargee_verifiee_puis_installee() = runTest {
        val f = Fake(json(7, sha(payload), payload.size.toLong()))
        val u = Updater(f, this); u.check(); advanceUntilIdle()
        assertIs<UpdateState.Available>(u.state.value)
        u.start(); advanceUntilIdle()
        assertIs<UpdateState.Installing>(u.state.value)
        assertContentEquals(payload, f.installedApk!!.readBytes())
    }

    @Test fun fichier_abime_refuse_et_jamais_installe() = runTest {
        val f = Fake(json(7, sha("autre chose".toByteArray()), payload.size.toLong()))
        val u = Updater(f, this); u.check(); advanceUntilIdle(); u.start(); advanceUntilIdle()
        assertEquals("Le fichier téléchargé est abîmé. Réessaie.", (u.state.value as UpdateState.Failed).message)
        assertNull(f.installedApk)
        val g = Fake(json(7, null, payload.size + 1L))
        val v = Updater(g, this); v.check(); advanceUntilIdle(); v.start(); advanceUntilIdle()
        assertIs<UpdateState.Failed>(v.state.value); assertNull(g.installedApk)
    }

    @Test fun coupure_pendant_le_telechargement_puis_reessai() = runTest {
        val f = Fake(json(7, sha(payload))).also { it.failDownload = true }
        val u = Updater(f, this); u.check(); advanceUntilIdle(); u.start(); advanceUntilIdle()
        assertEquals("Téléchargement impossible. Vérifie la connexion puis réessaie.", (u.state.value as UpdateState.Failed).message)
        f.failDownload = false; u.start(); advanceUntilIdle()
        assertIs<UpdateState.Installing>(u.state.value)
    }

    @Test fun sans_autorisation_on_explique_puis_on_enchaine_au_retour() = runTest {
        val f = Fake(json(7, sha(payload))).also { it.allowed = false }
        val u = Updater(f, this); u.check(); advanceUntilIdle(); u.start(); advanceUntilIdle()
        assertIs<UpdateState.NeedsPermission>(u.state.value)
        u.openPermission(); assertEquals(1, f.opened)
        u.permissionMaybeGranted(); advanceUntilIdle(); assertIs<UpdateState.NeedsPermission>(u.state.value)
        f.allowed = true; u.permissionMaybeGranted(); advanceUntilIdle()
        assertIs<UpdateState.Installing>(u.state.value)
    }

    @Test fun echec_d_installation_affiche_puis_plus_tard_redevient_proposition() = runTest {
        val f = Fake(json(7, sha(payload)))
        val u = Updater(f, this); u.check(); advanceUntilIdle(); u.start(); advanceUntilIdle()
        u.installFailed(); assertIs<UpdateState.Failed>(u.state.value)
        u.dismissFailure(); assertIs<UpdateState.Available>(u.state.value)
    }

    @Test fun pas_plus_d_une_verification_par_heure_sauf_forcee() = runTest {
        var clock = 1_000_000L
        val f = Fake(json(7))
        val u = Updater(f, this) { clock }
        u.check(); advanceUntilIdle(); u.check(); advanceUntilIdle()
        assertEquals(1, f.fetches)
        clock += RECHECK_ON_RESUME_MS; u.check(); advanceUntilIdle(); assertEquals(2, f.fetches)
        u.check(force = true); advanceUntilIdle(); assertEquals(3, f.fetches)
    }

    @Test fun ancien_fichier_telecharge_nettoye() = runTest {
        val f = Fake(json(7, sha(payload)))
        f.workDir.mkdirs(); File(f.workDir, "iptv-home-5.apk").writeText("vieux")
        val u = Updater(f, this); u.check(); advanceUntilIdle(); u.start(); advanceUntilIdle()
        assertEquals(listOf("iptv-home-7.apk"), f.workDir.listFiles()!!.map { it.name })
    }
}
