// Adapted from ExTV/rikkahub-agent, local/SpeechToTextTool.kt (AGPL v3).
package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool

fun speechToTextTool(context: Context): Tool = personalJsonTool(context,"speech_to_text",
    "Listen to speech on a visible foreground screen and return text. Requires Android's recognition service, not a local LLM. language BCP-47 (default device language); timeout_ms 1000-60000 (default 30000); prefer_offline true by default. Uses on-device service if available, otherwise asks system recognizer to prefer offline. The configured system service may require network. Cancel/background closes microphone and recognizer.",LocalToolOption.SpeechToText,
    InputSchema.Obj(buildJsonObject {
        put("language",buildJsonObject { put("type","string") }); put("timeout_ms",buildJsonObject { put("type","integer"); put("minimum",1000); put("maximum",60000) })
        put("prefer_offline",buildJsonObject { put("type","boolean") })
    }), validate={ PersonalUiRequest.Speech(speechLanguage(if (it.containsKey("language")) it.textArgument("language").also { v -> require(v != null) { "language должен быть строкой." } } else null),speechTimeout(it),personalBoolean(it,"prefer_offline",true)) },
    read={ request -> when (val result=awaitPersonalToolUi(context,request,request.timeoutMs+10000L)) {
        is PersonalUiResult.Speech -> buildJsonObject { put("text",result.text) }
        else -> personalUiError(result)
    } })
