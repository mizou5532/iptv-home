package home.iptv.tv.diag

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException

/** État du bouton « Signaler une lenteur ». */
sealed interface ReportState {
    data object Idle : ReportState
    data object Sending : ReportState
    data class Sent(val id: Long) : ReportState
    /** Pas de réseau ou serveur indisponible : gardé sur la télé, envoyé plus tard. */
    data object Queued : ReportState
}

/**
 * Rapports de lenteur : « Signaler une lenteur » (bouton de la barre du haut) ou automatique après un gros blocage
 * (au plus un toutes les [AUTO_EVERY_MS], [AUTO_PER_DAY] par jour sur cette télé). Un rapport qui ne part pas est
 * gardé sur la télé (10 au plus) et renvoyé au suivant.
 */
class PerfReporter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val householdId: () -> String?,
    private val send: suspend (householdId: String, kind: String, version: String, summary: String, data: JsonObject) -> Long,
    /** Ce que l'app sait d'elle-même au moment du rapport (catalogue, profil, diagnostic…), sans aucun identifiant. */
    private val appState: () -> Map<String, JsonElement> = { emptyMap() },
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private val _state = MutableStateFlow<ReportState>(ReportState.Idle)
    val state: StateFlow<ReportState> = _state
    private val lock = Mutex()
    private val prefs = context.getSharedPreferences("rapports-lenteur", Context.MODE_PRIVATE)
    private val pendingDir get() = java.io.File(context.filesDir, "rapports-en-attente").apply { mkdirs() }

    val version: String by lazy {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }

    /** Bouton « Signaler une lenteur ». */
    fun manual() {
        if (_state.value == ReportState.Sending) return
        scope.launch { _state.value = ReportState.Sending; _state.value = submit("manuel", "Signalé sur la télé") }
    }

    /** Gros blocage : rapport automatique, si les limites le permettent. */
    fun auto(reason: String) {
        val now = clockMs()
        val day = now / 86_400_000
        synchronized(prefs) {
            if (now - prefs.getLong("auto_last", 0) < AUTO_EVERY_MS) return
            val count = if (prefs.getLong("auto_day", -1) == day) prefs.getInt("auto_count", 0) else 0
            if (count >= AUTO_PER_DAY) return
            prefs.edit().putLong("auto_last", now).putLong("auto_day", day).putInt("auto_count", count + 1).apply()
        }
        scope.launch { submit("auto", reason) }
    }

    private suspend fun submit(kind: String, reason: String): ReportState = lock.withLock {
        val (summary, data) = withContext(Dispatchers.Default) {
            PerfMonitor.data.summary(reason) to PerfMonitor.data.report(kind, reason, extras())
        }
        val h = householdId()
        val sent = if (h == null) null else trySend(h, kind, summary, data)
        if (sent == null) { keep(kind, summary, data); return@withLock ReportState.Queued }
        PerfMonitor.data.resetCounters()
        PerfMonitor.data.event("Rapport n° $sent envoyé ($kind)")
        flushPending(h!!)
        ReportState.Sent(sent)
    }

    private suspend fun trySend(h: String, kind: String, summary: String, data: JsonObject): Long? = try {
        send(h, kind, version, summary, data)
    } catch (e: CancellationException) { throw e } catch (e: Exception) { null }

    private fun keep(kind: String, summary: String, data: JsonObject) = runCatching {
        val all = pendingDir.listFiles().orEmpty().sortedBy { it.name }
        all.dropLast(PENDING_MAX - 1).forEach { it.delete() }
        val body = buildJsonObject { put("type", kind); put("resume", summary); put("data", data) }
        java.io.File(pendingDir, "${clockMs()}.json").writeText(body.toString())
    }

    /** Rapports gardés sur la télé : envoyés quand le serveur répond de nouveau. */
    suspend fun flushPending(h: String) {
        val files = withContext(Dispatchers.IO) { pendingDir.listFiles().orEmpty().sortedBy { it.name } }
        for (f in files) {
            val o = runCatching { Json.parseToJsonElement(f.readText()) as JsonObject }.getOrNull()
            if (o == null) { f.delete(); continue }
            val kind = (o["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "manuel"
            val summary = (o["resume"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            val data = o["data"] as? JsonObject ?: run { f.delete(); null } ?: continue
            if (trySend(h, kind, "(envoi différé) $summary", data) == null) return
            f.delete()
        }
    }

    /**
     * Plantage : un rapport est écrit sur la télé avant que l'app se ferme (envoyé au prochain lancement), avec la
     * pile de l'erreur. Le message de l'erreur n'est gardé que s'il ne contient aucune adresse, et les identifiants
     * connus ([secrets]) en sont retirés.
     */
    fun installCrashCapture(secrets: () -> List<String>) {
        if (crashCaptureInstalled) return
        crashCaptureInstalled = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val crash = crashJson(t, e, runCatching { secrets() }.getOrDefault(emptyList()))
                val reason = "Plantage : ${e::class.java.simpleName}" + (firstAppFrame(e)?.let { " à $it" } ?: "")
                val data = PerfMonitor.data.report("auto", reason, runCatching { extras() }.getOrDefault(emptyMap()) + ("plantage" to crash))
                keep("auto", PerfMonitor.data.summary(reason), data)
            }
            previous?.uncaughtException(t, e)
        }
    }

    private fun firstAppFrame(e: Throwable): String? =
        generateSequence(e) { it.cause }.flatMap { it.stackTrace.asSequence() }.firstOrNull { it.className.startsWith("home.iptv") }?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }

    private fun crashJson(t: Thread, e: Throwable, secrets: List<String>): JsonObject = buildJsonObject {
        put("fil", t.name)
        put("erreurs", kotlinx.serialization.json.buildJsonArray {
            generateSequence(e) { it.cause }.take(6).forEach { x ->
                add(buildJsonObject {
                    put("classe", x::class.java.name)
                    safeMessage(x.message, secrets)?.let { put("message", it) }
                    put("pile", kotlinx.serialization.json.JsonArray(x.stackTrace.take(PerfData.STACK_MAX).map { kotlinx.serialization.json.JsonPrimitive(it.toString()) }))
                })
            }
        })
    }

    /**
     * Fermetures de l'app par Android depuis la dernière fois (Android 11 et plus) : plantage, « ne répond pas », manque
     * de mémoire… Un rapport automatique par lot, avec le début de la trace d'un « ne répond pas ».
     */
    suspend fun reportPreviousExits(h: String) {
        if (Build.VERSION.SDK_INT < 30) return
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val since = prefs.getLong("sorties_vues", 0)
        val exits = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 20) }.getOrDefault(emptyList())
            .filter { it.timestamp > since }
        if (exits.isEmpty()) return
        prefs.edit().putLong("sorties_vues", exits.maxOf { it.timestamp }).apply()
        val notable = exits.filter { it.reason != android.app.ApplicationExitInfo.REASON_USER_REQUESTED && it.reason != android.app.ApplicationExitInfo.REASON_EXIT_SELF }
        if (notable.isEmpty()) return
        val data = buildJsonObject {
            put("rapport", buildJsonObject { put("type", "auto"); put("raison", "Fermetures précédentes de l'app") ; put("a", PerfData.iso(clockMs())) })
            put("app", buildJsonObject { put("version", version) })
            put("appareil", buildJsonObject { put("fabricant", Build.MANUFACTURER); put("modele", Build.MODEL); put("android", Build.VERSION.RELEASE) })
            put("fermetures", kotlinx.serialization.json.buildJsonArray {
                notable.forEach { x ->
                    add(buildJsonObject {
                        put("a", PerfData.iso(x.timestamp)); put("raison", exitReason(x.reason)); put("importance", x.importance)
                        put("description", x.description.orEmpty().take(300)); put("memoire_pss_mo", x.pss / 1024); put("memoire_rss_mo", x.rss / 1024)
                        if (x.reason == android.app.ApplicationExitInfo.REASON_ANR) {
                            val trace = runCatching { x.traceInputStream?.bufferedReader()?.use { r -> r.lineSequence().take(400).toList() } }.getOrNull().orEmpty()
                            // Seulement le fil de l'écran (« main ») : le reste de la trace est inutile ici.
                            val start = trace.indexOfFirst { it.startsWith("\"main\"") }
                            val main = if (start < 0) trace.take(60) else trace.drop(start).takeWhile { it.isNotBlank() }.take(80)
                            put("trace_fil_ecran", kotlinx.serialization.json.JsonArray(main.map { kotlinx.serialization.json.JsonPrimitive(it.take(200)) }))
                        }
                    })
                }
            })
        }
        val summary = "Fermetures précédentes : " + notable.groupBy { exitReason(it.reason) }.entries.joinToString { (r, l) -> "${l.size} $r" }
        if (trySend(h, "auto", summary, data) == null) keep("auto", summary, data)
    }

    private fun exitReason(r: Int): String = when (r) {
        android.app.ApplicationExitInfo.REASON_ANR -> "ne répond pas (ANR)"
        android.app.ApplicationExitInfo.REASON_CRASH -> "plantage"
        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "plantage natif"
        android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "fermée par manque de mémoire"
        android.app.ApplicationExitInfo.REASON_SIGNALED -> "arrêtée par le système"
        android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "trop de ressources"
        android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "échec au démarrage"
        android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dépendance arrêtée"
        android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission changée"
        android.app.ApplicationExitInfo.REASON_OTHER -> "autre"
        else -> "code $r"
    }

    private fun extras(): Map<String, JsonElement> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val dm = context.resources.displayMetrics
        val mem = PerfMonitor.sampleMemory(am)
        val base = mapOf(
            "app" to buildJsonObject {
                put("version", version); put("processus_depuis_s", PerfMonitor.processAgeS())
            },
            "appareil" to buildJsonObject {
                put("fabricant", Build.MANUFACTURER); put("modele", Build.MODEL); put("android", Build.VERSION.RELEASE); put("sdk", Build.VERSION.SDK_INT)
                put("coeurs", Runtime.getRuntime().availableProcessors())
                put("ram_totale_mo", info.totalMem / MB); put("seuil_memoire_basse_mo", info.threshold / MB)
                put("appareil_peu_de_ram", am?.isLowRamDevice == true)
                put("classe_memoire_mo", am?.memoryClass ?: -1); put("grande_classe_memoire_mo", am?.largeMemoryClass ?: -1)
                put("ecran", "${dm.widthPixels}x${dm.heightPixels} ${dm.densityDpi}dpi")
            },
            "memoire" to buildJsonObject {
                put("tas_mo", mem.heapUsedMb); put("tas_max_mo", mem.heapMaxMb); put("natif_mo", mem.nativeMb)
                put("libre_systeme_mo", mem.availMb); put("memoire_basse", mem.lowMemory)
                put("gc", mem.gcCount); put("gc_ms", mem.gcMs); put("gc_bloquants", mem.blockingGcCount); put("fils", mem.threads)
            },
            "stockage" to buildJsonObject {
                put("cache_mo", dirMb(context.cacheDir)); put("fichiers_mo", dirMb(context.filesDir))
                put("bases_mo", dirMb(context.getDatabasePath("x").parentFile))
            },
            "fils" to buildJsonObject {
                Thread.getAllStackTraces().keys.groupBy { it.name.replace(Regex("[-#\\s]*\\d+$"), "") }
                    .entries.sortedByDescending { it.value.size }.take(25).forEach { (n, l) -> put(n.ifBlank { "?" }, l.size) }
            },
            "diagnostic" to buildJsonObject {
                put("ecran", Diagnostics.screen); put("focus", Diagnostics.focus); put("guide", Diagnostics.guide)
                put("mise_a_jour", Diagnostics.refresh); put("sport", Diagnostics.sport)
            },
        )
        return base + runCatching { appState() }.getOrDefault(emptyMap())
    }

    private fun dirMb(dir: java.io.File?): Long {
        if (dir == null) return -1
        var total = 0L; var n = 0
        dir.walkTopDown().forEach { if (it.isFile && n++ < 20_000) total += it.length() }
        return total / MB
    }

    private companion object {
        @Volatile var crashCaptureInstalled = false
        const val MB = 1024L * 1024L
        const val AUTO_EVERY_MS = 10 * 60_000L
        const val AUTO_PER_DAY = 8
        const val PENDING_MAX = 10
    }
}

/**
 * Message d'erreur montrable dans un rapport : jamais s'il contient une adresse (« :// », « @ », « ? ») ; les
 * identifiants connus en sont retirés ; 200 caractères au plus.
 */
internal fun safeMessage(message: String?, secrets: List<String>): String? {
    var m = message?.takeIf { it.isNotBlank() } ?: return null
    if ("://" in m || '@' in m || '?' in m) return null
    secrets.filter { it.length >= 3 }.forEach { m = m.replace(it, "***", ignoreCase = true) }
    return m.take(200)
}
