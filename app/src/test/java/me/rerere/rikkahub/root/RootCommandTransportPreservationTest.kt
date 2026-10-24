package me.rerere.rikkahub.root

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Exercise both real transports; replace only su authentication and capture input before real sh. */
@RunWith(Parameterized::class)
class RootCommandTransportPreservationTest(
    private val caseName: String,
    private val headless: Boolean,
    private val command: String,
    private val expectedOutput: String,
    private val fallback: Boolean,
) {
    @get:Rule val folder = TemporaryFolder()

    @Test(timeout = 15_000)
    fun `shell receives the original command including indentation and quoted multiline data`() = runBlocking {
        val bin = folder.newFolder("bin")
        val received = File(folder.root, "received command")
        executable(File(bin, "id"), "#!/bin/sh\nprintf '0\\n'\n")
        val capture = if (headless) {
            // Other sh children drain pipes and supervise groups; capture only the command script.
            "if [ \"\$#\" -eq 1 ] && [ \"\${1##*/}\" = command ]; then cat \"\$1\" > ${quote(received.path)}; fi"
        } else {
            // The supervisor reaches this shim first; the inner command replaces that capture.
            "if [ \"\$#\" -eq 2 ] && [ \"\$1\" = -c ]; then printf '%s' \"\$2\" > ${quote(received.path)}; fi"
        }
        executable(File(bin, "sh"), "#!/bin/sh\n$capture\nexec /bin/sh \"\$@\"\n")
        if (fallback) executable(File(bin, "mkfifo"), "#!/bin/sh\nexit 1\n")
        val su = folder.newFile("su")
        executable(su, "#!/bin/sh\nPATH=${quote(bin.path)}:\"\$PATH\"\nexport PATH\nexec /bin/sh -c \"\$2\"\n")
        val manager = RootShellManager(suExecutable = su.path, controlDirectory = folder.root)

        try {
            if (headless) assertEquals(RootStatus.READY, manager.verifyRoot(prepareHeadless = true))
            val result = manager.exec(command, 2_000, headless = headless)

            assertEquals(caseName, null, result.error)
            assertEquals(caseName, 0, result.exitCode)
            assertEquals(caseName, expectedOutput, result.stdout)
            assertEquals(caseName, "", result.stderr)
            assertEquals("$caseName: exact shell input", command, received.readText())
        } finally {
            manager.close()
        }
    }

    private fun executable(file: File, content: String) {
        file.writeText(content)
        check(file.setExecutable(true))
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}, headless={1}, fallback={4}")
        fun cases(): List<Array<Any>> {
            val commands = listOf(
                Triple(
                    "only indented lines",
                    "    printf '%s\\n' first\n    printf '%s\\n' second",
                    "first\nsecond\n",
                ),
                Triple(
                    "leading and trailing empty lines",
                    "\n\nprintf '%s\\n' bounds\n\n",
                    "bounds\n",
                ),
                Triple(
                    "tab and whitespace-only boundary lines",
                    " \n\tprintf '[%s]\\n' 'a  b' 2>/dev/null\n  ",
                    "[a  b]\n",
                ),
                Triple(
                    "multiline single quoted data",
                    "    printf '%s\\n' 'first\n      second\n\tthird\n    fourth'\n    ",
                    "first\n      second\n\tthird\n    fourth\n",
                ),
                Triple(
                    "multiline double quoted data",
                    "  printf '%s\\n' \"line one\n    line \\\"two\\\"\n\tline three\"\n  ",
                    "line one\n    line \"two\"\n\tline three\n",
                ),
            )
            return listOf(false to false, true to false, false to true).flatMap { (headless, fallback) ->
                commands.map { (name, command, output) -> arrayOf<Any>(name, headless, command, output, fallback) }
            }
        }
    }
}
