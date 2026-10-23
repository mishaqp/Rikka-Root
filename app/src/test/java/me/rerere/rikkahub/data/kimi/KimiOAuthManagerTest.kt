package me.rerere.rikkahub.data.kimi

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.spec.SecretKeySpec

@OptIn(ExperimentalCoroutinesApi::class)
class KimiOAuthManagerTest {
    @get:Rule val folder = TemporaryFolder()
    private fun store() = KimiCredentialStore(folder.root.resolve("kimi_session.enc"), Json) {
        SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    }
    private class Auth : KimiAuthApi {
        val polls = mutableListOf<Long>()
        var clock: () -> Long = { 0 }
        var result: suspend () -> KimiPollResult = { KimiPollResult.Success(KimiTokens("access", "refresh", Long.MAX_VALUE)) }
        override suspend fun authorize(region: KimiRegion) = KimiDeviceAuthorization("ABCD", "device",
            "${region.oauthHost}/device?user_code=ABCD", 60_000, 5_000)
        override suspend fun poll(region: KimiRegion, deviceCode: String): KimiPollResult {
            polls += clock(); return result()
        }
        override suspend fun refresh(region: KimiRegion, tokens: KimiTokens) = error("unused")
    }

    @Test fun `browser flow respects initial interval persists authorization and reports success`() = runTest {
        val auth = Auth().apply { clock = { testScheduler.currentTime } }
        val repository = KimiAccountRepository(store(), api = auth, refreshScope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))
        var opened = ""
        val manager = KimiOAuthManager(this, auth, repository, { opened = it })
        manager.startLogin(KimiRegion.CN)
        runCurrent()
        assertTrue(manager.status.value is KimiOAuthStatus.Waiting)
        assertTrue(opened.startsWith("https://auth.kimi.com/"))
        advanceUntilIdle()
        assertEquals(listOf(5_000L), auth.polls)
        assertTrue(manager.status.value is KimiOAuthStatus.Success)
        assertEquals("access", store().read()!!.tokens.accessToken)
    }

    @Test fun `slow down increases all subsequent polling intervals`() = runTest {
        val auth = Auth().apply { clock = { testScheduler.currentTime } }
        var poll = 0
        auth.result = { when (++poll) {
            1 -> KimiPollResult.Pending(true)
            2 -> KimiPollResult.Pending(false)
            else -> KimiPollResult.Success(KimiTokens("a", "r", Long.MAX_VALUE))
        } }
        val manager = KimiOAuthManager(this, auth, KimiAccountRepository(store(), api = auth, refreshScope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler)), {})
        manager.startLogin(KimiRegion.GLOBAL); advanceUntilIdle()
        assertEquals(listOf(5_000L, 15_000L, 25_000L), auth.polls)
        assertTrue(manager.status.value is KimiOAuthStatus.Success)
    }

    @Test fun `temporary server error backs off and still permits successful login`() = runTest {
        val auth = Auth()
        var calls = 0
        auth.result = {
            if (++calls == 1) throw KimiApiException(503)
            KimiPollResult.Success(KimiTokens("a", "r", Long.MAX_VALUE))
        }
        val manager = KimiOAuthManager(this, auth, KimiAccountRepository(store(), api = auth, refreshScope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler)), {})
        manager.startLogin(KimiRegion.CN); advanceUntilIdle()
        assertTrue(manager.status.value is KimiOAuthStatus.Success)
        assertEquals(2, calls)
    }

    @Test fun `denial and code expiry remain errors without a stored login`() = runTest {
        for (result in listOf(KimiPollResult.Denied, KimiPollResult.Expired)) {
            val auth = Auth().apply { this.result = { result } }
            val manager = KimiOAuthManager(this, auth, KimiAccountRepository(store(), api = auth, refreshScope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler)), {})
            manager.startLogin(KimiRegion.CN); advanceUntilIdle()
            assertTrue(manager.status.value is KimiOAuthStatus.Error)
            assertNull(store().read())
        }
    }

    @Test fun `pending authorization expires rather than polling forever`() = runTest {
        val auth = Auth().apply { result = { KimiPollResult.Pending(false) } }
        val manager = KimiOAuthManager(this, auth, KimiAccountRepository(store(), api = auth, refreshScope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler)), {})
        manager.startLogin(KimiRegion.CN); advanceUntilIdle()
        assertTrue(manager.status.value is KimiOAuthStatus.Error)
        assertTrue(auth.polls.size in 1..12)
        assertNull(store().read())
    }

    @Test fun `cancellation prevents late tokens and stale statuses from being published`() = runTest {
        val auth = Auth()
        val release = CompletableDeferred<Unit>()
        auth.result = { withContext(NonCancellable) {
            release.await(); KimiPollResult.Success(KimiTokens("late", "late-refresh", Long.MAX_VALUE))
        } }
        val manager = KimiOAuthManager(this, auth, KimiAccountRepository(store(), api = auth, refreshScope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler)), {})
        manager.startLogin(KimiRegion.CN)
        advanceTimeBy(5_001); runCurrent()
        manager.cancelLogin(); release.complete(Unit); advanceUntilIdle()
        assertEquals(KimiOAuthStatus.Idle, manager.status.value)
        assertNull(store().read())
    }

    @Test fun `missing browser retains a usable code for manual authorization`() = runTest {
        val auth = Auth()
        val manager = KimiOAuthManager(this, auth, KimiAccountRepository(store(), api = auth, refreshScope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler)), { error("No browser") })
        manager.startLogin(KimiRegion.CN); runCurrent()
        assertTrue((manager.status.value as KimiOAuthStatus.Waiting).browserLaunchFailed)
        advanceUntilIdle()
        assertTrue(manager.status.value is KimiOAuthStatus.Success)
    }
    @Test fun `foreground lease spans browser wait and releases on success and cancellation`() = runTest {
        val auth = Auth()
        var held = 0
        val manager = KimiOAuthManager(this, auth,
            KimiAccountRepository(store(), api = auth, refreshScope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler)), {},
            keepAlive = { operation -> held++; try { operation() } finally { held-- } })
        manager.startLogin(KimiRegion.CN); runCurrent()
        assertEquals(1, held)
        advanceUntilIdle()
        assertEquals(0, held)
        auth.result = { KimiPollResult.Pending(false) }
        manager.startLogin(KimiRegion.GLOBAL); runCurrent()
        assertEquals(1, held)
        manager.cancelLogin(); advanceUntilIdle()
        assertEquals(0, held)
        assertEquals(KimiOAuthStatus.Idle, manager.status.value)
    }

    @Test fun `consuming an old result cannot reset a newer authorization`() = runTest {
        val auth = Auth()
        val manager = KimiOAuthManager(this, auth,
            KimiAccountRepository(store(), api = auth, refreshScope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler)), {})
        manager.startLogin(KimiRegion.CN); advanceUntilIdle()
        val completed = manager.status.value
        assertTrue(completed is KimiOAuthStatus.Success)
        manager.startLogin(KimiRegion.GLOBAL); runCurrent()
        manager.consumeResult(completed)
        assertTrue(manager.status.value is KimiOAuthStatus.Waiting)
        manager.cancelLogin(); advanceUntilIdle()
    }

}
