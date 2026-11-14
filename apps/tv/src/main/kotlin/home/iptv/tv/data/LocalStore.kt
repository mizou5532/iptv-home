package home.iptv.tv.data

import android.content.Context
import android.content.SharedPreferences
import home.iptv.shared.sync.Profile
import home.iptv.shared.sync.SessionCodec
import home.iptv.shared.xtream.XtreamCreds
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Cache local de la télé : foyer, identifiants IPTV et profils (pour démarrer hors ligne). */
interface LocalStore {
    fun householdId(): String?
    fun saveHouseholdId(id: String)
    fun creds(): XtreamCreds?
    fun saveCreds(c: XtreamCreds)
    fun profiles(): List<Profile>
    fun saveProfiles(p: List<Profile>)
}

private val json = Json { ignoreUnknownKeys = true }

/** Stockage privé de l'app (SharedPreferences, non lisible par les autres apps). */
class AndroidLocalStore(context: Context) : LocalStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("iptv_home_local", Context.MODE_PRIVATE)

    override fun householdId(): String? = prefs.getString("household", null)
    override fun saveHouseholdId(id: String) { prefs.edit().putString("household", id).apply() }

    override fun creds(): XtreamCreds? {
        val server = prefs.getString("server", null) ?: return null
        val user = prefs.getString("username", null) ?: return null
        val pass = prefs.getString("password", null) ?: return null
        return XtreamCreds(server, user, pass)
    }

    override fun saveCreds(c: XtreamCreds) {
        prefs.edit().putString("server", c.server).putString("username", c.username)
            .putString("password", c.password).apply()
    }

    override fun profiles(): List<Profile> = runCatching {
        json.decodeFromString(ListSerializer(Profile.serializer()), prefs.getString("profiles", null) ?: "[]")
    }.getOrDefault(emptyList())

    override fun saveProfiles(p: List<Profile>) {
        prefs.edit().putString("profiles", json.encodeToString(ListSerializer(Profile.serializer()), p)).apply()
    }
}

/** Session Supabase (anonyme) de la télé, conservée entre deux lancements. */
class PrefsSessionManager(context: Context) : SessionManager {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("iptv_home_session", Context.MODE_PRIVATE)

    override suspend fun saveSession(session: UserSession) {
        prefs.edit().putString("session", SessionCodec.encode(session)).apply()
    }

    // Le contrat de supabase-kt : lever une exception s'il n'y a pas de session (loadSessionOrNull l'attrape).
    override suspend fun loadSession(): UserSession =
        SessionCodec.decode(checkNotNull(prefs.getString("session", null)) { "aucune session" })

    override suspend fun deleteSession() {
        prefs.edit().remove("session").apply()
    }
}
