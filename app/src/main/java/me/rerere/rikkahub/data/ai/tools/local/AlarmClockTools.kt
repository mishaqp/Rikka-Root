package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.AlarmClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.tools.RunExecutionContext

private data class AlarmArguments(
    val hour: Int,
    val minute: Int,
    val message: String?,
    val days: ArrayList<Int>?,
    val skipUi: Boolean,
)

private data class TimerArguments(val seconds: Int, val message: String?, val skipUi: Boolean)

private fun clockInteger(input: JsonObject, name: String, range: IntRange): Int {
    val value = (input[name] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
    require(value != null && value in range) {
        "$name должен быть целым числом от ${range.first} до ${range.last}."
    }
    return value
}

private fun clockMessage(input: JsonObject): String? {
    if ("message" !in input) return null
    return input.textArgument("message").also {
        require(it != null && it.length <= 4096 && '\u0000' !in it) {
            "message должен быть строкой до 4096 символов без нулевого символа."
        }
    }
}

private fun clockDays(input: JsonObject): ArrayList<Int>? {
    if ("days" !in input) return null
    val days = input["days"] as? JsonArray
        ?: throw IllegalArgumentException("days должен быть массивом целых чисел: воскресенье — 1, суббота — 7.")
    return ArrayList(days.map { day ->
        val value = (day as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        require(value != null && value in 1..7) {
            "days должен содержать целые числа от 1 до 7: воскресенье — 1, понедельник — 2, суббота — 7."
        }
        value
    })
}

private fun clockField(type: String, description: String, minimum: Int? = null, maximum: Int? = null, maxLength: Int? = null) = buildJsonObject {
    put("type", type)
    put("description", description)
    minimum?.let { put("minimum", it) }; maximum?.let { put("maximum", it) }; maxLength?.let { put("maxLength", it) }
}

private fun clockMessageField() = clockField("string", "Необязательная подпись до 4096 символов без нулевого символа.", maxLength = 4096)

private fun clockSkipUiField() = buildJsonObject {
    put("type", "boolean")
    put("default", true)
    put("description", "По умолчанию true: попросить часы выполнить запрос без экрана настройки. Приложение часов может показать свой интерфейс.")
}

private suspend fun dispatchClock(context: Context, intent: Intent, action: String): JsonObject =
    withContext(Dispatchers.Main.immediate) {
        if (currentCoroutineContext()[RunExecutionContext] != null) {
            return@withContext deviceToolError("Будильники и таймеры доступны только из открытого чата, без фонового запуска.")
        }
        if (!ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            return@withContext deviceToolError("Откройте Rikka-Root, чтобы передать запрос приложению часов.")
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // A direct launch also reaches Clock handlers hidden by Android package visibility.
        try {
            context.startActivity(intent)
            buildJsonObject {
                put("success", true)
                put("intent_fired", true)
                put("action", action)
                put("note", "Системный запрос передан приложению часов. Проверьте результат в часах: Android не сообщает, создан ли будильник или таймер.")
            }
        } catch (_: ActivityNotFoundException) {
            deviceToolError("Нет приложения часов для этого действия. Установите или включите приложение часов с поддержкой будильников и таймеров.")
        } catch (_: SecurityException) {
            deviceToolError("Android запретил запрос к часам. Проверьте доступность приложения часов и разрешения Rikka-Root.")
        }
    }

internal fun alarmClockTools(context: Context): List<Tool> = listOf(
    personalJsonTool(
        context = context,
        name = "set_alarm",
        description = "Передать приложению часов запрос на будильник. hour — 0–23, minute — 0–59; days использует нумерацию Android Calendar: воскресенье — 1, понедельник — 2, суббота — 7. Без days или с пустым массивом будильник не повторяется. skip_ui=true по умолчанию. Требуются подтверждение и открытое приложение; результат подтверждает только отправку запроса Android.",
        option = LocalToolOption.AlarmClock,
        schema = InputSchema.Obj(buildJsonObject {
            put("hour", clockField("integer", "Час в 24-часовом формате: от 0 до 23.", 0, 23))
            put("minute", clockField("integer", "Минута: от 0 до 59.", 0, 59))
            put("message", clockMessageField())
            put("days", buildJsonObject {
                put("type", "array")
                put("description", "Дни повторения Android Calendar: 1 — воскресенье, 2 — понедельник, 3 — вторник, 4 — среда, 5 — четверг, 6 — пятница, 7 — суббота. Пустой массив означает отсутствие повторения.")
                put("items", buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 7) })
            })
            put("skip_ui", clockSkipUiField())
        }, required = listOf("hour", "minute")),
        validate = { input ->
            AlarmArguments(clockInteger(input, "hour", 0..23), clockInteger(input, "minute", 0..59),
                clockMessage(input), clockDays(input), personalBoolean(input, "skip_ui", true))
        },
        read = { args ->
            val intent = Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, args.hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, args.minute)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, args.skipUi)
            args.message?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            args.days?.let { intent.putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, it) }
            dispatchClock(context, intent, "set_alarm")
        },
    ),
    personalJsonTool(
        context = context,
        name = "set_timer",
        description = "Передать приложению часов запрос на таймер. seconds — целое число секунд от 1 до 86400; message — необязательная подпись; skip_ui=true по умолчанию. Требуются подтверждение и открытое приложение; результат подтверждает только отправку запроса Android.",
        option = LocalToolOption.AlarmClock,
        schema = InputSchema.Obj(buildJsonObject {
            put("seconds", clockField("integer", "Продолжительность в секундах: целое число от 1 до 86400 (24 часа).", 1, 86400))
            put("message", clockMessageField())
            put("skip_ui", clockSkipUiField())
        }, required = listOf("seconds")),
        validate = { input ->
            TimerArguments(clockInteger(input, "seconds", 1..86_400), clockMessage(input),
                personalBoolean(input, "skip_ui", true))
        },
        read = { args ->
            val intent = Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, args.seconds)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, args.skipUi)
            args.message?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            dispatchClock(context, intent, "set_timer")
        },
    ),
    personalJsonTool(
        context = context,
        name = "show_alarms",
        description = "Открыть список будильников в системном приложении часов. Не читает список будильников. Требуются подтверждение и открытое приложение Rikka-Root.",
        option = LocalToolOption.AlarmClock,
        schema = InputSchema.Obj(buildJsonObject {}),
        validate = { Unit },
        read = { dispatchClock(context, Intent(AlarmClock.ACTION_SHOW_ALARMS), "show_alarms") },
    ),
)
