package me.rerere.rikkahub.data.kimi

import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.atomic.AtomicInteger

class KimiAccountRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private val store get() = KimiCredentialStore(folder.root.resolve("kimi_session.enc"), Json) {
        SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    }
    private fun account(expiry: Long = 0) = KimiAccount(region = KimiRegion.CN,
        tokens = KimiTokens("old-access", "old-refresh", expiry))

    private class Auth(private val operation: suspend (KimiTokens) -> KimiTokens) : KimiAuthApi {
        override suspend fun authorize(region: KimiRegion): KimiDeviceAuthorization = error("unused")
        override suspend fun poll(region: KimiRegion, deviceCode: String): KimiPollResult = error("unused")
        override suspend fun refresh(region: KimiRegion, tokens: KimiTokens) = operation(tokens)
    }

    @Test fun `first cold start acquisition reads persisted session before any network call`() = runBlocking {
        val saved = account(Long.MAX_VALUE)
        store.write(saved)
        val repository = KimiAccountRepository(store, refreshScope = this, api = Auth { error("fresh session must not refresh") })
        assertEquals(saved, repository.acquire())
        assertEquals(saved, repository.account.value)
    }

    @Test fun `concurrent expired requests rotate token once and persist before returning`() = runBlocking {
        store.write(account())
        val calls = AtomicInteger()
        val refreshed = KimiTokens("new-access", "rotated-refresh", Long.MAX_VALUE)
        val repository = KimiAccountRepository(store, refreshScope = this, api = Auth { calls.incrementAndGet(); delay(10); refreshed })
        val results = (1..8).map { async(Dispatchers.Default) { repository.acquire() } }.awaitAll()
        assertEquals(1, calls.get())
        assertTrue(results.all { it.tokens == refreshed })
        assertEquals(refreshed, store.read()!!.tokens)
    }

    @Test fun `temporary refresh failure retains credentials and allows next retry`() = runBlocking {
        store.write(account())
        val calls = AtomicInteger()
        val repository = KimiAccountRepository(store, refreshScope = this, api = Auth {
            if (calls.incrementAndGet() == 1) throw KimiApiException(503)
            KimiTokens("new", "rotated", Long.MAX_VALUE)
        })
        assertTrue(runCatching { repository.acquire() }.isFailure)
        assertEquals("old-refresh", store.read()!!.tokens.refreshToken)
        assertFalse(store.read()!!.needsLogin)
        assertEquals("new", repository.acquire().tokens.accessToken)
    }

    @Test fun `invalid grant marks session for reauthentication without deleting credentials`() = runBlocking {
        store.write(account())
        val repository = KimiAccountRepository(store, refreshScope = this, api = Auth { throw KimiApiException(400, "invalid_grant") })
        assertTrue(runCatching { repository.acquire() }.isFailure)
        assertTrue(store.read()!!.needsLogin)
        assertEquals("old-refresh", store.read()!!.tokens.refreshToken)
    }

    @Test fun `logout while refresh is in flight cannot resurrect persisted session`() = runBlocking {
        store.write(account())
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = KimiAccountRepository(store, refreshScope = this, api = Auth {
            started.complete(Unit); release.await()
            KimiTokens("late-access", "late-refresh", Long.MAX_VALUE)
        })
        val pending = async { runCatching { repository.acquire() } }
        started.await()
        withTimeout(2_000) { repository.signOut() }
        release.complete(Unit)
        assertTrue(pending.await().isFailure)
        assertNull(store.read())
        assertNull(repository.account.value)
    }

    @Test fun `cancelled refresh propagates cancellation and preserves usable refresh token`() = runBlocking {
        store.write(account())
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = KimiAccountRepository(store, refreshScope = this, api = Auth {
            started.complete(Unit); release.await()
            KimiTokens("new", "rotated", Long.MAX_VALUE)
        })
        val pending = launch { repository.acquire() }
        started.await(); withTimeout(1_000) { pending.cancelAndJoin() }
        assertEquals("old-refresh", store.read()!!.tokens.refreshToken)
        assertFalse(store.read()!!.needsLogin)
        release.complete(Unit)
        assertEquals("rotated", repository.acquire().tokens.refreshToken)
    }

    @Test fun `cancelled or superseded login ticket cannot replace a signed out account`() = runBlocking {
        val repository = KimiAccountRepository(store, refreshScope = this, api = Auth { it })
        val old = repository.beginLogin()
        val current = repository.beginLogin()
        val tokens = KimiTokens("new", "refresh", Long.MAX_VALUE)
        assertTrue(runCatching { repository.saveLogin(old, KimiRegion.CN, tokens) }.isFailure)
        repository.saveLogin(current, KimiRegion.GLOBAL, tokens)
        assertEquals(KimiRegion.GLOBAL, store.read()!!.region)
        repository.signOut()
        assertTrue(runCatching { repository.saveLogin(current, KimiRegion.CN, tokens) }.isFailure)
        assertNull(store.read())
    }

    @Test fun `refresh rejection for already rotated access token does not refresh twice`() = runBlocking {
        store.write(account(Long.MAX_VALUE))
        val calls = AtomicInteger()
        val repository = KimiAccountRepository(store, refreshScope = this, api = Auth {
            calls.incrementAndGet(); KimiTokens("new", "rotated", Long.MAX_VALUE)
        })
        repository.acquire(forceRefresh = true, rejectedAccessToken = "old-access")
        repository.acquire(forceRefresh = true, rejectedAccessToken = "old-access")
        assertEquals(1, calls.get())
    }
    @Test fun `starting another login does not discard an in flight valid token rotation`() = runBlocking {
        store.write(account())
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = KimiAccountRepository(store, refreshScope = this, api = Auth {
            started.complete(Unit); release.await()
            KimiTokens("new", "rotated", Long.MAX_VALUE)
        })
        val pending = async { runCatching { repository.acquire() } }
        started.await()
        repository.beginLogin()
        release.complete(Unit)
        assertTrue(pending.await().isSuccess)
        assertEquals("rotated", store.read()!!.tokens.refreshToken)
    }

    @Test fun `terminal refresh rejection retains metadata updated during network request`() = runBlocking {
        val saved = account()
        store.write(saved)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = KimiAccountRepository(store, refreshScope = this, api = Auth {
            started.complete(Unit); release.await(); throw KimiApiException(400, "invalid_grant")
        })
        val pending = async { runCatching { repository.acquire() } }
        started.await()
        repository.updateMetadata(saved.sessionId) { it.copy(name = "Updated profile") }
        release.complete(Unit)
        assertTrue(pending.await().isFailure)
        assertEquals("Updated profile", store.read()!!.name)
        assertTrue(store.read()!!.needsLogin)
    }

    @Test fun `caller cancellation after server rotation cannot lose the new refresh token`() = runBlocking {
        store.write(account())
        val caller = java.util.concurrent.atomic.AtomicReference<Job>()
        val calls = AtomicInteger()
        val repository = KimiAccountRepository(store, refreshScope = this, api = Auth {
            if (calls.incrementAndGet() > 1) throw KimiApiException(400, "invalid_grant")
            caller.get().cancel()
            KimiTokens("rotated-access", "rotated-refresh", Long.MAX_VALUE)
        })
        val pending = launch(start = CoroutineStart.LAZY) { repository.acquire() }
        caller.set(pending); pending.start(); pending.join()
        assertTrue(pending.isCancelled)
        val result = runCatching { repository.acquire() }
        assertTrue("Rotated credentials must survive cancelling their original waiter", result.isSuccess)
        assertEquals(1, calls.get())
        assertEquals("rotated-refresh", store.read()!!.tokens.refreshToken)
    }

}
