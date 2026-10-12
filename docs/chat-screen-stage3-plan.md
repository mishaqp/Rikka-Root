# Правки этапа 2 и этап 3: временная шкала Moru

> Исполнение: superpowers:subagent-driven-development. Пользователь прямо разрешил исправления отдельным первым коммитом и сразу этап 3; публикация готового пакета, зелёный Daily Build и остановка для телефона.

**Goal:** вернуть списку ширину с 16dp по краям, исправить inline-code chrome, затем перенести timeline инструментов/размышлений Moru в Compose.
**Architecture:** существующее `groupMessageParts()` остаётся источником порядка Content/Thinking/Chart. Чистая UI-проекция и Compose-компоненты потребляют прежние UIMessagePart и callbacks, не меняют историю, выполнение, разрешения, ConversationConfig или backup schemas.
**Tech Stack:** Kotlin, Compose, MaterialTheme, HugeIcons, JUnit/Robolectric Native Graphics/Roborazzi.
**Spec:** сообщения пользователя этой сессии; docs/chat-screen-audit.md; docs/chat-screen-redesign-plan.md как контекст; /workspace/refs/Moru как визуальный эталон.

## Global Constraints

- Сначала отдельный коммит двух ошибок этапа 2 с тестами и самостоятельным AFTER; затем сразу реализация этапа 3. Один push готового пакета по AGENTS.md, при падении CI исправлять и повторять автономно.
- Контент списка имеет 16dp по обоим краям независимо от jumper on/off и стороны. Навигация overlay без резервной колонки, 36dp, surface alpha0.85 light/0.4 dark и тонкая рамка, скрытие после1500ms покоя. Временно перекрывать край текста допустимо.
- Inline-code: фон из реальных границ текста на каждой визуальной строке, horizontal padding3dp (в пределах запрошенных2–4), без расширения/смещения по вертикали; native characters/wrap/selection/links и streaming нового текста сохранить.
- Timeline: одна непрозрачная MaterialTheme-карточка на соседний ThinkingBlock, radius16dp, иконка18dp в колонке24dp, gap8dp, line1dp/rail gap3dp, vertical step8dp, card padding8×4dp. Превью результата до4 строк, details по тапу, изображения и Показать ещё N.
- Pending approval и неотвеченный ask_user видимы при любом collapse; ✓ Once и ✗ deny-reason прямо в шаге; Для этого чата/Всегда в sheet/menu через прежние ToolPermissionPolicy/ToolApprovalSubmission. MCP redaction и workflow preview не обходить.
- Сохранить порядок text→tool→chart→text, каждый специализированный renderer/fallback/skill/ask_user/media и callback; никакого повторного запуска инструмента из Compose.
- Глубокое мышление:18dp иконка,13sp заголовок,12.5sp/1.32 preview до100dp, авто-follow, таймер по реальному createdAt/finishedAt; existing showThinkingContent/autoCloseThinking действуют независимо.
- showToolSummary управляет завершённой сводкой «Обработано», hideToolResultImages скрывает только inline thumbnails, autoCollapseToolSteps управляет автоматическим сворачиванием; все три поля уже есть в сериализуемом DisplaySetting: подключить к UI и добавить три switch в тот же SettingPreferencesUIPage. Старые пользовательские переключатели и backup значения сохранить.
- После завершения сворачивать ✓ Обработано · N с при подлинном доступном времени; как в Moru использовать число шагов при отсутствии timestamp span. Время сообщения нельзя выдавать за длительность отдельного tool-блока.
- Строка созданных/изменённых файлов использует фактические существующие успешные результаты и прежний workspace/local open/export/share route; не выводить выдуманные paths/revisions/diffs из shell text.
- Все новые UI-строки по-русски через stringResource, новые default strings translatable=false. Цвета MaterialTheme, HugeIcons. Blur/Glass/background animations/wallpapers/minimap отложены.
- Локально affected compile/unit; APK/full test/lint через Daily Build. 250 функциональных checklist-строк остаются. Исторические PNG не перезаписывать; record только явным новым флагом, CI глобальный record выбирает Verify.

## Review Focus

- Узкий320dp/411dp, font1/1.3, avatar on/off, short/long/attachments: bubble/header/actions и широкая tool-card заканчиваются16dp от края; overlay видимость не меняет bounds.
- AST и HTML inline-code su/line wrap/mixed styles/Unicode/RTL: background совпадает с glyph span, соседние символы не закрыты, native selection и stream fade остаются.
- 3+ timeline steps, несколько pending в скрытом префиксе, denied/ask_user/read-only/loading/смена ветки: важные формы доступны, approval не дублируется и не расширяет политику.
- Нет timestamps, root execution checkpoint, server FAILED и текстовый/truncated result: статус/секунды не выдумывать, полные details и specialized preview доступны.
- Legacy JSON, каждый tools/reasoning switch, созданный/изменённый файл, отсутствиеworkspace/exportroute: настройки реально меняют UI, файл не получает неверный opener.

## Task 1: исправления геометрии и inline-code

Files: ChatList.kt, ChatScrollNavigation.kt, MarkdownStyledText.kt; related runtime/layout tests; ChatBeforeScreenshotsTest.kt/ChatStage1ScreenshotPaths.kt/tests, app/build.gradle.kts и screenshot README.
Interfaces: overlay использует прежний ChatScrollNavigationVisibility без list padding; native inline geometry принимает TextLayoutResult/range и сохраняет AnnotatedString. Новая live AFTER chat-after-stage2-fixes, прежний chat-after-stage2 архив448PNG; явный chat.stage2fixes.record.
- [x] Добавить реальные production-page bounds assertions и cases user avatar on/off × short/long, attachments, fullwidth tool, overlay visible/hidden и su/wrap AST+HTML; показать RED до production fix.
- [x] Удалить резервную колонку, сохранить прежние flags/позицию/таймер/fade/scroll-capture. Исправить inline per-line range bounds и padding3dp безverticalinset; при соседнем тексте безопасно ограничить fill его геометрией.
- [x] Focused GREEN, сохранить native characters/streaming tests; ROOT выполняет общий record+Verify, просмотр всех тем/font scales и archive hash/mtime.
- [x] Независимый scoped review, отчёт исправлений, ПЕРВЫЙ commit без stage3 implementation.

## Task 2: чистая timeline-проекция и основной renderer

Files: новые message/ChatTimelinePresentation.kt, ChatToolTimeline.kt/ChatTimelineStep.kt (focused responsibilities); ChatMessage.kt integration; ChatMessageTools.kt и ui/ChainOfThought.kt только нужные визуальные интерфейсы.
Interfaces: pure presentTimeline(ordered ThinkingBlock steps, ToolDisplayOptions, loading, stable message/block identity) возвращает steps/status/visible indices/hidden count/foldability/actual duration. Renderer принимает существующие callbacks и display flags; Reasoning delegates в существующий ChatMessageReasoningStep, который restyled Task3. ApprovedActions остаются ToolApprovalAction/Submission.
- [x] RED tests contiguous order +chart boundary +stable identities +pending/ask_user always visible +error/root-checkpoint/server statuses +collapse/settings/duration fallback.
- [x] Реализовать card/rail/icon18/status shimmer/last-two/showMore/processed summary и safe restore expansion. По tap полный tool-sheet; ✓✗ inline, остальные scopes и denial в прежних UI gates; preview4lines и photos toggle без потери details. showToolSummary=false оставляет шаги доступными и отключает completed-summary fold; это не переключатель удаления result preview. Ошибки/отказы/неизвестный checkpoint, pending и ask_user не прятать.
- [x] Центральные русские display-названия для всех зарегистрированных групп инструментов, fallback Инструмент +rawname в details; имена/JSON/schema регистрации не менять.
- [x] Реальные runtime callbacks/read-only/loading/дубли +focused GREEN; scoped review.

## Task 3: размышления и файл-строка

Files: ChatMessageReasoning.kt; ChatMessageEditedFiles.kt и небольшой pure ProducedFilesPresentation.kt при необходимости; новые соответствующие tests/resources.
Interfaces: reasoning сохраняет extension-интерфейс ChainOfThoughtScope.ChatMessageReasoningStep без второй карточки/дублирующей иконки; generic Clock boundary по умолчанию Clock.System для подлинного времени и воспроизводимого снимка; elapsed из Reasoning.createdAt/finishedAt, settings из ToolDisplayOptions. Файлы consume реальные UIMessagePart успешных workspace/local результатов, produce identity/path/action-kind и labels, existing open/export/share callbacks.
- [x] RED settings/timer/preview/auto-close и actual files success/failure/dedupe/routes; не скрывать details/selection при stream end.
- [x] Глубокое мышление·таймер, preview100dp с auto-follow/fade12/28dp и13/12.5sp типографикой; сохранить click expand, visual regex, финальную selection.
- [x] Компактная created/changed-files строка: корректные пути/summary/dedupe/show-more/sheet, прежние export/share; no fake metadata.
- [x] Focused GREEN и scoped review.

## Task 4: существующая страница настроек

Files: SettingPreferencesUIPage.kt, MessageDisplayPresentation.kt (только mapping при необходимости), XML strings и settings/model/runtime tests.
- [x] RED реальные toggles showToolSummary/hideToolResultImages/autoCollapseToolSteps и независимость thinking flags; legacy defaults/roundtrip сохранить.
- [x] Три switch отдельным блоком того же экрана, atomic current.copy; подключить к actual timeline/processed summary/tool images/collapse.
- [x] Focused GREEN, scoped review; никакой новой страницы.

## Task 5: screenshots, итоговое review и сдача

Files: ChatBeforeScreenshotsTest.kt/fixture/path guard/tests/build record property, README, docs/chat-screen-stage3-report.md.
- [x] Live chat-after-stage3 +явный chat.stage3.record; stage2-fixes архив immutable. Новые actual page cases для loading/mixed/summary/expanded/pending in prefix/all scopes/deny/ask_user/MCP/images/details/created files/reasoning/settings иtext→tool→chart→text.
- [x] Focused compile и related function/config/approval/queue/voice tests; record, отдельный Verify с CI globalrecord, actual/diff review4themes×2fonts; все исторические hash/mtime unchanged.
- [x] Независимый whole-branch review; Moru→Kotlin отчёт, actual data limitations/phone checklist; второй commit.
- [ ] Push, exact-SHA Daily Build success, подпись/APK/artifacts; при failure читать log/чинить/повторять самостоятельно.
- [ ] Final ссылки/commits/что проверить; ОСТАНОВКА до телефона, этап4 не выполнять.
