package me.rerere.rikkahub.data.kimi

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

sealed interface KimiOAuthStatus {
    data object Idle : KimiOAuthStatus
    data object Starting : KimiOAuthStatus
    data class Waiting(val userCode: String, val verificationUrl: String, val browserLaunchFailed: Boolean = false, val region: KimiRegion = KimiRegion.CN) : KimiOAuthStatus
    data class Success(val sessionId: String) : KimiOAuthStatus
    data class Error(val message: String) : KimiOAuthStatus
}

class KimiOAuthManager(
    private val scope: CoroutineScope,
    private val api: KimiAuthApi,
    private val repository: KimiAccountRepository,
    private val openBrowser: suspend (String) -> Unit,
    private val keepAlive: suspend (suspend () -> Unit) -> Unit = { operation -> operation() },
) {
    private val mutableStatus = MutableStateFlow<KimiOAuthStatus>(KimiOAuthStatus.Idle)
    val status: StateFlow<KimiOAuthStatus> = mutableStatus.asStateFlow()
    private var job: Job? = null
    @Volatile private var owner = 0L

    @Synchronized
    fun startLogin(region: KimiRegion) {
        val attempt = ++owner
        job?.cancel()
        mutableStatus.value = KimiOAuthStatus.Starting
        job = scope.launch(start = CoroutineStart.LAZY) {
            var ticket: Long? = null
            fun publish(status: KimiOAuthStatus) {
                synchronized(this@KimiOAuthManager) {
                    if (owner == attempt) mutableStatus.value = status
                }
            }
            try {
                ticket = repository.beginLogin()
                keepAlive {
                    val authorization = api.authorize(region)
                    val waiting = KimiOAuthStatus.Waiting(authorization.userCode, authorization.verificationUrl, region = region)
                    publish(waiting)
                    try { openBrowser(authorization.verificationUrl) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { publish(waiting.copy(browserLaunchFailed = true)) }
                    val tokens = withTimeout(authorization.expiresInMillis) {
                        var received: KimiTokens? = null
                        var interval = authorization.intervalMillis
                        var failures = 0
                        while (received == null) {
                            delay(interval)
                            val result = try {
                                api.poll(region, authorization.deviceCode)
                            } catch (error: IOException) {
                                if (error is KimiApiException && !error.retryable) throw error
                                failures++
                                interval = maxOf(interval, (5_000L shl failures.coerceAtMost(4))).coerceAtMost(300_000L)
                                continue
                            }
                            when (result) {
                                is KimiPollResult.Success -> received = result.tokens
                                is KimiPollResult.Pending -> if (result.slowDown) interval += 5_000L
                                KimiPollResult.Denied -> throw IOException("Вы отклонили вход в Kimi.")
                                KimiPollResult.Expired -> throw IOException("Код входа Kimi истёк. Начните вход заново.")
                            }
                        }
                        requireNotNull(received)
                    }
                    currentCoroutineContext().ensureActive()
                    val saved = repository.saveLogin(requireNotNull(ticket), region, tokens)
                    publish(KimiOAuthStatus.Success(saved.sessionId))
                }
            } catch (_: TimeoutCancellationException) {
                publish(KimiOAuthStatus.Error("Код входа Kimi истёк. Начните вход заново."))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: KimiApiException) {
                publish(KimiOAuthStatus.Error(error.message.orEmpty()))
            } catch (error: IOException) {
                val message = if (error.message in setOf("Вы отклонили вход в Kimi.", "Код входа Kimi истёк. Начните вход заново.")) {
                    error.message.orEmpty()
                } else "Не удалось войти в Kimi. Проверьте сеть и повторите попытку."
                publish(KimiOAuthStatus.Error(message))
            } catch (_: Exception) {
                publish(KimiOAuthStatus.Error("Не удалось сохранить вход Kimi. Повторите попытку."))
            } finally {
                ticket?.let { withContext(NonCancellable) { repository.abandonLogin(it) } }
            }
        }.also { it.start() }
    }

    @Synchronized
    fun cancelLogin() { owner++; job?.cancel(); job = null; mutableStatus.value = KimiOAuthStatus.Idle }

    @Synchronized
    fun consumeResult(expected: KimiOAuthStatus = status.value) {
        if (status.value == expected && (expected is KimiOAuthStatus.Success || expected is KimiOAuthStatus.Error)) mutableStatus.value = KimiOAuthStatus.Idle
    }
}
