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
    override suspend fun loadSession(): UserSession {
        check(file.isFile) { "aucune session" }
        return SessionCodec.decode(file.readText())
    }

    override suspend fun deleteSession() {
        file.delete()
    }
}
