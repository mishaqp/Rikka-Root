package me.rerere.rikkahub.data.ai.tools

import me.rerere.rikkahub.data.ai.tools.local.checkTermuxCommand
import me.rerere.rikkahub.root.RootCommandGuard
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ProtectedPathDelimiterAuditTest(private val rootCommand: String, private val sharedCommand: String) {
    @Test fun `root floor blocks protected path before shell delimiter`() {
        assertNotNull(rootCommand, RootCommandGuard.check(rootCommand))
    }

    @Test fun `shared hardline blocks protected path before shell delimiter`() {
        assertNotNull(sharedCommand, HardlineCommandGuard.checkCommand(sharedCommand))
    }

    @Test fun `Termux floor blocks protected path before shell delimiter`() {
        assertNotNull(rootCommand, checkTermuxCommand(rootCommand, emptyArray()))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} / {1}")
        fun delimitedPaths(): List<Array<String>> = listOf(
            arrayOf("rm -rf /system; true", "rm -rf /; true"),
            arrayOf("rm -rf /system&& true", "rm -rf /&& true"),
            arrayOf("rm -rf /system|| true", "rm -rf /|| true"),
            arrayOf("rm -rf /system| cat", "rm -rf /| cat"),
            arrayOf("rm -rf /system& true", "rm -rf /& true"),
            arrayOf("(rm -rf /system)", "(rm -rf /)"),
            arrayOf("{ rm -rf /system;}", "{ rm -rf /;}"),
        )
    }
}
