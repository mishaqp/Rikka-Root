// Adapted from ExTV/rikkahub-agent, local/LocationTool.kt (AGPL v3).
// Uses Android LocationManager so the fork does not require Google Play Services.
package me.rerere.rikkahub.data.ai.tools.local

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool

private fun locationPayload(location: Location, cached: Boolean, precise: Boolean, requested: String): JsonObject = buildJsonObject {
    put("latitude", location.latitude); put("longitude", location.longitude)
    if (location.hasAccuracy()) put("accuracy_m", location.accuracy)
    if (location.hasAltitude()) put("altitude", location.altitude)
    if (location.hasSpeed()) put("speed", location.speed)
    if (location.hasBearing()) put("bearing", location.bearing)
    put("provider", location.provider.orEmpty()); put("timestamp_ms", location.time)
    put("cached", cached); put("age_ms", locationCachedAgeMs(location.time, System.currentTimeMillis()) ?: -1)
    put("precision", if (precise) "precise" else "approximate"); put("requested_accuracy", requested)
    if (cached) put("note", "Последнее известное местоположение; проверьте age_ms.")
}

fun locationTool(context: Context): Tool = personalJsonTool(context, "get_location",
    "Получает местоположение только при открытом Rikka-Root без Google Play Services; поддерживает приблизительное разрешение и явно указывает precision. accuracy high/balanced/low (по умолчанию balanced), timeout_ms 1000–60000 (по умолчанию 30000). Свежий кэш до 2 минут может использоваться сразу, более старый запасной результат помечается cached и age_ms. JSON содержит координаты, время и доступную точность. Не отслеживает в фоне; для истории перемещений не предназначен.", LocalToolOption.Location,
    InputSchema.Obj(buildJsonObject {
        put("accuracy", buildJsonObject { put("type", "string"); put("description", "Желаемая точность: high предпочитает GPS, balanced и low — сеть; фактическая точность зависит от разрешения и доступных служб."); put("enum", buildJsonArray { add("high"); add("balanced"); add("low") }); put("default", "balanced") })
        put("timeout_ms", buildJsonObject { put("type", "integer"); put("minimum", 1000); put("maximum", 60000); put("description", "Срок ожидания координат в миллисекундах: 1000–60000, по умолчанию 30000."); put("default", 30000) })
    }), ::locationArguments, read = { args -> withContext(Dispatchers.Main.immediate) {
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@withContext deviceToolError("Для получения местоположения откройте Rikka-Root. Фоновый доступ не используется.")
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            return@withContext deviceToolError("Разрешение местоположения отозвано. Включите его в настройках функции ассистента.", Manifest.permission.ACCESS_COARSE_LOCATION)
        val manager = context.getSystemService(LocationManager::class.java) ?: return@withContext deviceToolError("Служба местоположения недоступна. Включите геолокацию в настройках Android.")
        val precise = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val providers = locationProviders(args.accuracy, precise, Build.VERSION.SDK_INT) { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) return@withContext deviceToolError(if (precise) "Местоположение выключено или нет доступного провайдера. Включите геолокацию Android." else "Нет провайдера приблизительного местоположения. Включите сетевое определение местоположения или выдайте точный доступ в настройках Android.")
        val cached = providers.mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .filter { locationCachedAgeMs(it.time, System.currentTimeMillis()) != null }
            .maxByOrNull { it.time }
        val cachedAge = cached?.let { locationCachedAgeMs(it.time, System.currentTimeMillis()) }
        if (cached != null && cachedAge != null && cachedAge <= 120000) return@withContext locationPayload(cached, true, precise, args.accuracy)

        val fix = CompletableDeferred<Location?>()
        var background = false
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) { if (locationCachedAgeMs(location.time, System.currentTimeMillis()) != null) fix.complete(location) }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Deprecated by Android") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { background = true; fix.complete(null) }
        }
        lifecycle.addObserver(observer)
        try {
            var requested = 0
            providers.forEach { provider ->
                try { manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper()); requested++ }
                catch (_: IllegalArgumentException) { /* Provider disappeared; another provider may work. */ }
            }
            if (requested == 0) return@withContext deviceToolError("Провайдеры местоположения недоступны. Проверьте настройки Android.")
            val location = withTimeoutOrNull(args.timeoutMs.toLong()) { fix.await() }
            when {
                background || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) -> deviceToolError("Получение местоположения отменено: приложение ушло в фон. Откройте Rikka-Root и при необходимости повторите запрос.")
                location != null -> locationPayload(location, false, precise, args.accuracy)
                cached != null -> locationPayload(cached, true, precise, args.accuracy)
                else -> deviceToolError("За отведённое время местоположение не получено. Проверьте геолокацию и попробуйте у окна или на открытом месте.")
            }
        } finally {
            runCatching { manager.removeUpdates(listener) }
            lifecycle.removeObserver(observer)
        }
    } })
