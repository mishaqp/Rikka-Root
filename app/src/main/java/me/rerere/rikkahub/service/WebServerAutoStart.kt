package me.rerere.rikkahub.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.web.WebServerState

private const val TAG = "WebServerAutoStart"

/**
 * Starts the web server enabled in settings. Called from Application.onCreate and again when
 * RouteActivity comes to the foreground: a process started in the background (worker, alarm,
 * receiver) is not allowed to start a foreground service, so the first attempt can fail.
 */
internal object WebServerAutoStart {
    /** Returns true when a start request was handed to Android. */
    fun startIfEnabled(context: Context, settings: Settings, state: WebServerState): Boolean {
        if (!settings.webServerEnabled || state.isRunning || state.isLoading) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !context.isGranted(android.Manifest.permission.POST_NOTIFICATIONS)
        ) {
            Log.w(TAG, "notification permission not granted, skipping")
            return false
        }
        if (Build.VERSION.SDK_INT >= 37 &&
            !settings.webServerLocalhostOnly &&
            !context.isGranted(android.Manifest.permission.ACCESS_LOCAL_NETWORK)
        ) {
            Log.w(TAG, "local network permission not granted, skipping")
            return false
        }
        val intent = Intent(context, WebServerService::class.java).apply {
            action = WebServerService.ACTION_START
            putExtra(WebServerService.EXTRA_PORT, settings.webServerPort)
            putExtra(WebServerService.EXTRA_LOCALHOST_ONLY, settings.webServerLocalhostOnly)
        }
        return try {
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException when the process is in the background;
            // the next RouteActivity start retries.
            Log.w(TAG, "web server start deferred until the app is in the foreground", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "web server start rejected", e)
            false
        }
    }

    private fun Context.isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
