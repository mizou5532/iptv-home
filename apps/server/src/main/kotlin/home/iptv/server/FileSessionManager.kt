package home.iptv.server

import home.iptv.shared.sync.SessionCodec
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/** Session Supabase du serveur maison, conservée dans un fichier (droits 600) entre deux démarrages. */
class FileSessionManager(private val file: File) : SessionManager {

    override suspend fun saveSession(session: UserSession) {
        file.absoluteFile.parentFile?.mkdirs()
        // Écriture dans un fichier temporaire déjà en 600, puis remplacement atomique : jamais lisible par d'autres.
        val tmp = File(file.absoluteFile.parentFile, file.name + ".tmp").toPath()
        Files.deleteIfExists(tmp)
        try {
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } catch (e: UnsupportedOperationException) {
            Files.createFile(tmp) // système de fichiers sans droits POSIX
        }
        Files.writeString(tmp, SessionCodec.encode(session))
        Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    // Contrat de supabase-kt : lever une exception s'il n'y a pas de session.
    // Attention : supabase-kt avale cette exception ; un fichier abîmé est donc aussi détecté par [verify] au démarrage.
    override suspend fun loadSession(): UserSession = readOrNull() ?: error("aucune session")

    /**
     * Vérifie le fichier au démarrage : absent (premier lancement) n'est pas une erreur ; abîmé, il est mis de côté
     * dans `<fichier>.corrupt` et le démarrage s'arrête, au lieu de créer en silence un nouvel utilisateur
     * (le serveur perdrait son foyer).
     */
    fun verify() { readOrNull() }

    private fun readOrNull(): UserSession? {
        if (!file.isFile) return null
        return try {
            SessionCodec.decode(file.readText())
        } catch (e: Exception) {
            val copie = File(file.absoluteFile.parentFile, file.name + ".corrupt")
            try {
                Files.copy(file.toPath(), copie.toPath(), StandardCopyOption.REPLACE_EXISTING)
                try {
                    Files.setPosixFilePermissions(copie.toPath(), PosixFilePermissions.fromString("rw-------"))
                } catch (_: UnsupportedOperationException) {}
            } catch (_: Exception) {}
            throw CorruptSessionException(
                "Le fichier de session ${file.path} est illisible. Une copie est gardée dans ${copie.path}. " +
                    "Le serveur s'arrête pour ne pas créer un nouvel utilisateur et perdre son appairage : " +
                    "restaurez le fichier depuis une sauvegarde, ou supprimez-le pour appairer de nouveau le serveur.",
                e,
            )
        }
    }

    override suspend fun deleteSession() {
        file.delete()
    }
}

/** Fichier de session abîmé : arrêt volontaire, voir [FileSessionManager.verify]. */
class CorruptSessionException(message: String, cause: Throwable) : IllegalStateException(message, cause)
