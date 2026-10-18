package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import kotlinx.serialization.json.add
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.readClipboardText

internal fun buildClipboardTool(context: Context): Tool = Tool(
    name = "clipboard_tool",
    description = "Прочитать или записать обычный текст буфера обмена: action=read либо write. Для write нужен строковый text; записывайте только по явной просьбе пользователя. При чтении карты, пароли и токены скрыты как ••••; reveal=true запрашивает точные данные с обычным подтверждением. По умолчанию reveal=false. Не повторяйте раскрытые секреты в ответе или сводках. Возвращает text и сведения masked/warning при обнаружении секретов, для записи — success и text.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put(
                        "enum",
                        buildJsonArray {
                            add("read")
                            add("write")
                        }
                    ); put("description", "Операция: read — чтение, write — запись явно запрошенного пользователем текста.") })
                put("text", buildJsonObject {
                    put("type", "string"); put("description", "Строковый текст для записи, обязателен при action=write. Другие типы не преобразуются в текст.") })
                put("reveal", buildJsonObject {
                    put("type", "boolean"); put("description", "Только чтение: true раскрывает секреты с обычным подтверждением. По умолчанию false — маскирование."); put("default", false) })
            },
            required = listOf("action")
        )
    },
    execute = { deviceToolParts {
        val params = it as? JsonObject ?: error("Передайте параметры буфера обмена в JSON-объекте.")
        val reveal = personalBoolean(params, "reveal", false)
        val action = params.textArgument("action") ?: error("Укажите action: read для чтения или write для записи в буфер обмена.")
        when (action) {
            "read" -> {
                val text = context.readClipboardText()
                val sensitiveCategories = SensitiveContentDetector.scan(text)
                val payload = buildJsonObject {
                    put("text", if (sensitiveCategories.isEmpty() || reveal) text else SensitiveContentDetector.mask(text))
                    if (sensitiveCategories.isNotEmpty()) {
                        put("sensitive_content_detected", true)
                        put("masked", !reveal)
                        put("warning", "В буфере обмена обнаружены возможные конфиденциальные данные (${sensitiveCategories.joinToString { category -> category.name.lowercase() }}). " +
                            (if (reveal) "Не повторяйте их в ответе и не включайте в сводки; используйте только для запрошенного пользователем действия."
                            else "Они скрыты как ••••. Если пользователю нужно точное значение, повторите чтение с reveal: true — потребуется его подтверждение."))
                    }
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }

            "write" -> {
                val text = params.textArgument("text") ?: error("Для записи в буфер обмена укажите text.")
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    ?: error("Буфер обмена Android недоступен. Проверьте системную службу буфера обмена.")
                // Unlike the UI helper, a tool must propagate a rejected write instead of claiming success.
                clipboard.setPrimaryClip(ClipData.newPlainText("text", text))
                val payload = buildJsonObject {
                    put("success", true)
                    put("text", text)
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }

            else -> error("Укажите action: read для чтения или write для записи в буфер обмена.")
        }
    } }
)
