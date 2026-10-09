package me.rerere.rikkahub.ui.components.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.root.RootCommandGuard
import me.rerere.rikkahub.root.RootProcessResult
import org.junit.Assert.*
import org.junit.Test

class SchedulePowerActionsTest {
    @Test fun `explicit action whitelists only this package through guarded root command`() = runBlocking {
        val calls = mutableListOf<Pair<String, Int>>()
        val result = addScheduleToDozeWhitelist("me.rerere.rikkahub") { command, timeout ->
            assertNull(RootCommandGuard.check(command))
            calls += command to timeout
            RootProcessResult(exitCode = 0)
        }
        assertEquals(listOf("dumpsys deviceidle whitelist +me.rerere.rikkahub" to 20_000), calls)
        assertNull(result.error)
        assertEquals(0, result.exitCode)
    }

    @Test fun `shell syntax cannot enter the root action through a package name`() = runBlocking {
        for (packageName in listOf("", "me.app;reboot", "me.app\nreboot", "me.app app", "me.app\u0000", "me.app${'$'}(id)")) {
            var executed = false
            val result = addScheduleToDozeWhitelist(packageName) { _, _ ->
                executed = true
                RootProcessResult(exitCode = 0)
            }
            assertFalse(packageName, executed)
            assertEquals("invalid_package_name", result.error)
        }
    }

    @Test fun `root denial stays an error and is never reported as a grant`() = runBlocking {
        val denial = RootProcessResult(error = "root_not_granted")
        assertSame(denial, addScheduleToDozeWhitelist("me.rerere.rikkahub") { _, _ -> denial })
    }

    @Test fun `cancelling the user action cancels its root operation`() = runBlocking {
        val cancelled = CancellationException("screen closed")
        try {
            addScheduleToDozeWhitelist("me.rerere.rikkahub") { _, _ -> throw cancelled }
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertSame(cancelled, actual)
        }
    }
}
