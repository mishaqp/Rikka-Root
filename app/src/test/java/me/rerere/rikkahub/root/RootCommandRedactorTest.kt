package me.rerere.rikkahub.root

import org.junit.Assert.*
import org.junit.Test

class RootCommandRedactorTest {
    @Test fun onlyKnownNamesAndFlagsSurviveFreeArgumentMasking() {
        val value = RootCommandRedactor.redact("echo totally-unlabelled-secret; printf '%s' secret-value; rm -rf /private/path; unknown-secret-script --private-flag")
        assertTrue(value.contains("echo"))
        assertTrue(value.contains("printf"))
        assertTrue(value.contains("-rf"))
        listOf("totally-unlabelled-secret", "%s", "secret-value", "/private/path", "unknown-secret-script", "--private-flag").forEach {
            assertFalse("must mask $it", value.contains(it))
        }
    }

    @Test fun nestedPayloadsVariablesUrlsAndAssignmentsCannotLeak() {
        val value = RootCommandRedactor.redact("SECRET=private-env sh -c 'echo nested-private'; curl https://private-url; echo \"${'$'}PRIVATE_TOKEN\"")
        listOf("private-env", "nested-private", "private-url", "PRIVATE_TOKEN", "SECRET=").forEach {
            assertFalse("must mask $it", value.contains(it))
        }
    }

    @Test fun heredocAndMalformedQuotesCannotLeak() {
        val value = RootCommandRedactor.redact("sh <<EOF\nprivate-heredoc-body\nEOF\nprintf 'unclosed-private")
        assertFalse(value.contains("private-heredoc-body"))
        assertFalse(value.contains("unclosed-private"))
    }
}
