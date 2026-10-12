# Отчёт этапа 2: сообщения Moru и настройки отображения

Этап 2 переносит сообщения пользователя и ассистента, streaming, код, таблицы, источники, перевод и статистику токенов из Moru в native Jetpack Compose. Границы заданы [планом текущей задачи](chat-screen-stage2-plan.md) и [аудитом экрана чата](chat-screen-audit.md). Пять исправлений шапки и ввода вынесены в первый коммит `5851edc3` и описаны в [отчёте правок этапа 1](chat-screen-stage1-corrections-report.md).

## Реализованные изменения

- Пользовательское сообщение выровнено справа, пузырь имеет radius 16dp и сохраняет `bubbleOpacity`. Длинный текст сворачивается до viewport высотой девяти строк текущего шрифта: измеряется реально отрисованный Markdown при доступной ширине, а не число символов или переносов исходника. Нижний fade скрывает край; «Развернуть» и «Свернуть» обратимы, включая read-only историю.
- Ассистент по умолчанию отображается без пузыря. `showAssistantBubble` включает radius 16dp и `surfaceContainerHigh` с выбранной непрозрачностью. Цвета берутся из `MaterialTheme`, а пользовательский tint адаптирован к светлой и тёмной теме.
- Заголовок сообщения использует icon/avatar 32dp, имя 13sp и время 11sp. `showModelIcon`, `showModelName`, `showDateTimeInMessage`, пользовательский avatar/nickname и их прежние условия действуют независимо.
- Во время ответа fade применяется только к добавленным символам; ранее показанный текст остаётся видимым. До первого содержимого отображаются три анимированные точки. Streaming использует прежние сообщения и обновления истории.
- Inline-код получает rounded-фон и границу за настоящим текстом в AST и HTML Markdown-пути. Сохраняются выделение, переносы, ссылки и формулы. Fenced-код сохраняет язык, подсветку, copy, wrap, collapse, номера строк, preview и edit; HTML сохраняет свои штатные элементы управления.
- Таблицы получают rounded-контейнер, отдельную шапку и горизонтальную прокрутку. Copy переносит табличный текст, CSV сохраняется через существующий системный выбор файла; кавычки, запятые и переносы экранируются. Содержимое ячеек продолжает использовать rich renderer.
- Источники собраны в pill со значками и количеством; нажатие открывает настоящий лист источников. Карточки используют доступные `UrlCitation` title/URL/domain, порядок и переходы по ссылкам сохраняются.
- Перевод оформлен отдельным раскрываемым блоком с состоянием ожидания. Выбор языка, callback перевода, Markdown и цитаты сохранены. Таблицы и перевод наследуют выбранный шрифт сообщения; сохранённый масштаб применяется также к inline-коду, который сохраняет моноширинный шрифт.
- Русская строка токенов управляется `showTokenUsage` независимо от пользовательской панели действий. Используются фактические prompt/cache/completion/total и доступное время сообщения; скорость рассчитывается только при положительных completion и duration. Отсутствующие данные провайдера не заменяются оценкой.

`MessageDisplayPresentation` читает существующий `DisplaySetting`; разрешённость действий остаётся в `ChatMessagePresentation`. Выбранные альтернативы, checkpoint, read-only, loading, copy/select/edit/delete/regenerate/fork/share/favorite/TTS/Web preview и callbacks истории сохраняют прежние условия. При скрытой пользовательской панели действий переключение ветвей остаётся доступным; раскрытие текста не включает режим редактирования сообщения.

## Настройки и совместимость

В существующий экран настроек отображения добавлен блок «Сообщения пользователя» с тремя элементами управления: сворачивание длинных сообщений, число видимых строк 1–30 (default 9) и показ действий пользователя. Существующие настройки пузырей/непрозрачности, avatar/name/time/token, reasoning, code wrap/collapse/line numbers, LaTeX и шрифта продолжают работать.

Для новой установки `showUserAvatar=false`. Миграция DataStore выполняется до прежних version migrations: непустое старое хранилище без поля сохраняет прежнее неявное `true`; явно сохранённые `true` и `false` не меняются. JSON старых резервных копий обрабатывается до десериализации, включая отсутствие всего `displaySetting`; тот же путь применяется к отложенному восстановлению после перезапуска. Остальные существующие значения и новые поля сохраняются при roundtrip.

`showToolSummary`, `hideToolResultImages` и `autoCollapseToolSteps` подготовлены как сериализуемые настройки и присутствуют в presentation-модели. Элементы управления и применение к новому timeline откладываются до этапа 3. Текущие tool/reasoning/approval/ask_user/media/chart renderers и система разрешений продолжают работать по прежним правилам.

## Moru → Kotlin: источники и адаптация

Moru в `/workspace/refs/Moru` служит эталоном поведения; Flutter/Dart переписан на Kotlin с настройками, данными и callbacks Rikka-Root.

| Источник Moru | Файл Rikka-Root | Перенесено / адаптировано |
|---|---|---|
| `lib/features/chat/widgets/chat_message_widget.dart` | `ChatMessage.kt`, `ChatMessageSurface.kt`, `ChatMessageAvatar.kt`, `MessageDisplayPresentation.kt` | Пузырь 16dp, icon 32dp, имя 13sp, время 11sp; MaterialTheme и существующие display flags. |
| `chat_message_widget.dart`: user collapse и streaming | `CollapsibleUserMessageText.kt`, `ChatMessageStreaming.kt`, `MarkdownStyledText.kt`, `ChatWaitingPresentation.kt` | По ТЗ viewport 9 масштабируемых строк вместо ограничения исходных символов; fade новых glyphs 260ms, три точки с циклом 1100ms. |
| `lib/shared/widgets/markdown_with_highlight.dart` | `Markdown.kt`, `MarkdownNew.kt`, `MarkdownStyledText.kt`, `HighlightCodeBlock.kt`, `MarkdownTableBlock.kt` | Native AST/HTML, inline radius 6dp, таблица radius 12dp; selection, элементы управления кодом, copy/CSV и форматированные ячейки. |
| `chat_message_widget.dart`, `lib/features/chat/widgets/citation_sources_sheet.dart` | `ChatMessageCitations.kt` | Summary pill radius 20dp и текст 12sp, source card radius 12dp, title 14sp; используются доступные аннотации Rikka. |
| `chat_message_widget.dart`: translation | `ChatMessageTranslation.kt` | Раскрываемый блок с заголовком 13sp; прежние language dialog, callback, Markdown и состояние загрузки. |
| `lib/features/chat/widgets/token_display_widget.dart` | `ChatMessageNerdLine.kt` | Статистика 11.5sp, compact counts, скорость/время; русские подписи и фактический `TokenUsage`. |
| `lib/features/settings/pages/message_style_settings_page.dart`, `message_style_rows.dart` | `PreferencesStore.kt`, `SettingPreferencesUIPage.kt`, display migrations | Настройки в прежнем экране, три новых элемента управления сообщениями пользователя и сохранение legacy/default/backup значений. |

## Проверки и скриншоты

Итоговая локальная компиляция и проверка с флагом CI `-Proborazzi.test.record=true` завершились `BUILD SUCCESSFUL`: **632 теста в 46 классах, 0 failures/errors/skipped**. Это 592 focused-теста и 40 Roborazzi Verify-тестов. Record выполнен отдельным запуском: 40 тестов, 448 PNG. Сравнение итоговых SHA-256 и mtime подтвердило, что Verify не перезаписал ни 448 текущих, ни 736 исторических PNG. Результаты подтверждены итоговыми XML, `/tmp/chat-stage2-ci-verify-results.json`, `/tmp/chat-stage2-ci-verify.log` и `/tmp/chat-stage2-record.log`.

| Проверка | Содержательное покрытие / статус |
|---|---|
| `:app:compileDebugKotlin`, `:app:compileDebugUnitTestKotlin` | Успешны после итоговых исправлений. |
| Focused unit/Compose | Вошли в успешный итоговый набор: `MessageDisplayPresentationTest`, surface/viewport/waiting, token/translation/source и rich runtime/presentation tests; реальные результаты моделей, semantics и callbacks. |
| Settings и migration | Вошли в успешный набор: `ChatDisplayDefaultTest`, `MessageDisplaySerializationTest`, `PreferenceStoreMessageDisplayMigrationTest`, `MessageBehaviorDisplaySettingsTest`; flags, defaults, legacy/explicit JSON, backup/restore и элементы управления. |
| Контракты экрана | Существующие 250 строк функционального checklist и branch/actions/render/tool tests прошли в focused-наборе; не заменяют выполнение инструментов на устройстве. |
| Roborazzi record и отдельный Verify | AFTER [chat-after-stage2](../app/src/test/screenshots/chat-after-stage2): **448 PNG = 56 states × 4 themes × fontScale 1.0/1.3**. Отдельный Record: 40 тестов. Итоговый Verify: 40 тестов в составе набора 632 с флагом CI, 0 failures/errors/skipped. |
| Защита архивов | **208 BEFORE + 264 stage1 + 264 stage1-fixes = 736 PNG**: SHA-256 и mtime не изменились. Все 448 AFTER также остались неизменными при итоговом Verify с общим CI record flag. |
| Независимый review | `review_stage2` и `review_settings_stage2` завершены без блокирующих замечаний после исправлений rich typography. |
| Actions и APK | Публикация выполняется через [Daily Build](https://github.com/mishaqp/Rikka-Root/actions/workflows/daily-build.yml); точные SHA/run, `ui-screenshots` и подписанный APK подтверждаются в итоговом сообщении после публикации. |

Review выявил потерю сохранённого шрифта в таблицах и переводе и масштаба в таблицах, переводе и inline-коде; применение настроек исправлено до итогового focused-запуска. Сбой streaming runtime-теста был связан с синхронизацией snapshot состояния fixture: исправлено ожидание обновления перед измерением кадра. Production fade новых символов при этом не менялся. Screenshot-fixtures дополнены внешней границей BackupManager для настоящего read-only UI и поиском collapse viewport в unmerged semantics; проверки высоты, ветвей, сохранения и запрета редактирования сохранены. Перед обычной съёмкой реально завершаются Compose/Android таймеры и проверяется отсутствие всех четырёх временных кнопок прокрутки. Coil EventListener и Compose IdlingResource дожидаются терминального success/error/cancel настоящих image-запросов; offline favicon transport остаётся 404. Native ripple «+» завершается до замороженного кадра работающего инструмента. Pending translation монтируется при уже остановленных часах после завершения предыдущей навигации, затем измеряется в заданной фазе. Замороженные streaming/running/translation кадры сохраняют параметры анимаций; comparator не ослаблен.

Текущие screenshots снимают production `ChatPage` и native renderers через Robolectric SDK 35 Native Graphics, ru-RU, 411×891dp. К прежним 33 states добавлены 23: defaults/metadata/bubble opacity, collapse/expanded/off, stream/append/waiting, inline/fenced/HTML/table, sources pill/sheet и translation expanded/collapsed/pending. В отдельных runtime-тестах проверяются узкий экран, fontScale, реальные элементы управления, viewport bounds, branch callbacks и read-only/loading условия. Независимая визуальная проверка четырёх тем при fontScale 1.3, long text, metadata flags, code/table/sources и промежуточных streaming кадров не выявила видимых блокеров. Исторические каталоги защищены от записи; обычный CI выбирает Verify даже при глобальном `roborazzi.test.record=true`.

## Ограничения и отложенные работы

Этап 3, новый tool timeline, blur/Glass, обои и мини-карта не выполнялись. Backend, база/история, `ConversationConfig` и Android permissions ради этого оформления не менялись. Известная ёмкость контекста уже берётся из `ModelRegistry.MODEL_CONTEXT_LENGTH` исправлениями этапа 1; неизвестная отображается compact count с реальными prompt tokens, без выдуманного лимита.

JVM/native fixtures проверяют Compose-вёрстку и callbacks. Они не доказывают OEM/GPU, производительность длинной истории, реальную скорость streaming, IME/rotation/TalkBack, clipboard/SAF на устройстве, HTML/Mermaid WebView pixels, медиавоспроизведение, сеть, разрешения и root/Termux/SSH/MCP исполнение. HTML capture показывает настоящий код и элементы управления; сам preview требует телефона.

## Что проверить на телефоне

1. Light/Dark/AMOLED/Dynamic, 320/411dp, увеличенный и пользовательский шрифт, RTL/поворот/IME: сообщения, header, composer, sheets и selection доступны без перекрытий.
2. Каждый существующий display toggle: user avatar/nickname, assistant icon/name/time, bubbles/opacity, tokens, reasoning/auto-close, LaTeX, code wrap/collapse/line numbers, font family/ratio; затем перезапуск. Старые явные значения должны сохраниться.
3. Новая установка без user avatar; обновление старой установки с неявным/явным avatar; локальный/WebDAV/S3 backup и восстановление, включая завершение pending restore после перезапуска. Проверить остальные сохранённые значения.
4. Все три настройки сообщений пользователя: короткий и длинный Markdown, 9 строк по умолчанию, другие значения, большой шрифт, раскрытие/повторное сворачивание и collapse off. Copy/select берут полный текст; раскрытие не открывает редактор.
5. Начало streaming с тремя точками, несколько новых фрагментов, остановка, пустой завершённый ответ и ошибка. Уже показанный текст не мигает; token row действует при скрытых user actions.
6. Inline/fenced/HTML code, длинные строки, выделение, copy, preview/edit, wrap/collapse/номера строк, таблицы с форматированием, ссылками и формулами, copy/CSV через системный picker.
7. Sources pill и sheet, порядок/количество, длинные title/URL, открытие ссылки; перевод, выбор языка, pending, раскрытие/сворачивание и rich content со своим шрифтом.
8. Альтернативы и branch selector при user actions on/off, read-only/loading/checkpoint; copy/select/edit/delete/regenerate/fork/share/favorite/TTS/Web preview, attachments/media/chart, approvals и ask_user.
9. Исправления первого коммита: центрированный avatar16dp + длинное название; первая модель20dp/slot32dp в composer, tap/longpress; known context ring/unknown count/0; placeholder «Сообщение»; scroll buttons36dp, обе стороны и скрытие через1500ms. Проверить drawer/rename/menu/new chat/workspace, очередь/voice/Stop и volume scroll.

После зелёного Daily Build работа останавливается до проверки телефона. Новые улучшения сверх этой задачи и этап 3 требуют отдельного задания.
