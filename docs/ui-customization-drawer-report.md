# Настройки интерфейса, боковое меню и чат Moru

Основа — `c5fb5c70`, ветка `ccr-e93188a6-grrqko`. Образец — [Moru](https://github.com/mishaqp/Moru); Flutter-код не включён в приложение, представление написано на Kotlin/Jetpack Compose.

## Что изменено

**A. Интерфейс.** Настройки собраны в страницы в порядке Moru. Добавлены стиль сообщений с живым превью, отдельные параметры сообщений, отображения, поведения и вибрации, тема и поверхности, три назначения шрифтов, Google Fonts, обои и затемнение, стекло и экономный режим. Прежние настройки распределены по подходящим страницам, поиск открывает их реальные назначения. Старые данные и сериализованные имена сохранены; новые параметры по умолчанию оставляют прежнее поведение.

**B. Боковое меню.** Поиск и выбор ассистента расположены сверху, профиль и переходы — снизу. Строки и дневные группы оформлены как Moru; ширина мобильного меню — 75% экрана. Имя профиля занимает одну строку, полное имя сохранено для озвучивания и редактирования. Добавлен массовый выбор. Его кнопки адаптируют весь ряд «значок + полная подпись» к ширине, как `FittedBox(scaleDown)` Moru; область нажатия остаётся не менее 48dp. Папки, избранное, закреп, переименование, подтверждение удаления, статистика, ассистенты и «Ещё» сохранены. Массовый выбор использует точно ту же проверку поиска, что SQL-пагинация: невидимые результаты не попадают в операции.

**C. Чат.** Прозрачная строка 56dp содержит постоянную капсулу 40dp только с тонкой рамкой, без заливки — по последнему уточнению пользователя. Под строкой названия/модели нет тёмной полосы. Браузер доступен всегда, файлы и терминал — при рабочей папке. Ниже — строка 22dp с аватаром, названием и моделью; модель открывает существующий выбор для разговора. Список проходит под шапкой и вводом, края растворяются через Offscreen/DstIn. Нижнее растворение находится внутри измеренной области ввода: последний ответ и его действия сохраняют полный контраст, без дополнительного пустого отступа 180dp. Обои видны вокруг ввода, blur использует отдельные Haze-источники. Нажатие аватара открывает меню, долгое нажатие — настройки ассистента текущего разговора.

Внешнее окно поверх других приложений исключено по ответу пользователя «Только оформление и существующий фон». Мини-карта не добавлена. Новых Android-разрешений нет. Схема базы, формат истории, выполнение инструментов, разрешения, HARDLINE и владельцы ConversationConfig сохранены. Для двух новых поведенческих настроек используются существующие операции ChatService: удаление последующих сообщений при перегенерации и копирование только выбранных версий; прежние варианты остаются умолчаниями.

## Файлы Moru → Rikka-Root

В таблицах имена Kotlin-файлов относятся к соответствующим пакетам `app/src/main/java/me/rerere/rikkahub/`; файлы настроек Moru находятся в `lib/features/settings/pages/`, общие значения — в `lib/core/providers/settings_provider.dart`.

| Файл Moru | Файл Rikka-Root | Перенесено / адаптировано |
|---|---|---|
| `display_settings_page.dart` | `ui/pages/setting/SettingInterfacePages.kt` | Главный экран «Интерфейс», отступы 16dp, карточки, разделители и переходы в порядке Moru. |
| `display_chat_item_page.dart` | `SettingInterfacePages.kt`, `InterfaceControls.kt` | «Отображение сообщений»: независимые аватар, имя и время, модель и провайдер, карточки и файлы; прежний `showThinkingContent` сохранён отдельным пунктом. |
| `display_behavior_page.dart` | `SettingInterfacePages.kt`, `InterfaceControls.kt` | «Поведение и запуск»: реальные обработчики всех параметров; прежние автоназвания, озвучивание, громкость, положение кнопок, индикатор генерации и лимит строк сохранены здесь же. |
| `display_rendering_page.dart` | `SettingInterfacePages.kt`, `InterfaceControls.kt` | Markdown трёх ролей, формулы, порог сворачивания и перенос кода; прежние номера строк. |
| `display_haptics_page.dart` | `SettingInterfacePages.kt`, `InterfaceControls.kt`, `ui/hooks/Haptics.kt` | Общий переключатель и категории вибрации; прежняя настройка генерации сохранена, новые категории выключены по умолчанию. |
| `message_style_settings_page.dart` | `SettingInterfaceMessageStylePage.kt` | По умолчанию / матовое стекло / сплошной цвет, параметры ширины и абзацев, светлая/тёмная тема, пользователь/ассистент, сброс, цвета и ползунки. |
| `message_style_rows.dart` | `InterfaceSettingsWidgets.kt`, `SettingInterfaceMessageStylePage.kt` | Нативные строки, переключатели сегментов и редактор цвета ARGB. |
| `message_style_preview.dart` | `SettingInterfaceMessageStylePage.kt::MessageStylePreview` | Настоящие `ChatMessageSurface` и фон `AssistantBackground`, источник размытия Haze; активная роль выделена в превью. |
| `theme_settings_page.dart` | `SettingThemePage.kt`, `SettingInterfaceThemePages.kt`, `ui/theme/InterfaceColorScheme.kt` | Цветовой режим, динамическая палитра, чистый фон и AMOLED; палитры и свои темы доступны всегда, выбор палитры выключает динамические цвета. |
| `theme_advanced_settings_page.dart` | `SettingInterfaceThemePages.kt`, `InterfaceColorScheme.kt` | Многослойные поверхности и элементы панелей; чистый фон/AMOLED имеют приоритет над оттенками карточек. |
| `google_fonts_picker_page.dart` | `GoogleFontsRepository.kt`, `SettingInterfaceFontsPage.kt` | Публичный каталог Expo, обычное начертание TTF и лицензия, нативное превью, поиск, обновление, загрузка и применение; файлы сохраняются в хранилище приложения. |
| `mobile_background_settings_page.dart` | `SettingPreferencesNotificationPage.kt` | Использованы только существующие уведомления и Live Update Rikka. Новый механизм фонового выполнения не переносился. |
| `background_overlay_settings_page.dart` | — | Не переносилось: внешнее окно исключено по ответу пользователя «Только оформление и существующий фон»; спецдоступ `SYSTEM_ALERT_WINDOW` не добавлялся. |
| `glass_theme_settings_page.dart` | `SettingInterfaceThemePages.kt::withGlassTheme` | Пресет стекла с сохранённым `MessageStyleBackup`; выключение восстанавливает прежний стиль после перезапуска/импорта, экономный режим отключает живое размытие. |
| `display_settings_widgets.dart` | `InterfaceSettingsWidgets.kt` | Нативные строки и карточки, TalkBack, запись числового значения при завершении ввода, перенос длинных подписей. |
| `settings_search_page.dart` | `SettingsSearchIndex.kt`, `InterfaceSearchEntries.kt`, `SettingsCatalog.kt` | Поиск каждого параметра с переходом к реальной странице; новые отображаемые подписи берутся из ресурсов. |
| `settings_provider.dart` | `data/datastore/PreferencesStore.kt` | Совместимые поля и сериализованные значения; прежние умолчания Rikka и наследование объединённых настроек через `null`. |
| `lib/features/home/widgets/side_drawer.dart` | `ui/pages/chat/ChatDrawer.kt`, `ChatDrawerChrome.kt`, `ConversationList.kt` | Фиксированные верх/низ, поиск, ассистенты, профиль, группы и меню; прежние функции сохранены. |
| `lib/features/home/widgets/sidebar_selection_bars.dart` | `ui/pages/chat/ChatDrawerSelection.kt` | Счётчик, выбор всех результатов, закрепить, переместить, удалить с подтверждением. |
| `lib/features/home/pages/home_mobile_layout.dart` | `ChatPage.kt`, `ChatHeader.kt`, `Background.kt` | Постоянная капсула, строка названия/модели, действия аватара, фон и размещение списка. |
| `chat_header_switcher.dart` | `ChatHeader.kt` | Геометрия капсулы, сегменты, HugeIcons, прозрачные цвета MaterialTheme. |
| `chat_input_overlay_layout.dart` | `ChatListEdgeFade.kt`, `ChatPage.kt` | Мягкие верх/низ списка; фон и органы управления остаются отдельными слоями. |
| `chat_message_widget.dart` | `ChatMessage.kt`, `MessageDisplayPresentation.kt`, `ChatMessageSurface.kt`, `ChatTimelinePresentation.kt` | Метаданные, стили, Markdown-роли, сворачивание, карточки и сохранённые действия. |
| `chat_input_bar.dart` | `ChatInput.kt`, `ChatInputPresentation.kt`, `ChatInputState.kt` | Прозрачный внешний слой, отдельная непрозрачность, реальный blur, настройки редактирования и прежние callbacks. |

## Новые поля DisplaySetting: схема, потребители и умолчания

В `DisplaySetting` сохранены все 49 прежних полей и добавлены 56 новых: всего 105. Новые имя и даты с `null` наследуют `showUserAvatar` и `showDateTimeInMessage`; настройки запуска наследуют прежний `create_new_conversation_on_start`, сворачивание этапов — прежнюю логику `autoCollapseToolSteps`. Явное `false` работает независимо от старого объединённого переключателя. `userMessageCollapseChars=null` сохраняет выбранный `userMessageCollapseLines` (по умолчанию 9 строк); для кода сохраняется прежний порог 10 строк. Обои и стекло выключены. Общий переключатель вибрации сохраняет прежние события, все новые категории выключены по умолчанию.

| Moru/смысл | Новое поле, тип | Умолчание Rikka | Реальный потребитель |
|---|---|---|---|
| showUserName | `showUserName: Boolean?` | `null` | MessageDisplayPresentation.kt |
| showUserTimestamp | `showUserTimestamp: Boolean?` | `null` | MessageDisplayPresentation.kt |
| showModelTimestamp | `showAssistantTimestamp: Boolean?` | `null` | MessageDisplayPresentation.kt |
| showProviderInChatMessage | `showProviderInChatMessage: Boolean` | `false` | MessageDisplayPresentation.kt |
| useNewAssistantAvatarUx | `useAssistantAvatarInHeader: Boolean?` | `null` | ChatPage.kt |
| showToolCards | `showToolCards: Boolean` | `true` | ChatTimelinePresentation.kt; MessageDisplayPresentation.kt |
| showThinkingCards | `showThinkingCards: Boolean` | `true` | ChatTimelinePresentation.kt; MessageDisplayPresentation.kt |
| collapseThinkingSteps | `collapseThinkingSteps: Boolean?` | `null` | ChatTimelinePresentation.kt; ChatToolTimeline.kt; MessageDisplayPresentation.kt |
| collapseLongUserMessageChars | `userMessageCollapseChars: Int?` | `null` | MessageDisplayPresentation.kt |
| autoCollapseCodeBlockLines | `codeBlockCollapseLines: Int` | `10` | MessageDisplayPresentation.kt; HighlightCodeBlock.kt; MarkdownDisplayOptions.kt |
| enableDollarLatex | `enableDollarLatex: Boolean` | `true` | MessageDisplayPresentation.kt; Markdown.kt; MarkdownDisplayOptions.kt; MarkdownNew.kt; MarkdownWeb.kt |
| enableUserMarkdown | `enableUserMarkdown: Boolean` | `true` | MessageDisplayPresentation.kt |
| enableReasoningMarkdown | `enableReasoningMarkdown: Boolean` | `true` | ChatMessageReasoning.kt |
| enableAssistantMarkdown | `enableAssistantMarkdown: Boolean` | `true` | MessageDisplayPresentation.kt |
| regenerateDeleteTrailingMessages | `regenerateDeleteTrailingMessages: Boolean` | `false` | ChatService.kt |
| forkKeepMessageVersions | `forkKeepMessageVersions: Boolean` | `true` | ChatService.kt |
| keepThinkingAndToolCardsWhenEditingAssistant | `keepThinkingAndToolCardsWhenEditingAssistant: Boolean` | `true` | ChatPage.kt |
| showAppUpdates | `showAppUpdates: Boolean` | `true` | ChatDrawerChrome.kt |
| keepScreenOnDuringGeneration | `keepScreenOnDuringGeneration: Boolean` | `true` | ChatInputPresentation.kt |
| mobileMessageNavButtonsMode | `messageNavButtonsMode: MessageNavButtonsMode` | `MessageNavButtonsMode.SCROLL` | ChatList.kt |
| showChatListDate | `showChatListDate: Boolean` | `true` | ChatDrawer.kt |
| keepSidebarOpenOnAssistantTap | `keepSidebarOpenOnAssistantTap: Boolean` | `false` | ChatDrawer.kt; ChatDrawerChrome.kt |
| keepSidebarOpenOnTopicTap | `keepSidebarOpenOnTopicTap: Boolean` | `false` | ChatDrawer.kt |
| keepAssistantListExpandedOnSidebarClose | `keepAssistantListExpandedOnSidebarClose: Boolean` | `false` | ChatDrawerChrome.kt |
| newChatOnAssistantSwitch | `newChatOnAssistantSwitch: Boolean?` | `null` | ChatDrawer.kt |
| newChatAfterDelete | `newChatAfterDelete: Boolean` | `true` | ChatDrawer.kt |
| newChatOnLaunch | `newChatOnLaunch: Boolean?` | `null` | RouteActivity.kt |
| autoScrollIdleSeconds | `autoScrollIdleSeconds: Int` | `0` | ChatList.kt |
| hapticsGlobalEnabled | `hapticsEnabled: Boolean` | `true` | Haptics.kt |
| hapticsIosSwitch | `hapticsOnSwitch: Boolean` | `false` | Haptics.kt |
| hapticsOnDrawer | `hapticsOnDrawer: Boolean` | `false` | Haptics.kt |
| hapticsOnListItemTap | `hapticsOnListItemTap: Boolean` | `false` | Haptics.kt |
| hapticsOnCardTap | `hapticsOnCardTap: Boolean` | `false` | Haptics.kt |
| chatMessageBackgroundStyle | `chatMessageBackgroundStyle: ChatMessageBackgroundStyle` | `ChatMessageBackgroundStyle.DEFAULT` | ChatMessageSurface.kt; MessageDisplayPresentation.kt; DEFAULT сохраняет прежние поверхности |
| userChatBubbleStyleOverrides | `userChatBubbleStyleOverrides: ChatBubbleStyleOverrides` | `ChatBubbleStyleOverrides()` | ChatMessageSurface.kt::resolveMessageBubbleStyle: фон, текст, рамка, радиус и размытие пользователя |
| assistantChatBubbleStyleOverrides | `assistantChatBubbleStyleOverrides: ChatBubbleStyleOverrides` | `ChatBubbleStyleOverrides()` | ChatMessageSurface.kt::resolveMessageBubbleStyle: ответ, рассуждения, инструменты и перевод |
| assistantBubbleFitContent | `assistantBubbleFitContent: Boolean` | `true` | MessageDisplayPresentation.kt |
| assistantBubbleSplitParagraphs | `assistantBubbleSplitParagraphs: Boolean` | `false` | MessageDisplayPresentation.kt |
| chatBackgroundMaskStrength | `chatBackgroundMaskStrength: Float` | `1f` | Background.kt |
| Общий фон Rikka с приоритетом фона ассистента | `chatBackgroundEnabled: Boolean` | `false` | Background.kt |
| Фон ассистента или общий фон; прежний импорт FilesManager | `chatBackgroundImagePath: String` | `""` | SettingsFileReferences.kt; SettingsBackupFilePaths.kt; Background.kt |
| chatInputBackgroundOpacityLight | `chatInputBackgroundOpacityLight: Float` | `1f` | ChatInputPresentation.kt |
| chatInputBackgroundOpacityDark | `chatInputBackgroundOpacityDark: Float` | `1f` | ChatInputPresentation.kt |
| usePureBackground | `usePureBackground: Boolean` | `false` | InterfaceColorScheme.kt; Theme.kt и превью |
| useLayeredSurfaces | `useLayeredSurfaces: Boolean` | `false` | InterfaceColorScheme.kt; Theme.kt и превью |
| useLayeredSheetTiles | `useLayeredSheetTiles: Boolean` | `false` | InterfaceColorScheme.kt; Theme.kt и превью |
| glassTheme | `glassTheme: Boolean` | `false` | ChatInputPresentation.kt; Background.kt; ChatPage.kt |
| glassEconomy | `glassEconomy: Boolean` | `false` | ChatInputPresentation.kt; ChatMessageSurface.kt; ChatPage.kt |
| displayGlassRestoreKey | `glassPreviousMessageStyle: MessageStyleBackup?` | `null` | SettingInterfaceThemePages.kt::withGlassTheme: сохранение и восстановление пресета |
| appFontFamily | `appFontFamily: ChatFontFamily` | `ChatFontFamily.DEFAULT` | ChatFont.kt |
| appFontLocalPath | `appCustomFontPath: String` | `""` | ChatFont.kt::rememberAppFontFamily; Type.kt::withFontFamily; SettingsFileReferences.kt; BackupManager.kt |
| appFontLocalAlias | `appCustomFontName: String` | `""` | SettingInterfaceFontsPage.kt::fontChoiceLabel: подпись выбранного файла/семейства |
| codeFontFamily | `codeFontFamily: ChatFontFamily` | `ChatFontFamily.DEFAULT` | ChatFont.kt; HighlightCodeBlock.kt; MarkdownDisplayOptions.kt |
| codeFontLocalPath | `codeCustomFontPath: String` | `""` | ChatFont.kt::rememberCodeFontFamily; HighlightCodeBlock/Markdown; SettingsFileReferences.kt; BackupManager.kt |
| codeFontLocalAlias | `codeCustomFontName: String` | `""` | SettingInterfaceFontsPage.kt::fontChoiceLabel: подпись выбранного файла/семейства |
| appLocale / followSystem | `appLanguageTag: String?` | `null` | InterfaceAppLanguage.kt; RouteActivity.kt::attachBaseContext/AppRoutes: язык приложения |

## Страницы и русские подписи

Все 20 страниц используют нативное содержимое, которое также открывают рабочие маршруты приложения. Общая точка для скриншотов — `InterfaceSettingsContent`; в тестах Google Fonts получает фиксированный каталог без сетевых запросов.

| Страница | Заголовок в интерфейсе |
|---|---|
| `Hub` | Интерфейс |
| `Messages` | Отображение сообщений |
| `Rendering` | Настройки отображения содержимого |
| `Behavior` | Поведение и запуск |
| `Haptics` | Виброотклик |
| `Images` | Обработка изображений |
| `AutoRetry` | Автоповтор запросов |
| `FontSize` | Размер шрифта чата |
| `Scroll` | Задержка возврата автопрокрутки |
| `InputOpacity` | Непрозрачность фона поля ввода |
| `FontsApp` | Шрифт приложения |
| `FontsCode` | Шрифт кода |
| `FontsChat` | Шрифт сообщений |
| `Background` | Фон чата |
| `MessageStyle` | Стиль сообщений |
| `Glass` | Стекло |
| `Language` | Язык приложения |
| `ThemeAdvanced` | Дополнительные настройки темы |
| `Themes` | Настройки темы |
| `GoogleFonts` | Google Fonts |

«Использовать лимит строк» возвращает прежний режим после выбора символов. Пока порог символов не задан, поле пустое и подпись показывает действующий лимит строк. «Пузырь ответа ассистента» виден только в стиле по умолчанию; «Общая непрозрачность пузырей» доступна во всех стилях и умножается на индивидуальную непрозрачность.

## Параметры пузырей

| Поле ChatBubbleStyleOverrides | Значение при `null` | Редактор и отображение |
|---|---|---|
| backgroundArgbLight / Dark | `surfaceContainerHigh` активной темы | Независимо для роли и светлой/тёмной темы; непрозрачный ARGB `Long`, `ChatMessageSurface`. |
| borderArgbLight / Dark | `outlineVariant` активной темы | Независимо для роли и светлой/тёмной темы; `ChatMessageSurface`. |
| textArgbLight / Dark | `onSurface` активной темы | Текст, код, рассуждения, инструменты и перевод. |
| borderWidth | 0.8dp | 0..3dp |
| borderOpacity | Матовое стекло — 0,14; сплошной цвет — 0,16 | 0..1 |
| cornerRadius | 16dp | 0..28dp |
| blurSigma | 14dp | 0..30dp, размытие фона через Haze; экономный режим отключает его. |
| frostedOpacity | .66 | 0..1 |
| solidOpacity | 1 | 0..1 |

Пресет стекла соответствует Moru: у ассистента белая рамка с непрозрачностью 0,2 и матовый фон 0,34; у пользователя фон активной палитры (включая динамическую), белые текст и рамка 0,28, матовый фон 0,72. Выключение возвращает сохранённые стиль, параметры ролей и раскладку. Если запись восстановления отсутствует, текущий редактируемый стиль сохраняется.

## Шрифты, обои и резервные копии

- Шрифты приложения, кода и сообщений независимы. По умолчанию приложение и сообщения используют `FontFamily.Default`, код — прежний встроенный `JetbrainsMono`; варианты с засечками, моноширинный и локальный файл сохранены.
- Локальный шрифт проверяется через Android `Typeface` и сохраняется в `filesDir/fonts/<назначение>_font.<UUID>.ttf`. В настройках — переносимый относительный путь `fonts/...` и имя для подписи; предел файла — 64 МиБ. Скачанная лицензия хранится рядом в `.ttf.license`, временный каталог Google Fonts — в `cacheDir/google_fonts`.
- Google Fonts использует тот же публичный снимок каталога Expo, что Moru, без ключа API и встроенных файлов шрифтов. Каталог ограничен 8 МиБ и кэшируется на 7 дней; скачивается обычное начертание TTF с `https://fonts.gstatic.com`, лицензия ограничена 256 КиБ, шрифт — 64 МиБ. Поиск, обновление, загрузка, нативное превью, просмотр лицензии и применение работают через реальные обработчики.
- Импорт изображений использует прежний `FilesManager.createChatFilesByContents`. Поля `Assistant.background`, `backgroundOpacity` и `useGradientBackground` сохранены; общий фон выключен по умолчанию и применяется при отсутствии фона ассистента.
- `SettingsFileReferences.kt` защищает выбранные обои, шрифты и лицензии при очистке. Резервная копия использует прежние каталоги `UPLOAD`/`FONTS`; `SettingsBackupFilePaths.kt` меняет только URI реально восстановленных файлов, относительные пути шрифтов сохраняются.
- Новые значения enum имеют устойчивые `@SerialName`: `default/frosted/solid` и `always/scroll/never`. Прежние поля, enum и имена сохранены; `SettingsJsonMigrator` оставляет неизвестные данные и прежнее правило аватара пользователя.

## Дополнительные проверки поведения

- Превью, редактирование цветов и стеклянный пресет используют ту же итоговую палитру, что приложение, включая динамические цвета, AMOLED и слоистые поверхности.
- При разделении ответа документы с общими определениями ссылок остаются целыми; ссылки проверяются настоящим рендерером и обработчиком URI.
- Новые настройки вибрации по умолчанию выключены. Общий переключатель сохраняет прежние события приложения.
- Порог длинного сообщения наследует прежние строки. Интерфейс показывает действующий режим и позволяет переключиться между строками и символами.
- Выбранные обои сохраняются при выключенном фоне, очистке вложений и удалении ассистента. При восстановлении копии URI меняются только для реально восстановленных файлов; SAF/HTTP и частичный импорт сохранены. Шрифты используют переносимые относительные пути с лицензиями.
- Язык выбирается через LocaleManager на Android 13+; на более ранних версиях базовая конфигурация RouteActivity меняется до создания интерфейса. Кэш используется только для раннего запуска; основное значение остаётся в DisplaySetting.
- На Android ниже 13 отдельный аварийный SafeModeActivity сохраняет язык системы; выбор языка применяется к основному интерфейсу. Общая конфигурация Application не подменяется.

## Скриншоты

`chat-after-stage4` — защищённый архив из 796 PNG; живой набор — `chat-after-stage5`, запись только с `-Pchat.stage5.record=true`. Старые флаги и общий флаг CI не включают запись чата. Зафиксированы хеши и время изменения 3296 архивных PNG.

Новые сцены: все 20 страниц интерфейса, нижние части длинных страниц, обычное меню, поиск, массовый выбор, прокрученный чат с обоями. Светлая/тёмная тема, шрифт 1.0/1.3: 31 сцена × 4 = 124 новых кадра. Существующие 796 кадров чата сохранены в живом наборе с новой вёрсткой. Test ImageLoader офлайн, `serviceLoaderEnabled(false)`, `diskCache(null)`.

Снимок сначала прогревает нативные слои, затем сохраняет один итоговый кадр. С этим же PNG сравнивается эталон: опубликованное изображение и проверяемое изображение совпадают. Допуск сравнения не увеличен; отдельные проверки подтверждают, что намеренное различие одного пикселя отклоняется и временный файл удаляется. Совпадение канонических путей результата и эталона отклоняется до любого захвата, включая псевдонимы через `../`; исходные байты эталона сохраняются. Три действия для подготовки сцены вызывают настоящий обработчик через семантику Compose, чтобы системная анимация нажатия не попадала в эталон; проверки содержимого и обработчиков сохранены.

## Дополнение: сохранение этапа 4 и ошибки с телефона

- Успешные шаги показывают заголовок и результат; подпись «Результат получен» также убрана у завершённого серверного шага без результата. Пустое завершённое мышление скрыто, активное остаётся. Termux показывает последние строки экрана, ошибки сохраняют `deviceToolFailureReason`. Запросы разрешений, активные шаги и ошибки видны даже при скрытии обычных карточек. Новые проверки монтируют реальные компоненты и проверяют исходные callbacks.
- Обнаружен и воспроизведён сбой переключения формул: native Markdown сохранял разметку прежнего состояния. Ключ сохранённой разметки теперь учитывает фактическое дерево Markdown; формат исходного текста и истории не менялся. Проверки переключения формул проходят на реальном рендерере.
- Абсолютные пути и системные псевдонимы (`/sdcard`, `/mnt/sdcard`, `/storage/self/primary`) сопоставляются только с уже выданным SAF-tree и реальным томом Android. Доступ остаётся через ContentResolver с повторной проверкой чтения/записи; соседние папки, `..`, вложенные симлинки, другой том и отозванный доступ отклоняются. Поддержаны известные externalstorage-tree, корень Downloads и его реальные дочерние ID, path-valued `raw:`. Для облачных и непрозрачных выбранных подпапок физический путь не угадывается: сохранены их `content://` адреса.
- Обычные относительные, виртуальные и физические пути внутри scratch/upload/workspace используют прежний `checkFile` и не обращаются к SAF. Внешний путь сначала проверяется по границам известных томов; только затем запрашиваются выданные разрешения. Полный запуск обнаружил слишком ранний запрос Android-доступов — он воспроизведён двумя строгими тестами и исправлен. Целевая регрессия: 55/55, включая SSH, архивы, отчёты, приватные файлы, симлинки и 17 прежних проверок сопоставления SAF.
- `browser_open` на POCO / Android 16: установлен только факт, что запрос открытия вернулся, но окно не привязалось за 5 секунд. Причина на телефоне пока неизвестна. Английские подсказки заменены понятным русским объяснением и предложением открыть существующий «Браузер» в шапке. Исключения запуска используют `deviceToolFailureReason`; отмена сохраняется. Таймаут, разрешения, cookies, headless-режим и владельцы окна не менялись. Это улучшение ошибок, запуск на POCO пока не заявляется исправленным.

У файлов сохранилось прежнее ограничение: прямые `write_text_file`/`write_binary_file`/`copy_file` не создают отсутствующий SAF-документ по полному пути. Запись существующего файла и создание через существующий механизм `createFile`/batch-copy проверяются; правила восстановления при сбое сохранены. Автосоздание потребует отдельной доработки владения новым документом, удаления только собственного результата при отмене и сохранения резервной копии существующего файла — около четырёх файлов с проверками создания, отката и отмены. В этот небольшой перенос адресов такая транзакционная доработка не включена.

| Исходный код Rikka-Root | Файл Rikka-Root | Исправление по отдельному запросу пользователя |
| --- | --- | --- |
| Существующие `local/FileManagerTool.kt`, `local/FileManagerContentOps.kt` | `FileManagerTools.kt`, `LocalFileAccess.kt`, новый `GrantedStoragePaths.kt` | Адреса внутри выданного дерева направлены в прежние SAF-операции; имена, параметры и разрешения инструментов сохранены. |
| `local/MediaScannerTool.kt` | `MediaScannerTool.kt` | Используется прежнее определение томов Android, видимость helper изменена на internal. |
| Существующий foreground browser-open / исходная привязка | `BrowserTools.kt`, `BrowserController.kt` | Русская подсказка и обработка исключения открытия; запуск и таймаут сохранены. |

Для браузера следующая проверка на телефоне: сравнить ручное открытие и `browser_open` в том же разговоре, при открытом приложении и в фоне. Затем нужны временные отметки разрешения/отказа Android при открытии окна, создания/закрытия Activity и начала/завершения WebView/привязки. Только после этого выбирать исправление запуска или задержки; содержимое страниц, cookies и секреты для диагностики не нужны.

## Унаследованное закрытие Root-процесса и среда проверки

Полный локальный запуск обнаружил прежнюю гонку в `RootShellManagerTest`: после `destroyForcibly()` процесс ещё мог существовать, а тест сразу проверял `/proc`. Исходные Root-файлы до исправления совпадали с `c5fb5c70`. Отдельный пример на JDK 21 подтвердил живой процесс сразу после сигнала и его исчезновение после `waitFor`. В `HeadlessRootSession.close()` добавлено ожидание до 500 мс после уже существующего принудительного завершения. Команды, разрешения, Root-проверка, guardian, файлы управления и неизменённые тесты сохранены; при неответившем процессе закрытие может занимать до дополнительных 500 мс. Неизменённые проверки: `RootShellManagerTest` 45/45 и `RootProcessRunnerTest` 8/8 проходят после исправления.

Во время того же запуска daemon Gradle завершился по SIGKILL, полный XML-отчёт не был сформирован. Контейнер зафиксировал превышение лимита 16 ГиБ и один `oom_kill`; системный журнал недоступен, поэтому конкретный инициатор сигнала не подтверждён. Осиротевший worker закрыт, старый компилятор, удерживавший около 4,8 ГБ, остановлен. Только в локальном `~/.gradle/gradle.properties` установлены прежний лимит Gradle 4 ГБ, уменьшенный лимит Kotlin 2 ГБ и 2 worker; файлы проекта и настройки GitHub не менялись. Повторный общий запуск выполнен после исправления Root-гонки и настройки среды: он завершился успешно и сформировал полный отчёт. Неизменённый упавший тест не повторяли ради случайного успеха.

## Исправление проверки после первого CI

Первый [Daily Build](https://github.com/mishaqp/Rikka-Root/actions/runs/38360324871) для `1460fdc5` завершился на этапе тестов: 3457 проверок, одна ошибка в прежнем `ConfiguredSpeechASRTest.finalHttpTranscriptIsReadBeforeDisposalEvenAfterTransientRecorderError`. Все 244 проверки снимков и остальные 3456 тестов прошли. APK в этом запуске не собирался.

Эта проверка использует подставной `TestController` с состояниями Listening → Error → Idle, без настоящего HTTP, Android AudioRecord или MiMo. При `runBlocking` бюджет 200 мс и задержка ответа 20 мс зависели от планирования нагруженного CI. В одном тесте добавлен `runTest` с общими управляемыми часами: исходные 200 мс и 20 мс сохранены. В 0 мс проверяются запись и отсутствие stop/dispose, в 100 мс — временная ошибка, один stop и ожидание без dispose, в 120 мс — окончательный текст и освобождение контроллера. Прежние итоговые проверки сохранены, промежуточные усилены. Код распознавания, провайдеры, таймауты приложения и остальные тесты не менялись. После исправления весь класс проходит локально: **12/12**, 17 с; независимый обзор подтверждает сохранение и усиление проверок. Новый CI после исправления указан ниже.

Повторная отправка выполняется по явному правилу пользователя: исправить прочитанное падение CI и отправить исправление без нового разрешения. Это исправление проверки, без новой записи снимков или изменений оформления.

<!-- FINAL_VALIDATION -->
## Проверки и публикация

- Полная команда CI `./gradlew test --no-configuration-cache -Proborazzi.test.record=true` завершилась успешно за 5 мин 32 с: **3457/3457**, ошибок и пропусков нет; приложение — 3006 тестов, всего 13 модулей с отчётами.
- Списки сохранённых функций: Chat 106/106, Drawer 95/95, Rich Content 27/27, Sketch 34/34 — 262/262.
- Совместимость DisplaySetting и старых JSON: 7/7; защита архивных путей: 7/7; сохранение этапа 4: 6/6; реальный Markdown: 36/36. Запуск/ошибки браузера: 8/8; абсолютные пути SAF: 12/12, правила путей: 5/5, сохранение прежних локальных scopes: 2/2. Прежние Root-тесты: 53/53.
- В полном запуске снимки чата **сверялись**, общий `roborazzi.test.record=true` не записывает stage5: 120/120 прежних сценариев и 124/124 новых. Защитный помощник снимков: 4/4. Отдельная финальная сверка без флага записи прошла: **244/244**, 3 мин 53 с (`-Proborazzi.test.verify=true`).
- Финальная `./gradlew lint --no-configuration-cache` завершилась с ожидаемым ненулевым кодом из-за **45 унаследованных ошибок**: новых ошибок **0**, 575 предупреждений, 4 подсказки. `android_build.py check-lint --exit-code 1` проходит: 45 ≤ 51. Сравнение со снимком исходного `c5fb5c70` использует сами замечания и пути всех 13 модулей; добавленных и удалённых ошибок нет, сдвиг строк не считается новой ошибкой. Подавлений ошибок не добавлено.
- Живой набор — 920 PNG. Финальная проверка после отдельной сверки: все 3296 архивных PNG сохранили SHA-256 и время изменения.
- 15/15 проверок сценария сборки GitHub прошли. Независимые обзоры настроек, SAF, меню, снимков и Root-fix закончены; подтверждённых незакрытых P1/P2 нет. `git diff --check` чистый.
- Локальный APK не собирался. Все части отправляются одним push; Daily Build и свежий nightly APK будут указаны после выпуска.

| Часть | Коммит |
| --- | --- |
| A — настройки и потребители | `efff0a07` |
| B — боковое меню | `222277f7` |
| C — шапка, фон и края списка | `8dc2d45b` |
| SAF-пути и ошибки браузера | `6ca9cabd` |
| Закрытие Root-процесса | `0af9cdeb` |
| Снимки stage5 и офлайн-проверки | `6b0bf55f` |
| План и отчёт до публикации | `1460fdc5` |

Опубликованы семь коммитов одним push: `1460fdc571333eb4c69d2b6683a548979a876f70`. Первый [Daily Build](https://github.com/mishaqp/Rikka-Root/actions/runs/38360324871) завершился ошибкой одной проверки ASR; готовится исправление стабильности этой проверки. Свежий APK будет указан после завершения. Ссылки и итог Actions дополняются локально после единственного push, чтобы отчёт сам не запускал вторую сборку.
<!-- /FINAL_VALIDATION -->

## Проверить на телефоне

1. Светлая/тёмная тема, обои и стекло: прокрутить историю под шапкой и вводом, открыть модель, браузер, файлы/терминал; нажать и долго нажать аватар.
2. «Интерфейс»: поиск, превью сообщений, цвета двух тем, отдельные шрифты и Google Fonts, пределы сворачивания, вибрация, язык и режим экономии.
3. Меню: поиск, ассистенты, папки, избранное, закреп, переименование; выбор нескольких чатов и подтверждение удаления.
4. Сообщения: код, формулы, ссылки, картинки, инструменты и одобрения; изменить ответ с сохранением вложений, перегенерировать, выбрать другую версию, озвучить и использовать подсказку.
5. Крупный шрифт, клавиатура, длинная история и поворот; затем создать/восстановить копию с выбранными обоями и шрифтами.

Roborazzi проверяет нативные JVM-кадры и callbacks. Реальные IME, GPU/OEM, WebView, голос, Android-разрешения и выполнение инструментов требуют этой проверки на устройстве; показатели производительности на телефоне не заявляются.
