package me.rerere.rikkahub.ui.pages.setting

import androidx.annotation.StringRes
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.DisplaySetting

internal data class InterfaceToggle(
    val key: String,
    @StringRes val titleRes: Int,
    val searchTitle: String,
    val read: (DisplaySetting) -> Boolean,
    val write: (DisplaySetting, Boolean) -> DisplaySetting,
)

internal object InterfaceControls {
    val messages = listOf(
        InterfaceToggle("showUserAvatar", R.string.ui_display_settings_page_show_user_avatar_title, "Показывать аватар пользователя", { it.showUserAvatar }, { setting, value -> setting.copy(showUserAvatar = value) }),
        InterfaceToggle("showUserName", R.string.ui_display_settings_page_show_user_name_title, "Показывать имя пользователя", { it.showUserName ?: it.showUserAvatar }, { setting, value -> setting.copy(showUserName = value) }),
        InterfaceToggle("showUserTimestamp", R.string.ui_display_settings_page_show_user_timestamp_title, "Показывать время сообщений пользователя", { it.showUserTimestamp ?: it.showDateTimeInMessage }, { setting, value -> setting.copy(showUserTimestamp = value) }),
        InterfaceToggle("showUserMessageActions", R.string.ui_display_settings_page_show_user_message_actions_title, "Действия под сообщениями пользователя", { it.showUserMessageActions }, { setting, value -> setting.copy(showUserMessageActions = value) }),
        InterfaceToggle("showModelIcon", R.string.ui_display_settings_page_chat_model_icon_title, "Значок модели в чате", { it.showModelIcon }, { setting, value -> setting.copy(showModelIcon = value) }),
        InterfaceToggle("useAssistantAvatarInHeader", R.string.ui_display_settings_page_use_new_assistant_avatar_ux_title, "Аватар ассистента в заголовке чата", { it.useAssistantAvatarInHeader ?: true }, { setting, value -> setting.copy(useAssistantAvatarInHeader = value) }),
        InterfaceToggle("showModelName", R.string.ui_display_settings_page_show_model_name_title, "Показывать название модели", { it.showModelName }, { setting, value -> setting.copy(showModelName = value) }),
        InterfaceToggle("showAssistantTimestamp", R.string.ui_display_settings_page_show_model_timestamp_title, "Показывать время ответов модели", { it.showAssistantTimestamp ?: it.showDateTimeInMessage }, { setting, value -> setting.copy(showAssistantTimestamp = value) }),
        InterfaceToggle("showProviderInChatMessage", R.string.ui_display_settings_page_show_provider_in_chat_message_title, "Провайдер после названия модели", { it.showProviderInChatMessage }, { setting, value -> setting.copy(showProviderInChatMessage = value) }),
        InterfaceToggle("showTokenUsage", R.string.ui_display_settings_page_show_token_stats_title, "Статистика токенов и контекста", { it.showTokenUsage }, { setting, value -> setting.copy(showTokenUsage = value) }),
        InterfaceToggle("showThinkingCards", R.string.ui_display_settings_page_show_thinking_cards_title, "Показывать карточки рассуждений", { it.showThinkingCards }, { setting, value -> setting.copy(showThinkingCards = value) }),
        InterfaceToggle("showToolCards", R.string.ui_display_settings_page_show_tool_cards_title, "Показывать карточки инструментов", { it.showToolCards }, { setting, value -> setting.copy(showToolCards = value) }),
        InterfaceToggle("showProducedFiles", R.string.ui_display_settings_page_show_produced_files_title, "Показывать файлы под ответами", { it.showProducedFiles }, { setting, value -> setting.copy(showProducedFiles = value) }),
    )
    val rendering = listOf(
        InterfaceToggle("enableDollarLatex", R.string.ui_display_settings_page_enable_dollar_latex_title, "Формулы внутри $...$", { it.enableDollarLatex }, { setting, value -> setting.copy(enableDollarLatex = value) }),
        InterfaceToggle("enableLatexRendering", R.string.ui_display_settings_page_enable_math_title, "Отображение формул", { it.enableLatexRendering }, { setting, value -> setting.copy(enableLatexRendering = value) }),
        InterfaceToggle("enableUserMarkdown", R.string.ui_display_settings_page_enable_user_markdown_title, "Markdown в сообщениях пользователя", { it.enableUserMarkdown }, { setting, value -> setting.copy(enableUserMarkdown = value) }),
        InterfaceToggle("enableReasoningMarkdown", R.string.ui_display_settings_page_enable_reasoning_markdown_title, "Markdown в рассуждениях", { it.enableReasoningMarkdown }, { setting, value -> setting.copy(enableReasoningMarkdown = value) }),
        InterfaceToggle("enableAssistantMarkdown", R.string.ui_display_settings_page_enable_assistant_markdown_title, "Markdown в сообщениях ассистента", { it.enableAssistantMarkdown }, { setting, value -> setting.copy(enableAssistantMarkdown = value) }),
        InterfaceToggle("codeBlockAutoCollapse", R.string.ui_display_settings_page_auto_collapse_code_block_title, "Автоматически сворачивать блоки кода", { it.codeBlockAutoCollapse }, { setting, value -> setting.copy(codeBlockAutoCollapse = value) }),
        InterfaceToggle("codeBlockAutoWrap", R.string.ui_display_settings_page_mobile_code_block_wrap_title, "Перенос строк в блоках кода на телефоне", { it.codeBlockAutoWrap }, { setting, value -> setting.copy(codeBlockAutoWrap = value) }),
    )
    val behavior = listOf(
        InterfaceToggle("autoCloseThinking", R.string.ui_display_settings_page_auto_collapse_thinking_title, "Автоматически сворачивать рассуждения", { it.autoCloseThinking }, { setting, value -> setting.copy(autoCloseThinking = value) }),
        InterfaceToggle("collapseThinkingSteps", R.string.ui_display_settings_page_collapse_thinking_steps_title, "Сворачивать этапы рассуждений", { it.collapseThinkingSteps ?: it.autoCollapseToolSteps }, { setting, value -> setting.copy(collapseThinkingSteps = value) }),
        InterfaceToggle("showToolSummary", R.string.ui_display_settings_page_show_tool_result_summary_title, "Показывать сводку результатов инструментов", { it.showToolSummary }, { setting, value -> setting.copy(showToolSummary = value) }),
        InterfaceToggle("hideToolResultImages", R.string.ui_display_settings_page_hide_tool_result_images_title, "Скрывать изображения в результатах инструментов", { it.hideToolResultImages }, { setting, value -> setting.copy(hideToolResultImages = value) }),
        InterfaceToggle("insertSuggestionOnly", R.string.ui_display_settings_page_insert_suggestion_only_title, "Вставлять подсказки без отправки", { it.insertSuggestionOnly }, { setting, value -> setting.copy(insertSuggestionOnly = value) }),
        InterfaceToggle("collapseLongUserMessages", R.string.ui_display_settings_page_collapse_long_user_messages_title, "Сворачивать длинные сообщения", { it.collapseLongUserMessages }, { setting, value -> setting.copy(collapseLongUserMessages = value) }),
        InterfaceToggle("regenerateDeleteTrailingMessages", R.string.ui_display_settings_page_regenerate_delete_trailing_messages_title, "Удалять сообщения ниже при повторной генерации", { it.regenerateDeleteTrailingMessages }, { setting, value -> setting.copy(regenerateDeleteTrailingMessages = value) }),
        InterfaceToggle("confirmAssistantRegeneration", R.string.ui_display_settings_page_show_regenerate_confirm_dialog_title, "Подтверждать повторную генерацию", { it.confirmAssistantRegeneration }, { setting, value -> setting.copy(confirmAssistantRegeneration = value) }),
        InterfaceToggle("forkKeepMessageVersions", R.string.ui_display_settings_page_fork_keep_message_versions_title, "Сохранять версии сообщений при создании ветки", { it.forkKeepMessageVersions }, { setting, value -> setting.copy(forkKeepMessageVersions = value) }),
        InterfaceToggle("keepThinkingAndToolCardsWhenEditingAssistant", R.string.ui_display_settings_page_edit_assistant_keep_thinking_tool_cards_title, "Сохранять рассуждения и инструменты при редактировании ответа", { it.keepThinkingAndToolCardsWhenEditingAssistant }, { setting, value -> setting.copy(keepThinkingAndToolCardsWhenEditingAssistant = value) }),
        InterfaceToggle("showAppUpdates", R.string.ui_display_settings_page_show_updates_title, "Показывать обновления", { it.showAppUpdates }, { setting, value -> setting.copy(showAppUpdates = value) }),
        InterfaceToggle("keepScreenOnDuringGeneration", R.string.ui_display_settings_page_keep_screen_on_during_generation_title, "Не выключать экран во время генерации", { it.keepScreenOnDuringGeneration }, { setting, value -> setting.copy(keepScreenOnDuringGeneration = value) }),
        InterfaceToggle("showChatListDate", R.string.ui_display_settings_page_show_chat_list_date_title, "Показывать даты в списке чатов", { it.showChatListDate }, { setting, value -> setting.copy(showChatListDate = value) }),
        InterfaceToggle("keepSidebarOpenOnAssistantTap", R.string.ui_display_settings_page_keep_sidebar_open_on_assistant_tap_title, "Не закрывать боковую панель при выборе ассистента", { it.keepSidebarOpenOnAssistantTap }, { setting, value -> setting.copy(keepSidebarOpenOnAssistantTap = value) }),
        InterfaceToggle("keepSidebarOpenOnTopicTap", R.string.ui_display_settings_page_keep_sidebar_open_on_topic_tap_title, "Не закрывать боковую панель при выборе темы", { it.keepSidebarOpenOnTopicTap }, { setting, value -> setting.copy(keepSidebarOpenOnTopicTap = value) }),
        InterfaceToggle("keepAssistantListExpandedOnSidebarClose", R.string.ui_display_settings_page_keep_assistant_list_expanded_on_sidebar_close_title, "Не сворачивать список ассистентов при закрытии панели", { it.keepAssistantListExpandedOnSidebarClose }, { setting, value -> setting.copy(keepAssistantListExpandedOnSidebarClose = value) }),
        InterfaceToggle("newChatAfterDelete", R.string.ui_display_settings_page_new_chat_after_delete_title, "Новый чат после удаления темы", { it.newChatAfterDelete }, { setting, value -> setting.copy(newChatAfterDelete = value) }),
        InterfaceToggle("sendOnEnter", R.string.ui_display_settings_page_enter_to_send_title, "Отправлять по Enter", { it.sendOnEnter }, { setting, value -> setting.copy(sendOnEnter = value) }),
        InterfaceToggle("pasteLongTextAsFile", R.string.ui_display_settings_page_long_paste_as_file_title, "Вставлять длинный текст как файл", { it.pasteLongTextAsFile }, { setting, value -> setting.copy(pasteLongTextAsFile = value) }),
    )
    val haptics = listOf(
        InterfaceToggle("hapticsEnabled", R.string.ui_display_settings_page_haptics_global_title, "Общий виброотклик", { it.hapticsEnabled }, { setting, value -> setting.copy(hapticsEnabled = value) }),
        InterfaceToggle("hapticsOnSwitch", R.string.ui_display_settings_page_haptics_ios_switch_title, "Виброотклик переключателей", { it.hapticsOnSwitch }, { setting, value -> setting.copy(hapticsOnSwitch = value) }),
        InterfaceToggle("hapticsOnDrawer", R.string.ui_display_settings_page_haptics_on_sidebar_title, "Виброотклик боковой панели", { it.hapticsOnDrawer }, { setting, value -> setting.copy(hapticsOnDrawer = value) }),
        InterfaceToggle("hapticsOnListItemTap", R.string.ui_display_settings_page_haptics_on_list_item_tap_title, "Виброотклик элементов списка", { it.hapticsOnListItemTap }, { setting, value -> setting.copy(hapticsOnListItemTap = value) }),
        InterfaceToggle("hapticsOnCardTap", R.string.ui_display_settings_page_haptics_on_card_tap_title, "Виброотклик карточек", { it.hapticsOnCardTap }, { setting, value -> setting.copy(hapticsOnCardTap = value) }),
        InterfaceToggle("enableMessageGenerationHapticEffect", R.string.ui_display_settings_page_haptics_on_generate_title, "Виброотклик при генерации", { it.enableMessageGenerationHapticEffect }, { setting, value -> setting.copy(enableMessageGenerationHapticEffect = value) }),
    )
}
