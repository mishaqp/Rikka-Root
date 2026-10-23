package me.rerere.rikkahub.root

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.RunExecutionContext
import me.rerere.rikkahub.data.ai.tools.RunOrigin
import me.rerere.rikkahub.data.ai.tools.local.buildRootTool
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.uuid.Uuid

/** Real shells, process groups, FIFOs and result files; only su authentication is replaced. */
class RootResultIntegrityAuditTest {
    @get:Rule val folder = TemporaryFolder()

    @Test(timeout = 20_000)
    fun `unconfirmed cleanup retains partial streams and never replays the command`() = checkUnconfirmedCleanup(false)

    @Test(timeout = 20_000)
    fun `missing cleanup acknowledgement retains partial streams without replay`() = checkUnconfirmedCleanup(true)

    private fun checkUnconfirmedCleanup(missingAcknowledgement: Boolean) = runBlocking {
        val fixture = Fixture(folder.newFolder())
        fixture.executable("mv", if (missingAcknowledgement) "exit 1" else
            "read nonce outcome < \"\$1\"\nprintf '%s unknown' \"\$nonce\" > \"\$2\"")
        try {
            assertEquals(RootStatus.READY, fixture.manager.verifyRoot(prepareHeadless = true))
            val calls = fixture.calls.readLines().size
            val effect = File(fixture.directory, "effect")
            val result = fixture.manager.exec(
                "printf 'partial out\\n'; printf 'partial err\\n' >&2; printf once >> ${quote(effect.path)}",
                2_000, headless = true,
            )

            assertEquals("root_cleanup_unconfirmed", result.error)
            assertNull("An untrusted acknowledgement cannot establish an exit code", result.exitCode)
            assertFalse(fixture.manager.isHeadlessReady())
            assertEquals("partial out\n", result.stdout)
            assertEquals("partial err\n", result.stderr)
            assertEquals("once", effect.readText())
            assertEquals("root_interaction_required", fixture.manager.exec("printf twice >> ${quote(effect.path)}", 1_000, headless = true).error)
            assertEquals("once", effect.readText())
            assertEquals(calls, fixture.calls.readLines().size)
        } finally { fixture.manager.close() }
    }

    @Test(timeout = 15_000)
    fun `unconfirmed cleanup retains actual output truncation flags`() = runBlocking {
        val fixture = Fixture(folder.newFolder())
        fixture.executable("mv", "read nonce outcome < \"\$1\"\nprintf '%s unknown' \"\$nonce\" > \"\$2\"")
        try {
            assertEquals(RootStatus.READY, fixture.manager.verifyRoot(prepareHeadless = true))
            val result = fixture.manager.exec("head -c 9000 /dev/zero | tr '\\000' o; head -c 3000 /dev/zero | tr '\\000' e >&2", 3_000, headless = true)
            assertEquals("root_cleanup_unconfirmed", result.error)
            assertTrue(result.stdoutTruncated)
            assertTrue(result.stderrTruncated)
            assertTrue(result.stdout.startsWith("o".repeat(8_000)))
            assertTrue(result.stderr.startsWith("e".repeat(2_000)))
        } finally { fixture.manager.close() }
    }

    @Test(timeout = 15_000)
    fun `foreground root rejects NUL without probing launching or silently modifying a command`() = checkNulCommand(false)

    @Test(timeout = 15_000)
    fun `headless root rejects NUL without launching or silently modifying a command`() = checkNulCommand(true)

    private fun checkNulCommand(headless: Boolean) = runBlocking {
        val fixture = Fixture(folder.newFolder())
        try {
            if (headless) assertEquals(RootStatus.READY, fixture.manager.verifyRoot(prepareHeadless = true))
            val calls = fixture.calls.readLines().size
            val effect = File(fixture.directory, "must-not-exist")
            val arguments = buildJsonObject { put("command", "touch ${quote(effect.path)}\u0000") }
            val tool = buildRootTool(fixture.manager)
            val parts = if (headless) withContext(RunExecutionContext(Uuid.random(), Uuid.random(), RunOrigin.SUB_AGENT)) {
                tool.execute(arguments)
            } else tool.execute(arguments)
            val result = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
            assertEquals("headless=$headless", "invalid_arguments", result["error"]?.jsonPrimitive?.content)
            assertFalse("Invalid command must not have effects", effect.exists())
            assertEquals("Invalid input must not start su", calls, fixture.calls.readLines().size)
        } finally { fixture.manager.close() }
    }

    private class Fixture(val directory: File) {
        private val bin = File(directory, "bin").apply { mkdir() }
        val calls = File(directory, "calls").apply { writeText("") }
        val manager: RootShellManager

        init {
            executable("id", "printf '0\\n'")
            val su = File(directory, "su").apply {
                writeText("#!/bin/sh\nprintf 'call\\n' >> ${quote(calls.path)}\nPATH=${quote(bin.path)}:\"\$PATH\"\nexport PATH\nexec /bin/sh -c \"\$2\"\n")
                check(setExecutable(true))
            }
            manager = RootShellManager(suExecutable = su.path, controlDirectory = directory)
        }

        fun executable(name: String, script: String) {
            File(bin, name).apply { writeText("#!/bin/sh\n$script\n"); check(setExecutable(true)) }
        }
    }

    companion object {
        private fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
    }
}
