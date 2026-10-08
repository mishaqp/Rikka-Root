package me.rerere.rikkahub.data.ai.tools.local

import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.root.RootApprovalPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxCommandGuardTest {
    @Test
    fun actualToolsRequestApprovalAndRejectBeforeAndroidDispatch() = runBlocking {
        // A context with no Android delegate proves that blocked tools cannot reach
        // package probing, tmux installation or service dispatch.
        val context = ContextWrapper(null)
        val tools = listOf(
            termuxRunCommandTool(context), termuxSessionStartTool(context),
            termuxSessionSendTool(context), termuxSessionReadTool(context),
            termuxSessionKillTool(context), termuxSessionListTool(context),
        )
        assertEquals(listOf("termux_run_command", "termux_session_start", "termux_session_send",
            "termux_session_read", "termux_session_kill", "termux_session_list"), tools.map { it.name })
        tools.forEach { assertTrue(it.needsApproval(buildJsonObject {})) }
        for (tool in tools.take(2)) {
            val result = tool.execute(buildJsonObject { put("command", "rm -rf /system") })
            assertTrue((result.single() as UIMessagePart.Text).text.contains("blocked_by_safety_floor"))
        }
        val executableResult = tools.first().execute(buildJsonObject {
            put("executable", "/data/data/com.termux/files/usr/bin/reboot")
        })
        assertTrue((executableResult.single() as UIMessagePart.Text).text.contains("blocked_by_safety_floor"))
    }

    @Test
    fun shellAndExecutableModeUseTheSameRootFloor() {
        assertNotNull(checkTermuxCommand("rm -rf /system", emptyArray()))
        assertNotNull(checkTermuxCommand("/data/data/com.termux/files/usr/bin/bash", arrayOf("-c", "rm -rf /")))
        assertNotNull(checkTermuxCommand("/data/data/com.termux/files/usr/bin/bash", arrayOf("-c", "reboot")))
        assertNotNull(checkTermuxCommand("/data/data/com.termux/files/usr/bin/dd", arrayOf("if=/dev/zero", "of=/dev/block/sda")))
        assertNull(checkTermuxCommand("/data/data/com.termux/files/usr/bin/echo", arrayOf("hello")))
    }

    @Test
    fun partialInputAndLiteralKeysCannotSplitAForbiddenCommand() {
        val pending = TermuxSessionInputGuard()
        assertNull(pending.check("rk_a", "rm -", emptyList(), false))
        assertNotNull(pending.check("rk_a", "rf /system", emptyList(), true))
        assertNull(pending.check("rk_b", "", listOf("r", "m", "Space", "-"), false))
        assertNotNull(pending.check("rk_b", "", listOf("r", "f", "Space", "/", "Enter"), false))
    }

    @Test
    fun cancellationClearsPendingInputAndSessionsAreIndependent() {
        val pending = TermuxSessionInputGuard()
        assertNull(pending.check("rk_a", "rm -", emptyList(), false))
        assertNull(pending.check("rk_a", "", listOf("C-c"), false))
        assertNull(pending.check("rk_a", "echo hi", emptyList(), true))
        assertNull(pending.check("rk_b", "echo hi", listOf("Enter"), false))
        assertEquals("", pending.pending("rk_a"))
        assertEquals("", pending.pending("rk_b"))
    }

    @Test
    fun historyCompletionMustBeReadBeforeAnEnterKey() {
        val pending = TermuxSessionInputGuard()
        assertNotNull(pending.check("rk_a", null, listOf("Up", "Enter"), false))
        assertNull(pending.check("rk_a", null, listOf("Up"), false))
        assertNotNull(pending.check("rk_a", null, listOf("Enter"), false, "root# rm -rf /system"))
        assertNull(pending.check("rk_b", null, listOf("Up"), false))
        assertNull(pending.check("rk_b", null, listOf("Enter"), false, "root# echo hi"))
    }

    @Test
    fun headlessCannotSplitAMandatoryConfirmationCommand() {
        val pending = TermuxSessionInputGuard()
        assertNull(pending.check("rk_a", "seten", emptyList(), false,
            additionalCheck = RootApprovalPolicy::reason))
        assertNotNull(pending.check("rk_a", "force 0", emptyList(), true,
            additionalCheck = RootApprovalPolicy::reason))
        // Interactive calls still use their own approval flow.
        assertNull(TermuxSessionInputGuard().check("rk_b", "setenforce 0", emptyList(), true))
    }

    @Test
    fun restoredScreenPreservesCommandSeparatorAndChecksBothPolicies() {
        assertEquals("setenforce ", termuxVisibleCommandLine("old output\nroot# setenforce \n\n"))
        assertNotNull(TermuxSessionInputGuard().check("restored", "0", emptyList(), true,
            currentScreen = "root# setenforce \n\n",
            additionalCheck = termuxSessionApprovalCheck(true, "0")))
        assertNotNull(TermuxSessionInputGuard().check("restored", "0", emptyList(), true,
            currentScreen = "root# setenforce \n\n",
            additionalCheck = termuxSessionApprovalCheck(false, "0")))
        // HARDLINE remains unconditional, including restored screens.
        assertNotNull(TermuxSessionInputGuard().check("restored", "/system", emptyList(), true,
            currentScreen = "root# rm -rf ",
            additionalCheck = termuxSessionApprovalCheck(false, "/system")))
    }

    @Test
    fun foregroundFragmentsAndKeysCannotUseAutomaticApprovalForHiddenAction() {
        val pending = TermuxSessionInputGuard()
        assertNull(pending.check("rk_a", "seten", emptyList(), false,
            additionalCheck = termuxSessionApprovalCheck(false, "seten")))
        assertNotNull(pending.check("rk_a", "force 0", emptyList(), true,
            additionalCheck = termuxSessionApprovalCheck(false, "force 0")))
        assertNotNull(TermuxSessionInputGuard().check("rk_b", "setenforce", listOf("Space", "0", "Enter"), false,
            additionalCheck = termuxSessionApprovalCheck(false, "setenforce")))
        // A complete action displayed by the foreground mandatory-approval policy still works.
        assertNull(TermuxSessionInputGuard().check("rk_c", "setenforce 0", emptyList(), true,
            currentScreen = "user@device:~ $ ",
            additionalCheck = termuxSessionApprovalCheck(false, "setenforce 0")))
        assertNull(TermuxSessionInputGuard().check("rk_d", "echo ok", emptyList(), true,
            currentScreen = "user@device:~ $ ",
            additionalCheck = termuxSessionApprovalCheck(true, "echo ok")))
    }

    @Test
    fun actualScreenOverridesCachedInputChangedByAnotherTerminalClient() {
        val pending = TermuxSessionInputGuard()
        assertNull(pending.check("rk_a", "echo ", emptyList(), false))
        assertNotNull(pending.check("rk_a", "/system", emptyList(), true,
            currentScreen = "root# rm -rf "))
        assertNull(pending.check("rk_b", "echo ", emptyList(), false))
        assertNotNull(pending.check("rk_b", "0", emptyList(), true,
            currentScreen = "root# setenforce ",
            additionalCheck = termuxSessionApprovalCheck(true, "0")))
        assertNull(pending.check("rk_c", "echo ", emptyList(), false))
        assertNotNull(pending.check("rk_c", "0", emptyList(), true,
            currentScreen = "root# setenforce ",
            additionalCheck = termuxSessionApprovalCheck(false, "0")))
        // The terminal already contains the cached prefix, so it must not be appended twice.
        assertNull(pending.check("rk_d", "echo ", emptyList(), false))
        assertNull(pending.check("rk_d", "hi", emptyList(), true, currentScreen = "root# echo "))
    }
}
