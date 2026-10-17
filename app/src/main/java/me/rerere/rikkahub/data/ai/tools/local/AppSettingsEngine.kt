package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import androidx.compose.ui.graphics.toArgb
import me.rerere.ai.core.ReasoningLevel
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.theme.PresetThemes
import me.rerere.rikkahub.ui.theme.ColorMode
import me.rerere.rikkahub.ui.theme.findThemeById
import me.rerere.rikkahub.ui.theme.findPresetTheme
import me.rerere.rikkahub.ui.pages.setting.withGlassTheme
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

internal const val APP_SETTINGS_MANUAL_ONLY = "Это можно изменить только вручную в настройках"

internal data class AppSettingChange(
    val key: String,
    val title: String,
    val before: JsonElement,
    val after: JsonElement,
    val beforeLabel: String = readableSettingValue(before),
    val afterLabel: String = readableSettingValue(after),
) {
    val description: String get() = "$title: ${publicAppSettingText(beforeLabel)} → ${publicAppSettingText(afterLabel)}"
    fun toJson(): JsonObject = buildJsonObject {
        put("key", key); put("title", title); put("before", publicAppSettingValue(before)); put("after", publicAppSettingValue(after))
        put("before_label", publicAppSettingText(beforeLabel)); put("after_label", publicAppSettingText(afterLabel)); put("description", description)
    }
}

internal data class AppSettingsChangePreview(
    val key: String,
    val title: String,
    val before: JsonElement,
    val after: JsonElement,
    val beforeLabel: String = readableSettingValue(before),
    val afterLabel: String = readableSettingValue(after),
    val changes: List<AppSettingChange> = listOf(AppSettingChange(key, title, before, after, beforeLabel, afterLabel)),
) {
    val description: String get() = changes.joinToString("\n") { it.description }
    fun toJson(): JsonObject = buildJsonObject {
        put("key", key); put("title", title); put("before", publicAppSettingValue(before)); put("after", publicAppSettingValue(after))
        put("before_label", publicAppSettingText(beforeLabel)); put("after_label", publicAppSettingText(afterLabel)); put("description", description)
        put("changes", JsonArray(changes.map { it.toJson() }))
    }
}

private fun readableSettingValue(value: JsonElement): String = when (value) {
    JsonNull -> "По умолчанию"
    JsonPrimitive(true) -> "Включено"
    JsonPrimitive(false) -> "Выключено"
    else -> publicAppSettingText((value as? JsonPrimitive)?.content?.ifEmpty { "Пусто" } ?: value.toString())
}

private class AppSettingsValidationException(val code: String, message: String) : IllegalArgumentException(message)

private fun rejectSetting(code: String, message: String): Nothing = throw AppSettingsValidationException(code, message)
private fun manualSetting(): Nothing = rejectSetting("manual_only", APP_SETTINGS_MANUAL_ONLY)

private data class AppSettingField(
    val key: String,
    val title: String,
    val type: String,
    val nullable: Boolean = false,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val choices: ((Settings) -> List<JsonElement>)? = null,
    val read: (Settings) -> JsonElement,
    val write: (Settings, JsonElement) -> Settings,
    val label: (Settings, JsonElement) -> String = { _, value -> readableSettingValue(value) },
) {
    val section: String get() = key.substringBefore('.')

    fun validate(settings: Settings, value: JsonElement) {
        if (value == JsonNull && nullable) return
        val primitive = value as? JsonPrimitive ?: manualSetting()
        val correctType = when (type) {
            "boolean" -> !primitive.isString && primitive.booleanOrNull != null
            "integer" -> !primitive.isString && primitive.longOrNull != null
            "number" -> !primitive.isString && primitive.doubleOrNull?.isFinite() == true
            "string" -> primitive.isString
            else -> false
        }
        if (!correctType || primitive == JsonNull) rejectSetting("invalid_type", "Неверный тип настройки «$title»: требуется $type.")
        if (minimum != null || maximum != null) {
            val number = primitive.doubleOrNull ?: rejectSetting("invalid_type", "Укажите число для настройки «$title».")
            if (!number.isFinite() || (minimum != null && number < minimum) || (maximum != null && number > maximum)) {
                rejectSetting("invalid_range", "Значение «$title» должно быть в диапазоне $minimum…$maximum.")
            }
        }
        if (choices != null && value !in choices.invoke(settings)) rejectSetting("invalid_choice", "Значение «$title» недоступно. Прочитайте текущий раздел настроек и выберите существующий вариант или ID.")
        if (primitive.isString && primitive.content.length > 100_000) rejectSetting("value_too_long", "Текст настройки слишком длинный: максимум 100000 символов.")
    }

    fun toJson(settings: Settings): JsonObject = buildJsonObject {
        put("key", key); put("title", title); put("type", type); put("nullable", nullable)
        val value = read(settings)
        put("value", publicAppSettingValue(value)); put("value_label", publicAppSettingText(label(settings, value)))
        minimum?.let { put("minimum", it) }; maximum?.let { put("maximum", it) }
        choices?.let { put("choices", JsonArray(it(settings).map(::publicAppSettingValue))) }
    }
}

private val appSettingsSections = linkedMapOf(
    "display" to "Интерфейс, сообщения и поведение",
    "theme" to "Тема и оформление",
    "language" to "Язык приложения",
    "models" to "Модели, заголовки, подсказки и перевод",
    "search" to "Поиск",
    "speech" to "Синтез и распознавание речи",
    "network" to "Обычные сетевые настройки",
)

private fun patchDisplaySetting(node: JsonObject, remaining: List<String>, value: JsonElement): JsonObject =
    JsonObject(node + (remaining.first() to if (remaining.size == 1) value else
        patchDisplaySetting(node.getValue(remaining.first()).jsonObject, remaining.drop(1), value)))

/** Only these scalar leaves can be changed. Never deserialize input as Settings or provider configs. */
private val appSettingFields: List<AppSettingField> by lazy {
    buildList {
        fun display(
            name: String, title: String, type: String = "boolean", nullable: Boolean = false,
            min: Double? = null, max: Double? = null, options: List<String>? = null, section: String = "display",
            dynamicOptions: ((Settings) -> List<String>)? = null,
        ) {
            val path = name.split('.')
            add(AppSettingField("$section.$name", title, type, nullable, min, max,
                choices = if (dynamicOptions != null) { settings -> dynamicOptions(settings).map(::JsonPrimitive) }
                    else options?.let { entries -> { _: Settings -> entries.map(::JsonPrimitive) } },
                read = { settings ->
                    var node: JsonElement = JsonInstant.encodeToJsonElement(DisplaySetting.serializer(), settings.displaySetting)
                    path.forEach { node = node.jsonObject.getValue(it) }
                    node
                },
                write = { settings, value ->
                    val current = JsonInstant.encodeToJsonElement(DisplaySetting.serializer(), settings.displaySetting).jsonObject
                    val changed = JsonInstant.decodeFromJsonElement(DisplaySetting.serializer(), patchDisplaySetting(current, path, value))
                    settings.copy(displaySetting = when (name) {
                        "messageNavButtonsMode" -> changed.copy(showMessageJumper = value.jsonPrimitive.content != "never")
                        "autoCollapseToolSteps" -> changed.copy(collapseThinkingSteps = null)
                        else -> changed
                    })
                },
            ))
        }
        val booleans = linkedMapOf(
            "showUserAvatar" to "Показывать аватар пользователя",
            "showAssistantBubble" to "Показывать пузырь ответов",
            "showModelIcon" to "Значок модели в чате",
            "showModelName" to "Показывать название модели",
            "showDateTimeInMessage" to "Время сообщений",
            "showTokenUsage" to "Статистика токенов и контекста",
            "showThinkingContent" to "Показывать рассуждения",
            "autoCloseThinking" to "Автоматически сворачивать рассуждения",
            "collapseLongUserMessages" to "Сворачивать длинные сообщения пользователя",
            "showUserMessageActions" to "Действия под сообщениями пользователя",
            "showToolSummary" to "Сводка результатов инструментов",
            "hideToolResultImages" to "Скрывать изображения результатов инструментов",
            "autoCollapseToolSteps" to "Сворачивать этапы инструментов",
            "confirmAssistantRegeneration" to "Подтверждать повторную генерацию ответа",
            "insertSuggestionOnly" to "Вставлять подсказки без отправки",
            "showProducedFiles" to "Показывать файлы под ответами",
            "showMessageJumper" to "Кнопки перехода между сообщениями",
            "messageJumperOnLeft" to "Кнопки перехода слева",
            "enableMessageGenerationHapticEffect" to "Виброотклик при генерации",
            "skipCropImage" to "Пропускать обрезку изображений",
            "enableNotificationOnMessageGeneration" to "Уведомлять о готовности ответа",
            "enableLiveUpdateNotification" to "Уведомление с прогрессом ответа",
            "codeBlockAutoWrap" to "Перенос строк в блоках кода",
            "codeBlockAutoCollapse" to "Сворачивать блоки кода",
            "showLineNumbers" to "Номера строк кода",
            "ttsOnlyReadQuoted" to "Читать вслух только текст в кавычках",
            "ttsOnlyReadOutsideBrackets" to "Читать вслух текст вне скобок",
            "autoPlayTTSAfterGeneration" to "Читать ответ вслух автоматически",
            "pasteLongTextAsFile" to "Вставлять длинный текст как файл",
            "sendOnEnter" to "Отправлять по Enter",
            "enableAutoScroll" to "Автоматическая прокрутка",
            "enableAutoChatTitles" to "Автоматические заголовки чатов",
            "enableLatexRendering" to "Отображать формулы",
            "enableVolumeKeyScroll" to "Прокрутка кнопками громкости",
            "showProviderInChatMessage" to "Провайдер после названия модели",
            "showToolCards" to "Карточки инструментов",
            "showThinkingCards" to "Карточки рассуждений",
            "enableDollarLatex" to "Формулы внутри $...$",
            "enableUserMarkdown" to "Markdown в сообщениях пользователя",
            "enableReasoningMarkdown" to "Markdown в рассуждениях",
            "enableAssistantMarkdown" to "Markdown в ответах",
            "showAppUpdates" to "Показывать обновления",
            "keepScreenOnDuringGeneration" to "Не выключать экран при генерации",
            "showChatListDate" to "Даты в списке чатов",
            "keepSidebarOpenOnAssistantTap" to "Оставлять панель при выборе ассистента",
            "keepSidebarOpenOnTopicTap" to "Оставлять панель при выборе чата",
            "keepAssistantListExpandedOnSidebarClose" to "Сохранять раскрытый список ассистентов",
            "newChatAfterDelete" to "Открывать новый чат после удаления",
            "hapticsEnabled" to "Общий виброотклик",
            "hapticsOnSwitch" to "Виброотклик переключателей",
            "hapticsOnDrawer" to "Виброотклик боковой панели",
            "hapticsOnListItemTap" to "Виброотклик элементов списка",
            "hapticsOnCardTap" to "Виброотклик карточек",
            "useAppIconStyleLoadingIndicator" to "Индикатор загрузки со значком приложения",
            "assistantBubbleFitContent" to "Размер пузыря ответа по содержимому",
            "assistantBubbleSplitParagraphs" to "Разделять ответ по абзацам",
            "chatBackgroundEnabled" to "Фоновое изображение чата",
            "enableBlurEffect" to "Размытие интерфейса",
        )
        booleans.forEach { (name, title) -> display(name, title) }
        linkedMapOf(
            "showUserName" to "Показывать имя пользователя", "showUserTimestamp" to "Время сообщений пользователя",
            "showAssistantTimestamp" to "Время ответов", "useAssistantAvatarInHeader" to "Аватар ассистента в заголовке",
            "collapseThinkingSteps" to "Сворачивать этапы рассуждений", "newChatOnAssistantSwitch" to "Новый чат при смене ассистента",
            "newChatOnLaunch" to "Новый чат при запуске",
        ).forEach { (name, title) -> display(name, title, nullable = true) }
        display("userNickname", "Имя пользователя", "string")
        display("fontSizeRatio", "Масштаб текста чата", "number", min = .5, max = 2.0)
        display("bubbleOpacity", "Непрозрачность пузырей", "number", min = .1, max = 1.0)
        display("userMessageCollapseLines", "Строк до сворачивания сообщения", "integer", min = 3.0, max = 50.0)
        display("userMessageCollapseChars", "Символов до сворачивания сообщения", "integer", nullable = true, min = 50.0, max = 100_000.0)
        display("codeBlockCollapseLines", "Строк до сворачивания кода", "integer", min = 1.0, max = 999.0)
        display("pasteLongTextThreshold", "Порог вставки текста как файла", "integer", min = 100.0, max = 1_000_000.0)
        display("volumeKeyScrollRatio", "Скорость прокрутки кнопками громкости", "number", min = .1, max = 2.0)
        display("autoScrollIdleSeconds", "Пауза автоматической прокрутки, секунд", "integer", min = 0.0, max = 64.0)
        display("chatBackgroundMaskStrength", "Затемнение фона чата", "number", min = 0.0, max = 2.0)
        display("chatInputBackgroundOpacityLight", "Непрозрачность поля ввода: светлая тема", "number", min = 0.0, max = 1.0)
        display("chatInputBackgroundOpacityDark", "Непрозрачность поля ввода: тёмная тема", "number", min = 0.0, max = 1.0)
        display("conversationSortOrder", "Порядок чатов", "string", options = listOf("update_time", "create_time"))
        display("messageNavButtonsMode", "Показ кнопок перехода", "string", options = listOf("always", "scroll", "never"))
        display("backgroundEffectType", "Эффект фона", "string", options = listOf("blur", "glass"))
        display("chatMessageBackgroundStyle", "Стиль сообщений", "string", options = listOf("default", "frosted", "solid"))
        for ((name, title) in listOf("chatFontFamily" to "Шрифт чата", "appFontFamily" to "Шрифт приложения", "codeFontFamily" to "Шрифт кода")) {
            display(name, title, "string", dynamicOptions = { settings ->
                val customFont = when (name) {
                    "chatFontFamily" -> settings.displaySetting.chatCustomFontPath
                    "appFontFamily" -> settings.displaySetting.appCustomFontPath
                    else -> settings.displaySetting.codeCustomFontPath
                }
                listOf("default", "serif", "monospace") + if (customFont.isNotBlank()) listOf("custom") else emptyList()
            })
        }
        for ((name, title) in listOf("usePureBackground" to "Однотонный фон", "useLayeredSurfaces" to "Многослойные поверхности", "useLayeredSheetTiles" to "Многослойные плитки меню", "glassEconomy" to "Экономичный режим стеклянной темы")) display(name, title, section = "theme")
        for ((role, title) in listOf("user" to "Сообщение пользователя", "assistant" to "Ответ ассистента")) {
            val prefix = "${role}ChatBubbleStyleOverrides"
            for ((name, label) in listOf("backgroundArgbLight" to "фон светлой темы", "backgroundArgbDark" to "фон тёмной темы", "borderArgbLight" to "граница светлой темы", "borderArgbDark" to "граница тёмной темы", "textArgbLight" to "текст светлой темы", "textArgbDark" to "текст тёмной темы")) {
                display("$prefix.$name", "$title: $label (ARGB)", "integer", nullable = true, min = 0.0, max = 4_294_967_295.0)
            }
            for ((name, label, max) in listOf(Triple("borderWidth", "толщина границы", 3.0), Triple("borderOpacity", "непрозрачность границы", 1.0), Triple("cornerRadius", "скругление", 28.0), Triple("blurSigma", "размытие", 30.0), Triple("frostedOpacity", "непрозрачность стекла", 1.0), Triple("solidOpacity", "непрозрачность фона", 1.0))) {
                display("$prefix.$name", "$title: $label", "number", nullable = true, min = 0.0, max = max)
            }
        }
        display("appLanguageTag", "Язык приложения", "string", nullable = true, options = listOf("", "ru", "zh-CN", "zh-Hant", "en"), section = "language")

        add(AppSettingField("theme.dynamicColor", "Динамические цвета Android", "boolean", read = { JsonPrimitive(it.dynamicColor) }, write = { s, v -> s.copy(dynamicColor = v.jsonPrimitive.boolean) }))
        add(AppSettingField("theme.themeId", "Цветовая тема", "string", choices = { s -> (PresetThemes.map { it.id } + s.customThemes.map { it.id }).map(::JsonPrimitive) }, read = { JsonPrimitive(it.themeId) }, write = { s, v -> s.copy(themeId = v.jsonPrimitive.content) }))
        fun model(name: String, title: String, read: (Settings) -> Uuid, write: (Settings, Uuid) -> Settings) {
            add(AppSettingField("models.$name", title, "string", choices = { s -> s.providers.flatMap { it.models }.map { JsonPrimitive(it.id.toString()) } },
                read = { JsonPrimitive(read(it).toString()) }, write = { s, v -> write(s, Uuid.parse(v.jsonPrimitive.content)) },
                label = { s, v -> s.providers.flatMap { it.models }.find { it.id.toString() == v.jsonPrimitive.content }?.let { "${it.displayName.ifBlank { it.modelId }} (${it.id})" } ?: v.jsonPrimitive.content }))
        }
        model("chatModelId", "Модель чата по умолчанию", { it.chatModelId }) { s, v -> s.copy(chatModelId = v) }
        model("fastModelId", "Быстрая модель для заголовков и подсказок", { it.fastModelId }) { s, v -> s.copy(fastModelId = v) }
        model("translateModeId", "Модель перевода", { it.translateModeId }) { s, v -> s.copy(translateModeId = v) }
        model("imageGenerationModelId", "Модель генерации изображений", { it.imageGenerationModelId }) { s, v -> s.copy(imageGenerationModelId = v) }
        model("ocrModelId", "Модель распознавания текста", { it.ocrModelId }) { s, v -> s.copy(ocrModelId = v) }
        model("compressModelId", "Модель сжатия контекста", { it.compressModelId }) { s, v -> s.copy(compressModelId = v) }
        fun prompt(name: String, title: String, read: (Settings) -> String, write: (Settings, String) -> Settings) {
            add(AppSettingField("models.$name", title, "string", read = { JsonPrimitive(read(it)) }, write = { s, v -> write(s, v.jsonPrimitive.content) }))
        }
        prompt("titlePrompt", "Инструкция для заголовков", { it.titlePrompt }) { s, v -> s.copy(titlePrompt = v) }
        prompt("suggestionPrompt", "Инструкция для подсказок", { it.suggestionPrompt }) { s, v -> s.copy(suggestionPrompt = v) }
        prompt("translatePrompt", "Инструкция для перевода", { it.translatePrompt }) { s, v -> s.copy(translatePrompt = v) }
        prompt("ocrPrompt", "Инструкция распознавания текста", { it.ocrPrompt }) { s, v -> s.copy(ocrPrompt = v) }
        prompt("compressPrompt", "Инструкция сжатия контекста", { it.compressPrompt }) { s, v -> s.copy(compressPrompt = v) }
        add(AppSettingField("models.enableSuggestion", "Подсказки ответов", "boolean", read = { JsonPrimitive(it.enableSuggestion) }, write = { s, v -> s.copy(enableSuggestion = v.jsonPrimitive.boolean) }))
        add(AppSettingField("models.fastModelReasoningLevel", "Рассуждения быстрой модели", "string", choices = { ReasoningLevel.entries.map { JsonPrimitive(it.effort.let { effort -> if (effort == "none") "off" else effort }) } }, read = { JsonPrimitive(if (it.fastModelReasoningLevel == ReasoningLevel.OFF) "off" else it.fastModelReasoningLevel.effort) }, write = { s, v -> s.copy(fastModelReasoningLevel = ReasoningLevel.entries.first { if (it == ReasoningLevel.OFF) v.jsonPrimitive.content == "off" else it.effort == v.jsonPrimitive.content }) }))
        add(AppSettingField("models.translateThinkingBudget", "Бюджет рассуждений при переводе", "integer", choices = { ReasoningLevel.entries.map { JsonPrimitive(it.budgetTokens) } }, read = { JsonPrimitive(it.translateThinkingBudget) }, write = { s, v -> s.copy(translateThinkingBudget = v.jsonPrimitive.int) }))
        add(AppSettingField("search.selectedServiceId", "Служба поиска", "string", choices = { s -> s.searchServices.map { JsonPrimitive(it.id.toString()) } }, read = { s -> s.searchServices.getOrNull(s.searchServiceSelected)?.let { JsonPrimitive(it.id.toString()) } ?: JsonNull }, write = { s, v -> s.copy(searchServiceSelected = s.searchServices.indexOfFirst { it.id.toString() == v.jsonPrimitive.content }) }, label = { s, v -> s.searchServices.find { it.id.toString() == (v as? JsonPrimitive)?.content }?.let { "${it.displayName} (${it.id})" } ?: readableSettingValue(v) }))
        // The search UI accepts an Int without a maximum. Require a positive result count.
        add(AppSettingField("search.resultSize", "Количество результатов поиска", "integer", minimum = 1.0, maximum = Int.MAX_VALUE.toDouble(), read = { JsonPrimitive(it.searchCommonOptions.resultSize) }, write = { s, v -> s.copy(searchCommonOptions = s.searchCommonOptions.copy(resultSize = v.jsonPrimitive.int)) }))
        add(AppSettingField("speech.selectedTTSProviderId", "Служба синтеза речи", "string", choices = { s -> s.ttsProviders.map { JsonPrimitive(it.id.toString()) } }, read = { JsonPrimitive(it.selectedTTSProviderId.toString()) }, write = { s, v -> s.copy(selectedTTSProviderId = Uuid.parse(v.jsonPrimitive.content)) }, label = { s, v -> s.ttsProviders.find { it.id.toString() == (v as? JsonPrimitive)?.content }?.let { "${it.name} (${it.id})" } ?: readableSettingValue(v) }))
        add(AppSettingField("speech.selectedASRProviderId", "Служба распознавания речи", "string", choices = { s -> s.asrProviders.map { JsonPrimitive(it.id.toString()) } }, read = { it.selectedASRProviderId?.let { id -> JsonPrimitive(id.toString()) } ?: JsonNull }, write = { s, v -> s.copy(selectedASRProviderId = Uuid.parse(v.jsonPrimitive.content)) }, label = { s, v -> s.asrProviders.find { it.id.toString() == (v as? JsonPrimitive)?.content }?.let { "${it.name} (${it.id})" } ?: readableSettingValue(v) }))
        add(AppSettingField("speech.defaultTTSPlaybackSpeed", "Скорость чтения вслух", "number", minimum = .5, maximum = 2.0, read = { JsonPrimitive(it.defaultTTSPlaybackSpeed) }, write = { s, v -> s.copy(defaultTTSPlaybackSpeed = v.jsonPrimitive.float) }))
        add(AppSettingField("network.enableAutoRetry", "Автоматический повтор сетевых запросов", "boolean", read = { JsonPrimitive(it.networkSetting.enableAutoRetry) }, write = { s, v -> s.copy(networkSetting = s.networkSetting.copy(enableAutoRetry = v.jsonPrimitive.boolean)) }))
    }
}

private fun appSettingFields(themePreferences: AppThemePreferences?): List<AppSettingField> = buildList {
    addAll(appSettingFields)
    add(AppSettingField("theme.glassTheme", "Стеклянная тема", "boolean",
        read = { JsonPrimitive(it.displaySetting.glassTheme) },
        write = { settings, value ->
            val accents = themePreferences?.glassAccents(settings) ?: run {
                val preset = findThemeById(settings.themeId, settings.customThemes) ?: findPresetTheme(settings.themeId)
                (preset.standardLight.primary.toArgb().toLong() and 0xFFFFFFFFL) to
                    (preset.standardDark.primary.toArgb().toLong() and 0xFFFFFFFFL)
            }
            settings.copy(displaySetting = settings.displaySetting.withGlassTheme(value.jsonPrimitive.boolean, accents.first, accents.second))
        }))
    if (themePreferences != null) {
        add(AppSettingField("theme.colorMode", "Режим светлой и тёмной темы", "string",
            choices = { ColorMode.entries.map { JsonPrimitive(it.name) } },
            read = { JsonPrimitive(themePreferences.colorMode().name) },
            write = { _, _ -> error("Оформление хранится в SharedPreferences.") },
            label = { _, value -> when (value.jsonPrimitive.content) { "LIGHT" -> "Светлая"; "DARK" -> "Тёмная"; else -> "Системная" } }))
        add(AppSettingField("theme.amoledDark", "Чёрный фон AMOLED в тёмной теме", "boolean",
            read = { JsonPrimitive(themePreferences.amoledDark()) },
            write = { _, _ -> error("Оформление хранится в SharedPreferences.") }))
    }
}

private fun appSettingField(key: String, themePreferences: AppThemePreferences?): AppSettingField =
    appSettingFields(themePreferences).find { it.key == key } ?: manualSetting()

internal fun appSettingsSecretMutationBlocked(settings: Settings, key: String, value: JsonElement, themePreferences: AppThemePreferences? = null): Boolean {
    if (appSettingsContainsCredential(value)) return true
    val before = runCatching { appSettingField(key, themePreferences).read(settings) }.getOrNull() ?: return false
    return appSettingsContainsCredential(before) && value != APP_SETTINGS_SECRET_MASK
}

internal fun previewAppSettingsChange(settings: Settings, key: String, value: JsonElement, themePreferences: AppThemePreferences? = null): AppSettingsChangePreview {
    if (settings.init) rejectSetting("settings_not_ready", "Настройки ещё загружаются. Повторите вызов после загрузки приложения.")
    val field = appSettingField(key, themePreferences)
    val rawBefore = field.read(settings)
    if (appSettingsSecretMutationBlocked(settings, key, value, themePreferences)) manualSetting()
    val protectedBefore = appSettingsContainsCredential(rawBefore)
    if (!protectedBefore) field.validate(settings, value)
    // A masked roundtrip is a no-op. Raw values stay in typed settings and never enter approval markers.
    val effectiveValue = if (protectedBefore) rawBefore else value
    val before = publicAppSettingValue(rawBefore)
    val after = publicAppSettingValue(effectiveValue)
    val primary = AppSettingChange(key, field.title, before, after,
        publicAppSettingText(field.label(settings, rawBefore)), publicAppSettingText(field.label(settings, effectiveValue)))
    val changes = mutableListOf(primary)
    if (key.startsWith("display.") || key == "theme.glassTheme" || key == "language.appLanguageTag") {
        // Serialize only the ordinary display settings, never the complete Settings (credentials).
        val changed = if (protectedBefore) settings else field.write(settings, value)
        val oldDisplay = JsonInstant.encodeToJsonElement(DisplaySetting.serializer(), settings.displaySetting)
        val newDisplay = JsonInstant.encodeToJsonElement(DisplaySetting.serializer(), changed.displaySetting)
        val fields = appSettingFields(themePreferences).associateBy { it.key }
        fun collect(path: String, old: JsonElement, new: JsonElement) {
            if (old == new) return
            if (old is JsonObject || new is JsonObject) {
                val oldObject = old as? JsonObject
                val newObject = new as? JsonObject
                (oldObject?.keys.orEmpty() + newObject?.keys.orEmpty()).forEach { child ->
                    collect("$path.$child", oldObject?.get(child) ?: JsonNull, newObject?.get(child) ?: JsonNull)
                }
            } else {
                val equivalentKey = when (path) {
                    "display.glassTheme" -> "theme.glassTheme"
                    "display.appLanguageTag" -> "language.appLanguageTag"
                    else -> path
                }
                if (equivalentKey != key) {
                    val title = fields[equivalentKey]?.title ?: when {
                        path.startsWith("display.glassPreviousMessageStyle") -> "Сохранённый стиль для восстановления (${path.substringAfter("display.glassPreviousMessageStyle")})"
                        path.startsWith("display.userChatBubbleStyleOverrides") -> "Оформление сообщений пользователя (${path.substringAfterLast('.')})"
                        path.startsWith("display.assistantChatBubbleStyleOverrides") -> "Оформление ответов (${path.substringAfterLast('.')})"
                        else -> "Параметр оформления (${path.removePrefix("display.")})"
                    }
                    changes += AppSettingChange(equivalentKey, title, publicAppSettingValue(old), publicAppSettingValue(new))
                }
            }
        }
        collect("display", oldDisplay, newDisplay)
    }
    return AppSettingsChangePreview(key, field.title, before, after, primary.beforeLabel, primary.afterLabel, changes)
}

/** Functional update always receives the latest persisted snapshot, including after approval. */
internal class AppSettingsEngine(
    private val snapshot: () -> Settings,
    private val update: suspend ((Settings) -> Settings) -> Settings,
    private val themePreferences: AppThemePreferences?,
) {
    constructor(snapshot: () -> Settings, update: suspend ((Settings) -> Settings) -> Settings) : this(snapshot, update, null)

    fun listSections(): JsonObject = buildJsonObject {
        put("status", "ok")
        put("sections", buildJsonArray { appSettingsSections.forEach { (id, title) -> add(buildJsonObject {
            put("id", id); put("title", title); put("keys", JsonArray(appSettingFields(themePreferences).filter { it.section == id }.map { JsonPrimitive(it.key) }))
        }) } })
        put("message", "Для каждого изменения требуется одобрение. Секреты скрыты; безопасность, удаления и доступ ко всей памяти меняются только вручную.")
    }

    fun get(section: String? = null, key: String? = null): JsonObject = safely {
        if ((section == null) == (key == null)) rejectSetting("invalid_arguments", "Укажите ровно одно поле: section или key. Список разделов: list_sections.")
        val settings = snapshot()
        if (key != null) {
            val field = appSettingFields(themePreferences).find { it.key == key }
            if (field == null) return@safely buildJsonObject { put("status", "error"); put("code", "manual_only"); put("key", publicAppSettingText(key)); put("value", "••••"); put("message", APP_SETTINGS_MANUAL_ONLY) }
            return@safely buildJsonObject { put("status", "ok"); put("setting", field.toJson(settings)) }
        }
        if (section !in appSettingsSections) return@safely buildJsonObject { put("status", "error"); put("code", "manual_only"); put("section", section?.let(::publicAppSettingText)); put("value", "••••"); put("message", APP_SETTINGS_MANUAL_ONLY) }
        buildJsonObject {
            put("status", "ok"); put("section", section); put("title", appSettingsSections.getValue(section!!))
            put("settings", JsonArray(appSettingFields(themePreferences).filter { it.section == section }.map { it.toJson(settings) }))
            when (section) {
                "models" -> put("available_models", buildJsonArray { settings.providers.forEach { provider -> provider.models.forEach { model -> add(buildJsonObject {
                    put("id", model.id.toString()); put("name", publicAppSettingText(model.displayName.ifBlank { model.modelId })); put("model_id", publicAppSettingText(model.modelId))
                    put("provider_id", provider.id.toString()); put("provider_name", publicAppSettingText(provider.name))
                }) } } })
                "search" -> put("available_services", buildJsonArray { settings.searchServices.forEach { add(buildJsonObject { put("id", it.id.toString()); put("name", publicAppSettingText(it.displayName)) }) } })
                "speech" -> {
                    put("available_tts", buildJsonArray { settings.ttsProviders.forEach { add(buildJsonObject { put("id", it.id.toString()); put("name", publicAppSettingText(it.name)) }) } })
                    put("available_asr", buildJsonArray { settings.asrProviders.forEach { add(buildJsonObject { put("id", it.id.toString()); put("name", publicAppSettingText(it.name)) }) } })
                }
                "theme" -> put("available_custom_themes", buildJsonArray { settings.customThemes.forEach { add(buildJsonObject { put("id", publicAppSettingText(it.id)); put("name", publicAppSettingText(it.name)) }) } })
            }
        }
    }

    fun previewSet(key: String, value: JsonElement): AppSettingsChangePreview = previewAppSettingsChange(snapshot(), key, value, themePreferences)

    suspend fun set(key: String, value: JsonElement, expectedBefore: JsonElement?, expectedPreview: JsonObject? = null): JsonObject = safelySuspend {
        if (expectedPreview?.get(APP_SETTINGS_SECRET_EDIT_BLOCKED) == JsonPrimitive(true)) manualSetting()
        val preview = previewSet(key, value)
        if (expectedBefore == null) rejectSetting("approval_required", "Изменение настроек требует нового одобрения пользователя.")
        if (preview.before != expectedBefore) stalePreview()
        if (expectedPreview != null && expectedPreview != preview.toJson()) stalePreview()
        applyApproved(preview)
    }

    suspend fun applyApproved(preview: AppSettingsChangePreview): JsonObject = safelySuspend {
        val current = snapshot()
        if (preview.after == APP_SETTINGS_SECRET_MASK && appSettingsContainsCredential(appSettingField(preview.key, themePreferences).read(current))) {
            val fresh = previewAppSettingsChange(current, preview.key, preview.after, themePreferences)
            if (fresh.changes != preview.changes) stalePreview()
            // Preserve even a freshly rotated secret; a masked no-op performs no Settings write.
            return@safelySuspend savedResult(current, fresh, unchanged = true)
        }
        val saved = if (preview.key in setOf("theme.colorMode", "theme.amoledDark")) {
            val preferences = themePreferences ?: manualSetting()
            val current = snapshot()
            val fresh = previewAppSettingsChange(current, preview.key, preview.after, preferences)
            if (fresh.changes != preview.changes || !preferences.compareAndSet(preview.key, preview.before, fresh.after)) stalePreview()
            current
        } else update { current ->
            val fresh = previewAppSettingsChange(current, preview.key, preview.after, themePreferences)
            if (fresh.changes != preview.changes) stalePreview()
            val field = appSettingField(preview.key, themePreferences)
            // A literal mask can become a protected value between snapshot and this transaction.
            // The public diff may still match; preserve the trusted current value instead of writing the mask.
            if (preview.after == APP_SETTINGS_SECRET_MASK && appSettingsContainsCredential(field.read(current))) current
            else field.write(current, fresh.after)
        }
        savedResult(saved, preview)
    }

    private fun savedResult(saved: Settings, preview: AppSettingsChangePreview, unchanged: Boolean = false): JsonObject = buildJsonObject {
            put("status", "ok"); put("change", preview.toJson())
            put("setting", appSettingField(preview.key, themePreferences).toJson(saved))
            put("message", if (unchanged) "Скрытое значение сохранено без изменений." else "Настройка сохранена.")
    }
}

private fun stalePreview(): Nothing = rejectSetting("stale_preview", "Настройка изменилась после подготовки одобрения. Повторите вызов, чтобы подтвердить актуальные значения «было → стало».")

private fun settingsError(exception: Exception): JsonObject = buildJsonObject {
    put("status", "error")
    put("code", (exception as? AppSettingsValidationException)?.code ?: "settings_failed")
    put("message", if (exception is AppSettingsValidationException) exception.message else "Не удалось сохранить настройки. Повторите вызов или измените настройку вручную.")
}

private inline fun safely(block: () -> JsonObject): JsonObject = try { block() } catch (cancelled: CancellationException) { throw cancelled } catch (exception: Exception) { settingsError(exception) }
private suspend inline fun safelySuspend(crossinline block: suspend () -> JsonObject): JsonObject = try { block() } catch (cancelled: CancellationException) { throw cancelled } catch (exception: Exception) { settingsError(exception) }
