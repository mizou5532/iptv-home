package home.iptv.tv.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.security.MessageDigest

/** Contenu de `version.json` publié avec chaque version. Les champs du fichier APK sont absents des anciennes versions. */
data class RemoteVersion(val versionCode: Int, val versionName: String, val apkSha256: String? = null, val apkSize: Long? = null)

/** Lecture tolérante : un fichier mal formé ne propose jamais de mise à jour. */
fun parseRemoteVersion(text: String): RemoteVersion? = runCatching {
    val o = Json.parseToJsonElement(text) as JsonObject
    fun prim(key: String) = o[key] as? JsonPrimitive
    RemoteVersion(
        versionCode = prim("versionCode")?.intOrNull ?: return null,
        versionName = prim("versionName")?.takeIf { it.isString }?.contentOrNull ?: return null,
        apkSha256 = prim("apkSha256")?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() },
        apkSize = prim("apkSize")?.longOrNull?.takeIf { it > 0 },
    )
}.getOrNull()?.takeIf { it.versionCode > 0 && it.versionName.isNotBlank() }

fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** Le fichier correspond à ce que `version.json` annonce (taille et empreinte quand elles sont connues). */
fun apkMatches(file: File, remote: RemoteVersion): Boolean =
    file.isFile && file.length() > 0 &&
        (remote.apkSize == null || file.length() == remote.apkSize) &&
        (remote.apkSha256 == null || sha256Hex(file).equals(remote.apkSha256.trim(), ignoreCase = true))

sealed interface UpdateState {
    /** Rien à proposer (à jour, ou vérification impossible : on réessaiera plus tard, sans message). */
    data object None : UpdateState
    data class Available(val remote: RemoteVersion) : UpdateState
    data class Downloading(val remote: RemoteVersion, val percent: Int) : UpdateState
    /** Android doit d'abord autoriser IPTV Home à installer des applications. */
    data class NeedsPermission(val remote: RemoteVersion) : UpdateState
    data class Installing(val remote: RemoteVersion) : UpdateState
    data class Failed(val remote: RemoteVersion, val message: String) : UpdateState
}

/** Ce que l'Android réel fournit ; remplacé par des faux dans les tests. */
interface UpdatePlatform {
    val installedVersionCode: Long
    suspend fun fetchVersion(): String
    /** Télécharge l'APK dans [target] ; [progress] reçoit 0..100. */
    suspend fun download(target: File, progress: (Int) -> Unit)
    fun canInstall(): Boolean
    fun openInstallPermission()
    /** Lance l'installation ; le résultat arrive plus tard par [Updater.installFailed] ou par la relance de l'app. */
    fun install(apk: File)
    val workDir: File
}

const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L
const val RECHECK_ON_RESUME_MS = 60 * 60 * 1000L

/**
 * Vérifie la présence d'une nouvelle version et pilote téléchargement, vérification et installation.
 * Une seule opération à la fois ; un échec réseau à la vérification ne montre rien (l'app reste utilisable).
 */
class Updater(private val platform: UpdatePlatform, private val scope: CoroutineScope, private val now: () -> Long = System::currentTimeMillis) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.None)
    val state: StateFlow<UpdateState> = _state
    private var lastCheck = 0L
    private var job: Job? = null

    /** [force] = vérifier même si la dernière vérification est récente. */
    fun check(force: Boolean = false) {
        if (job?.isActive == true) return
        if (!force && lastCheck != 0L && now() - lastCheck < RECHECK_ON_RESUME_MS) return
        job = scope.launch {
            lastCheck = now()
            val remote = try { parseRemoteVersion(platform.fetchVersion()) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            val current = _state.value
            // Une opération en cours (ou un échec affiché) n'est pas effacée par une vérification.
            if (current is UpdateState.Downloading || current is UpdateState.Installing) return@launch
            _state.value = if (remote != null && remote.versionCode > platform.installedVersionCode) {
                if (current is UpdateState.Failed && current.remote == remote) current else UpdateState.Available(remote)
            } else UpdateState.None
        }
    }

    fun start() {
        val remote = when (val s = _state.value) {
            is UpdateState.Available -> s.remote
            is UpdateState.Failed -> s.remote
            is UpdateState.NeedsPermission -> s.remote
            else -> return
        }
        if (job?.isActive == true) return
        if (!platform.canInstall()) { _state.value = UpdateState.NeedsPermission(remote); return }
        job = scope.launch {
            val apk = File(platform.workDir, "iptv-home-${remote.versionCode}.apk")
            try {
                platform.workDir.mkdirs()
                platform.workDir.listFiles()?.filter { it.name.endsWith(".apk") }?.forEach { it.delete() }
                _state.value = UpdateState.Downloading(remote, 0)
                platform.download(apk) { p -> _state.value = UpdateState.Downloading(remote, p.coerceIn(0, 100)) }
                if (!apkMatches(apk, remote)) {
                    apk.delete()
                    _state.value = UpdateState.Failed(remote, "Le fichier téléchargé est abîmé. Réessaie.")
                    return@launch
                }
                _state.value = UpdateState.Installing(remote)
                platform.install(apk)
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                apk.delete()
                _state.value = UpdateState.Failed(remote, "Téléchargement impossible. Vérifie la connexion puis réessaie.")
            }
        }
    }

    fun openPermission() = platform.openInstallPermission()

    /** Retour du réglage : si l'autorisation est donnée, on enchaîne. */
    fun permissionMaybeGranted() {
        if (_state.value is UpdateState.NeedsPermission && platform.canInstall()) start()
    }

    fun installFailed(message: String = "L'installation n'a pas abouti. Réessaie.") {
        val remote = when (val s = _state.value) {
            is UpdateState.Installing -> s.remote
            is UpdateState.Downloading -> s.remote
            else -> return
        }
        _state.value = UpdateState.Failed(remote, message)
    }

    /** « Plus tard » : le bouton reste, le panneau se ferme (géré par l'écran). Un échec redevient une simple proposition. */
    fun dismissFailure() {
        (_state.value as? UpdateState.Failed)?.let { _state.value = UpdateState.Available(it.remote) }
    }
}
