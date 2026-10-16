package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HardlineCommandGuardShellTest {
    @Test fun `shared command position blocks shell flags and wrappers`() {
        listOf("sh -c reboot", "bash -lc reboot", "bash -xec 'reboot'", "busybox reboot",
            "toybox reboot", "env PATH=/system/bin timeout 5 busybox reboot", "su 0 reboot").forEach {
            assertNotNull("must block: $it", HardlineCommandGuard.checkCommand(it))
        }
    }

    @Test fun `shared command position recognizes groups and control bodies`() {
        listOf("(reboot)", "{ reboot; }", "if true; then reboot; fi", "for x in 1; do reboot; done").forEach {
            assertNotNull("must block: $it", HardlineCommandGuard.checkCommand(it))
        }
    }

    @Test fun `shared command position retains ordinary diagnostic arguments`() {
        listOf("rm -rf /etc-backup; true", "rm -rf /tmp/root-test; true", "echo reboot", "printf '%s' reboot", "echo '(reboot)'", "busybox echo reboot", "env PATH=/system/bin echo reboot").forEach {
            assertNull("must allow: $it", HardlineCommandGuard.checkCommand(it))
        }
    }
}
