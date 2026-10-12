package me.rerere.rikkahub.data.datastore

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.CustomBody
import me.rerere.asr.ASRProviderSetting
import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.serverUrl
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.ui.theme.CustomTheme

// Draft patches compare the opening values, then change only dirty configuration fields.
private fun <T> dirty(current: T, before: T, requested: T): T =
    if (before != requested) requested else current

fun applyProxyDraft(current: NetworkSetting, before: NetworkSetting, requested: NetworkSetting, reset: Boolean = false): NetworkSetting =
    current.copy(
        proxyUrl = if (reset) requested.proxyUrl else dirty(current.proxyUrl, before.proxyUrl, requested.proxyUrl),
        proxyUsername = if (reset) requested.proxyUsername else dirty(current.proxyUsername, before.proxyUsername, requested.proxyUsername),
        proxyPassword = if (reset) requested.proxyPassword else dirty(current.proxyPassword, before.proxyPassword, requested.proxyPassword),
    )

fun appendAssistantPresetMessage(current: Assistant, payload: UIMessage): Assistant {
    val role = when (current.presetMessages.lastOrNull()?.role ?: MessageRole.ASSISTANT) {
        MessageRole.USER -> MessageRole.ASSISTANT
        else -> MessageRole.USER
    }
    return current.copy(presetMessages = current.presetMessages + payload.copy(role = role))
}

/** Moves the current row by captured identities, retaining concurrent rows and edits. */
fun <T, K> moveSettingsItem(rows: List<T>, moved: K, anchor: K, after: Boolean, key: (T) -> K): List<T> {
    if (moved == anchor) return rows
    val from = rows.indexOfFirst { key(it) == moved }
    if (from < 0 || rows.none { key(it) == anchor }) return rows
    return rows.toMutableList().apply {
        val row = removeAt(from)
        val target = indexOfFirst { key(it) == anchor }
        add(target + if (after) 1 else 0, row)
    }
}

/** An unchanged frame permits positional duplicates; otherwise identify a unique observed row. */
private fun <T> settingsRowIndex(current: List<T>, observed: List<T>, index: Int, identities: List<(T) -> Any?> = emptyList()): Int? {
    val before = observed.getOrNull(index) ?: return null
    if (current == observed) return index
    if (observed.count { it == before } == 1 && current.count { it == before } == 1) return current.indexOf(before)
    if (current.size != observed.size) return null
    identities.forEach { identity ->
        val value = identity(before)
        if (observed.count { identity(it) == value } == 1 && current.count { identity(it) == value } == 1) {
            val target = current.indexOfFirst { identity(it) == value }
            if (target == index) return target
        }
    }
    return null
}

fun applyProviderDraft(current: ProviderSetting, before: ProviderSetting, requested: ProviderSetting): ProviderSetting {
    if (requested::class != before::class || current::class != before::class) {
        val target = if (requested::class != before::class) requested else current
        val common = target.copyProvider(
            enabled = dirty(current.enabled, before.enabled, requested.enabled),
            name = dirty(current.name, before.name, requested.name),
            models = current.models,
            customHeaders = current.customHeaders,
            builtIn = current.builtIn,
            description = current.description,
            shortDescription = current.shortDescription,
            balanceOption = current.balanceOption.copy(
                enabled = dirty(current.balanceOption.enabled, before.balanceOption.enabled, requested.balanceOption.enabled),
                apiPath = dirty(current.balanceOption.apiPath, before.balanceOption.apiPath, requested.balanceOption.apiPath),
                resultPath = dirty(current.balanceOption.resultPath, before.balanceOption.resultPath, requested.balanceOption.resultPath),
            ),
        )
        val key = dirty(providerDraftKey(current), providerDraftKey(before), providerDraftKey(requested))
        val url = dirty(providerDraftUrl(current), providerDraftUrl(before), providerDraftUrl(requested))
        return when (common) {
            is ProviderSetting.OpenAI -> common.copy(apiKey = key ?: common.apiKey, baseUrl = url ?: common.baseUrl)
            is ProviderSetting.Google -> common.copy(apiKey = key ?: common.apiKey, baseUrl = url ?: common.baseUrl)
            is ProviderSetting.Claude -> common.copy(apiKey = key ?: common.apiKey, baseUrl = url ?: common.baseUrl)
            is ProviderSetting.Codex -> common
        }
    }
    return when (current) {
        is ProviderSetting.OpenAI -> {
            before as ProviderSetting.OpenAI
            requested as ProviderSetting.OpenAI
            current.copy(
                enabled = dirty(current.enabled, before.enabled, requested.enabled),
                name = dirty(current.name, before.name, requested.name),
                apiKey = dirty(current.apiKey, before.apiKey, requested.apiKey),
                baseUrl = dirty(current.baseUrl, before.baseUrl, requested.baseUrl),
                chatCompletionsPath = dirty(current.chatCompletionsPath, before.chatCompletionsPath, requested.chatCompletionsPath),
                useResponseApi = dirty(current.useResponseApi, before.useResponseApi, requested.useResponseApi),
                includeHistoryReasoning = dirty(current.includeHistoryReasoning, before.includeHistoryReasoning, requested.includeHistoryReasoning),
                responsesPath = dirty(current.responsesPath, before.responsesPath, requested.responsesPath),
                balanceOption = current.balanceOption.copy(
                    enabled = dirty(current.balanceOption.enabled, before.balanceOption.enabled, requested.balanceOption.enabled),
                    apiPath = dirty(current.balanceOption.apiPath, before.balanceOption.apiPath, requested.balanceOption.apiPath),
                    resultPath = dirty(current.balanceOption.resultPath, before.balanceOption.resultPath, requested.balanceOption.resultPath),
                ),
            )
        }
        is ProviderSetting.Google -> {
            before as ProviderSetting.Google
            requested as ProviderSetting.Google
            current.copy(
                enabled = dirty(current.enabled, before.enabled, requested.enabled),
                name = dirty(current.name, before.name, requested.name),
                apiKey = dirty(current.apiKey, before.apiKey, requested.apiKey),
                baseUrl = dirty(current.baseUrl, before.baseUrl, requested.baseUrl),
                vertexAI = dirty(current.vertexAI, before.vertexAI, requested.vertexAI),
                useServiceAccount = dirty(current.useServiceAccount, before.useServiceAccount, requested.useServiceAccount),
                privateKey = dirty(current.privateKey, before.privateKey, requested.privateKey),
                serviceAccountEmail = dirty(current.serviceAccountEmail, before.serviceAccountEmail, requested.serviceAccountEmail),
                location = dirty(current.location, before.location, requested.location),
                projectId = dirty(current.projectId, before.projectId, requested.projectId),
                useInteractionsApi = dirty(current.useInteractionsApi, before.useInteractionsApi, requested.useInteractionsApi),
                balanceOption = current.balanceOption.copy(
                    enabled = dirty(current.balanceOption.enabled, before.balanceOption.enabled, requested.balanceOption.enabled),
                    apiPath = dirty(current.balanceOption.apiPath, before.balanceOption.apiPath, requested.balanceOption.apiPath),
                    resultPath = dirty(current.balanceOption.resultPath, before.balanceOption.resultPath, requested.balanceOption.resultPath),
                ),
            )
        }
        is ProviderSetting.Claude -> {
            before as ProviderSetting.Claude
            requested as ProviderSetting.Claude
            current.copy(
                enabled = dirty(current.enabled, before.enabled, requested.enabled),
                name = dirty(current.name, before.name, requested.name),
                apiKey = dirty(current.apiKey, before.apiKey, requested.apiKey),
                baseUrl = dirty(current.baseUrl, before.baseUrl, requested.baseUrl),
                promptCaching = dirty(current.promptCaching, before.promptCaching, requested.promptCaching),
                promptCacheTtl = dirty(current.promptCacheTtl, before.promptCacheTtl, requested.promptCacheTtl),
                balanceOption = current.balanceOption.copy(
                    enabled = dirty(current.balanceOption.enabled, before.balanceOption.enabled, requested.balanceOption.enabled),
                    apiPath = dirty(current.balanceOption.apiPath, before.balanceOption.apiPath, requested.balanceOption.apiPath),
                    resultPath = dirty(current.balanceOption.resultPath, before.balanceOption.resultPath, requested.balanceOption.resultPath),
                ),
            )
        }
        is ProviderSetting.Codex -> {
            before as ProviderSetting.Codex
            requested as ProviderSetting.Codex
            current.copy(
                enabled = dirty(current.enabled, before.enabled, requested.enabled),
                name = dirty(current.name, before.name, requested.name),
                balanceOption = current.balanceOption.copy(
                    enabled = dirty(current.balanceOption.enabled, before.balanceOption.enabled, requested.balanceOption.enabled),
                    apiPath = dirty(current.balanceOption.apiPath, before.balanceOption.apiPath, requested.balanceOption.apiPath),
                    resultPath = dirty(current.balanceOption.resultPath, before.balanceOption.resultPath, requested.balanceOption.resultPath),
                ),
            )
        }
    }
}

fun applyTtsDraft(current: TTSProviderSetting, before: TTSProviderSetting, requested: TTSProviderSetting): TTSProviderSetting {
    if (requested::class != before::class) return requested.copyProvider(name = dirty(current.name, before.name, requested.name))
    if (current::class != before::class) return current
    return when (current) {
        is TTSProviderSetting.OpenAI -> {
            before as TTSProviderSetting.OpenAI
            requested as TTSProviderSetting.OpenAI
            current.copy(
                name = dirty(current.name, before.name, requested.name),
                apiKey = dirty(current.apiKey, before.apiKey, requested.apiKey),
                baseUrl = dirty(current.baseUrl, before.baseUrl, requested.baseUrl),
                model = dirty(current.model, before.model, requested.model),
                voice = dirty(current.voice, before.voice, requested.voice),
            )
        }
        is TTSProviderSetting.Gemini -> {
            before as TTSProviderSetting.Gemini
            requested as TTSProviderSetting.Gemini
            current.copy(
                name = dirty(current.name, before.name, requested.name),
                apiKey = dirty(current.apiKey, before.apiKey, requested.apiKey),
                baseUrl = dirty(current.baseUrl, before.baseUrl, requested.baseUrl),
                model = dirty(current.model, before.model, requested.model),
                voiceName = dirty(current.voiceName, before.voiceName, requested.voiceName),
            )
        }
        is TTSProviderSetting.SystemTTS -> {
            before as TTSProviderSetting.SystemTTS
            requested as TTSProviderSetting.SystemTTS
            current.copy(
                name = dirty(current.name, before.name, requested.name),
                speechRate = dirty(current.speechRate, before.speechRate, requested.speechRate),
                pitch = dirty(current.pitch, before.pitch, requested.pitch),
            )
        }
        is TTSProviderSetting.MiniMax -> {
            before as TTSProviderSetting.MiniMax
            requested as TTSProviderSetting.MiniMax
            current.copy(
                name = dirty(current.name, before.name, requested.name),
                apiKey = dirty(current.apiKey, before.apiKey, requested.apiKey),
                baseUrl = dirty(current.baseUrl, before.baseUrl, requested.baseUrl),
                model = dirty(current.model, before.model, requested.model),
                voiceId = dirty(current.voiceId, before.voiceId, requested.voiceId),
                speed = dirty(current.speed, before.speed, requested.speed),
            )
        }
        is TTSProviderSetting.Qwen -> {
            before as TTSProviderSetting.Qwen
            requested as TTSProviderSetting.Qwen
            current.copy(
                name = dirty(current.name, before.name, requested.name),
                apiKey = dirty(current.apiKey, before.apiKey, requested.apiKey),
                baseUrl = dirty(current.baseUrl, before.baseUrl, requested.baseUrl),
                model = dirty(current.model, before.model, requested.model),
                voice = dirty(current.voice, before.voice, requested.voice),
                format = dirty(current.format, before.format, requested.format),
                sampleRate = dirty(current.sampleRate, before.sampleRate, requested.sampleRate),
            )
        }
        is TTSProviderSetting.Groq -> {
            before as TTSProviderSetting.Groq
            requested as TTSProviderSetting.Groq
            current.copy(
                name = dirty(current.name, before.name, requested.name),
                apiKey = dirty(current.apiKey, before.apiKey, requested.apiKey),
                baseUrl = dirty(current.baseUrl, before.baseUrl, requested.baseUrl),
                model = dirty(current.model, before.model, requested.model),
                voice = dirty(current.voice, before.voice, requested.voice),
            )
        }
        is TTSProviderSetting.XAI -> {
            before as TTSProviderSetting.XAI
            requested as TTSProviderSetting.XAI
            current.copy(
                name = dirty(current.name, before.name, requested.name),
                apiKey = dirty(current.apiKey, before.apiKey, requested.apiKey),
                baseUrl = dirty(current.baseUrl, before.baseUrl, requested.baseUrl),
                voiceId = dirty(current.voiceId, before.voiceId, requested.voiceId),
                language = dirty(current.language, before.language, requested.language),
            )
        }
        is TTSProviderSetting.MiMo -> {
            before as TTSProviderSetting.MiMo
            requested as TTSProviderSetting.MiMo
            current.copy(
                name = dirty(current.name, before.name, requested.name),
                apiKey = dirty(current.apiKey, before.apiKey, requested.apiKey),
                baseUrl = dirty(current.baseUrl, before.baseUrl, requested.baseUrl),
                model = dirty(current.model, before.model, requested.model),
                voice = dirty(current.voice, before.voice, requested.voice),
            )
        }
        is TTSProviderSetting.ElevenLabs -> {
            before as TTSProviderSetting.ElevenLabs
            requested as TTSProviderSetting.ElevenLabs
            current.copy(
                name = dirty(current.name, before.name, requested.name),
                apiKey = dirty(current.apiKey, before.apiKey, requested.apiKey),
                baseUrl = dirty(current.baseUrl, before.baseUrl, requested.baseUrl),
                model = dirty(current.model, before.model, requested.model),
                voiceId = dirty(current.voiceId, before.voiceId, requested.voiceId),
                stability = dirty(current.stability, before.stability, requested.stability),
                similarityBoost = dirty(current.similarityBoost, before.similarityBoost, requested.similarityBoost),
            )
        }
        is TTSProviderSetting.Step -> {
            before as TTSProviderSetting.Step
            requested as TTSProviderSetting.Step
            current.copy(
                name = dirty(current.name, before.name, requested.name),
                apiKey = dirty(current.apiKey, before.apiKey, requested.apiKey),
                baseUrl = dirty(current.baseUrl, before.baseUrl, requested.baseUrl),
                model = dirty(current.model, before.model, requested.model),
                voice = dirty(current.voice, before.voice, requested.voice),
                responseFormat = dirty(current.responseFormat, before.responseFormat, requested.responseFormat),
                speed = dirty(current.speed, before.speed, requested.speed),
                volume = dirty(current.volume, before.volume, requested.volume),
                sampleRate = dirty(current.sampleRate, before.sampleRate, requested.sampleRate),
                instruction = dirty(current.instruction, before.instruction, requested.instruction),
            )
        }
        is TTSProviderSetting.FishAudio -> {
            before as TTSProviderSetting.FishAudio
            requested as TTSProviderSetting.FishAudio
            current.copy(
                name = dirty(current.name, before.name, requested.name),
                apiKey = dirty(current.apiKey, before.apiKey, requested.apiKey),
                baseUrl = dirty(current.baseUrl, before.baseUrl, requested.baseUrl),
                model = dirty(current.model, before.model, requested.model),
                referenceId = dirty(current.referenceId, before.referenceId, requested.referenceId),
                temperature = dirty(current.temperature, before.temperature, requested.temperature),
                speed = dirty(current.speed, before.speed, requested.speed),
                format = dirty(current.format, before.format, requested.format),
                topP = dirty(current.topP, before.topP, requested.topP),
                chunkLength = dirty(current.chunkLength, before.chunkLength, requested.chunkLength),
                normalize = dirty(current.normalize, before.normalize, requested.normalize),
                latency = dirty(current.latency, before.latency, requested.latency),
            )
        }
        is TTSProviderSetting.Volcengine -> {
            before as TTSProviderSetting.Volcengine
            requested as TTSProviderSetting.Volcengine
            current.copy(
                name = dirty(current.name, before.name, requested.name),
                apiKey = dirty(current.apiKey, before.apiKey, requested.apiKey),
                baseUrl = dirty(current.baseUrl, before.baseUrl, requested.baseUrl),
                resourceId = dirty(current.resourceId, before.resourceId, requested.resourceId),
                speaker = dirty(current.speaker, before.speaker, requested.speaker),
                speechRate = dirty(current.speechRate, before.speechRate, requested.speechRate),
            )
        }
    }
}

fun applyAsrDraft(current: ASRProviderSetting, before: ASRProviderSetting, requested: ASRProviderSetting): ASRProviderSetting {
    if (requested::class != before::class) return requested
    if (current::class != before::class) return current
    return when (current) {
        is ASRProviderSetting.OpenAIRealtime -> {
            before as ASRProviderSetting.OpenAIRealtime
            requested as ASRProviderSetting.OpenAIRealtime
            current.copy(
                name = dirty(current.name, before.name, requested.name),
                apiKey = dirty(current.apiKey, before.apiKey, requested.apiKey),
                websocketUrl = dirty(current.websocketUrl, before.websocketUrl, requested.websocketUrl),
                model = dirty(current.model, before.model, requested.model),
                language = dirty(current.language, before.language, requested.language),
                prompt = dirty(current.prompt, before.prompt, requested.prompt),
                sampleRate = dirty(current.sampleRate, before.sampleRate, requested.sampleRate),
                vadThreshold = dirty(current.vadThreshold, before.vadThreshold, requested.vadThreshold),
                prefixPaddingMs = dirty(current.prefixPaddingMs, before.prefixPaddingMs, requested.prefixPaddingMs),
                silenceDurationMs = dirty(current.silenceDurationMs, before.silenceDurationMs, requested.silenceDurationMs),
            )
        }
        is ASRProviderSetting.DashScope -> {
            before as ASRProviderSetting.DashScope
            requested as ASRProviderSetting.DashScope
            current.copy(
                name = dirty(current.name, before.name, requested.name),
                apiKey = dirty(current.apiKey, before.apiKey, requested.apiKey),
                websocketUrl = dirty(current.websocketUrl, before.websocketUrl, requested.websocketUrl),
                model = dirty(current.model, before.model, requested.model),
                language = dirty(current.language, before.language, requested.language),
                sampleRate = dirty(current.sampleRate, before.sampleRate, requested.sampleRate),
                vadThreshold = dirty(current.vadThreshold, before.vadThreshold, requested.vadThreshold),
                silenceDurationMs = dirty(current.silenceDurationMs, before.silenceDurationMs, requested.silenceDurationMs),
            )
        }
        is ASRProviderSetting.Volcengine -> {
            before as ASRProviderSetting.Volcengine
            requested as ASRProviderSetting.Volcengine
            current.copy(
                name = dirty(current.name, before.name, requested.name),
                apiKey = dirty(current.apiKey, before.apiKey, requested.apiKey),
                websocketUrl = dirty(current.websocketUrl, before.websocketUrl, requested.websocketUrl),
                resourceId = dirty(current.resourceId, before.resourceId, requested.resourceId),
                language = dirty(current.language, before.language, requested.language),
                silenceDurationMs = dirty(current.silenceDurationMs, before.silenceDurationMs, requested.silenceDurationMs),
            )
        }
        is ASRProviderSetting.MiMo -> {
            before as ASRProviderSetting.MiMo
            requested as ASRProviderSetting.MiMo
            current.copy(
                name = dirty(current.name, before.name, requested.name),
                apiKey = dirty(current.apiKey, before.apiKey, requested.apiKey),
                baseUrl = dirty(current.baseUrl, before.baseUrl, requested.baseUrl),
                model = dirty(current.model, before.model, requested.model),
                language = dirty(current.language, before.language, requested.language),
                sampleRate = dirty(current.sampleRate, before.sampleRate, requested.sampleRate),
                segmentDurationSec = dirty(current.segmentDurationSec, before.segmentDurationSec, requested.segmentDurationSec),
            )
        }
        is ASRProviderSetting.Step -> {
            before as ASRProviderSetting.Step
            requested as ASRProviderSetting.Step
            current.copy(
                name = dirty(current.name, before.name, requested.name),
                apiKey = dirty(current.apiKey, before.apiKey, requested.apiKey),
                baseUrl = dirty(current.baseUrl, before.baseUrl, requested.baseUrl),
                model = dirty(current.model, before.model, requested.model),
                language = dirty(current.language, before.language, requested.language),
                sampleRate = dirty(current.sampleRate, before.sampleRate, requested.sampleRate),
                segmentDurationSec = dirty(current.segmentDurationSec, before.segmentDurationSec, requested.segmentDurationSec),
                enableItn = dirty(current.enableItn, before.enableItn, requested.enableItn),
                enableTimestamp = dirty(current.enableTimestamp, before.enableTimestamp, requested.enableTimestamp),
                hotwords = dirty(current.hotwords, before.hotwords, requested.hotwords),
            )
        }
    }
}

fun applyMediaDraft(current: MediaGenerationProviderSetting, before: MediaGenerationProviderSetting, requested: MediaGenerationProviderSetting): MediaGenerationProviderSetting {
    if (requested::class != before::class) return requested
    if (current::class != before::class) return current
    return when (current) {
        is MediaGenerationProviderSetting.OpenAI -> {
            before as MediaGenerationProviderSetting.OpenAI
            requested as MediaGenerationProviderSetting.OpenAI
            current.copy(
                name = dirty(current.name, before.name, requested.name),
                apiKey = dirty(current.apiKey, before.apiKey, requested.apiKey),
                baseUrl = dirty(current.baseUrl, before.baseUrl, requested.baseUrl),
                models = applyMediaModelsDraft(current.models, before.models, requested.models),
            )
        }
        is MediaGenerationProviderSetting.Aliyun -> {
            before as MediaGenerationProviderSetting.Aliyun
            requested as MediaGenerationProviderSetting.Aliyun
            current.copy(
                name = dirty(current.name, before.name, requested.name),
                apiKey = dirty(current.apiKey, before.apiKey, requested.apiKey),
                workspaceId = dirty(current.workspaceId, before.workspaceId, requested.workspaceId),
                baseUrl = dirty(current.baseUrl, before.baseUrl, requested.baseUrl),
                models = applyMediaModelsDraft(current.models, before.models, requested.models),
            )
        }
        is MediaGenerationProviderSetting.Volcengine -> {
            before as MediaGenerationProviderSetting.Volcengine
            requested as MediaGenerationProviderSetting.Volcengine
            current.copy(
                name = dirty(current.name, before.name, requested.name),
                apiKey = dirty(current.apiKey, before.apiKey, requested.apiKey),
                baseUrl = dirty(current.baseUrl, before.baseUrl, requested.baseUrl),
                models = applyMediaModelsDraft(current.models, before.models, requested.models),
            )
        }
        is MediaGenerationProviderSetting.MiniMax -> {
            before as MediaGenerationProviderSetting.MiniMax
            requested as MediaGenerationProviderSetting.MiniMax
            current.copy(
                name = dirty(current.name, before.name, requested.name),
                apiKey = dirty(current.apiKey, before.apiKey, requested.apiKey),
                baseUrl = dirty(current.baseUrl, before.baseUrl, requested.baseUrl),
                models = applyMediaModelsDraft(current.models, before.models, requested.models),
            )
        }
        is MediaGenerationProviderSetting.OpenRouter -> {
            before as MediaGenerationProviderSetting.OpenRouter
            requested as MediaGenerationProviderSetting.OpenRouter
            current.copy(
                name = dirty(current.name, before.name, requested.name),
                apiKey = dirty(current.apiKey, before.apiKey, requested.apiKey),
                baseUrl = dirty(current.baseUrl, before.baseUrl, requested.baseUrl),
                models = applyMediaModelsDraft(current.models, before.models, requested.models),
            )
        }
    }
}

fun applyMcpDraft(current: McpServerConfig, before: McpServerConfig, requested: McpServerConfig): McpServerConfig {
    val options = current.commonOptions.copy(
        enable = dirty(current.commonOptions.enable, before.commonOptions.enable, requested.commonOptions.enable),
        name = dirty(current.commonOptions.name, before.commonOptions.name, requested.commonOptions.name),
        headers = dirty(current.commonOptions.headers, before.commonOptions.headers, requested.commonOptions.headers),
        tools = current.commonOptions.tools.map { tool ->
            val old = before.commonOptions.tools.find { it.name == tool.name }
            val edit = requested.commonOptions.tools.find { it.name == tool.name }
            if (old != null && edit != null) tool.copy(
                enable = dirty(tool.enable, old.enable, edit.enable),
                needsApproval = dirty(tool.needsApproval, old.needsApproval, edit.needsApproval),
            ) else tool
        },
    )
    val target = if (requested::class != before::class) requested else current
    val url = dirty(current.serverUrl, before.serverUrl, requested.serverUrl)
    return when (target) {
        is McpServerConfig.SseTransportServer -> target.copy(commonOptions = options, url = url)
        is McpServerConfig.StreamableHTTPServer -> target.copy(commonOptions = options, url = url)
    }
}

fun applyModelDraft(current: Model, before: Model, requested: Model): Model = current.copy(
    modelId = dirty(current.modelId, before.modelId, requested.modelId),
    displayName = dirty(current.displayName, before.displayName, requested.displayName),
    type = dirty(current.type, before.type, requested.type),
    customHeaders = dirty(current.customHeaders, before.customHeaders, requested.customHeaders),
    customBodies = dirty(current.customBodies, before.customBodies, requested.customBodies),
    inputModalities = applyDraftMembership(current.inputModalities, before.inputModalities, requested.inputModalities),
    outputModalities = applyDraftMembership(current.outputModalities, before.outputModalities, requested.outputModalities),
    abilities = applyDraftMembership(current.abilities, before.abilities, requested.abilities),
    tools = applyDraftMembership(current.tools, before.tools, requested.tools).toSet(),
    providerOverwrite = if (current.providerOverwrite != null && before.providerOverwrite != null && requested.providerOverwrite != null)
        applyProviderDraft(requireNotNull(current.providerOverwrite), requireNotNull(before.providerOverwrite), requireNotNull(requested.providerOverwrite))
        else dirty(current.providerOverwrite, before.providerOverwrite, requested.providerOverwrite),
    supportedReasoningEfforts = dirty(current.supportedReasoningEfforts, before.supportedReasoningEfforts, requested.supportedReasoningEfforts),
)

fun applyModeDraft(current: PromptInjection.ModeInjection, before: PromptInjection.ModeInjection, requested: PromptInjection.ModeInjection): PromptInjection.ModeInjection = current.copy(
    name = dirty(current.name, before.name, requested.name),
    enabled = dirty(current.enabled, before.enabled, requested.enabled),
    priority = dirty(current.priority, before.priority, requested.priority),
    position = dirty(current.position, before.position, requested.position),
    content = dirty(current.content, before.content, requested.content),
    injectDepth = dirty(current.injectDepth, before.injectDepth, requested.injectDepth),
    role = dirty(current.role, before.role, requested.role),
)

fun applyLorebookEntryDraft(current: PromptInjection.RegexInjection, before: PromptInjection.RegexInjection, requested: PromptInjection.RegexInjection): PromptInjection.RegexInjection = current.copy(
    name = dirty(current.name, before.name, requested.name),
    enabled = dirty(current.enabled, before.enabled, requested.enabled),
    priority = dirty(current.priority, before.priority, requested.priority),
    position = dirty(current.position, before.position, requested.position),
    content = dirty(current.content, before.content, requested.content),
    injectDepth = dirty(current.injectDepth, before.injectDepth, requested.injectDepth),
    role = dirty(current.role, before.role, requested.role),
    keywords = dirty(current.keywords, before.keywords, requested.keywords),
    useRegex = dirty(current.useRegex, before.useRegex, requested.useRegex),
    caseSensitive = dirty(current.caseSensitive, before.caseSensitive, requested.caseSensitive),
    scanDepth = dirty(current.scanDepth, before.scanDepth, requested.scanDepth),
    constantActive = dirty(current.constantActive, before.constantActive, requested.constantActive),
)

fun applyThemeDraft(current: CustomTheme, before: CustomTheme, requested: CustomTheme): CustomTheme = current.copy(
    name = dirty(current.name, before.name, requested.name),
    primaryColorArgb = dirty(current.primaryColorArgb, before.primaryColorArgb, requested.primaryColorArgb),
    secondaryColorArgb = dirty(current.secondaryColorArgb, before.secondaryColorArgb, requested.secondaryColorArgb),
    tertiaryColorArgb = dirty(current.tertiaryColorArgb, before.tertiaryColorArgb, requested.tertiaryColorArgb),
)

fun applyLorebookDraft(current: Lorebook, before: Lorebook, requested: Lorebook): Lorebook {
    val oldEntries = before.entries.associateBy { it.id }
    val edits = requested.entries.associateBy { it.id }
    val additions = requested.entries.filter { it.id !in oldEntries }
    var entries = current.entries.filterNot { it.id in oldEntries && it.id !in edits }.map { row ->
        val baseline = oldEntries[row.id]
        val edit = edits[row.id]
        if (baseline != null && edit != null) applyLorebookEntryDraft(row, baseline, edit) else row
    } + additions.filter { added -> current.entries.none { it.id == added.id } }
    // Replay only moves made in this draft, leaving current additions/content intact.
    val order = (before.entries.filter { it.id in edits } + additions).map { it.id }.toMutableList()
    requested.entries.forEachIndexed { index, row ->
        val from = order.indexOf(row.id)
        if (from >= 0 && from != index) {
            entries = moveSettingsItem(entries, row.id, order[index], false) { it.id }
            order.add(index, order.removeAt(from))
        }
    }
    return current.copy(
        name = dirty(current.name, before.name, requested.name),
        description = dirty(current.description, before.description, requested.description),
        enabled = dirty(current.enabled, before.enabled, requested.enabled),
        entries = entries,
    )
}

private fun applyMediaModelsDraft(current: List<MediaGenerationModel>, before: List<MediaGenerationModel>, requested: List<MediaGenerationModel>): List<MediaGenerationModel> {
    val opening = before.associateBy { it.id }
    val changes = requested.associateBy { it.id }
    return current.filterNot { it.id in opening && it.id !in changes }.map { row ->
        val old = opening[row.id]
        val edit = changes[row.id]
        if (old != null && edit != null) row.copy(
            modelId = dirty(row.modelId, old.modelId, edit.modelId),
            displayName = dirty(row.displayName, old.displayName, edit.displayName),
            kind = dirty(row.kind, old.kind, edit.kind),
        ) else row
    } + requested.filter { it.id !in opening && current.none { row -> row.id == it.id } }
}

private fun headerRowIndex(current: List<CustomHeader>, observed: List<CustomHeader>, index: Int) =
    settingsRowIndex(current, observed, index, listOf({ it.name }, { it.value }))
private fun bodyRowIndex(current: List<CustomBody>, observed: List<CustomBody>, index: Int) =
    settingsRowIndex(current, observed, index, listOf({ it.key }, { it.value }))

fun updateHeaderName(current: List<CustomHeader>, observed: List<CustomHeader>, index: Int, name: String): List<CustomHeader> {
    val target = headerRowIndex(current, observed, index) ?: return current
    return current.mapIndexed { i, row -> if (i == target) row.copy(name = name) else row }
}
fun updateHeaderValue(current: List<CustomHeader>, observed: List<CustomHeader>, index: Int, value: String): List<CustomHeader> {
    val target = headerRowIndex(current, observed, index) ?: return current
    return current.mapIndexed { i, row -> if (i == target) row.copy(value = value) else row }
}
fun updateBodyKey(current: List<CustomBody>, observed: List<CustomBody>, index: Int, key: String): List<CustomBody> {
    val target = bodyRowIndex(current, observed, index) ?: return current
    return current.mapIndexed { i, row -> if (i == target) row.copy(key = key) else row }
}
fun updateBodyValue(current: List<CustomBody>, observed: List<CustomBody>, index: Int, value: kotlinx.serialization.json.JsonElement): List<CustomBody> {
    val target = bodyRowIndex(current, observed, index) ?: return current
    return current.mapIndexed { i, row -> if (i == target) row.copy(value = value) else row }
}
fun deleteHeaderRow(current: List<CustomHeader>, observed: List<CustomHeader>, index: Int): List<CustomHeader> {
    val target = headerRowIndex(current, observed, index) ?: return current
    return current.filterIndexed { i, _ -> i != target }
}
fun deleteBodyRow(current: List<CustomBody>, observed: List<CustomBody>, index: Int): List<CustomBody> {
    val target = bodyRowIndex(current, observed, index) ?: return current
    return current.filterIndexed { i, _ -> i != target }
}

private fun providerDraftKey(provider: ProviderSetting): String? = when (provider) {
    is ProviderSetting.OpenAI -> provider.apiKey
    is ProviderSetting.Google -> provider.apiKey
    is ProviderSetting.Claude -> provider.apiKey
    is ProviderSetting.Codex -> null
}
private fun providerDraftUrl(provider: ProviderSetting): String? = when (provider) {
    is ProviderSetting.OpenAI -> provider.baseUrl
    is ProviderSetting.Google -> provider.baseUrl
    is ProviderSetting.Claude -> provider.baseUrl
    is ProviderSetting.Codex -> null
}

private fun <T> applyDraftMembership(current: Collection<T>, before: Collection<T>, requested: Collection<T>): List<T> =
    current.filterNot { it in before && it !in requested } + requested.filter { it !in before && it !in current }
