// Adapted from ExTV/rikkahub-agent, local/SpeechToTextTool.kt (AGPL v3).
package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool

fun speechToTextTool(context: Context): Tool = personalJsonTool(context,"speech_to_text",
    "Слушает речь на видимом экране и возвращает JSON text; требуется микрофон. Сначала Android, при недоступности, ошибке или таймауте просит повторить речь через ASR из настроек чата с его существующей конфигурацией. language — BCP-47 (по умолчанию язык устройства) только для Android; запасной ASR использует собственный язык. timeout_ms 1000–60000 (по умолчанию 30000) ограничивает обе попытки и финальную транскрипцию. prefer_offline=true предпочитает локальный Android, но распознавание может требовать сеть. Отмена, фон или потеря аудиофокуса закрывают микрофон. Для сохранения аудио используйте record_audio.",LocalToolOption.SpeechToText,
    InputSchema.Obj(buildJsonObject {
        put("language",buildJsonObject { put("type","string"); put("description", "Тег языка BCP-47 для распознавания Android, например ru-RU; по умолчанию текущий язык устройства. Запасной ASR использует свой язык."); put("maxLength", 80) }); put("timeout_ms",buildJsonObject { put("type","integer"); put("minimum",1000); put("maximum",60000); put("description", "Общий срок распознавания в миллисекундах, 1000–60000; по умолчанию 30000, включая запасную попытку."); put("default", 30000) })
        put("prefer_offline",buildJsonObject { put("type","boolean"); put("description", "Предпочитать локальное распознавание Android; true по умолчанию, доступность без сети не гарантируется."); put("default", true) })
    }), validate={ PersonalUiRequest.Speech(speechLanguage(if (it.containsKey("language")) it.textArgument("language").also { v -> require(v != null) { "language должен быть строкой." } } else null),speechTimeout(it),personalBoolean(it,"prefer_offline",true)) },
    read={ request -> when (val result=awaitPersonalToolUi(context,request,request.timeoutMs+10000L)) {
        is PersonalUiResult.Speech -> buildJsonObject { put("text",result.text) }
        else -> personalUiError(result, "Проверьте микрофон, язык Android и провайдер распознавания речи в настройках приложения.")
    } })
