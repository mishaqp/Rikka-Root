# Аудит Moru UI и настроек «Интерфейс»

Дата: 2026-10-15. Исходный HEAD: `2d58f3b6`. Область: реальные настройки, ChatHeader, drawer, длинный чат и перерисовки. Рабочую production-логику UI сохраняем: кандидат auto-follow отклонён после проверки настоящего Scaffold; архивные скриншоты, безопасность и доступы не менялись. Gradle запускает root; ниже статическая сверка и существующее покрытие, а не утверждение о новом полном GREEN.

## Результат исследования

Подтверждённых новых UI-дефектов нет. Гипотеза об auto-follow была последовательно проверена и снята, рабочая логика сохраняется.

1. Диагностический `composerShrinkingDuringGenerationKeepsFollowingTheNextStreamChunk` заменял immutable PaddingValues 220→80dp. На исходном `LaunchedEffect(state)` он упал (`/tmp/rikka-audit-all-red.log:560`), а добавление ключа innerPadding сделало его зелёным (`/tmp/rikka-audit-data-ui-green.log`). Это показывает только контракт при замене объекта padding.
2. Сверка с экраном показала, что `ChatPage.kt:460` передаёт прямой padding Material3 Scaffold, который сохраняет mutable holder. Добавлен `scaffoldComposerShrinkingDuringGenerationKeepsFollowingTheNextStreamChunk`: реальный Scaffold.bottomBar меняет высоту 220→80dp, затем последний ответ растёт большим chunk.
3. Root вернул production к исходному `LaunchedEffect(state)` и запустил именно Scaffold case: `ChatListScrollRuntimeTest.scaffoldComposerShrinkingDuringGenerationKeepsFollowingTheNextStreamChunk` (исходное поведение: GREEN), `BUILD SUCCESSFUL in 23s`. Следовательно, реальный экран уже корректно следует за актуальной геометрией; synthetic RED не основание менять рабочую production-логику. Предложенная правка и synthetic regressions удалены; сохранён только реальный Scaffold regression. Production diff `ChatList.kt` полностью отсутствует.

Существующие проверки normal geometry, отключённого auto-scroll и ручного перехода к истории сохранены. `loadingState` и `conversationUpdated` читаются через rememberUpdatedState; новая desktop-функциональность/изменение вида не вводятся.

Гипотеза о неработающем аппаратном Enter снята как основание для дефекта: Moru отдельно имеет desktop shortcut и mobile IME setting; перенос mobile setting использует `ImeAction.Send`/`onKeyboardAction` и уже проверяется `ChatComposerInteractionTest`. Новую desktop-функциональность аудит не предполагает.

## Сохранение и условные значения

Все строки ниже, кроме отдельно отмеченных, сохраняются через `InterfacePageState.updateDisplaySetting` → `SettingVM.updateSettings` → `PreferencesStore.update`. Последний применяет transform к заново прочитанному состоянию внутри `updateMutex` + `dataStore.edit`, затем публикует committed Settings; устаревшая копия всей страницы не перезаписывает чужую правку. `DisplaySetting` сериализован целиком в DataStore и в настройках резервной копии. Покрытие: `MessageDisplaySerializationTest`, `PreferenceStoreMessageDisplayMigrationTest`, `ChatDisplayDefaultTest`; UI читает lifecycle StateFlow/LocalSettings.

Обозначения тестов: **Display** = `MessageDisplayPresentationTest`, **Custom** = `ChatCustomizationPresentationTest` + `ChatCustomizationRuntimeTest`, **Drawer** = `ChatDrawerRuntimeTest`/`ChatDrawerNavigationTest`/`ChatDrawerChromeTest`, **Scroll** = `ChatScrollNavigationTest` + `ChatListScrollRuntimeTest`, **Service** = `ChatDisplayBehaviorTest` + `ChatDisplayBehaviorServiceRuntimeTest`, **Theme** = `InterfaceColorSchemeTest`, **Profile** = `InterfaceProfileDraftRuntimeTest`. Ссылка на тест означает имеющееся профильное покрытие; аппаратные разрешения, вибромотор, сеть и импорт через Android picker требуют проверки на телефоне.

## Полная сверка доступных настроек

Каждая настройка из `InterfaceControls` сопоставлена с потребителем; дополнительные controls страниц также включены. Связанные поля одной функции сгруппированы, чтобы таблица оставалась читаемой.

| Страница / поле | Реальное применение | Сохранение / профильный тест |
|---|---|---|
| Сообщения: `showUserAvatar` | `MessageDisplayPresentation` → `ChatMessageHeader` | Display, Custom |
| `showUserName` | Независимое имя в `ChatMessageHeader`; null наследует showUserAvatar | Display, Custom |
| `showUserTimestamp` | Время пользователя; null наследует legacy showDateTimeInMessage | Display, Custom |
| `showUserMessageActions` | Действия пользователя в `ChatMessageActions` | Display, Custom, `MessageActionsDisplaySettingsTest` |
| `showModelIcon` | Иконка модели в `ChatMessageHeader` | Display, Custom |
| `useAssistantAvatarInHeader` | Аватар именно в `ChatPage.TopBar`/`ChatHeader`, null → true | `ChatHeaderIntegrationTest` |
| `showModelName` | Подпись модели в заголовке сообщения | Display, Custom |
| `showAssistantTimestamp` | Время ассистента; null наследует legacy время | Display, Custom |
| `showProviderInChatMessage` | Провайдер в заголовке сообщения; нет пустой шапки без провайдера | Display, Custom |
| `showTokenUsage` | Статистика сообщения, скрывается во время потока | Display, Custom |
| `showThinkingCards` | `MessagePartsBlock`/timeline, фильтрация reasoning cards | Display, Custom, `ToolTimelineDisplaySettingsTest` |
| `showToolCards` | Timeline и читаемый fallback ошибок при скрытых карточках | Display, `Stage4ToolPresentationRuntimeTest` |
| `showProducedFiles` | `ChatMessage` produced-files strip | Display, Custom |
| `userNickname` | Имя пользователя в сообщениях и нижнем профиле drawer | Profile, `ChatDrawerChromeTest` |
| `userAvatar` (emoji/image) | Аватар пользователя в сообщениях и drawer | Profile, `ChatDrawerChromeLayoutTest`; image picker — Android boundary |
| `showThinkingContent` | Preview reasoning во время потока, `rememberReasoningState` реагирует на изменение | Custom, `ChatTimelinePresentationTest` |
| Рендеринг: `enableDollarLatex` | Markdown parser/display options для `$…$` | Display, Custom; markdown tests |
| `enableLatexRendering` | Markdown math renderer | Display, Custom; markdown tests |
| `enableUserMarkdown` | Реальный plain/Markdown branch пользователя | Custom runtime |
| `enableReasoningMarkdown` | `ReasoningContent` → `MessageTextContent` | Custom runtime |
| `enableAssistantMarkdown` | Реальный plain/Markdown branch ассистента | Custom runtime |
| `codeBlockAutoCollapse`, `codeBlockCollapseLines` | `MarkdownDisplayOptions` → CodeBlock | Custom; `InterfaceNumberKeyboardRuntimeTest` для числового ввода |
| `codeBlockAutoWrap` | Перенос CodeBlock | Display, Custom |
| `showLineNumbers` | Номера строк CodeBlock | Custom |
| Поведение: `autoCloseThinking` | `nextReasoningCardState` после окончания рассуждения | Timeline/Custom tests |
| `collapseThinkingSteps` | Collapse finished timeline steps; null наследует autoCollapseToolSteps | Display, `ToolTimelineDisplaySettingsTest` |
| `autoCollapseToolSteps` | Legacy collapse; control также очищает nullable override | Display, `ToolTimelineDisplaySettingsTest` |
| `showToolSummary` | Сводка результата в карточке инструмента | Display, `Stage4ToolPresentationRuntimeTest` |
| `hideToolResultImages` | Изображения в tool result UI | Display, Custom |
| `insertSuggestionOnly` | `ChatList`: подсказка вставляет draft либо вызывает onSendSuggestion | `ChatSuggestionBubblesTest`, checklist/runtime scope |
| `collapseLongUserMessages`, `userMessageCollapseChars`, `userMessageCollapseLines` | `ChatMessage` collapse; null chars включает измеренные rendered lines | Custom runtime, `MessageBehaviorDisplaySettingsTest` |
| `regenerateDeleteTrailingMessages` | `ChatService.regenerate` читает актуальное значение | Service runtime |
| `confirmAssistantRegeneration` | Подтверждение action перед onRegenerate | Display/Custom, Service |
| `forkKeepMessageVersions` | `ChatService.fork` сохраняет либо выбирает версии | Service runtime |
| `keepThinkingAndToolCardsWhenEditingAssistant` | `ChatInputState` edit/rebuild parts | `ChatInputEditCustomizationTest`, Service |
| `showAppUpdates` | `ChatDrawerChrome` update indicator, учитывается временное отключение проверок | Drawer/Chrome |
| `keepScreenOnDuringGeneration` | `composerSurfacePresentation` → window keep-screen-on; только loading | `ComposerCustomizationTest` |
| `showChatListDate` | `ConversationList` даты строк drawer | Drawer/Chrome/Layout |
| `keepSidebarOpenOnAssistantTap` | `ChatDrawer.navigateChat` + pending reopen request | Drawer navigation/runtime |
| `keepSidebarOpenOnTopicTap` | Тот же маршрут для выбранной темы | Drawer navigation/runtime |
| `keepAssistantListExpandedOnSidebarClose` | Assistant picker сохраняет/сбрасывает expansion при закрытии | Drawer navigation/Chrome |
| `newChatOnAssistantSwitch` | После settings commit новый либо последний чат ассистента | Drawer runtime; null наследует legacy preference |
| `newChatOnLaunch` | `RouteActivity` initial conversation routing | Nullable DisplaySetting; null наследует `create_new_conversation_on_start` |
| `newChatAfterDelete` | Single и batch delete текущего чата в drawer | `ChatDrawerDeleteCoordinatorTest`, Drawer runtime |
| `sendOnEnter` | `ChatInput` mobile IME Send и onKeyboardAction | `ChatComposerInteractionTest` |
| `pasteLongTextAsFile`, `pasteLongTextThreshold` | contentReceiver создаёт файл вместо вставки длинного текста | `ComposerCustomizationTest`; clipboard/Android boundary |
| `conversationSortOrder` | `ChatDrawerVM` query по updated/created и date grouping | `DrawerConversationQueryTest`, `DrawerConversationPresentationTest` |
| `enableAutoChatTitles` | `ConversationTitleGenerator` → fast model либо local title | title generator/Service tests |
| `messageNavButtonsMode`, `showMessageJumper`, `messageJumperOnLeft` | Реальные MessageJumper controls/visibility/placement | Scroll, `ChatScrollNavigationLayoutTest` |
| `enableVolumeKeyScroll`, `volumeKeyScrollRatio` | `ChatList` слушатель RouteActivity, актуальные settings через rememberUpdatedState | Scroll; физические клавиши — device boundary |
| `useAppIconStyleLoadingIndicator` | `RabbitLoading` | Display/Composer tests; статическая связь подтверждена |
| `defaultTTSPlaybackSpeed` | `ui/hooks/TTS.kt` вызывает setSpeed | Отдельный Settings key с clamp .5..2; TTS/provider boundary |
| `ttsOnlyReadQuoted`, `ttsOnlyReadOutsideBrackets` | `ChatMessageActions`, `VoiceMode`, `TTSAutoPlay` | Display/Service tests; TTS audio boundary |
| `autoPlayTTSAfterGeneration` | `TTSAutoPlay` читает updated settings/conversation, исключает active voice session | Display/Service tests; audio boundary |
| Haptics: `hapticsEnabled` | `ProvideAppHaptics` глобальный gate + action gates | `HapticsTest`, header tests |
| `hapticsOnSwitch` | `InterfaceSwitchRow` action gate | `HapticsTest`, `InterfaceSwitchAccessibilityTest` |
| `hapticsOnDrawer` | ChatHeader/drawer action gate | `HapticsTest`, `ChatHeaderLayoutTest` |
| `hapticsOnListItemTap` | Drawer list-item action gate | `HapticsTest`, Drawer tests |
| `hapticsOnCardTap` | Timeline/card action gate | `HapticsTest`, Custom tests |
| `enableMessageGenerationHapticEffect` | `MessagePartsBlock` debounce, updated loading/parts, глобальный gate | `ChatGenerationHapticRuntimeTest` |
| Изображения: `skipCropImage` | `ChatAttachmentPicker`; UI «Обрезать» инвертирует поле корректно | Display serialization; picker boundary |
| Автоповтор: `networkSetting.enableAutoRetry` | `GenerationLoop` network retry policy | Отдельный NetworkSetting через atomic update; network tests |
| Размер: `fontSizeRatio` | `ChatMessage` текст + timeline/reasoning/code options | Display, Custom; layout 1.3 |
| Скролл: `enableAutoScroll`, `autoScrollIdleSeconds` | `ChatList` + `rememberChatAutoFollowReady` (0..64s) | Scroll; настоящий Scaffold resize regression прошёл на исходном production |
| Opacity: `chatInputBackgroundOpacityLight/Dark` | `composerSurfacePresentation` выбирает текущую тему | `ComposerCustomizationTest` |
| Шрифты app: `appFontFamily`, `appCustomFontPath/Name` | `rememberAppFontFamily` → theme Typography | Display serialization; импорт/Google Fonts tests |
| Шрифты code: `codeFontFamily`, `codeCustomFontPath/Name` | `rememberCodeFontFamily` → Markdown code font | Display serialization; импорт/Google Fonts tests |
| Шрифты chat: `chatFontFamily`, `chatCustomFontPath/Name` | `ChatFontProvider`/ChatMessage, reasoning наследует family | Display, Custom; импорт/Google Fonts tests |
| Google Fonts catalog/search/license/apply | `SettingInterfaceGoogleFontsPage`, apply целит app/code/chat; отмена освобождает pending download | `GoogleFontsCatalogTest`; сеть/picker отдельно |
| Фон global: `chatBackgroundEnabled`, `chatBackgroundImagePath` | `AssistantBackground`, fallback при отсутствии assistant image | `ChatBackgroundPresentationTest`, `ChatBackgroundRuntimeTest` |
| Фон assistant: `Assistant.background`, `backgroundOpacity`, `useGradientBackground` | `AssistantBackground` для актуального ассистента/чата | Хранится в assistants, atomic map transform; Background runtime |
| `chatBackgroundMaskStrength` | `AssistantBackground` маска | Background tests |
| `enableBlurEffect`, `backgroundEffectType` | Chat background blur/glass rendering | Background/Composer/Custom tests |
| Стиль: `chatMessageBackgroundStyle` | `ChatMessageSurface`/resolveMessageBubbleStyle | Custom/Display serialization |
| `userChatBubbleStyleOverrides`, `assistantChatBubbleStyleOverrides` | Реальные цвет background/text/border отдельно light/dark; opacity, blur, cornerRadius, borderWidth | Custom/Display serialization |
| `assistantBubbleFitContent`, `assistantBubbleSplitParagraphs` | `MessageText` layout, split preserves links/URIs | Custom runtime |
| `showAssistantBubble`, `bubbleOpacity` | Legacy/default ChatMessageSurface | Display/Custom |
| Стекло: `glassTheme`, `glassPreviousMessageStyle` | Обратимый preset; theme возвращает точный backup style/overrides | Display serialization/Custom |
| `glassEconomy` | `ChatPage`/bubble backdrop blur gate | Custom/Composer tests; device GPU performance отдельно |
| Язык: `appLanguageTag` | `RouteActivity` → syncInterfaceAppLanguage/AppCompat | `InterfaceAppLanguageTest`; nullable сохраняет legacy/system |
| Theme: `themeId`, `customThemes`, `dynamicColor` | `settingsBaseColorScheme` → `RikkahubTheme` | Отдельные Settings fields; Theme tests |
| Theme: `colorMode` LIGHT/DARK/SYSTEM | `rememberCurrentColorMode` → RikkahubTheme | SharedPreferences `colorMode`; live flow listener |
| Theme: `amoledDark` | `rememberAmoledDarkMode` → black surface/background только dark | SharedPreferences `amoledDark`; Theme tests |
| `usePureBackground` | `effectiveDisplayColorScheme` white/black surface/background | Theme |
| `useLayeredSurfaces`, `useLayeredSheetTiles` | Отдельные container roles для поверхностей/листов | Theme |
| Hub links Notifications/Network | Existing preferences pages, реальные маршруты Screen.* | Не новые dummy controls; уведомления/сеть вне UI rendering audit |

## Moru layout и длинный чат

`ChatHeaderLayoutTest` параметризует 320/411dp и fontScale 1/1.3: двухстрочная шапка, длинные имена, отдельные rename/model/avatar gestures, workspace capsule 148×40dp, browser без workspace, big screen, read-only и прозрачность над wallpaper в светлой/тёмной теме. В production нет непрозрачной плашки шапки. `ChatHeaderIntegrationTest` связывает реальные настройки и навигацию.

Drawer: `ChatDrawerChromeLayoutTest`, `ChatDrawerFolderLayoutTest`, `InterfaceNavigationRowLayoutTest`/`InterfaceSwitchAccessibilityTest` проверяют узкие и увеличенные layouts; `ChatDrawerRuntimeTest` проверяет реальные repository updates, selection, folder move/delete, assistant switch после сохранения. Date grouping реагирует на календарный день/зону; sorting/search имеет реальные paging queries.

Длинный чат использует LazyColumn с ключами message node и shared indexes; settings/providermap/assistant memoization не теряет dependencies. При streaming некоторые summary/size-info вычисления заново проходят messageNodes (например `rememberConversationSizeInfo`, drawer current metadata). Это потенциальная область профилирования CPU на телефоне, не доказанный функциональный дефект; тест по количеству вызовов/искусственному wall-clock лимиту не добавлялся.

## Центральные команды

Реальный Scaffold regression и соседние текущие проверки:

```sh
./gradlew :app:testDebugUnitTest --tests 'me.rerere.rikkahub.ui.pages.chat.ChatListScrollRuntimeTest' --no-configuration-cache
```

Предложенный короткий набор аудита (без перезаписи screenshots):

```sh
./gradlew :app:testDebugUnitTest --tests 'me.rerere.rikkahub.ui.screenshot.ChatHeaderLayoutTest' --tests 'me.rerere.rikkahub.ui.screenshot.ChatHeaderIntegrationTest' --tests 'me.rerere.rikkahub.ui.pages.chat.ChatDrawer*' --tests 'me.rerere.rikkahub.ui.pages.chat.ChatListScrollRuntimeTest' --tests 'me.rerere.rikkahub.ui.pages.setting.Interface*' --tests 'me.rerere.rikkahub.ui.components.message.ChatCustomization*' --tests 'me.rerere.rikkahub.ui.theme.InterfaceColorSchemeTest' --tests 'me.rerere.rikkahub.data.datastore.MessageDisplaySerializationTest' --no-configuration-cache
```

## Независимое ревью соседних root diff

Прочитаны текущие diff `ai/util/FileEncoder.kt`, `common/http/SSE.kt`, новые `FileEncoderDataUrlTest`/`SseFlowTest` и provider regressions Claude/Gemini. Блокирующих замечаний нет. `withPrefix=false` удаляет canonical data URL prefix, provider tests проверяют настоящий payload; default OpenAI prefix сохраняется. `trySendBlocking` корректен на reader thread OkHttp и даёт backpressure вместо потери audio events. Закрытие callbackFlow при отмене освобождает заблокированный send, затем `awaitClose` вызывает eventSource.cancel. Optional улучшение теста — наблюдать завершение transport callback thread при cancellation; текущий тест проверяет завершение collector. Неканонические percent-encoded data URL не входят в tested base64 path и в этом audit не расширялись.

## Ограничения

Новый Gradle запуск агентом не выполнялся; root централизует результаты. Synthetic RED auto-follow не воспроизводится в реальном Scaffold и снят; baseline Scaffold проверка зелёная. Новых подтверждённых багов в настройках, прозрачной шапке, drawer или auto-follow нет. Аппаратные IME/volume keys, GPU blur на длинном чате, picker process recreation и физический виброотклик требуют устройства. Архивные screenshots не обновлялись, commit/push не делались.
