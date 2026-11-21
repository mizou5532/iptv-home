package home.iptv.server

import java.io.File

/** Réglages du serveur maison, lus dans l'environnement du conteneur. */
data class Config(
    val supabaseUrl: String,
    /** Clé publique (publishable) : jamais la clé service_role. */
    val supabaseKey: String,
    val dataDir: File,
    val port: Int,
    /** Adresse https du serveur sur le réseau de la maison (ex. https://192.168.2.81:8443). */
    val lanUrl: String,
) {
    companion object
}

fun Config.Companion.fromEnv(env: Map<String, String>): Config {
    fun requis(nom: String): String =
        env[nom]?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("Variable d'environnement $nom manquante : renseignez-la dans la configuration du serveur.")

    val portTexte = env["PORT"]?.trim()?.takeIf { it.isNotEmpty() } ?: "8443"
    val port = portTexte.toIntOrNull()?.takeIf { it in 1..65535 }
        ?: throw IllegalStateException("Variable d'environnement PORT invalide : « $portTexte » n'est pas un numéro de port.")
    return Config(
        supabaseUrl = requis("SUPABASE_URL"),
        supabaseKey = requis("SUPABASE_KEY"),
        dataDir = File(env["DATA_DIR"]?.trim()?.takeIf { it.isNotEmpty() } ?: "/data"),
        port = port,
        lanUrl = requis("LAN_URL"),
    )
}
