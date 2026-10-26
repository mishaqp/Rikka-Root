package me.rerere.rikkahub.ui.pages.setting

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen

/** Search labels are stable public metadata; displayed labels are Android string resources. */
internal object InterfaceSearchEntries {
    val entries: List<SettingsSearchEntry> = buildList {
        InterfaceControls.messages.forEach { control ->
            add(entry("ui_${control.key}", control.searchTitle, control.titleRes, Screen.SettingInterfaceMessages, "messages отображение интерфейс "))
        }
        InterfaceControls.rendering.forEach { control ->
            add(entry("ui_${control.key}", control.searchTitle, control.titleRes, Screen.SettingInterfaceRendering, "rendering отображение интерфейс "))
        }
        InterfaceControls.behavior.forEach { control ->
            add(entry("ui_${control.key}", control.searchTitle, control.titleRes, Screen.SettingInterfaceBehavior, "behavior отображение интерфейс "))
        }
        InterfaceControls.haptics.forEach { control ->
            add(entry("ui_${control.key}", control.searchTitle, control.titleRes, Screen.SettingInterfaceHaptics, "haptics отображение интерфейс вибрация vibration"))
        }
        add(entry("ui_theme", "Настройки темы", R.string.ui_display_settings_page_theme_settings_title, Screen.SettingTheme, "theme палитра"))
        add(entry("ui_themeAdvanced", "Дополнительные настройки темы", R.string.ui_theme_advanced, Screen.SettingInterfaceThemeAdvanced, "layer layered поверхности панели"))
        add(entry("ui_pure", "Чистый фон", R.string.ui_theme_pure, Screen.SettingTheme, "фон white black"))
        add(entry("ui_layered", "Многослойные поверхности", R.string.ui_theme_advanced_settings_page_use_layered_surfaces_title, Screen.SettingInterfaceThemeAdvanced, "layered поверхности карточки фон"))
        add(entry("ui_sheetTiles", "Многослойные элементы панелей", R.string.ui_theme_advanced_settings_page_use_layered_sheet_tiles_title, Screen.SettingInterfaceThemeAdvanced, "панели элементы карточки tiles layered модели ассистенты вложения рабочая папка"))
        add(entry("ui_glass", "Стекло", R.string.ui_glass_theme_title, Screen.SettingInterfaceGlass, "glass frosted стеклянная тема"))
        add(entry("ui_glassEconomy", "Экономный режим", R.string.ui_glass_economy_title, Screen.SettingInterfaceGlass, "glass экономия батарея blur"))
        add(entry("ui_language", "Язык приложения", R.string.ui_display_settings_page_language_title, Screen.SettingInterfaceLanguage, "locale русский english chinese system"))
        add(entry("ui_messageStyle", "Стиль сообщений", R.string.ui_message_style_settings_page_title, Screen.SettingInterfaceMessageStyle, "bubble пузырьки стекло frosted solid"))
        add(entry("ui_fitContent", "Подгонять пузырь ассистента под содержимое", R.string.ui_message_style_settings_page_assistant_fit_content, Screen.SettingInterfaceMessageStyle, "bubble ширина"))
        add(entry("ui_splitParagraphs", "Разделять абзацы на пузыри", R.string.ui_message_style_settings_page_assistant_split_paragraphs, Screen.SettingInterfaceMessageStyle, "bubble paragraph абзац"))
        add(entry("ui_blurSigma", "Размытие", R.string.ui_message_style_settings_page_blur, Screen.SettingInterfaceMessageStyle, "blur sigma frosted размытие"))
        add(entry("ui_bubbleBackground", "Фон", R.string.ui_message_style_settings_page_background_color, Screen.SettingInterfaceMessageStyle, "bubble цвет фон"))
        add(entry("ui_bubbleOpacity", "Непрозрачность фона", R.string.ui_message_style_settings_page_background_opacity, Screen.SettingInterfaceMessageStyle, "bubble прозрачность opacity"))
        add(entry("ui_bubbleBorder", "Рамка", R.string.ui_message_style_settings_page_border_color, Screen.SettingInterfaceMessageStyle, "border цвет рамка"))
        add(entry("ui_bubbleBorderOpacity", "Непрозрачность рамки", R.string.ui_message_style_settings_page_border_opacity, Screen.SettingInterfaceMessageStyle, "border transparency рамка"))
        add(entry("ui_bubbleBorderWidth", "Толщина рамки", R.string.ui_message_style_settings_page_border_width, Screen.SettingInterfaceMessageStyle, "border width рамка"))
        add(entry("ui_bubbleText", "Текст", R.string.ui_message_style_settings_page_text_color, Screen.SettingInterfaceMessageStyle, "bubble text цвет"))
        add(entry("ui_bubbleRadius", "Радиус скругления", R.string.ui_message_style_settings_page_corner_radius, Screen.SettingInterfaceMessageStyle, "radius угол"))
        add(entry("ui_legacyBubble", "Пузырь ответа ассистента", R.string.ui_message_legacy_bubble, Screen.SettingInterfaceMessageStyle, "bubble legacy"))
        add(entry("ui_legacyOpacity", "Общая непрозрачность пузырей", R.string.ui_message_legacy_opacity, Screen.SettingInterfaceMessageStyle, "bubble legacy opacity"))
        add(entry("ui_background", "Фон чата", R.string.ui_background, Screen.SettingInterfaceBackground, "wallpaper обои изображение"))
        add(entry("ui_backgroundEnabled", "Общий фон чата", R.string.ui_background_enabled, Screen.SettingInterfaceBackground, "wallpaper общий обои"))
        add(entry("ui_backgroundMask", "Непрозрачность затемнения фона чата", R.string.ui_display_settings_page_chat_background_mask_title, Screen.SettingInterfaceBackground, "mask dim затемнение"))
        add(entry("ui_backgroundOpacity", "Непрозрачность изображения", R.string.ui_background_opacity, Screen.SettingInterfaceBackground, "обои wallpaper alpha"))
        add(entry("ui_backgroundGradient", "Градиент поверх фона", R.string.ui_background_gradient, Screen.SettingInterfaceBackground, "gradient градиент"))
        add(entry("ui_legacyBlur", "Размытие поля ввода", R.string.ui_blur_effect_title, Screen.SettingInterfaceBackground, "blur стекло"))
        add(entry("ui_appFont", "Шрифт приложения", R.string.ui_display_settings_page_app_font_title, Screen.SettingInterfaceFonts("app"), "font шрифт ttf otf custom google"))
        add(entry("ui_codeFont", "Шрифт кода", R.string.ui_display_settings_page_code_font_title, Screen.SettingInterfaceFonts("code"), "font code monospace код ttf otf google"))
        add(entry("ui_chatFont", "Шрифт сообщений", R.string.ui_fonts_chat, Screen.SettingInterfaceFonts("chat"), "font chat serif ttf otf custom google"))
        add(entry("ui_googleFonts", "Google Fonts", R.string.ui_google_fonts_title, Screen.SettingInterfaceGoogleFonts("app"), "font каталог загрузить шрифт google"))
        add(entry("ui_fontSize", "Размер шрифта чата", R.string.ui_display_settings_page_chat_font_size_title, Screen.SettingInterfaceFontSize, "font size размер масштаб"))
        add(entry("ui_autoScroll", "Задержка возврата автопрокрутки", R.string.ui_display_settings_page_auto_scroll_idle_title, Screen.SettingInterfaceScroll, "scroll прокрутка delay автопрокрутка"))
        add(entry("ui_inputOpacity", "Непрозрачность фона поля ввода", R.string.ui_display_settings_page_chat_input_background_opacity_title, Screen.SettingInterfaceInputOpacity, "opacity прозрачность ввод"))
        add(entry("ui_codeLines", "Порог автосворачивания", R.string.ui_display_settings_page_auto_collapse_code_block_lines_title, Screen.SettingInterfaceRendering, "code collapse порог строки"))
        add(entry("ui_lineNumbers", "Номера строк в блоках кода", R.string.ui_line_numbers, Screen.SettingInterfaceRendering, "code номера строк"))
        add(entry("ui_userCollapseChars", "Порог сворачивания", R.string.ui_display_settings_page_collapse_long_user_messages_chars_title, Screen.SettingInterfaceBehavior, "collapse chars порог символы"))
        add(entry("ui_userCollapseLines", "Порог сворачивания прежних сообщений (строк)", R.string.ui_legacy_lines, Screen.SettingInterfaceBehavior, "collapse lines порог строки"))
        add(entry("ui_pasteThreshold", "Порог преобразования", R.string.ui_display_settings_page_long_paste_as_file_threshold_title, Screen.SettingInterfaceBehavior, "paste вставка файл символы"))
        add(entry("ui_navButtons", "Кнопки навигации по сообщениям", R.string.ui_display_settings_page_message_nav_buttons_title, Screen.SettingInterfaceBehavior, "navigation кнопки прокрутка"))
        add(entry("ui_switchChat", "Новый чат при смене ассистента", R.string.ui_display_settings_page_new_chat_on_assistant_switch_title, Screen.SettingInterfaceBehavior, "assistant переключение новый"))
        add(entry("ui_launchChat", "Новый чат при запуске", R.string.ui_display_settings_page_new_chat_on_launch_title, Screen.SettingInterfaceBehavior, "launch запуск новый"))
        add(entry("ui_autoTitles", "Автоматические названия чатов", R.string.ui_auto_titles, Screen.SettingInterfaceBehavior, "title названия"))
        add(entry("ui_navLeft", "Кнопки навигации слева", R.string.ui_nav_left, Screen.SettingInterfaceBehavior, "scroll navigation left"))
        add(entry("ui_volumeScroll", "Прокрутка кнопками громкости", R.string.ui_volume_scroll, Screen.SettingInterfaceBehavior, "volume громкость scroll"))
        add(entry("ui_volumeSpeed", "Шаг прокрутки кнопками громкости", R.string.ui_volume_scroll_speed, Screen.SettingInterfaceBehavior, "volume ratio скорость"))
        add(entry("ui_loadingIcon", "Индикатор генерации со значком приложения", R.string.ui_loading_icon, Screen.SettingInterfaceBehavior, "loading индикатор"))
        add(entry("ui_toolCollapse", "Автоматически сворачивать завершённые шаги инструментов", R.string.ui_auto_collapse_tool_steps, Screen.SettingInterfaceBehavior, "tools collapse инструменты"))
        add(entry("ui_ttsSpeed", "Скорость озвучивания", R.string.ui_tts_speed, Screen.SettingInterfaceBehavior, "tts речь скорость"))
        add(entry("ui_ttsQuotes", "Озвучивать только текст в кавычках", R.string.ui_tts_quotes, Screen.SettingInterfaceBehavior, "tts цитаты речь"))
        add(entry("ui_ttsBrackets", "Озвучивать только текст вне скобок", R.string.ui_tts_outside_brackets, Screen.SettingInterfaceBehavior, "tts скобки речь"))
        add(entry("ui_ttsAuto", "Озвучивать после генерации", R.string.ui_tts_auto, Screen.SettingInterfaceBehavior, "tts auto речь"))
        add(entry("ui_thinkingContent", "Показывать содержимое рассуждений по умолчанию", R.string.ui_show_thinking_content, Screen.SettingInterfaceMessages, "reasoning содержание рассуждения"))
        add(entry("ui_profileName", "Имя пользователя", R.string.ui_profile_name, Screen.SettingInterfaceMessages, "nickname имя"))
        add(entry("ui_profileAvatar", "Аватар пользователя", R.string.ui_profile_avatar, Screen.SettingInterfaceMessages, "avatar эмодзи фото"))
        add(entry("ui_imageCrop", "Обрезать изображение перед отправкой", R.string.ui_image_crop, Screen.SettingInterfaceImages, "image crop картинка обрезка"))
        add(entry("ui_retry", "Автоповтор запросов", R.string.ui_auto_retry_title, Screen.SettingInterfaceAutoRetry, "retry сетевые ошибки повтор"))
        add(entry("ui_collapseMode", "Использовать лимит строк", R.string.ui_collapse_use_lines, Screen.SettingInterfaceBehavior, "collapse строки символы режим"))
        add(entry("ui_conversationOrder", "Порядок чатов", R.string.ui_conversation_order, Screen.SettingInterfaceBehavior, "sort order дата список"))
    }

    private fun entry(id: String, title: String, titleRes: Int, route: Screen, keywords: String) =
        SettingsSearchEntry(id, title, "Настройки интерфейса", HugeIcons.Settings03, SettingsSection.General,
            SettingsTarget.Route(route), keywords, titleRes = titleRes, descriptionRes = R.string.ui_interface_description)
}
