# Ночная проверка настроек интерфейса

Проверяемая основа: `c5fb5c70..f1800f41`, включая `efff0a07`, `222277f7`, `8dc2d45b`. Прочитаны обязательный `AGENTS.md`, исходный `docs/ui-customization-drawer-report.md`, изменения настроек, тема/шрифты/hooks, реестр маршрутов и реальные потребители. Справочник поведения — `/workspace/refs/Moru`; Dart-код не переносился. Старые отчёты и архивные PNG не редактировались.

## Найденные проблемы

| Важность | Файл и причина | Сценарий | Действие и проверка |
|---|---|---|---|
| P2, доступность | `InterfaceSettingsWidgets.kt::InterfaceSwitchRow`: `clickable(role=Switch)` у строки без `ToggleableState`; отдельный интерактивный дочерний `Switch` | TalkBack фокусирует подпись и переключатель отдельно, состояние подписанной строки не озвучивается | Регрессионный Compose-тест сначала проверяет `assertIsOn` у строки, затем единственную цель Switch, один update при семантическом клике и при касании правой части; RED: `assertIsOn` отклоняет строку без ToggleableState; GREEN: 1/1, state/единственная цель/click/thumb/external update |
| Не подтверждено текущими кадрами | Первоначальное подозрение на отсутствующие Switch в `settings_rendering.png` | Проверены individual/native и contact кадры light/dark/AMOLED/dynamic, fonts 1/1.3, widths 320/411 | Все переключатели видны; Rendering/RenderingBottom 32 файла проверены, missing-switch fix не делался |
| P2, читаемость | `InterfaceNavigationRow`: trailing selected value занимает ширину label на 320dp | Hub тема на font1.3 — 10 строк по 1–2 буквы; font label — 3 строки | Native RED 2/2 в `/tmp/settings-navigation-confirmed-red.log`; minimal responsive layout, GREEN/re-review ниже |
| P3, читаемость | `StyleSegmentedToggle`: последняя «ь» в «Пользователь» переносится отдельно | MessageStyle 320dp/font1.3, обе роли | Native RED: 2 строки вместо 1; полный однострочный autosize text, GREEN 6/6 и 32 after-кадра ниже |

## Карта хранения и потребителей

Каждая строка ниже соответствует **одному из всех 105 полей** `DisplaySetting`; наличие только declaration/editor не считалось потребителем. Пути потребителей указаны относительно `app/src/main/java/me/rerere/rikkahub`. Для компактности даны имена файлов; одинаковых имён среди указанных потребителей нет.

`D` означает существующий DataStore `files/datastore/settings.preferences_pb`, ключ `display_setting`, сериализованный объект `DisplaySetting`. Backup: `settings.json → displaySetting.<имя поля>`. Все 105 полей имеют этот же storage и входят в обычный полный JSON backup. Никакой второй настройки или новой схемы хранения не добавлено. Писатели новых страниц используют `InterfacePageState.updateDisplaySetting → SettingVM.updateSettings → SettingsStore.update`: преобразование применяется к актуальным настройкам, а не к устаревшему снимку всего объекта.

| Поле setting | Фактический потребитель | Хранение |
|---|---|---|
| `userAvatar` | `SettingsFileReferences.kt`, `SettingsBackupFilePaths.kt`, `ChatMessageAvatar.kt`, `MessageDisplayPresentation.kt`, `ChatDrawer.kt`, `ChatDrawerChrome.kt`, `ChatVM.kt` | D |
| `userNickname` | `PlaceholderTransformer.kt`, `ChatMessageAvatar.kt`, `MessageDisplayPresentation.kt`, `ChatDrawer.kt`, `ChatDrawerChrome.kt` | D |
| `useAppIconStyleLoadingIndicator` | `RabbitLoading.kt` | D |
| `showUserAvatar` | `PreferenceStoreMessageDisplayMigration.kt`, `SettingsJsonMigrator.kt`, `ChatMessageAvatar.kt`, `MessageDisplayPresentation.kt` | D |
| `showAssistantBubble` | `MessageDisplayPresentation.kt`, `ConversationExport.kt` | D |
| `bubbleOpacity` | `ChatMessage.kt`, `ChatMessageSurface.kt`, `MessageDisplayPresentation.kt` | D |
| `showModelIcon` | `MessageDisplayPresentation.kt`, `ConversationExport.kt` | D |
| `showModelName` | `MessageDisplayPresentation.kt` | D |
| `showDateTimeInMessage` | `ChatMessageActions.kt`, `MessageDisplayPresentation.kt` | D |
| `showTokenUsage` | `ChatMessage.kt`, `ChatMessageNerdLine.kt`, `MessageDisplayPresentation.kt` | D |
| `showThinkingContent` | `ChatMessageReasoning.kt`, `MessageDisplayPresentation.kt` | D |
| `autoCloseThinking` | `ChatMessageReasoning.kt`, `ChatTimelinePresentation.kt`, `MessageDisplayPresentation.kt` | D |
| `collapseLongUserMessages` | `MessageDisplayPresentation.kt` | D |
| `userMessageCollapseLines` | `MessageDisplayPresentation.kt` | D |
| `showUserMessageActions` | `MessageDisplayPresentation.kt` | D |
| `showToolSummary` | `MessageDisplayPresentation.kt` | D |
| `hideToolResultImages` | `MessageDisplayPresentation.kt` | D |
| `autoCollapseToolSteps` | `MessageDisplayPresentation.kt` | D |
| `confirmAssistantRegeneration` | `ChatMessageActions.kt`, `ChatMessagePresentation.kt` | D |
| `insertSuggestionOnly` | `ChatList.kt` | D |
| `showProducedFiles` | `ChatMessage.kt` | D |
| `updateCheckDisabledUntilEpochMillis` | `ChatDrawer.kt`, `ChatVM.kt` | D |
| `showMessageJumper` | `ChatList.kt` | D |
| `messageJumperOnLeft` | `ChatList.kt` | D |
| `fontSizeRatio` | `ChatMessage.kt`, `ChatMessageReasoning.kt`, `ChatMessageTools.kt`, `ChatMessageTranslation.kt`, `ChatToolTimeline.kt`, `MessageDisplayPresentation.kt`, `Markdown.kt`, `MarkdownDisplayOptions.kt`, `MarkdownNew.kt`, `MarkdownTableBlock.kt`, `ChainOfThought.kt` | D |
| `enableMessageGenerationHapticEffect` | `Haptics.kt`, `ChatMessage.kt` | D |
| `skipCropImage` | `ChatAttachmentPicker.kt` | D |
| `enableNotificationOnMessageGeneration` | `ChatNotificationManager.kt` | D |
| `enableLiveUpdateNotification` | `ChatNotificationManager.kt` | D |
| `codeBlockAutoWrap` | `MessageDisplayPresentation.kt`, `HighlightCodeBlock.kt`, `MarkdownDisplayOptions.kt` | D |
| `codeBlockAutoCollapse` | `MessageDisplayPresentation.kt`, `HighlightCodeBlock.kt`, `MarkdownDisplayOptions.kt` | D |
| `showLineNumbers` | `MessageDisplayPresentation.kt`, `HighlightCodeBlock.kt`, `MarkdownDisplayOptions.kt` | D |
| `ttsOnlyReadQuoted` | `ChatMessageActions.kt`, `TTSAutoPlay.kt`, `VoiceMode.kt` | D |
| `ttsOnlyReadOutsideBrackets` | `ChatMessageActions.kt`, `TTSAutoPlay.kt`, `VoiceMode.kt` | D |
| `autoPlayTTSAfterGeneration` | `TTSAutoPlay.kt` | D |
| `pasteLongTextAsFile` | `ChatInput.kt` | D |
| `pasteLongTextThreshold` | `ChatInput.kt` | D |
| `sendOnEnter` | `ChatInput.kt` | D |
| `enableAutoScroll` | `ChatList.kt` | D |
| `enableAutoChatTitles` | `ConversationTitleGenerator.kt` | D |
| `enableLatexRendering` | `MessageDisplayPresentation.kt`, `Markdown.kt`, `MarkdownDisplayOptions.kt`, `MarkdownNew.kt`, `MarkdownWeb.kt` | D |
| `enableBlurEffect` | `ChatInputPresentation.kt` | D |
| `backgroundEffectType` | `ChatInputPresentation.kt` | D |
| `chatFontFamily` | `ChatFont.kt`, `ChatMessage.kt`, `ChatMessageReasoning.kt`, `MessageDisplayPresentation.kt` | D |
| `chatCustomFontPath` | `SettingsFileReferences.kt`, `ChatFont.kt`, `ChatMessage.kt`, `MessageDisplayPresentation.kt` | D |
| `chatCustomFontName` | SettingInterfaceFontsPage.kt::fontChoiceLabel, fontSelection (подпись, не эффект рендера) | D |
| `enableVolumeKeyScroll` | `ChatList.kt` | D |
| `volumeKeyScrollRatio` | `ChatList.kt` | D |
| `conversationSortOrder` | `ChatDrawer.kt`, `ChatDrawerVM.kt` | D |
| `showUserName` | `MessageDisplayPresentation.kt` | D |
| `showUserTimestamp` | `MessageDisplayPresentation.kt` | D |
| `showAssistantTimestamp` | `MessageDisplayPresentation.kt` | D |
| `showProviderInChatMessage` | `MessageDisplayPresentation.kt` | D |
| `useAssistantAvatarInHeader` | `ChatPage.kt` | D |
| `showToolCards` | `ChatTimelinePresentation.kt`, `MessageDisplayPresentation.kt` | D |
| `showThinkingCards` | `ChatTimelinePresentation.kt`, `MessageDisplayPresentation.kt` | D |
| `collapseThinkingSteps` | `ChatTimelinePresentation.kt`, `ChatToolTimeline.kt`, `MessageDisplayPresentation.kt` | D |
| `userMessageCollapseChars` | `MessageDisplayPresentation.kt` | D |
| `codeBlockCollapseLines` | `MessageDisplayPresentation.kt`, `HighlightCodeBlock.kt`, `MarkdownDisplayOptions.kt` | D |
| `enableDollarLatex` | `MessageDisplayPresentation.kt`, `Markdown.kt`, `MarkdownDisplayOptions.kt`, `MarkdownMathSource.kt`, `MarkdownNew.kt`, `MarkdownWeb.kt` | D |
| `enableUserMarkdown` | `MessageDisplayPresentation.kt` | D |
| `enableReasoningMarkdown` | `ChatMessageReasoning.kt` | D |
| `enableAssistantMarkdown` | `MessageDisplayPresentation.kt` | D |
| `regenerateDeleteTrailingMessages` | `ChatService.kt` | D |
| `forkKeepMessageVersions` | `ChatService.kt` | D |
| `keepThinkingAndToolCardsWhenEditingAssistant` | `ChatPage.kt` | D |
| `showAppUpdates` | `ChatDrawerChrome.kt` | D |
| `keepScreenOnDuringGeneration` | `ChatInputPresentation.kt` | D |
| `messageNavButtonsMode` | `ChatList.kt` | D |
| `showChatListDate` | `ChatDrawer.kt` | D |
| `keepSidebarOpenOnAssistantTap` | `ChatDrawer.kt`, `ChatDrawerChrome.kt` | D |
| `keepSidebarOpenOnTopicTap` | `ChatDrawer.kt` | D |
| `keepAssistantListExpandedOnSidebarClose` | `ChatDrawerChrome.kt` | D |
| `newChatOnAssistantSwitch` | `ChatDrawer.kt` | D |
| `newChatAfterDelete` | `ChatDrawer.kt` | D |
| `newChatOnLaunch` | `RouteActivity.kt` | D |
| `autoScrollIdleSeconds` | `ChatList.kt` | D |
| `hapticsEnabled` | `Haptics.kt` | D |
| `hapticsOnSwitch` | `Haptics.kt` | D |
| `hapticsOnDrawer` | `Haptics.kt` | D |
| `hapticsOnListItemTap` | `Haptics.kt` | D |
| `hapticsOnCardTap` | `Haptics.kt` | D |
| `chatMessageBackgroundStyle` | `ChatMessageSurface.kt`, `MessageDisplayPresentation.kt` | D |
| `userChatBubbleStyleOverrides` | `ChatMessageSurface.kt` | D |
| `assistantChatBubbleStyleOverrides` | `ChatMessageSurface.kt` | D |
| `assistantBubbleFitContent` | `MessageDisplayPresentation.kt` | D |
| `assistantBubbleSplitParagraphs` | `MessageDisplayPresentation.kt` | D |
| `chatBackgroundMaskStrength` | `Background.kt` | D |
| `chatBackgroundEnabled` | `Background.kt` | D |
| `chatBackgroundImagePath` | `SettingsFileReferences.kt`, `SettingsBackupFilePaths.kt`, `Background.kt` | D |
| `chatInputBackgroundOpacityLight` | `ChatInputPresentation.kt` | D |
| `chatInputBackgroundOpacityDark` | `ChatInputPresentation.kt` | D |
| `usePureBackground` | `InterfaceColorScheme.kt`, `Theme.kt` | D |
| `useLayeredSurfaces` | `InterfaceColorScheme.kt`, `Theme.kt` | D |
| `useLayeredSheetTiles` | `InterfaceColorScheme.kt`, `Theme.kt` | D |
| `glassTheme` | `ChatInputPresentation.kt`, `Background.kt`, `ChatPage.kt` | D |
| `glassEconomy` | `ChatInputPresentation.kt`, `ChatMessageSurface.kt`, `ChatPage.kt` | D |
| `glassPreviousMessageStyle` | SettingInterfaceThemePages.kt::withGlassTheme (снимок для восстановления) | D |
| `appFontFamily` | `ChatFont.kt` | D |
| `appCustomFontPath` | `SettingsFileReferences.kt`, `ChatFont.kt` | D |
| `appCustomFontName` | SettingInterfaceFontsPage.kt::fontChoiceLabel, fontSelection (подпись) | D |
| `codeFontFamily` | `ChatFont.kt`, `HighlightCodeBlock.kt`, `MarkdownDisplayOptions.kt` | D |
| `codeCustomFontPath` | `SettingsFileReferences.kt`, `ChatFont.kt` | D |
| `codeCustomFontName` | SettingInterfaceFontsPage.kt::fontChoiceLabel, fontSelection (подпись) | D |
| `appLanguageTag` | `RouteActivity.kt` | D |

### Вложенные параметры и дополнительные настройки страниц

| Setting | Consumer | Storage / совместимость |
|---|---|---|
| `ChatBubbleStyleOverrides.backgroundArgbLight/Dark`, `borderArgbLight/Dark`, `textArgbLight/Dark` | `ChatMessageSurface.resolveMessageBubbleStyle`; редактор и превью используют ту же итоговую палитру | D, внутри user/assistant override; `null` наследует тему независимо для двух режимов |
| `borderWidth`, `borderOpacity`, `cornerRadius`, `blurSigma`, `frostedOpacity`, `solidOpacity` | `ChatMessageSurface.resolveCustomMessageBubbleStyle` | D внутри override; существующие диапазоны и клемпинг потребителя сохранены |
| `MessageStyleBackup.style/user/assistant/assistantBubbleFitContent/assistantBubbleSplitParagraphs` | `withGlassTheme(false, ...)` | D внутри `glassPreviousMessageStyle`; exact JSON roundtrip покрыт `GlassThemePresetTest` |
| `Settings.dynamicColor` | `settingsBaseColorScheme` | DataStore `dynamic_color`; Android 12+; сохранён старый выбор |
| `Settings.themeId/customThemes` | `settingsBaseColorScheme`, `findThemeById` | DataStore `theme_id/custom_themes`, обычный backup |
| `ColorMode`, AMOLED | `rememberCurrentColorMode`, `RikkahubTheme`, `effectiveDisplayColorScheme` | Прежние SharedPreferences `rikkahub.preferences` (`colorMode`, `amoledDark`); не появились новые JSON поля |
| `Settings.defaultTTSPlaybackSpeed` | `ChatMessageActions`, `TTSAutoPlay`, `VoiceMode` | DataStore `default_tts_playback_speed`, обычный backup |
| `Settings.networkSetting.enableAutoRetry` | `GenerationLoop` → существующая retry policy | DataStore `network_setting`, обычный backup |
| `Assistant.background/backgroundOpacity/useGradientBackground` | `AssistantBackground` | Существующий Assistant JSON в DataStore `assistants` и backup; фон ассистента имеет приоритет перед общим |
| `create_new_conversation_on_start` | fallback `RouteActivity`, `ChatDrawer`, Behavior UI | Прежние SharedPreferences; новые nullable `newChatOnLaunch/newChatOnAssistantSwitch` наследуют его до явного изменения |
| Локальный шрифт и лицензия | `ChatFont`, import/preview/apply | `files/fonts/<target>_font.<UUID>.ttf`, относительный путь D; `.ttf.license` рядом; оба файла входят в file backup |
| Общие обои и аватар | `FilesManager`, `Settings.localFileUrls`, `remapRestoredFileUrls` | `files/upload`, file URI D; rebase только реально staged upload файлов; выключение опции не освобождает выбранный файл |
| Google Fonts catalog / download | `GoogleFontsRepository` | `cache/google_fonts`, не пользовательские settings; каталог Expo 7 дней / 8 MiB, HTTPS gstatic TTF 64 MiB, лицензия 256 KiB; redirects выключены |
| `appLanguageTag` ранний кэш | `attachInterfaceLanguageContext` / `syncInterfaceAppLanguage` | D — authoritative choice; Android 13+ LocaleManager; до Android 13 SharedPreferences `interface_app_language/language_tag` только синхронный стартовый mirror |

## Все 20 страниц

| PageId | Проверенное содержимое / Moru reference |
|---|---|
| Hub | `SettingInterfacePage` → реальные Screen маршруты; `display_settings_page.dart` |
| Messages | 13 независимых toggles + profile/name/avatar + прежний thinking-content; `display_chat_item_page.dart` |
| Rendering | dollar/math/user/reasoning/assistant Markdown, code collapse/wrap/lines; `display_rendering_page.dart` |
| Behavior | 19 основных toggles + inherited launch/switch + collapse mode/count + nav mode + sort + прежние volume/loading/TTS; `display_behavior_page.dart` |
| Haptics | master + switch/drawer/list/card/generation; `display_haptics_page.dart`, глобальный gating сохраняет прежние события |
| Images | `skipCropImage` инвертируется для подписи «обрезать»; `image_settings_page.dart` |
| AutoRetry | прежний `networkSetting.enableAutoRetry`; `auto_retry_page.dart` |
| FontSize | прежний `fontSizeRatio`; preview и сообщения |
| Scroll | прежний `enableAutoScroll` + `autoScrollIdleSeconds`; `ChatList` |
| InputOpacity | независимые light/dark параметры, `ChatInputPresentation` |
| FontsApp | default/serif/mono/custom/local/Google/reset; `ChatFont`, `Type.withFontFamily` |
| FontsCode | независимый выбор; прежний `JetbrainsMono` default, native/web Markdown |
| FontsChat | прежние chat font поля и resolver сохранены |
| Background | общий фон + фон выбранного ассистента + затемнение + прежний input blur; Moru и существующая логика Assistant |
| MessageStyle | DEFAULT/FROSTED/SOLID, role/light/dark, fit/split, reset confirmation, preview; `message_style_settings_page.dart` |
| Glass | обратимый Moru preset + economy; `glass_theme_settings_page.dart`, `settings_provider.dart::setGlassTheme` |
| Language | system/ru/zh-CN/zh-Hant/en → D и реальный platform locale |
| ThemeAdvanced | layered surfaces/sheet tiles; `theme_advanced_settings_page.dart` |
| Themes | существующие палитры/custom/import/export/create + dark/system/dynamic/pure/AMOLED |
| GoogleFonts | filter/refresh/download/progress/preview/license/apply, независимая цель font picker |

## Совместимость, дубли и безопасность

- Старые имена и `@SerialName` не удалены: `MessageNavButtonsMode` — `always/scroll/never`, style — `default/frosted/solid`. `MessageDisplaySerializationTest` проверяет полный old/new JSON roundtrip и legacy defaults.
- nullable name/timestamp/collapse/launch сохраняют старое объединённое поведение; явное `false` не заменяется fallback. Старый avatar migration сохранён. Порог символов `null` оставляет старый лимит строк; UI предлагает оба режима.
- `glassPreviousMessageStyle` полностью сериализуется. Повторное включение не перезаписывает снимок. Выключение после restart/restore восстанавливает оригинальные overrides/fit/split; при отсутствии снимка сохраняет текущий style. Наследование assistant blurSigma обеими ролями совпадает с Moru.
- `SettingPreferencesPage/UI` redirect на hub, General на Behavior, Theme на Themes. Старая реализация названа `*LegacyPage`; `SettingPreferencesUILegacy` имеет отдельный маршрут, но новые поиск/hub ведут на новые страницы. Это сохранённый код совместимости, не конкурирующая копия storage. В hub есть два перехода к прежней странице уведомлений с разными смыслами; функционального расхождения данных нет.
- Font resolver проверяет canonical containment в app files; отсутствующий/невалидный файл возвращает безопасный default. SettingsFileReferences сохраняет выключенные обои, не выбранные сейчас font paths и лицензии. Backup сохраняет относительные пути fonts, перезаписывает URI только восстановленных upload bytes и не затрагивает SAF/HTTP/невыбранные files.
- Не менялись права Android, tool approvals, HARDLINE, root, секреты/AndroidKeyStore, ConversationConfig, структура сообщений или выполнение инструментов. Изменения ограничены контролом settings и его тестом.

## Выполненные проверки

- RED: `/tmp/settings-switch-confirmed-red.log`, `InterfaceSwitchAccessibilityTest.labeledRowExposesItsStateAsOneSwitchAndBothClickTargetsUpdateOnce`: ожидаемое отсутствие `ToggleableState` у `ui_math`, `assertIsOn` отклоняет строку. До настоящего RED исправлены обвязка теста (import click, LocalSettings provider); один запуск блокировала локальная repository override / HTTP 503. Эти ошибки не считаются RED.
- Минимальная правка: родитель `toggleable(value=checked, role=Switch)` владеет единственным действием, дочерний Switch получает `onCheckedChange=null`. `minimumInteractiveComponentSize` явно сохраняет прежние 48dp control и 64dp однострочной строки; настройки и haptic callback прежние.
- GREEN: `/tmp/settings-green.log`, 22 с, exit 0. 43/43 тестов, 0 ошибок/пропусков: `InterfaceSwitchAccessibilityTest` 1, `MessageDisplaySerializationTest` 7, `SettingsBackupFilePathsTest` 4, `SettingsFileReferencesTest` 5, `GlassThemePresetTest` 2, `GoogleFontsCatalogTest` 2, `InterfaceColorSchemeTest` 2, `SettingsSearchIndexTest` 7, `HapticsTest` 2, `InterfaceAppLanguageTest` 10, `RouteActivityLanguageTest` 1. XML сохранены в `/tmp/settings-green-xml`.
- Команда: `./gradlew :app:testDebugUnitTest --tests '*InterfaceSwitchAccessibilityTest' --tests '*MessageDisplaySerializationTest' --tests '*SettingsBackupFilePathsTest' --tests '*SettingsFileReferencesTest' --tests '*GlassThemePresetTest' --tests '*GoogleFontsCatalogTest' --tests '*InterfaceColorSchemeTest' --tests '*SettingsSearchIndexTest' --tests '*HapticsTest' --tests '*InterfaceAppLanguageTest' --tests '*RouteActivityLanguageTest' --no-configuration-cache`. Полная общая и визуальная проверки координируются root; эта цифра описывает только целевые settings-классы.
- `git diff --check` чистый для моих файлов.

Все Gradle-команды выполняются через `flock /tmp/rikka-overnight-gradle.lock`, с `--no-configuration-cache`. APK локально не собирался; push не выполнялся.

## Остатки и телефон

Реальный TalkBack, native font import через document picker, большие/невалидные файлы, Google Fonts download/license, platform app locale/recreation, вибрация и производительность стекла требуют устройства. Область проверки JVM — настоящие Compose callbacks, сериализация, backup с реальными bytes и palette resolution; результатом не заявляется GPU/OEM поведение.

## Дополнительный визуальный проход всей матрицы

В `/tmp/overnight-stage5-review` просмотрены **448 исходных current PNG**: 28 settings-сцен × light/dark/AMOLED/dynamic × fontScale 1/1.3 × width 320/411dp. Контактные листы составлены из исходных кадров **без масштабирования** и просмотрены через `view_image(original)`. Отдельные подозрительные кадры дополнительно открывались индивидуально (Hub light 320/1.3 и MessageStyle AMOLED 411/1.3). Побайтовые SHA-256 дубликаты RenderingBottom подтверждены тем же просмотренным Rendering-кадром. Список путей, SHA-256, размеров, способов просмотра и вердиктов — `/tmp/overnight-settings-visual.json`; 448 записей, все SHA совпадали с source после первоначального прохода.

Найден один blocker: trailing value в Hub сжимает title. Before всех 80 кадров пяти возможных затронутых сцен сохранены в `/tmp/overnight-settings-layout-before` с `manifest.json`: Hub, HubBottom, Background, BackgroundBottom, Language × 16. На странице 320dp отступы Scaffold оставляют 288dp для строки. Реальный Native-тест воспроизвёл 10 строк «Настройки темы» и 3 строки «Шрифт сообщений». Первоначальная обвязка без Native Graphics давала ложный положительный результат; она исправлена **до** production fix, эти запуски не названы RED. Один запуск также был заблокирован временно неконсистентной чужой ChatBefore fixture; её исправил root.

Минимальная правка `InterfaceNavigationRow`: когда доступная ширина меньше 360dp и есть detail/detailContent, выбранное значение отображается под подписью в той же Column. Иконка, chevron и исходный navigation/haptic callback сохранены; в остальных строках используется прежнее размещение. На широких кадрах helper имеет 379dp (viewport411 минус32dp); compact branch не применяется. GREEN: `/tmp/settings-navigation-green.log`, exit 0, 23 с, **10/10** без ошибок и пропусков: Native `InterfaceNavigationRowLayoutTest` 2, `InterfaceSwitchAccessibilityTest` 1, `SettingsSearchIndexTest` 7. Проверены <=2 строки подписи, containment, отсутствие overlap label/value и реальный navigation callback ровно один раз. XML сохранены в `/tmp/settings-navigation-green-xml`.

Root повторно записал current всех 80 возможных затронутых кадров. Ровно 40 узких изменились и пересмотрены по native контактным листам в `/tmp/overnight-settings-layout-after`; 40 широких совпали SHA-256 с первоначально просмотренными. Hub читается, подписи/значения не перекрываются и действия присутствуют. Подозрительные AMOLED контактные листы отображались с артефактом текста в инструменте просмотра; исходные индивидуальные PNG дополнительно открывались и исправны. Обновлённые SHA и способы просмотра внесены в общий manifest.

Отдельная безопасная доводка P3: `SettingInterfaceMessageStylePage.kt::StyleSegmentedToggle` использует `TextAutoSize.StepBased` для полного текста в одну строку (нижняя граница 10sp, верхняя — прежний fontSize). Состояния, роли, callbacks и сохранение прежние. Native `MessageStyleRoleLabelsLayoutTest` запускает настоящую страницу при 320dp/font1.3, проверяет отсутствие overflow/ellipsis и конец строки на последнем символе у обеих подписей, переключает Ассистент → Пользователь → Ассистент с `assertIsSelected`. RED `/tmp/settings-role-confirmed-red.log`: 2 строки «Пользователь» вместо 1. GREEN `/tmp/settings-role-green.log`, exit 0, 22 с, **6/6** без ошибок/пропусков: role labels 1, navigation layout 2, switch accessibility 1, glass preset 2; XML `/tmp/settings-role-green-xml`.

Before 32 кадров MessageStyle/MessageStyleBottom сохранены в `/tmp/overnight-settings-role-before`; after — `/tmp/overnight-settings-role-after`. Все 32 повторно просмотрены; изменились ровно 8 кадров 320dp/font1.3, остальные 24 имеют прежний SHA. Полные role labels читаемы, состояние видно, действий не потеряно. Финальный `/tmp/overnight-settings-visual.json` содержит **448/448** current settings PNG с совпадающими SHA-256, включая все перезаписанные кадры; blockers не осталось в проверенной матрице.

Допустимый обычный wrap: на 320dp/font1.3 длинное «экспериментально» в ThemeAdvanced и длинные названия палитр могут переноситься посимвольно. Полный текст присутствует, элементы не перекрывают действия; общее правило переноса сохранено.

Границы: просмотрены неподвижные Native JVM-кадры, а не GPU/OEM или реальные IME. Длинные страницы сняты сверху/снизу; все промежуточные scroll positions не охвачены. Google Fonts использует фиксированный offline catalog; download/progress/license/apply диалоги не представлены в этой PNG-матрице. Системная тема AMOLED в fixture не меняет SharedPreferences выбора режима в Theme page: переключатель AMOLED на снимке показывает fixture preference, а не отсутствие чёрного background. Ни один stage5/архивный PNG этим агентом не записывался.
