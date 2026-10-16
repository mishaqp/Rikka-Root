package me.rerere.rikkahub.data.ai.tools

import me.rerere.rikkahub.data.ai.tools.local.checkTermuxCommand
import me.rerere.rikkahub.root.RootCommandGuard
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class HardlineCommandPositionAuditTest(private val command: String) {
    @Test fun `root floor blocks visible power command`() {
        assertNotNull(command, RootCommandGuard.check(command))
    }

    @Test fun `shared hardline blocks visible power command`() {
        assertNotNull(command, HardlineCommandGuard.checkCommand(command))
    }

    @Test fun `Termux blocks visible power command`() {
        assertNotNull(command, checkTermuxCommand(command, emptyArray()))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun commandPositions(): List<Array<String>> = listOf(
            "if true; then :; else reboot; fi",
            "if reboot; then :; fi",
            "while reboot; do :; done",
            "until reboot; do :; done",
            "command reboot",
            "! reboot",
            "FOO=1 reboot",
            "env -u FOO reboot",
            "bash -ce reboot",
            "bash -lce reboot",
        ).map { arrayOf(it) }
    }
}
