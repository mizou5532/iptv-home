package home.iptv.tv.diag

import android.os.SystemClock
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

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

/**
 * Ordre des travaux de fond au démarrage (0.41, choix du propriétaire) : Séries, puis Films, puis Sport, puis Direct
 * (et le remplissage de la recherche en dernier). Une étape attend que les précédentes soient finies, mais jamais plus
 * de [STEP_CAP_MS] par étape avant elle depuis le lancement : rien ne reste bloqué si une étape ne vient pas (profil
 * qui n'ouvre jamais Séries, panne). Ce qu'on demande à l'écran (ouvrir le Direct, un match) n'attend jamais.
 */
class StartupOrder(private val now: () -> Long = { SystemClock.elapsedRealtime() }, private val stepCapMs: Long = STEP_CAP_MS) {
    enum class Step { SERIES, MOVIES, SPORT, DIRECT, SEARCH }

    private val start = now()
    private val finished = kotlinx.coroutines.flow.MutableStateFlow<Set<Step>>(emptySet())

    fun done(step: Step) { finished.value = finished.value + step }

    fun isDone(step: Step) = step in finished.value

    /** Attend le tour de [step] : étapes précédentes finies, ou délai écoulé. */
    suspend fun turn(step: Step) {
        val before = Step.entries.filter { it.ordinal < step.ordinal }.toSet()
        if (before.isEmpty()) return
        val left = step.ordinal * stepCapMs - (now() - start)
        if (left <= 0) return
        kotlinx.coroutines.withTimeoutOrNull(left) { finished.first { it.containsAll(before) } }
    }

    companion object {
        const val STEP_CAP_MS = 20_000L
        /** L'ordre de l'app (un par processus). */
        val app = StartupOrder()
    }
}

