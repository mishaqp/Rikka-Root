# Этап 0: исправления аудита чата

> Для исполнителей: использовать superpowers:subagent-driven-development или superpowers:executing-plans. Исполнять шаги RED → GREEN → commit. Требования пользователя имеют приоритет над процессными рекомендациями skills.

**Goal:** устранить девять перечисленных ошибок и зафиксировать принятое решение SET-01 перед редизайном.

**Architecture:** сохранить текущую Compose-вёрстку и форматы резервной копии; защищать историю на границах загрузки, мутаций и Room, метаданные обновлять отдельными колонками, бюджет использовать из существующего TokenBudgetStore, настройки изменять внутри транзакции DataStore.

**Tech Stack:** Kotlin, Compose, Room/DataStore, корутины, JUnit/MockK/Robolectric/Roborazzi; APK через Daily Build.

**Spec:** запрос пользователя «Этап 0 перед редизайном» от 2026-10-11; исходный [аудит](../../chat-screen-audit.md), коммит `8acbc537`.

## Global Constraints

- Редизайн пока НЕ начинать.
- Для каждого бага: сначала тест, воспроизводящий проблему (падает), затем исправление (тест проходит). Отдельный коммит на каждый пункт.
- SET-01 — НЕ исправлять: обычные ключи и пароли остаются как в upstream и входят в резервную копию; существующие KeyStore-хранилища MCP, SSH, Codex OAuth, JS-навыков не трогать.
- Эталонные скриншоты не должны измениться (кроме R-37 — обновить осознанно).
- Один push, зелёный Daily Build, отчёт по каждому пункту + что проверить на телефоне. Остановиться после сдачи.
- Runtime-регрессии проверяют настоящий production-код. Ошибка компиляции из-за ещё не существующего API не считается воспроизведением бага.
- Исходные «ДО» доступны в коммите `8acbc537`; все намеренные изменения PNG перечислить и просмотреть.
- Локально только компиляция затронутых модулей и связанные unit/UI-тесты. Полные tests/lint/release/signature — Daily Build.

## Review Focus

1. Неполная история не теряется через память, очередь, fork, Undo, tool approval или прямой вызов repository; экспорт должен сохранить незагруженные данные.
2. Поздние metadata responses, streaming и final saves не меняют чужие поля и не отменяют ручное переименование.
3. OCR/перевод разделяют долговечный ledger с основным запросом; failed/cancelled submitted requests учитываются, ошибки бюджета не проглатываются.
4. Файлы сохраняются при вложенных tool outputs, неактивных branches, других сохранённых/активных чатах и неопределённом результате проверки ссылок.
5. Настройки сохраняют несвязанные nested/list/draft изменения, single-key writes, миграции, нормализацию и прежнюю переносимость бэкапа.

## Task 1 — SVC-04: неполная история только для чтения

**Files:** `data/repository/ConversationRepository.kt`, `data/model/Conversation.kt`, `service/ConversationSession*.kt`, `service/ChatService.kt`, `ui/pages/chat/ChatVM.kt`, `ChatPage.kt`, соответствующие History/delete/export callers и строки ресурсов; новые repository/service/UI regression tests.

**Interfaces:** добавить transient `HistoryLoadState` (COMPLETE/NOT_LOADED/INCOMPLETE) к Conversation; полная запись допускает только COMPLETE. Service/VM используют тот же признак до любых побочных действий. Метаданные и файл-копирование следующих задач сохраняют/учитывают его.

- [x] RED: реальная Room DB со 130 узлами, DAO бросает SQLiteBlobTooBigException либо IllegalStateException на странице offset=64; попытка текущего full save не должна менять ни один исходный row/title. Проверить фактическое падение (сейчас остаётся 66 rows).
- [x] RED: реальные service delete/fork/update операции не должны менять память, удалять файлы/исходные rows либо выдавать grants после неполной загрузки; UI должен показывать точный текст и кнопку экспорта.
- [x] GREEN: прекратить загрузку на первом failed page, отметить неполный prefix; блокировать insert/full rewrite и мутации до state/file/provider/grant effects. Summary/placeholder не считать полным пустым чатом; перед отправкой дождаться initialization.
- [x] GREEN: условный статус «Часть истории не загрузилась, чат открыт только для чтения» и «Экспорт». Recovery использует существующий raw DB+files backup; явно обозначить, что это полная резервная копия, а не частичный текстовый экспорт. Обычные чаты не меняют вёрстку.
- [x] VERIFY: отсутствие silent skip/бесконечного loop; успешный reload можно записывать, старый incomplete snapshot нельзя; History Undo/delete и прямые writers fail closed; targeted tests и нормальные screenshots.
- [x] COMMIT: `fix(chat): preserve incomplete histories in read-only mode`.

## Task 2 — SVC-01: атомарные title/suggestions

**Files:** `ChatService.kt`, `ConversationSession.kt`, `ConversationRepository.kt`, `ConversationDAO.kt`, при необходимости узкий FTS metadata update; runtime service/DAO tests.

**Interfaces:** существующий `updateTitleIfUnchanged(id, expectedTitle, title)`; новый column-only suggestions update. Merge в latest session под её lock, без загрузки Room snapshot в память и без node rewrites.

- [x] RED: задержанный fast provider; до ответа добавить текущий stream и ручное название через настоящие public service operations. После title/suggestions все message IDs/text и ручное название должны сохраниться. Проверить падения.
- [x] GREEN: expectedTitle взять до request; DAO CAS и live-state merge менять только свои поля. Suggestions reset также atomic. Deleted row не воссоздавать. Согласовать с full/checkpoint saves, чтобы старый save не вернул прежние metadata.
- [x] VERIFY: deterministic barriers для rename, stream и save ordering; Room rows/config/folder/pin/assistant неизменны. Сохранить существующие fast-model/budget/fallback и FTS title semantics без переиндексации старой истории.
- [x] COMMIT: `fix(chat): update generated metadata without replacing history`.

## Task 3 — SET-01: решение пользователя

**Files:** `AGENTS.md`, `docs/chat-screen-audit.md`.

- [x] Внести дословное исключение для обычных настроек и устранить абсолютную формулировку «только браузерное исключение»; прежние browser backup ограничения сохранить.
- [x] Пометить SET-01 «принято пользователем», убрать требование переноса обычных настроек в KeyStore. Production/backup/vault не менять; TDD здесь не нужен, поскольку это не исправление бага.
- [x] VERIFY: точность текста и отсутствие source изменений.
- [x] COMMIT: `docs(security): accept portable upstream settings secrets`.

## Task 4 — SVC-03: общий бюджет OCR/перевода

**Files:** `data/ai/TranslationHandler.kt`, `data/ai/transformers/OcrTransformer.kt`, `data/ai/GenerationLoop.kt`, `service/ChatService.kt`, при необходимости общий costguards request helper; real-path budget tests.

**Interfaces:** существующие `TokenBudgetStore.getLedger`, `TokenBudgetContext`, reserve/markSubmitted/observe/markCompleted/closePreserving. Никакого второго ledger для conversation.

- [x] RED: exhausted shared ledger + настоящие translation stream/Qwen-MT и OCR paths должны отклонить запрос до provider. Explicit GenerationLoop ledger должен попасть в OCR без искусственного внешнего context. Зафиксировать фактические provider calls на baseline.
- [x] GREEN: message translation получает ledger своего conversation; handler учитывает inherited ledger в обеих ветках. Main loop передаёт context только вокруг input transforms. OCR использует тот же reserve/accounting и не проглатывает budget/cancellation/persistence failures.
- [x] VERIFY: usage/max-output bounds, failed submitted request, cancellation, persistence failure, cache hit и shared store reload. Standalone translator без conversation context сохраняет поведение; ошибки бюджета на русском.
- [x] COMMIT: `fix(budget): account translation and OCR against conversation limits`.

## Task 5 — SVC-05: вложенные файлы и общие ссылки

**Files:** `ChatService.kt`, `ConversationRepository.kt`, при необходимости FilesManager/reference cleanup helper; service/real DB/files tests.

**Interfaces:** `UIMessagePart.Tool.output` рекурсивно; `MessageNodeDAO.hasFileReference` по raw JSON и актуальные session/queue/submitting references. Зависит от fail-closed истории Task 1.

- [x] RED: actual fork вложенных Tool→Tool→media, inactive candidates; URLs должны стать независимыми и bytes сохраниться. Delete source не должен удалить fork files.
- [x] RED: два чата разделяют URL, в том числе nested/inactive reference; обычное удаление чата и candidate сохраняет файл. Ошибка reference query оставляет файл.
- [x] GREEN: рекурсивное копирование, guards до неполного fork; физическое удаление только после успешной записи и общей проверки persisted/runtime ссылок. Последнюю ссылку удалять; не удалять при неопределённости. Проверка/delete согласованы с writers.
- [x] VERIFY: реальные временные файлы/Room, remote URL unchanged, config/metadata retained, active/queued references и last-reference cleanup.
- [x] COMMIT: `fix(chat): preserve nested and shared attachment files`.

## Task 6 — SCREEN-01: Android Share

**Files:** `RouteActivity.kt`, `AndroidManifest.xml`, `ui/pages/share/handler/ShareHandlerPage.kt`, при необходимости ShareHandlerVM; real Intent/navigation tests.

**Interfaces:** упорядоченный список stream URI через ShareHandler до `Screen.Chat.initFiles`; text передаётся одновременно. Legacy внутренний String sender остаётся совместимым.

- [x] RED: ACTION_SEND Parcelable Uri + CharSequence/text; ACTION_SEND_MULTIPLE с двумя URI и text на SDK 28/35. Baseline теряет stream/не создаёт route; наблюдать assertion failures.
- [x] GREEN: совместимые typed/legacy Parcelable getters, безопасный String fallback; multiple MIME filters и ordered list transport. Assistant selection создаёт draft text+all files без автоотправки.
- [x] VERIFY: image/document shares, malformed/empty stream, legacy sender, navigation и manifest resolution.
- [x] COMMIT: `fix(share): accept single and multiple Android stream attachments`.

## Task 7 — SCREEN-02: соответствие файл↔MIME

**Files:** `FilesManager.kt`, `ChatPage.kt`, narrowly reused import result helper; real FileProvider/ContentProvider/production page tests.

**Interfaces:** результат успешной копии сохраняет исходный URI/MIME рядом с local URI; не применять общий индекс к двум независимо отфильтрованным спискам.

- [x] RED: missing WAV перед valid PNG, unknown MIME перед PNG, PDF/DOCX/TXT вместе с text; настоящая production import path должна сохранить каждый успешный файл с правильным типом. Наблюдать текущие потери/неверный Audio/Image.
- [x] GREEN: mapping каждого input независимо, successful copy с собственной metadata, Document fallback; failure не ломает соседей и text.
- [x] VERIFY: порядок, MIME fallbacks, partial failures, и normal208 screenshot Verify.
- [x] COMMIT: `fix(chat): preserve MIME identity when importing attachments`.

## Task 8 — R-37: видимое название одобряемого инструмента

**Files:** `ChatMessageTools.kt`, narrowly opt-in `ChainOfThought.kt`, actual Compose bounds test; intentional affected chat-before PNGs.

- [x] RED: production Pending root approval при fontScale=1.3 и width320/360; title имеет положительную ширину/видимый TextLayoutResult, все scope buttons внутри card и переносятся. Baseline title вытеснен.
- [x] GREEN: pending controls получают строку под label либо opt-in layout; обычные reasoning/export extra остаются прежними. Approval scopes/callbacks/HARDLINE не менять.
- [x] VERIFY: 1.0/1.3 + все четыре темы, semantics/tool identity/clicks. Просмотреть every changed PNG; обновить только views, изменённые R-37, сохранить исходные «ДО» в git `8acbc537`.
- [x] COMMIT: `fix(approval): keep tool names visible with wrapped scope actions`.

## Task 9 — SVC-06: выбранная альтернатива

**Files:** private delete helper в `ChatService.kt`, `ChatBranchCharacterizationTest.kt`, real public-service delete tests.

- [x] RED: `[A,B,C]`, selected B; delete A → selected B at0, в live state и Room. Обновить прежний characterization этого бага на требуемую identity и увидеть runtime failure.
- [x] GREEN: корректно пересчитать индекс surviving selected candidate; при удалении selected сохранить существующий fallback. Не менять IDs, остальные nodes и checkpoints.
- [x] VERIFY: удаление до/после selected, первого/последнего/единственного candidate, missing message.
- [x] COMMIT: `fix(chat): preserve selected alternatives after deletion`.

## Task 10 — SET-04: атомарные настройки

**Files:** `PreferencesStore.kt`, SettingVM/AssistantDetailVM/ChatVM и production producers/leaf callback helpers из `/tmp/stage0-settings-design.md`; typed nested/draft update helpers только при необходимости; real DataStore/VM/editor tests.

**Interfaces:** ordinary `SettingsStore.update(transform)` читает/decode/normalize текущие Preferences и записывает результат в одном DataStore transaction; убрать ordinary whole-Settings overload. Explicit restore-before-initialization остаётся отдельной whole replacement. Листовые изменения применяются к current nested/item/list state.

- [x] RED: реальные direct selected-assistant/count writes и два store instances при frozen presentation cache; второй save обязан сохранить оба намерения. Реальные QuickMessagesVM/editor/draft races не теряют несвязанные поля/элементы. Зафиксировать assertion failures до refactor.
- [x] GREEN: общий production decoder/writer, transaction read-modify-protect-write; StateFlow не baseline. Прокинуть intent transforms, keyed deltas/moves, draft dirty-field patches; не менять defaults/migrations/backup JSON/vaults/UI.
- [x] VERIFY: nested fields, assistants/providers/models/MCP/OAuth/tools, collections/draft conflicts, backup reminder timestamp, anonymous header/body row safety, restore/normalization/vault failure и file cleanup после commit. Компилятор обнаруживает старые snapshot callers.
- [x] COMMIT: `fix(settings): apply edits atomically to current persisted state`.

## Финальная проверка и сдача

Запись отражает проверенное локальное состояние перед завершающим docs-коммитом.
Единственный push и результат Daily Build фиксируются в отчёте сдачи после этой
записи; они не объявлены выполненными заранее.

- [x] Каждая задача имеет сохранённые RED/GREEN команды, runtime assertion evidence, XML counts и собственный коммит; SET-01 — принятое решение о политике, TDD не требуется.
- [x] Независимый review каждой существенной задачи и всей серии. Итоговый код [46691913](https://github.com/mishaqp/Rikka-Root/commit/46691913674028ab1c896bd7e07579f35c7e4a4c): APPROVE, открытых замечаний по корректности нет; проверены пересечения задач и свежие XML 649/0.
- [x] Audit содержит статусы и реальные integrated commit hashes; исходная evidence закреплена на `8acbc537`. Audit и этот план подготовлены к завершающему docs-коммиту.
- [x] Normal Roborazzi Verify проверяет 208 captures; только 64 согласованных R-37 PNG обновлены и просмотрены, 144 остальные побайтно прежние. Два read-only UI-теста дополняют обычные captures.
- [x] Локальная компиляция затронутого приложения и связанные проверки: компиляция app и 649 связанных тестов в одном свежем прогоне: 0 failures/errors/skips; все 208 Roborazzi captures в Verify; функциональный checklist 250/250.
- [ ] Единственный push на `ccr-e93188a6-grrqko` — ожидается после docs-коммита и проверки чистого дерева.
- [ ] Daily Build completed/success на итоговом SHA — ожидается после push; проверить reports/ui-screenshots и подписанный APK, выдать отчёт по десяти пунктам и phone scenarios, остановиться.
