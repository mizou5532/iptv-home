package home.iptv.tv.update

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Adresse publique de la page : `version.json` et l'APK y sont servis (même lien que pour Downloader). */
const val UPDATE_BASE = "https://iptv-home-config.vercel.app"
private const val ACTION_INSTALL_STATUS = "home.iptv.tv.update.INSTALL_STATUS"

/** Installé par l'application, une seule fois par processus (voir IptvApp). */
object Updates {
    @Volatile var updater: Updater? = null
}

class AndroidUpdatePlatform(private val context: Context, private val http: HttpClient) : UpdatePlatform {
    override val installedVersionCode: Long =
        androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))

    override suspend fun fetchVersion(): String {
        // Le paramètre évite toute copie en cache d'une ancienne version.json.
        val response = http.get("$UPDATE_BASE/version.json?t=${System.currentTimeMillis()}")
        if (!response.status.isSuccess()) throw IOException("version.json : ${response.status.value}")
        return response.bodyAsText()
    }

    override suspend fun download(target: File, progress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        http.prepareGet("$UPDATE_BASE/iptv-home.apk?t=${System.currentTimeMillis()}").execute { response ->
            if (!response.status.isSuccess()) throw IOException("APK : ${response.status.value}")
            val total = response.contentLength() ?: -1L
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(64 * 1024)
            var read = 0L
            var last = -1
            target.outputStream().use { out ->
                while (true) {
                    val n = channel.readAvailable(buffer, 0, buffer.size)
                    if (n < 0) break
                    if (n == 0) { if (channel.isClosedForRead) break else continue }
                    out.write(buffer, 0, n)
                    read += n
                    if (total > 0) {
                        val percent = (read * 100 / total).toInt()
                        if (percent != last) { last = percent; progress(percent) }
                    }
                }
            }
        }
    }

    // Avant Android 8 (Fire OS 6) : pas de permission par app, le réglage « Sources inconnues » est global.
    override fun canInstall(): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    override fun openInstallPermission() {
        val own = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        for (intent in listOf(own, Intent(Settings.ACTION_SECURITY_SETTINGS), Intent(Settings.ACTION_SETTINGS))) {
            try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: ActivityNotFoundException) { }
        }
    }

    override fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            // Android 12+ : pas de confirmation quand l'app se met elle-même à jour (si Android l'accepte).
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, UpdateReceiver::class.java).setAction(ACTION_INSTALL_STATUS)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
    }

    override val workDir: File = File(context.cacheDir, "updates")
}

/** Résultat de l'installation : confirmation Android à afficher, ou échec à signaler à l'écran. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm == null) Updates.updater?.installFailed()
                else runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { Updates.updater?.installFailed() }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // Android remplace l'app ; RelaunchReceiver tente de la rouvrir.
            PackageInstaller.STATUS_FAILURE_ABORTED -> Updates.updater?.installFailed("Mise à jour annulée.")
            else -> Updates.updater?.installFailed()
        }
    }
}

/** Après une mise à jour : tentative de relance (Android peut la bloquer ; il suffit alors de rouvrir l'app). */
class RelaunchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
