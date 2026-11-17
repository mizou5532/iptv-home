package home.iptv.tv.startup

import home.iptv.shared.sync.PairingState
import home.iptv.shared.sync.Profile
import home.iptv.shared.sync.SyncClient
import home.iptv.tv.data.LocalStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

sealed interface StartupUi {
    data object Loading : StartupUi
    data class Pairing(val code: String, val qrUrl: String) : StartupUi
    data class Ready(val profiles: List<Profile>, val offline: Boolean, val deviceLabel: String? = null, val welcomeTheme: String? = null) : StartupUi
    data class Failed(val message: String) : StartupUi
}

const val MSG_OFFLINE = "Pas de connexion internet. Vérifie le réseau de la télé."
const val MSG_NO_CREDS = "Ajoute tes identifiants IPTV sur la page de configuration."

class StartupViewModel(
    private val sync: SyncClient,
    private val store: LocalStore,
    private val configPageUrl: String,
    private val deviceName: String = "Télé du salon",
    private val pollEvery: Duration = 3.seconds,
    private val clock: Clock = Clock.System,
    private val scope: CoroutineScope,
    private val networkTimeout: Duration = 4.seconds,
) {
    private val _ui = MutableStateFlow<StartupUi>(StartupUi.Loading)
    val ui: StateFlow<StartupUi> = _ui
    private var job: Job? = null
    private var identityJob: Job? = null

    fun start() {
        job?.cancel()
        identityJob?.cancel()
        _ui.value = StartupUi.Loading
        job = scope.launch { run() }
    }

    private suspend fun run() {
        try {
            val household = withTimeout(networkTimeout) {
                sync.ensureSignedIn()
                sync.currentHousehold()
            }
            if (household != null) refreshFrom(household) else pair()
        } catch (e: CancellationException) {
            if (e is kotlinx.coroutines.TimeoutCancellationException) offline() else throw e
        } catch (e: Exception) {
            offline()
        }
    }

    private suspend fun pair() {
        while (true) {
            val waiting: PairingState.Waiting = try {
                withTimeout(networkTimeout) { sync.createPairingCode(deviceName) }
            } catch (e: CancellationException) {
                if (e is kotlinx.coroutines.TimeoutCancellationException) { offline(); return } else throw e
            } catch (e: Exception) {
                offline(); return
            }
            _ui.value = StartupUi.Pairing(waiting.code, "$configPageUrl/?code=${waiting.code}")
            while (clock.now() < waiting.expiresAt) {
                delay(pollEvery)
                val household = try {
                    withTimeout(networkTimeout) { sync.currentHousehold() }
                } catch (e: CancellationException) {
                    if (e is kotlinx.coroutines.TimeoutCancellationException) null else throw e
                } catch (e: Exception) {
                    null // réseau capricieux : on garde le code affiché et on réessaie
                }
                if (household != null) {
                    try { refreshFrom(household) } catch (e: CancellationException) {
                        if (e is kotlinx.coroutines.TimeoutCancellationException) offline() else throw e
                    } catch (e: Exception) { offline() }
                    return
                }
            }
            // Code expiré : la boucle en demande un nouveau.
        }
    }

    /** Les identifiants et profils de Supabase font foi ; le cache local ne sert que hors ligne. */
    private suspend fun refreshFrom(household: String) {
        val (creds, profiles) = withTimeout(networkTimeout) {
            sync.credentials(household) to sync.profiles(household)
        }
        store.saveHouseholdId(household)
        if (creds == null) {
            _ui.value = StartupUi.Failed(MSG_NO_CREDS)
            return
        }
        store.saveCreds(creds)
        store.saveProfiles(profiles)
        _ui.value = StartupUi.Ready(profiles, offline = false, welcomeTheme = store.welcomeTheme())
        // Thème de « Qui regarde ? » (réglage du foyer) : à part, il ne retarde jamais l'affichage des profils.
        scope.launch {
            try {
                val theme = withTimeout(networkTimeout) { sync.householdSettings(household) }.welcomeTheme
                store.saveWelcomeTheme(theme)
                val current = _ui.value
                if (current is StartupUi.Ready && current.welcomeTheme != theme) _ui.value = current.copy(welcomeTheme = theme)
            } catch (e: CancellationException) {
                if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            } catch (_: Exception) { /* thème connu gardé */ }
        }
        // Indépendant du démarrage : un serveur ancien ou lent ne bloque jamais les profils.
        identityJob = scope.launch {
            try {
                val label = withTimeout(networkTimeout) { sync.reportDeviceIdentity(household, deviceName) }
                val current = _ui.value
                if (label != null && current is StartupUi.Ready && !current.offline) _ui.value = current.copy(deviceLabel = label)
            } catch (e: CancellationException) {
                if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            } catch (_: Exception) { /* Migration non encore publiée ou réseau indisponible : identité inchangée. */ }
        }
    }

    private fun offline() {
        _ui.value = if (store.householdId() != null && store.creds() != null) {
            StartupUi.Ready(store.profiles(), offline = true, welcomeTheme = store.welcomeTheme())
        } else {
            StartupUi.Failed(MSG_OFFLINE)
        }
    }
}
