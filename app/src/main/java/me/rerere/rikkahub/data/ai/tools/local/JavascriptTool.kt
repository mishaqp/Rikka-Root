package me.rerere.rikkahub.data.ai.tools.local

import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.QuickJsInterruptedException
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import kotlin.time.Duration.Companion.milliseconds

internal fun buildJavascriptTool(): Tool = Tool(
    name = "eval_javascript",
    description = """
        Выполнить JavaScript в QuickJS (ES2020) для вычислений и обработки данных.
        Возвращается значение последнего выражения; для десятичных чисел используйте toFixed().
        Сообщения console возвращаются в logs. DOM и API Node.js недоступны.
        Лимит выполнения — $JS_EXECUTION_TIMEOUT_MS мс; код вроде '1 + 2' возвращает 3.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("code", buildJsonObject {
                    put("type", "string")
                    put("description", "Код JavaScript для выполнения в QuickJS без DOM и API Node.js; результат — последнее выражение.")
                })
            },
            required = listOf("code")
        )
    },
    execute = {
        val code = requireNotNull(it.jsonObject["code"]?.jsonPrimitive?.contentOrNull) {
            "Укажите код JavaScript в code."
        }
        listOf(UIMessagePart.Text(evaluateJavascript(code)))
    }
)

internal suspend fun evaluateJavascript(
    code: String,
    timeoutMillis: Long = JS_EXECUTION_TIMEOUT_MS,
): String = withContext(Dispatchers.Default) {
    val logs = StringBuilder()
    var logsTruncated = false
    fun appendLog(line: String) {
        val remaining = JS_MAX_LOG_CHARS - logs.length
        if (remaining <= 0) {
            logsTruncated = true
            return
        }
        val entry = if (logs.isEmpty()) line else "\n$line"
        logs.append(entry.take(remaining))
        if (entry.length > remaining) logsTruncated = true
    }

    fun errorPayload(message: String) = buildJsonObject {
        put("error", message)
    }.toString()

    try {
        withTimeoutOrNull(timeoutMillis.milliseconds) {
            // This binding interrupts native evaluation on timeout/cancellation and closes
            // the runtime only after execution stops, including result serialization.
            quickJs(Dispatchers.Default) {
                memoryLimit = JS_MEMORY_LIMIT_BYTES
                maxStackSize = JS_MAX_STACK_BYTES
                evaluationTimeoutMillis = timeoutMillis
                function("__rikkahubLog") { args ->
                    appendLog(args[0] as String)
                }
                // Convert values inside the timed evaluation: getters/toJSON can run JS,
                // and returning arbitrary objects would also bypass the output limit.
                val result = evaluate<String?>(
                    """
                    (() => {
                        const log = globalThis.__rikkahubLog;
                        delete globalThis.__rikkahubLog;
                        const stringify = JSON.stringify;
                        const toString = String;
                        const format = value => {
                            if (value !== null && typeof value === 'object') {
                                try { return stringify(value); } catch (_) {}
                            }
                            return toString(value);
                        };
                        globalThis.console = {};
                        for (const level of ['log', 'info', 'warn', 'error', 'debug']) {
                            console[level] = (...args) => {
                                let line = '[' + (level === 'debug' ? 'LOG' : level.toUpperCase()) + ']';
                                for (const arg of args) {
                                    line += ' ' + format(arg);
                                    if (line.length > $JS_MAX_LOG_CHARS) {
                                        line = line.slice(0, $JS_MAX_LOG_CHARS) + ' [усечено]';
                                        break;
                                    }
                                }
                                log(line);
                            };
                        }
                        const result = (0, eval)(${JsonPrimitive(code)});
                        if (result == null) return null;
                        const type = typeof result;
                        const text = type === 'object' || type === 'function'
                            ? stringify(result) : toString(result);
                        if (text != null && text.length > $JS_MAX_RESULT_CHARS) {
                            throw new Error('Результат JavaScript превышает допустимый размер');
                        }
                        return text == null ? null : text;
                    })()
                    """.trimIndent()
                )
                buildJsonObject {
                    if (logs.isNotEmpty()) {
                        put("logs", logs.toString() + if (logsTruncated) "\n[Журнал усечён]" else "")
                    }
                    put("logs_truncated", logsTruncated)
                    put("result", result?.let(::JsonPrimitive) ?: JsonNull)
                }.toString()
            }
        } ?: errorPayload("Превышено время выполнения JavaScript: ${timeoutMillis} мс")
    } catch (_: QuickJsInterruptedException) {
        errorPayload("Превышено время выполнения JavaScript: ${timeoutMillis} мс")
    } catch (e: QuickJsException) {
        errorPayload((e.message ?: "Не удалось выполнить JavaScript").take(JS_MAX_LOG_CHARS))
    }
}

private const val JS_EXECUTION_TIMEOUT_MS = 10_000L
private const val JS_MEMORY_LIMIT_BYTES = 64L * 1024 * 1024
private const val JS_MAX_STACK_BYTES = 256L * 1024
private const val JS_MAX_LOG_CHARS = 64 * 1024
private const val JS_MAX_RESULT_CHARS = 1024 * 1024
