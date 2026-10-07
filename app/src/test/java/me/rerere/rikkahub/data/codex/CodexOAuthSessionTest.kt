package me.rerere.rikkahub.data.codex

import org.junit.Assert.*
import kotlinx.coroutines.*
import java.util.Collections
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class CodexOAuthSessionTest {
    @Test
    fun `three rapid logins wait for cancelled predecessor cleanup before acquiring listener`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lifecycle = CodexOAuthLoginLifecycle(scope)
        val started = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val newestStarted = CompletableDeferred<Unit>()
        val events = Collections.synchronizedList(mutableListOf<String>())
        try {
            lifecycle.start {
                started.complete(Unit)
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) {
                        cleaning.complete(Unit)
                        release.await()
                        events.add("first cleaned")
                    }
                }
            }
            withTimeout(5_000) { started.await() }
            val middle = lifecycle.start { events.add("middle acquired") }
            withTimeout(5_000) { cleaning.await() }
            val newest = lifecycle.start {
                events.add("newest acquired")
                newestStarted.complete(Unit)
            }
            middle.join()
            assertNull(withTimeoutOrNull(100) { newestStarted.await() })
            release.complete(Unit)
            withTimeout(5_000) { newest.join() }
            assertEquals(listOf("first cleaned", "newest acquired"), events.toList())
        } finally { release.complete(Unit); scope.cancel() }
    }

    @Test
    fun `PKCE challenge is S256 with random unpadded URL-safe secrets`() {
        val sessions = CodexOAuthSessions()
        val first = sessions.start("http://127.0.0.1:1455/auth/callback")
        val second = sessions.start("http://127.0.0.1:1455/auth/callback")
        assertNotEquals(first.state, second.state)
        assertNotEquals(first.verifier, second.verifier)
        assertTrue(first.verifier.length in 43..128)
        assertTrue(first.verifier.matches(Regex("[A-Za-z0-9_-]+")))
        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(first.verifier.toByteArray(Charsets.US_ASCII))), first.challenge)
    }

    @Test
    fun `state mismatch does not consume active login but matching callback is single-use`() {
        val sessions = CodexOAuthSessions()
        val login = sessions.start("http://127.0.0.1:1455/auth/callback")
        assertNull(sessions.consume("wrong-state"))
        assertEquals(login, sessions.consume(login.state))
        assertNull(sessions.consume(login.state))
    }

    @Test
    fun `new login invalidates previous state and cancellation clears verifier`() {
        val sessions = CodexOAuthSessions()
        val old = sessions.start("http://127.0.0.1:1455/auth/callback")
        val current = sessions.start("http://127.0.0.1:1457/auth/callback")
        assertNull(sessions.consume(old.state))
        sessions.clear()
        assertNull(sessions.consume(current.state))
    }

    @Test
    fun `callback expires at bounded monotonic deadline`() {
        var now = 10L
        val sessions = CodexOAuthSessions(nowMillis = { now })
        val login = sessions.start("http://127.0.0.1:1455/auth/callback")
        now += CODEX_OAUTH_TIMEOUT_MS
        assertNull(sessions.consume(login.state))
    }
}
