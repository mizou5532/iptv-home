package home.iptv.tv.startup

import android.content.Context
import android.content.Intent

/**
 * Redémarre complètement l'application (comme un « forcer l'arrêt » suivi d'un relancement), sans redémarrer la télé :
 * l'activité est relancée dans une tâche neuve, puis le processus actuel s'arrête. Au relancement, tout repart de zéro,
 * dont la vérification des mises à jour.
 */
fun restartApp(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    val restart = Intent.makeRestartActivityTask(launch.component)
    context.startActivity(restart)
    Runtime.getRuntime().exit(0)
}
