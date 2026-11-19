package home.iptv.tv.diag

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window

/**
 * Surveillance de la lenteur sur la télé, toujours active et légère :
 * - images de l'écran (durée de chaque image dessinée, sans rien ajouter quand l'écran est immobile) ;
 * - blocages du fil de l'écran : un petit chien de garde vérifie toutes les ~0,5 s que l'écran répond ; au-delà de
 *   [STALL_MS], l'endroit exact du code en cours est relevé (pile du fil de l'écran) ;
 * - délai des touches de la télécommande (entre l'appui et son traitement) et durée du traitement ;
 * - mémoire toutes les 30 s (tas de l'app, mémoire libre de la télé, ramasse-miettes) ;
 * - demandes réseau par type (voir [PerfData.classify]) ;
 * - travaux de fond (remplissage, index, mises à jour) avec leur durée.
 *
 * [onSevere] est appelé (hors du fil de l'écran) après un gros blocage ou une touche très en retard : rapport
 * automatique (limité par [PerfReporter]).
 */
object PerfMonitor {
    val data = PerfData()
    @Volatile var onSevere: (String) -> Unit = {}
    @Volatile private var started = false
    private var thread: HandlerThread? = null
    private val processStart = SystemClock.elapsedRealtime()

    /** Démarre la surveillance (une fois par processus). */
    fun start(context: Context) {
        if (started) return
        started = true
        // Tests (Robolectric) : pas de chien de garde (il posterait sans fin sur le fil de l'écran simulé).
        if (android.os.Build.FINGERPRINT == "robolectric") return
        val t = HandlerThread("surveillance-lenteur", android.os.Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
        thread = t
        val bg = Handler(t.looper)
        val main = Handler(Looper.getMainLooper())
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        // Chien de garde du fil de l'écran.
        bg.post(object : Runnable {
            @Volatile var postedAt = 0L
            @Volatile var answered = true
            var captured: List<String>? = null
            override fun run() {
                val now = SystemClock.uptimeMillis()
                if (answered) {
                    answered = false; postedAt = now; captured = null
                    main.post {
                        val took = SystemClock.uptimeMillis() - postedAt
                        answered = true
                        if (took >= STALL_MS) {
                            val stack = captured ?: emptyList()
                            data.stall(took, stack)
                            if (took >= SEVERE_STALL_MS) bg.post { onSevere("Écran figé ${took} ms (${data.screen.ifBlank { "?" }})") }
                        }
                    }
                } else if (captured == null && now - postedAt >= STALL_MS) {
                    // L'écran ne répond pas : où en est-il ? (pile du fil de l'écran, noms de classes et de méthodes seulement)
                    captured = runCatching { Looper.getMainLooper().thread.stackTrace.map { it.toString() } }.getOrDefault(emptyList())
                }
                bg.postDelayed(this, if (answered) WATCH_EVERY_MS else 100)
            }
        })
        // Mémoire toutes les 30 s.
        bg.post(object : Runnable {
            override fun run() {
                runCatching { data.memory(sampleMemory(am)) }
                bg.postDelayed(this, MEMORY_EVERY_MS)
            }
        })
        data.event("Démarrage de l'app")
    }

    /** Images de l'écran de cette activité (Android 7+ ; rien quand l'écran est immobile). */
    fun watchFrames(activity: Activity) {
        val looper = thread?.looper ?: return
        runCatching {
            activity.window.addOnFrameMetricsAvailableListener({ _: Window, m: FrameMetrics, _: Int ->
                data.frame(m.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000)
            }, Handler(looper))
        }
    }

    /** Une touche : [eventTime] de l'appui, [handledAt] après son traitement. */
    fun key(keyCode: Int, eventTime: Long, startedAt: Long, handledAt: Long) {
        val lag = startedAt - eventTime
        val handle = handledAt - startedAt
        data.key(android.view.KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_"), lag, handle)
        if (lag + handle >= SEVERE_KEY_MS) thread?.looper?.let { Handler(it).post { onSevere("Touche en retard ${lag + handle} ms (${data.screen.ifBlank { "?" }})") } }
    }

    /** Travail de fond mesuré (durée et « en cours » dans les rapports). */
    inline fun <T> work(name: String, block: () -> T): T {
        val token = data.workStart(name)
        try { return block() } finally { data.workEnd(token) }
    }

    fun sampleMemory(am: ActivityManager?): PerfData.Memory {
        val rt = Runtime.getRuntime()
        val info = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        fun stat(name: String) = runCatching { Debug.getRuntimeStat(name)?.toLong() }.getOrNull() ?: -1
        return PerfData.Memory(
            atMs = System.currentTimeMillis(),
            heapUsedMb = ((rt.totalMemory() - rt.freeMemory()) / MB).toInt(),
            heapMaxMb = (rt.maxMemory() / MB).toInt(),
            nativeMb = (Debug.getNativeHeapAllocatedSize() / MB).toInt(),
            availMb = (info.availMem / MB).toInt(),
            lowMemory = info.lowMemory,
            gcCount = stat("art.gc.gc-count"),
            gcMs = stat("art.gc.gc-time"),
            blockingGcCount = stat("art.gc.blocking-gc-count"),
            threads = Thread.activeCount(),
        )
    }

    fun processAgeS(): Long = (SystemClock.elapsedRealtime() - processStart) / 1000

    const val STALL_MS = 700L
    const val SEVERE_STALL_MS = 2_500L
    const val SEVERE_KEY_MS = 2_000L
    private const val WATCH_EVERY_MS = 500L
    private const val MEMORY_EVERY_MS = 30_000L
    private const val MB = 1024L * 1024L
}

/** Intercepteur réseau : durée et issue de chaque demande, par type, sans jamais l'adresse (voir [PerfData.classify]). */
class PerfInterceptor : okhttp3.Interceptor {
    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val req = chain.request()
        val (kind, op) = PerfData.classify(req.url.host, req.url.encodedPath, req.url.queryParameter("action"))
        val t0 = SystemClock.elapsedRealtime()
        try {
            val res = chain.proceed(req)
            PerfMonitor.data.network(kind, op, SystemClock.elapsedRealtime() - t0, if (res.isSuccessful) "ok" else "http ${res.code}")
            return res
        } catch (e: Exception) {
            // Seulement le nom de l'erreur : son message peut contenir l'adresse du fournisseur.
            PerfMonitor.data.network(kind, op, SystemClock.elapsedRealtime() - t0, e::class.java.simpleName)
            throw e
        }
    }
}
