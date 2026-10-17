package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.readClipboardText

internal fun buildClipboardTool(context: Context): Tool = Tool(
    name = "clipboard_tool",
    description = """
        Read or write plain text from the device clipboard.
        Use action: read or write. For write, provide text.
        Card numbers, passwords and tokens in a read are masked; pass reveal: true only when
        the user asked for the exact value (this needs user approval).
        Do NOT write to the clipboard unless the user has explicitly requested it.
    """.trimIndent().replace("\n", " "),
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
                    )
                    put("description", "Operation to perform: read or write")
                })
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "Text to write to the clipboard (required for write)")
                })
                put("reveal", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Read only: return sensitive values unmasked. Requires user approval.")
                })
            },
            required = listOf("action")
        )
    },
    execute = { deviceToolParts {
        val params = it.jsonObject
        val action = params["action"]?.jsonPrimitive?.contentOrNull ?: error("Укажите action: read для чтения или write для записи в буфер обмена.")
        when (action) {
            "read" -> {
                val text = context.readClipboardText()
                val sensitiveCategories = SensitiveContentDetector.scan(text)
                val reveal = params["reveal"]?.jsonPrimitive?.booleanOrNull == true
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
                val text = params["text"]?.jsonPrimitive?.contentOrNull ?: error("Для записи в буфер обмена укажите text.")
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
