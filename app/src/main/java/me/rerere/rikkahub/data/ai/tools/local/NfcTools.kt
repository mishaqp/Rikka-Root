// Adapted from ExTV/rikkahub-agent, local/NfcTools.kt (AGPL v3).
package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.content.Intent
import android.nfc.NfcManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.ui.activity.NfcToolActivity
import java.util.UUID

private fun nfcTimeoutSchema() = buildJsonObject {
    put("type", "integer"); put("minimum", 5); put("maximum", 120)
    put("description", "Foreground session timeout in seconds (default 30)")
}

internal fun nfcStatusPayload(available: Boolean, enabled: Boolean, foreground: Boolean, busy: Boolean): JsonObject = buildJsonObject {
    put("available", available)
    put("enabled", available && enabled)
    put("app_in_foreground", foreground)
    put("session_active", busy)
    put("ready_to_read", available && enabled && foreground && !busy)
    put("status", when {
        !available -> "На устройстве нет NFC."
        !enabled -> "NFC выключен. Включите его в настройках Android."
        !foreground -> "Для чтения метки откройте Rikka-Root."
        busy -> "Уже открыт сеанс NFC. Завершите или отмените его."
        else -> "NFC готов. Для чтения вызовите nfc_read_tag и поднесите метку."
    })
}

fun nfcStatusTool(context: Context): Tool = Tool(
    name = "nfc_status",
    description = "Check whether NFC hardware is present, enabled and ready to read. Reports foreground and active-session state. Does not scan tags or open a screen.",
    parameters = { InputSchema.Obj(buildJsonObject {}) },
    needsApproval = { true },
    execute = { deviceToolResult { withContext(Dispatchers.Main.immediate) {
        val adapter = context.getSystemService(NfcManager::class.java)?.defaultAdapter
        nfcStatusPayload(adapter != null, adapter?.isEnabled == true,
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED), sharedNfcSessions.isBusy())
    } } },
)

private suspend fun runNfcSession(context: Context, write: Boolean, timeout: Int, records: JsonArray?): JsonObject {
    val unavailable = withContext(Dispatchers.Main.immediate) {
        if (!ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            "Для NFC откройте Rikka-Root. Фоновый запуск не открывает экран чтения или записи метки."
        } else {
            val adapter = context.getSystemService(NfcManager::class.java)?.defaultAdapter
            when {
                adapter == null -> "На устройстве нет NFC."
                !adapter.isEnabled -> "NFC выключен. Включите его в настройках Android."
                else -> null
            }
        }
    }
    if (unavailable != null) return deviceToolError(unavailable)
    val id = UUID.randomUUID().toString()
    val session = try { sharedNfcSessions.register(id, write, timeout, records) } catch (_: IllegalStateException) {
        return deviceToolError("Уже открыт другой сеанс NFC. Завершите или отмените его.")
    }
    try {
        withContext(Dispatchers.Main.immediate) {
            check(ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            context.startActivity(Intent(context, NfcToolActivity::class.java).apply {
                // Only the random ID crosses Android IPC. Tag contents remain in memory.
                putExtra(NfcToolActivity.EXTRA_REQUEST_ID, id)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
        return when (val result = withTimeoutOrNull(timeout * 1000L + 5000L) { session.await() }) {
            is NfcResult.ReadOk -> buildJsonObject { put("records", Json.parseToJsonElement(result.recordsJson)); put("tag_id_hex", result.tagIdHex); put("ndef_supported", result.ndefSupported) }
            is NfcResult.WriteOk -> buildJsonObject { put("success", true); put("tag_id_hex", result.tagIdHex) }
            is NfcResult.Error -> deviceToolError(result.message)
            NfcResult.Cancelled -> deviceToolError(if (write) "Сеанс NFC отменён. Если запись уже началась, проверьте содержимое метки." else "Чтение NFC отменено.")
            NfcResult.Timeout, null -> deviceToolError("Время сеанса NFC истекло.")
        }
    } finally {
        sharedNfcSessions.cancel(id)
    }
}

fun nfcReadTagTool(context: Context): Tool = Tool(
    name = "nfc_read_tag",
    description = "Read NDEF records and the UID of a physically tapped NFC tag. Opens a foreground screen with Cancel. Requires the app open; timeout 5-120 seconds, default 30.",
    parameters = { InputSchema.Obj(buildJsonObject { put("timeout_seconds", nfcTimeoutSchema()) }) },
    needsApproval = { true },
    execute = { input -> deviceToolResult {
        val obj = input as? JsonObject ?: return@deviceToolResult deviceToolError("Параметры должны быть объектом.")
        val timeout = if (obj.containsKey("timeout_seconds")) obj.integerArgument("timeout_seconds") else 30
        if (timeout == null || timeout !in 5..120) return@deviceToolResult deviceToolError("timeout_seconds должен быть целым числом от 5 до 120.")
        runNfcSession(context, false, timeout, null)
    } },
)

fun nfcWriteTagTool(context: Context): Tool = Tool(
    name = "nfc_write_tag",
    description = "Replace a physically tapped writable NFC tag's NDEF contents with text, URI or raw records (at most 32 records and 64 KiB). Opens a foreground screen with Cancel. Requires the app open; timeout 5-120 seconds, default 30.",
    parameters = { InputSchema.Obj(buildJsonObject {
        put("records", buildJsonObject {
            put("type", "array"); put("minItems", 1); put("maxItems", 32)
            put("items", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("kind", buildJsonObject { put("type", "string"); put("description", "text, uri, or raw") })
                    put("value", buildJsonObject { put("type", "string"); put("description", "Text, absolute URI, or base64 payload for raw") })
                    put("tnf", buildJsonObject { put("type", "integer"); put("description", "Required for raw, 0-5") })
                    put("type_b64", buildJsonObject { put("type", "string"); put("description", "Required for raw: base64 type") })
                    put("id_b64", buildJsonObject { put("type", "string"); put("description", "Optional base64 record ID") })
                    put("language", buildJsonObject { put("type", "string"); put("description", "Optional text language code, default ru") })
                })
            })
        })
        put("timeout_seconds", nfcTimeoutSchema())
    }, required = listOf("records")) },
    needsApproval = { true },
    execute = { input -> deviceToolResult {
        val obj = input as? JsonObject ?: return@deviceToolResult deviceToolError("Параметры должны быть объектом.")
        val records = obj["records"] as? JsonArray ?: return@deviceToolResult deviceToolError("Укажите массив records.")
        try { NfcNdefCodec.encodeRecords(records) } catch (_: IllegalArgumentException) {
            return@deviceToolResult deviceToolError("Некорректные records. Нужны 1–32 записи text, uri с абсолютной ссылкой или raw с корректными base64 и TNF 0–5; общий размер — до 64 КиБ.")
        }
        val timeout = if (obj.containsKey("timeout_seconds")) obj.integerArgument("timeout_seconds") else 30
        if (timeout == null || timeout !in 5..120) return@deviceToolResult deviceToolError("timeout_seconds должен быть целым числом от 5 до 120.")
        runNfcSession(context, true, timeout, records)
    } },
)
