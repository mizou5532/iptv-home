package home.iptv.tv.diag

import android.os.SystemClock
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay

/**
 * Travail de fond « poli » (0.40, enquête lenteur) : il passe après l'écran.
 * - [lowPriority] baisse la priorité du fil le temps d'un calcul lourd ;
 * - [UserActivity.awaitIdle] attend que la télécommande soit calme avant une demande de fond au fournisseur.
 */
inline fun <T> lowPriority(block: () -> T): T {
    val before = runCatching { android.os.Process.getThreadPriority(android.os.Process.myTid()) }.getOrDefault(0)
    runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
    try {
        return block()
    } finally {
        runCatching { android.os.Process.setThreadPriority(before) }
    }
}

/** Dernière touche de la télécommande (voir MainActivity.dispatchKeyEvent). */
object UserActivity {
    @Volatile var lastInputMs = Long.MIN_VALUE / 2
        private set

    fun touched(nowMs: Long = SystemClock.uptimeMillis()) { lastInputMs = nowMs }

    /**
     * Attend [quietMs] sans touche (on ne navigue plus), au plus [maxWaitMs] : le travail de fond ne prend jamais
     * le processeur pendant qu'on se déplace dans les listes, mais n'est jamais bloqué indéfiniment.
     */
    suspend fun awaitIdle(quietMs: Long = QUIET_MS, maxWaitMs: Long = MAX_WAIT_MS, now: () -> Long = { SystemClock.uptimeMillis() }) {
        val start = now()
        while (true) {
            val t = now()
            val since = t - lastInputMs
            if (since >= quietMs || t - start >= maxWaitMs) return
            delay((quietMs - since).coerceIn(50, quietMs))
        }
    }

    const val QUIET_MS = 2_000L
    const val MAX_WAIT_MS = 30_000L
}

/** Fils de priorité basse pour le travail de fond (remplissage de la recherche) : l'écran garde la main. */
val backgroundDispatcher: kotlinx.coroutines.CoroutineDispatcher by lazy {
    val n = java.util.concurrent.atomic.AtomicInteger()
    java.util.concurrent.Executors.newFixedThreadPool(2) { r ->
        Thread({
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
            r.run()
        }, "fond-${n.incrementAndGet()}").apply { isDaemon = true }
    }.asCoroutineDispatcher()
}
