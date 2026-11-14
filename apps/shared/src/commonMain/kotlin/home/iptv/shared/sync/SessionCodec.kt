package home.iptv.shared.sync

import io.github.jan.supabase.auth.user.UserSession
import kotlinx.serialization.json.Json

/**
 * Encodage de la session Supabase persistée. `encodeDefaults = true` est indispensable : `expiresAt` a une valeur
 * par défaut (maintenant + expiresIn) et serait sinon omis quand elle est égale à la valeur par défaut ;
 * relue plus tard, la session paraîtrait alors valide pour toujours.
 */
object SessionCodec {
    internal val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(session: UserSession): String = json.encodeToString(UserSession.serializer(), session)

    fun decode(text: String): UserSession = json.decodeFromString(UserSession.serializer(), text)
}
