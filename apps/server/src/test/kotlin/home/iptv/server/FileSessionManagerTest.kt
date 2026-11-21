package home.iptv.server

import home.iptv.shared.sync.SessionCodec
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FileSessionManagerTest {
    private val dir = Files.createTempDirectory("session-test").toFile()
    private val file = dir.resolve("session.json")
    private val session = UserSession(accessToken = "a", refreshToken = "r", expiresIn = 3600, tokenType = "bearer", user = null)

    @Test
    fun roundTripWithMode600() = runTest {
        val m = FileSessionManager(file)
        m.saveSession(session)
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())))
        assertEquals("a", m.loadSession().accessToken)
        assertEquals("r", m.loadSession().refreshToken)
        m.deleteSession()
        assertTrue(!file.exists())
    }

    @Test
    fun missingFileMeansNoSession() = runTest {
        val m = FileSessionManager(file)
        m.verify() // premier lancement : pas d'erreur
        assertFailsWith<IllegalStateException> { m.loadSession() }
    }

    @Test
    fun corruptFileFailsLoudlyAndIsKept() = runTest {
        file.writeText("{pas du json")
        val m = FileSessionManager(file)
        val e = assertFailsWith<CorruptSessionException> { m.verify() }
        assertTrue("session.json.corrupt" in e.message!!)
        assertFailsWith<CorruptSessionException> { m.loadSession() }
        val copie = dir.resolve("session.json.corrupt")
        assertEquals("{pas du json", copie.readText())
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(copie.toPath())))
    }
}
