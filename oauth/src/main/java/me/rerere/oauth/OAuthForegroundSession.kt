package me.rerere.oauth

import android.content.Context
import java.util.UUID

/** Share the existing foreground lease with device-code flows that have no loopback server. */
suspend fun <T> withOAuthForegroundSession(context: Context, operation: suspend () -> T): T {
    val sessionId = "device-${UUID.randomUUID()}"
    try {
        OAuthCallbackForegroundService.acquire(context, sessionId)
        return operation()
    } finally {
        OAuthCallbackForegroundService.release(context, sessionId)
    }
}
