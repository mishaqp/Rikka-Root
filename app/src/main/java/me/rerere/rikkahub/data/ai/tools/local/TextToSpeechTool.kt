package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getSelectedTTSProvider
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.tts.provider.TTSManager

internal fun buildTextToSpeechTool(
    eventBus: AppEventBus,
    ttsManager: TTSManager,
    settingsStore: SettingsStore,
): Tool = Tool(
    name = "text_to_speech",
    description = """
        Озвучить текст выбранным движком синтеза речи по просьбе пользователя или когда полезен
        голосовой ответ. Вызов возвращается сразу, звук воспроизводится в фоне на устройстве.
        Передавайте естественный текст без Markdown, не более $MAX_SPEAK_CHARS символов.
    """.trimIndent().replace("\n", " "),
    systemPrompt = { _, _ ->
        // 当前选中的 TTS provider 若硬编码了语气标记引导，则注入 system prompt（否则为空）
        settingsStore.settingsFlow.value.getSelectedTTSProvider()
            ?.let { ttsManager.getPromptGuidance(it) }
            .orEmpty()
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "Текст для озвучивания без Markdown: не более $MAX_SPEAK_CHARS символов.")
                    put("maxLength", MAX_SPEAK_CHARS)
                })
            },
            required = listOf("text")
        )
    },
    execute = { deviceToolParts {
        val text = it.jsonObject["text"]?.jsonPrimitive?.contentOrNull
            ?: error("Укажите text — текст для озвучивания.")
        // The app's TTS splits long text into chunks itself (system or cloud provider), so the
        // single-utterance Android limit does not apply; only bound runaway cost.
        require(text.length <= MAX_SPEAK_CHARS) {
            "Текст для озвучивания слишком длинный: не более $MAX_SPEAK_CHARS символов. Сократите текст или разделите его на части."
        }
        eventBus.emit(AppEvent.Speak(text))
        val payload = buildJsonObject {
            put("success", true)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    } }
)

internal const val MAX_SPEAK_CHARS = 20_000
