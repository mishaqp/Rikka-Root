package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.ai.tools.local.checkTermuxCommand
import me.rerere.rikkahub.root.RootCommandGuard
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HardlineReviewRegressionTest {
    private fun mcp(json: String) = HardlineCommandGuard.checkToolParsed(
        "mcp__notion__create_page", Json.parseToJsonElement(json).jsonObject,
    )

    @Test fun `MCP prose that mentions shutdown words is not a command`() {
        for (text in listOf("Reboot plan for Q3", "Notes\nShutdown checklist", "Restart; reboot required")) {
            assertNull(text, mcp("""{"title":${JsonPrimitive(text)}}"""))
            assertNull(text, mcp("""{"page":{"children":[{"text":${JsonPrimitive(text)}}]}}"""))
        }
    }

    @Test fun `MCP command arguments and destructive payloads stay blocked`() {
        assertNotNull(mcp("""{"command":"rm -rf /"}"""))
        assertNotNull(mcp("""{"command":"reboot"}"""))
        assertNotNull(mcp("""{"args":["sh","-c","reboot"]}"""))
        assertNotNull(mcp("""{"commands":["ls","reboot"]}"""))
        assertNull(mcp("""{"args":["git","commit","-m","Reboot fix"]}"""))
        assertNotNull(mcp("""{"task":"rm -rf /"}"""))
        assertNotNull(mcp("""{"steps":[{"script":"echo ok; shutdown -h now"}]}"""))
    }

    @Test fun `root floor closes remaining wrapper bypasses`() {
        for (command in listOf(
            "su -c 'reboot'", "su -c \"reboot -p\"", "su root -c reboot", "sudo -u root reboot",
            "timeout -s KILL 5 reboot", "nice reboot", "nice -n 10 reboot", "xargs reboot",
            "cmd power reboot", "cmd power shutdown", "echo ok; su -c 'reboot'",
        )) assertNotNull(command, RootCommandGuard.check(command))
    }

    @Test fun `quoted data and harmless commands are not blocked`() {
        for (command in listOf(
            "echo reboot", "grep reboot log", "ls -la", "cat /sdcard/note.txt",
            "git commit -m \"fix; reboot loop\"", "echo 'a; shutdown now'",
        )) {
            assertNull(command, RootCommandGuard.check(command))
            assertNull(command, HardlineCommandGuard.checkCommand(command))
        }
    }

    @Test fun `quoted shell bodies and substitutions are still executed`() {
        for (command in listOf(
            "sh -c 'echo x; reboot'", "bash -lc \"true && reboot\"", "echo \"$(reboot)\"", "echo \\\"; reboot",
            "echo \"`reboot`\"", "eval \"x; reboot\"",
        )) assertNotNull(command, RootCommandGuard.check(command))
    }

    @Test fun `termux pattern flags of ordinary programs are data`() {
        assertNull(checkTermuxCommand("grep", arrayOf("-ic", "shutdown", "log")))
        assertNotNull(checkTermuxCommand("bash", arrayOf("-lc", "reboot")))
        assertNotNull(checkTermuxCommand("/system/bin/sh", arrayOf("-c", "reboot")))
        assertNotNull(checkTermuxCommand("busybox", arrayOf("sh", "-c", "reboot")))
    }
}
