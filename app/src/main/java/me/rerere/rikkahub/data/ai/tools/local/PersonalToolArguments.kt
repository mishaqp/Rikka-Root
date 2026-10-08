// Argument contracts adapted from ExTV/rikkahub-agent local tools (AGPL v3).
package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.Context
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import java.util.Locale

internal fun personalInt(input: JsonObject, name: String, default: Int, range: IntRange): Int {
    val value = if (input.containsKey(name)) input.integerArgument(name) else default
    require(value != null && value in range) { "$name должен быть целым числом от ${range.first} до ${range.last}." }
    return value
}
internal fun personalLimit(input: JsonObject, default: Int, max: Int): Int = personalInt(input, "limit", default, 1..max)
internal fun personalBoolean(input: JsonObject, name: String, default: Boolean): Boolean {
    if (!input.containsKey(name)) return default
    return (input[name] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        ?: throw IllegalArgumentException("$name должен быть true или false.")
}
internal fun personalSince(input: JsonObject): Long? {
    if (!input.containsKey("since_ms")) return null
    val value = (input["since_ms"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
    require(value != null && value >= 0) { "since_ms должен быть неотрицательным целым временем в миллисекундах." }
    return value
}
internal fun personalQuery(input: JsonObject): String = input.textArgument("query").also {
    require(!it.isNullOrBlank() && it.length <= 256) { "query должен содержать от 1 до 256 символов." }
}!!

internal data class SmsArguments(val recipient: String, val body: String, val subscriptionId: Int?)
/** Android's isValidSubscriptionId is API 29+, but its validity rule is simply nonnegative. */
internal fun resolveSmsSubscriptionId(requested: Int?, defaultId: () -> Int): Int? =
    (requested ?: defaultId()).takeIf { it >= 0 }
internal fun validateSmsArguments(input: JsonObject): SmsArguments {
    val recipient = input.textArgument("recipient")
    val body = input.textArgument("body")
    require(!recipient.isNullOrBlank() && recipient.length <= 48 && Regex("^[+]?[0-9 ()-]+$").matches(recipient)) { "recipient должен быть одним телефонным номером." }
    val number = recipient.filterNot { it in " ()-" }
    require(number.count { it in '0'..'9' } in 3..20) { "Телефонный номер должен содержать от 3 до 20 цифр." }
    require(!body.isNullOrEmpty() && body.length <= 4096) { "body должен содержать от 1 до 4096 символов." }
    val subscription = if (input.containsKey("subscription_id")) input.integerArgument("subscription_id").also {
        require(it != null && it >= 0) { "subscription_id должен быть неотрицательным целым номером SIM-подписки Android." }
    } else null
    return SmsArguments(number, body, subscription)
}
internal fun smsSubstringSelection(query: String): String = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
internal fun recordingDuration(input: JsonObject): Int = personalInt(input, "duration_ms", 10000, 1000..300000)
internal fun speechTimeout(input: JsonObject): Int = personalInt(input, "timeout_ms", 30000, 1000..60000)
internal fun speechLanguage(value: String?): String {
    if (value == null) return Locale.getDefault().toLanguageTag()
    require(value.length <= 80 && Regex("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*").matches(value)) { "language должен быть корректным тегом языка, например ru-RU." }
    return Locale.forLanguageTag(value).toLanguageTag().also { require(it != "und") { "Неизвестный тег языка." } }
}
internal data class LocationArguments(val accuracy: String, val timeoutMs: Int)
internal fun locationProviders(accuracy: String, precise: Boolean, sdkInt: Int, enabled: (String) -> Boolean): List<String> {
    val ordered=if (accuracy == "high") listOf("gps", "network") else listOf("network", "gps")
    // Android 12+ accepts COARSE for GPS and obfuscates the result itself.
    return ordered.filter { (precise || sdkInt >= 31 || it != "gps") && enabled(it) }
}
internal fun locationArguments(input: JsonObject): LocationArguments {
    val accuracy = if (input.containsKey("accuracy")) input.textArgument("accuracy") else "balanced"
    require(accuracy in listOf("high", "balanced", "low")) { "accuracy должен быть high, balanced или low." }
    return LocationArguments(accuracy!!, personalInt(input, "timeout_ms", 30000, 1000..60000))
}
internal fun locationCachedAgeMs(fixTimeMs: Long, nowMs: Long): Long? =
    if (fixTimeMs < 0 || nowMs < 0 || fixTimeMs > nowMs && fixTimeMs - nowMs > 5000) null else (nowMs - fixTimeMs).coerceAtLeast(0)

/** Only our structural validation errors are exposed. Android/provider exceptions use deviceToolResult. */
internal fun <T> personalJsonTool(
    context: Context, name: String, description: String, option: LocalToolOption,
    schema: InputSchema.Obj, validate: (JsonObject) -> T, read: suspend (T) -> JsonObject,
): Tool = Tool(name = name, description = description, parameters = { schema }, needsApproval = { true }, execute = { input: JsonElement ->
    deviceToolResult {
        val obj = input as? JsonObject ?: return@deviceToolResult deviceToolError("Параметры должны быть объектом.")
        val args = try { validate(obj) } catch (error: IllegalArgumentException) {
            return@deviceToolResult deviceToolError(error.message ?: "Некорректные параметры.")
        }
        val missing = missingLocalToolPermissions(context, option)
        if (missing.isNotEmpty()) return@deviceToolResult deviceToolError("Доступ не предоставлен или отозван. Включите разрешение этой функции в настройках ассистента.", missing.first())
        withContext(Dispatchers.IO) { read(args) }
    }
})
