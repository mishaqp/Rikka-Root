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
import kotlinx.serialization.json.JsonPrimitive
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
    put("description", "Тайм-аут физического сеанса NFC в секундах: 5–120, по умолчанию 30."); put("default", 30)
}

internal fun nfcStatusPayload(available: Boolean, enabled: Boolean, foreground: Boolean, busy: Boolean): JsonObject = buildJsonObject {
    put("available", available)
    put("enabled", available && enabled)
    put("app_in_foreground", foreground)
    put("session_active", busy)
    put("ready_to_read", available && enabled && foreground && !busy)
    put("status", when {
        !available -> "На устройстве нет NFC. Используйте устройство с поддержкой NFC."
        !enabled -> "NFC выключен. Включите его в настройках Android."
        !foreground -> "Для чтения метки откройте Rikka-Root."
        busy -> "Уже открыт сеанс NFC. Завершите или отмените его."
        else -> "NFC готов. Для чтения вызовите nfc_read_tag и поднесите метку."
    })
}

fun nfcStatusTool(context: Context): Tool = Tool(
    name = "nfc_status",
    description = "Проверить наличие и включение NFC, открыто ли приложение и занят ли сеанс. Возвращает available, enabled, app_in_foreground, session_active, ready_to_read и русскую подсказку status. Метки не сканирует и экран не открывает; для чтения вызовите nfc_read_tag.",
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
                adapter == null -> "На устройстве нет NFC. Используйте устройство с поддержкой NFC."
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
            is NfcResult.Error -> deviceToolError(result.message + if (write)
                " Выберите доступную для записи NDEF-метку с достаточным объёмом и удерживайте её у NFC-антенны."
                else " Проверьте совместимость метки и удерживайте её у NFC-антенны до завершения чтения.")
            NfcResult.Cancelled -> deviceToolError(if (write) "Сеанс NFC отменён. Если запись уже началась, проверьте содержимое метки." else "Чтение NFC отменено. При необходимости запустите nfc_read_tag снова и поднесите метку.")
            NfcResult.Timeout, null -> deviceToolError("Время сеанса NFC истекло. Проверьте NFC в настройках Android и поднесите метку после запуска нового сеанса.")
        }
    } finally {
        sharedNfcSessions.cancel(id)
    }
}

fun nfcReadTagTool(context: Context): Tool = Tool(
    name = "nfc_read_tag",
    description = "Прочитать NDEF-записи и UID физически поднесённой NFC-метки. Открывает экран с отменой только при открытом Rikka-Root. timeout_seconds — 5–120 секунд, по умолчанию 30. Сначала можно проверить nfc_status; для записи есть nfc_write_tag. Возвращает records, tag_id_hex и ndef_supported либо ошибку с дальнейшим действием.",
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
    description = "Заменить содержимое физически поднесённой доступной для записи NDEF-метки. records: 1–32 записи text, uri или raw, суммарно до 64 КиБ; timeout_seconds: 5–120, по умолчанию 30. Открывает экран с отменой, требует открытого Rikka-Root; отмена после начала записи не доказывает отсутствие изменений. Для проверки содержимого используйте nfc_read_tag. Возвращает success и tag_id_hex либо объяснение ошибки.",
    parameters = { InputSchema.Obj(buildJsonObject {
        put("records", buildJsonObject {
            put("type", "array"); put("minItems", 1); put("maxItems", 32)
            put("items", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("kind", buildJsonObject { put("type", "string"); put("description", "Формат записи: text — текст, uri — абсолютная ссылка, raw — содержимое в base64."); put("enum", JsonArray(listOf(JsonPrimitive("text"), JsonPrimitive("uri"), JsonPrimitive("raw")))) })
                    put("value", buildJsonObject { put("type", "string"); put("description", "Текст, абсолютная URI-ссылка либо base64 данных для raw. Общий размер всех записей — до 64 КиБ."); put("maxLength", 100000) })
                    put("tnf", buildJsonObject { put("type", "integer"); put("description", "Для raw обязателен целый TNF от 0 до 5; согласуйте его с типом и содержимым записи."); put("minimum", 0); put("maximum", 5) })
                    put("type_b64", buildJsonObject { put("type", "string"); put("description", "Для raw обязателен тип записи в base64; после декодирования до 255 байт.") })
                    put("id_b64", buildJsonObject { put("type", "string"); put("description", "Необязательный идентификатор записи в base64, после декодирования до 255 байт.") })
                    put("language", buildJsonObject { put("type", "string"); put("description", "Языковой код текстовой записи, по умолчанию ru; до 63 латинских букв, цифр и дефисов, пустая строка допустима."); put("default", "ru"); put("maxLength", 63); put("pattern", "^[A-Za-z0-9-]{0,63}$") })
                }); put("description", "Одна NDEF-запись: kind и value обязательны; для raw дополнительно нужны tnf и type_b64."); put("required", JsonArray(listOf(JsonPrimitive("kind"), JsonPrimitive("value")))) }); put("description", "От 1 до 32 NDEF-записей, суммарно до 64 КиБ. Каждая содержит kind и value; поля raw описаны внутри записи.") })
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
