package me.rerere.rikkahub.root

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Some su implementations already make their command shell a session/group leader. */
@RunWith(Parameterized::class)
class RootSessionLeaderTest(private val sessionLeader: Boolean) {
    @get:Rule val folder = TemporaryFolder()

    @Test(timeout = 15_000)
    fun `waits for the actual command and preserves its nonzero exit and both streams`() = runBlocking {
        val (manager, calls) = manager()
        try {
            val result = manager.exec("printf 'BEFORE\\n'; sleep 0.1; printf 'AFTER\\n'; printf 'diagnostic\\n' >&2; exit 37", 5_000)

            assertNull(result.error)
            assertEquals(37, result.exitCode)
            assertEquals("BEFORE\nAFTER\n", result.stdout)
            assertEquals("diagnostic\n", result.stderr)
            assertEquals("Only the UID probe and one command launch are allowed", 2, calls.readLines().size)
        } finally {
            manager.close()
        }
    }

    @Test(timeout = 15_000)
    fun `command timeout is not replaced by an early successful launcher exit`() = runBlocking {
        val (manager, _) = manager()
        try {
            val result = manager.exec("sleep 0.5; printf 'AFTER\\n'", 100)

            assertEquals("command_timeout", result.error)
            assertNull(result.exitCode)
            assertFalse(result.stdout.contains("AFTER"))
        } finally {
            manager.close()
        }
    }

    private fun manager(): Pair<RootShellManager, File> {
        val bin = folder.newFolder("bin")
        executable(File(bin, "id"), "#!/bin/sh\nprintf '0\\n'\n")
        val calls = folder.newFile("calls")
        val su = folder.newFile("su")
        val launch = if (sessionLeader) "/usr/bin/setsid /bin/sh" else "/bin/sh"
        executable(su, "#!/bin/sh\nprintf 'call\\n' >> ${quote(calls.path)}\nPATH=${quote(bin.path)}:\"\$PATH\"\nexport PATH\nexec $launch -c \"\$2\"\n")
        return RootShellManager(suExecutable = su.path, controlDirectory = folder.root) to calls
    }

    private fun executable(file: File, content: String) {
        file.writeText(content)
        check(file.setExecutable(true))
    }

    private fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "su shell sessionLeader={0}")
        fun cases(): List<Array<Any>> = listOf(arrayOf(false), arrayOf(true))
    }
}
