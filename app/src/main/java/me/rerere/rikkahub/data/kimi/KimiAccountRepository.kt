package me.rerere.rikkahub.data.kimi

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class KimiAccountRepository internal constructor(
    private val store: KimiCredentialStore,
    private val api: KimiAuthApi,
    private val refreshScope: CoroutineScope,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val stateMutex = Mutex()
    private val refreshMutex = Mutex()
    private var loaded = false
    private var sessionRevision = 0L
    private var loginRevision = 0L
    private val mutableAccount = MutableStateFlow<KimiAccount?>(null)
    val account: StateFlow<KimiAccount?> = mutableAccount.asStateFlow()

    suspend fun load() = stateMutex.withLock { loadLocked() }

    suspend fun beginLogin(): Long = stateMutex.withLock { loadLocked(); ++loginRevision }

    suspend fun abandonLogin(ticket: Long) = stateMutex.withLock {
        if (loginRevision == ticket) loginRevision++
    }

    suspend fun saveLogin(ticket: Long, region: KimiRegion, tokens: KimiTokens): KimiAccount = stateMutex.withLock {
        currentCoroutineContext().ensureActive()
        if (loginRevision != ticket) throw CancellationException("Вход Kimi отменён или заменён.")
        val result = KimiAccount(region = region, tokens = tokens)
        commitLocked(result)
        sessionRevision++
        loginRevision++
        result
    }

    suspend fun signOut() = stateMutex.withLock {
        loadLocked()
        sessionRevision++
        loginRevision++
        commitLocked(null)
    }

    suspend fun acquire(forceRefresh: Boolean = false, rejectedAccessToken: String? = null): KimiAccount {
        currentCoroutineContext().ensureActive()
        // The app owns refresh-and-persist. A stopped generation cancels only its wait,
        // so a successful server-side token rotation still reaches durable storage.
        // Return failures as a value to avoid cancelling the owning app scope.
        return refreshScope.async {
            runCatching { acquireOwned(forceRefresh, rejectedAccessToken) }
        }.await().getOrThrow()
    }

    private suspend fun acquireOwned(forceRefresh: Boolean, rejectedAccessToken: String?): KimiAccount = refreshMutex.withLock {
        val (current, owner) = stateMutex.withLock {
            loadLocked()
            val current = account.value ?: error("Войдите в Kimi в настройках провайдера.")
            if (current.needsLogin) error("Сессия Kimi недействительна. Войдите снова.")
            current to sessionRevision
        }
        val expiresSoon = current.tokens.expiresAt <= nowMillis() + 60_000L
        if (!expiresSoon && (!forceRefresh || (rejectedAccessToken != null && rejectedAccessToken != current.tokens.accessToken))) {
            return@withLock current
        }
        val refreshed = try {
            api.refresh(current.region, current.tokens)
        } catch (error: KimiApiException) {
            if (error.reauthenticationRequired) stateMutex.withLock {
                if (sessionRevision == owner && account.value?.sessionId == current.sessionId) {
                    commitLocked(requireNotNull(account.value).copy(needsLogin = true))
                }
            }
            throw error
        }
        currentCoroutineContext().ensureActive()
        stateMutex.withLock {
            if (sessionRevision != owner || account.value?.sessionId != current.sessionId) {
                throw CancellationException("Сессия Kimi изменилась во время обновления.")
            }
            // Metadata can change while the token is being refreshed.
            val updated = requireNotNull(account.value).copy(tokens = refreshed, needsLogin = false)
            commitLocked(updated)
            updated
        }
    }

    suspend fun updateMetadata(sessionId: String, transform: (KimiAccount) -> KimiAccount) = stateMutex.withLock {
        loadLocked()
        account.value?.takeIf { it.sessionId == sessionId }?.let { commitLocked(transform(it)) }
    }

    private suspend fun loadLocked() {
        if (loaded) return
        mutableAccount.value = withContext(ioDispatcher) { store.read() }
        loaded = true
    }

    private suspend fun commitLocked(value: KimiAccount?) {
        currentCoroutineContext().ensureActive()
        // A cancelled writer still publishes exactly the state committed to disk.
        withContext(NonCancellable + ioDispatcher) {
            store.write(value)
            mutableAccount.value = value
        }
    }
}
