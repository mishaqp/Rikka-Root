# Исправления аудита инструментов 9b74e541

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Track each test-first cycle below.

**Goal:** устранить все 21 пункта аудита, сохранив проверки и обязательные подтверждения.

**Architecture:** исправления остаются в существующих подсистемах. Общие правила схем, командных позиций и прав доступа переиспользуются; данные ассистентов и MCP-секреты проверяются на границе доступа.

**Tech Stack:** Kotlin, coroutines, Android/Compose, Room, JUnit/Robolectric, Gradle, GitHub Actions.

**Spec:** запрос пользователя от 2026-10-16 с пунктами 1–21, исходный commit `9b74e541`.

## Global Constraints

- Ветка `ccr-e93188a6-grrqko`, пользовательские тексты на русском.
- На каждый пункт — регрессионный тест с подтверждённым падением до исправления и прохождением после.
- Тесты не отключать и не ослаблять; lint без новых ошибок.
- Локально только проверки; APK только Daily Build после одного итогового push.
- Не создавать PR. Один итоговый commit; дополнительные исправления CI допустимы по AGENTS.md.

## Review Focus

- Вызов инструмента с финальным текстом в одном ASSISTANT должен успешно завершать headless и считать реальные шаги.
- Смена адреса MCP и подстановка чужой ссылки не раскрывают секреты; пропущенные поля update сохраняются.
- Ручное подтверждение root сохраняется, автоматические обходы запрещаются до побочного эффекта.
- Отмена родительской корутины остаётся отменой; локальный таймаут инструмента возвращает модели ошибку.
- Ввод модели (JSON, пути, имена свойств и MCP-инструментов) не нарушает границы и протокол.

## Независимые задачи

Для каждой задачи: сначала добавить тесты против исходного API; общий RED-прогон должен падать по ожидаемым утверждениям. После RED внести минимальные исправления, затем общий GREEN-прогон и независимая проверка diff.

### 1. Цикл генерации и headless — пункты 1,6,16,17,21

Files: `service/HeadlessTaskRunner.kt`, `data/ai/GenerationLoop.kt`.
Tests: `GenerationLoopAuditRegressionTest` (новый).

- [x] RED: Prompt tool → text даёт SUCCEEDED, steps=2; настоящий step limit остаётся FAILED.
- [x] RED: MCP/subagent HARDLINE до approval и после resume не допускает эффект.
- [x] RED: локальный timeout даёт ошибку tool и финальный ответ; родительская отмена пробрасывается.
- [x] RED: большой вывод без workspace сокращается с пометкой, нетекстовые части сохранены.
- [x] RED: битый JSON не создаёт Pending; предел времени/шагов виден пользователю.
- [x] GREEN (реализация готова; полный runtime-прогон ниже): счётчик через callback реального шага; проверка незавершённых Tool и текста после последнего Tool; общая проверка HARDLINE; ensureActive при обработке cancellation; ограничение вывода; ранняя проверка JSON и видимый limit.

### 2. MCP — пункты 3,9,18

Files: `data/ai/mcp/control/*`, `data/ai/mcp/McpManager.kt`, необходимые места вызова approval renderer.
Tests: `McpAuditRegressionTest`, `McpToolNameAuditTest`, дополнительные тесты контекста approval.

- [x] RED: host change удаляет OAuth и секретные ссылки; omitted headers/enabled сохраняются.
- [x] RED: approval содержит старый и новый URL и предупреждение.
- [x] RED: URL и ссылки с любыми именами заголовков редактируются; чужая ссылка не разрешается.
- [x] RED: имена соответствуют `[a-zA-Z0-9_-]`, <=64, уникальны; mapping сохраняет original.
- [x] GREEN (реализация готова; полный runtime-прогон ниже): исправить границы update/secret resolution и единое построение имён.

### 3. Провайдеры — пункты 2,19,20

Files: `ai/core/Tool.kt`, provider request/decoder builders, `ai/util/Json.kt`, `ProviderConnectionTester.kt` при необходимости.
Tests: `EmptyToolParametersRegressionTest`, `SchemaFilteringRegressionTest`, `ChatCompletionsToolIdentityRegressionTest`.

- [x] RED: все API сериализуют пустую object-schema для default и explicit null.
- [x] RED: свойства format/enum/const сохраняются; required содержит только существующие имена.
- [x] RED: отсутствующие id уникальны, пустой id не стирает прежний, object arguments принимаются.
- [x] GREEN (реализация готова; полный runtime-прогон ниже): единый fallback schema, контекстная фильтрация схем и устойчивое декодирование.

### 4. Командные защиты — пункты 5,7

Files: `RootTool.kt`, `RootCommandGuard.kt`, `TermuxCommandGuard.kt`, `HardlineCommandGuard.kt`.
Tests: `RootToolTest`, `RootCommandGuardTest`, `TermuxCommandGuardTest`, `HardlineCommandGuardShellTest`.

- [x] RED: automatic root блокируется до probing; manual Approved сохраняется.
- [x] RED: каждый shell flag, wrapper, command separator/group, svc power и setprop sys.powerctl блокируется.
- [x] GREEN (реализация готова; полный runtime-прогон ниже): общий CMD_POS, обработка executable+arguments и automatic invocation.

### 5. Изоляция данных — пункты 8,10,11

Files: `WorkspaceTools.kt`, `ChatToolFactory.kt`, `MemoryTools.kt`, `MemoryRepository.kt`, `MemoryDAO.kt`, `ConversationTools.kt`.
Tests: регрессии workspace paths, memory ownership, conversation assistant scope.

- [x] RED: . и .. не обходят writable prefix во всех инструментах с путями.
- [x] RED: чужая/несуществующая память не изменяется, ответ not_found.
- [x] RED: search передаёт владельца и не возвращает чужие чаты.
- [x] GREEN (реализация готова; полный runtime-прогон ниже): нормализация до доступа, scoped repository/DAO, передача assistantId.

### 6. Устройство — пункты 13,14,16

Files: `ClipboardTool.kt`, `ScreenshotTool.kt`, `DeviceToolSupport.kt`, `ToolMediaPlaybackService.kt`.
Tests: `ClipboardSensitiveContentTest`, `ScreenshotStoragePrivacyTest`, `DeviceToolTimeoutTest`, `ToolMediaCommandTimeoutTest`.

- [x] RED: clipboard sensitive data обрабатывается detector.
- [x] RED: screenshot private default; явный gallery opt-in; pending row удаляется при ошибке.
- [x] RED: локальные таймауты не становятся Stop; отмена родителя сохраняется.
- [x] GREEN (реализация готова; полный runtime-прогон ниже): detector, private cache default и cleanup, корректная cancellation.

### 7. Одобрения — пункты 3,4,6,12,13,15

Files: `ToolPermissionPolicy.kt`, `RunExecutionContext.kt`, `WorkflowApprovalRenderer.kt`, workflow preview в `ChatMessageTools.kt`, `ChatService.kt`, `ConversationRoutes.kt`.
Tests: `ApprovalAuditPolicyTest`, `WorkflowApprovalRendererAuditTest`, регрессии ChatService/HTTP.

- [x] RED: MCP mutations и SMS не обходятся Always/YOLO/headless; calendar_query/get_screen_time registered.
- [x] RED: HARDLINE проверяет также прямые headless MCP.
- [x] RED: полные аргументы workflow доступны без усечения.
- [x] RED: answer принимает только Pending ask_user.
- [x] GREEN (реализация готова; полный runtime-прогон ниже): policy, scrollable preview, gate answer.

## Итоговая проверка и публикация

- [x] Проверить diff по пунктам, прочитать независимое ревью, устранить замечания.
- [ ] Выполнить затронутые unit suites и lint с унаследованным gate.
- [ ] Commit/push в рабочую ветку один раз, дождаться Daily Build (все tests, lint, подписанный APK).
- [ ] Короткий русский отчёт: номера, оставшиеся ограничения, commit, Actions/APK, шаги на телефоне.

## Подтверждённые проверки перед публикацией

- `:app:compileDebugKotlin` — PASS; production-код всех исправлений компилируется.
- Четыре provider regression suites: исходный `9b74e541` — 13 ожидаемых падений из 14 тестов; исправленная версия — 14/14 PASS.
- Изолированный JVM-прогон реальных command guard sources — 149/149 PASS после воспроизведения обходов на исходном коде.
- `BoundedSearchOutputTest` — 5/5 PASS; дополнительная проверка сохранения title сначала выявила ошибку, затем прошла.
- Независимые review провайдеров, командных защит, изоляции данных, одобрений, цикла генерации и MCP завершены. Итоговый review не содержит открытых Critical/Important.
- Полный app RED/GREEN и lint требуют итогового выполнения. Локальная загрузка ряда Android/Robolectric-зависимостей периодически возвращает HTTP 503; это не считается падением регрессионного теста.

Итоговый Daily Build с маркером `[audit-baseline]` сначала создаёт отдельный worktree **исходного production-кода** и копирует только baseline-compatible тесты. Для каждого пункта 1–21 требуется конкретное assertion failure в свежем JUnit XML; compile/download/init failures не засчитываются. Затем без отключений выполняются обычные полные unit suites и lint с исходным лимитом 51 ошибки. Подписанный APK собирается один раз, только после успешных проверок. RED-лог, XML и машинный список доказательств `build/audit-baseline/evidence.json` сохраняются в отчётах Actions.

Первый Actions-прогон `38967505239` подтвердил RED для всех пунктов 1–21. После этого найден ещё один вариант пункта 7: защищённый путь непосредственно перед `;`, `&&`, `||`, `|`, `&` или закрывающим разделителем. Дополнительные 21 проверки воспроизвели его до правки и прошли после. Старый прогон остановлен до APK; `ProtectedPathDelimiterAuditTest` включён в baseline gate следующего итогового прогона. Расширены только границы пути в двух guards; похожие безопасные пути сохраняют разрешённое поведение.

Повторный локальный прогон после восстановления сети скомпилировал весь app test source и выявил таймауты неизменённых ChatService-тестов: default suspend callbacks создавали новые экземпляры и не совпадали с MockK stubs. `javap` подтвердил `new` в default bridge. Callback-параметры сделаны nullable с default `null` и безопасным вызовом; HeadlessTaskRunner по-прежнему передаёт явные обработчики. Тестовые условия, stubs и таймауты не менялись. Последующий прогон должен подтвердить восстановление старых сценариев вместе с новыми регрессиями; предыдущие Actions остановлены до APK.

Итоговый локальный targeted GREEN после nullable-default исправления: **app — 34 suites, 327 tests; ai — 4 suites, 14 tests; failures=0, errors=0, skipped=0**. Включены новые регрессии и неизменённые ChatServiceMetadataTest, ChatProcessDeathTest, ChatDurableQueueIntegrationTest, ChatDisplayBehaviorServiceRuntimeTest. Комбинированный Gradle-процесс вернул exit 1 только из-за загрузки lint/metadata-зависимостей HTTP 503; свежий app lint XML отсутствовал, gate корректно отказал в принятии неполного анализа. Окончательный lint и полные suites остаются обязательными в Actions до APK. Исходный XML lint скачан: 45 ошибок (3 NonObservableLocale, 1 ContextCastToActivity, 41 LocalContextGetResourceValueCall); итог следует сравнить по самим issue, а не только с лимитом 51.


## Исправления после полного прогона CI

Прогон `38969821934` подтвердил RED всех 21 пунктов, затем выполнил **4790 unit-тестов**: 37 падений, без errors/skips. APK не собирался. Три старых ожидания GenerationLoop приведены к обязательным подтверждениям `mcp_add`/`mcp_update`/`send_sms` и исполняемой ошибке HARDLINE; проверки отсутствия побочных эффектов сохранены и расширены. Убрано удвоение `hardline:` в русском сообщении. Тест MCP сравнивает точное стабильное имя с hash, ASCII и лимит 64; checklist проверяет актуальный renderer с текущей конфигурацией сервера.

32 падения UI вызваны новым обязательным предупреждением и недоступностью «Для этого чата»/«Всегда» для `mcp_add`. Добавлены соответствующие функциональные утверждения, прежняя проверка сокрытия секрета сохранена. После визуальной проверки всех 16 сочетаний темы/масштаба/ширины обновлены только 44 затронутых PNG: approval — 12, mcp_pending — 16, timeline_native_mcp_redacted_details — 16. Остальные 420 из 464 снимков не изменились; 212 общих снимков локального и CI прогона совпали побайтно. Пороги сравнения и тестовые методы не изменялись. Отдельный Verify этих 32 тестов в общем локальном прогоне прошёл.

Дополнительно пункт 16 проверен для обычного CancellationException от отменённого дочернего Deferred при активном вызывающем контексте: **пять новых assertion failures до правки** в GenerationLoop/chat, headless Prompt, DirectActions, device/SAF и media; затем все пять прошли. Baseline gate дополнен этими обязательными условиями. Настоящая отмена родителя проверяется отменой Job, завершением cleanup и отсутствием возврата результата. В файловом сценарии сохранены проверки удаления только созданного документа; в root — durable start marker и отсутствие повторного исполнения. Намеренная остановка политики отделена от coroutine cancellation и остаётся BLOCKED без дополнительного запроса модели. Media сохраняет CANCEL cleanup.

Общий локальный targeted-прогон: app **43 suites, 517 tests**, ai **4 suites, 14 tests**; единственное падение — новый тест identity базового marker. Отдельное воспроизведение подтвердило копирование RuntimeException при coroutine stacktrace recovery (тот же тип/текст, cause — исходный объект). Fixture уточнён до сигнала с payload, как реальный HeadlessStop; exact identity, статус, код, payload и активность вызывающего контекста проверяются для direct/SAF. Повторный DeviceToolFailureReasonTest — **20/20 PASS**, Gradle BUILD SUCCESSFUL (18s). Полный Actions остаётся обязательным перед APK. CI Python helpers: **22/22 PASS**. Независимый closing review не выявил ослабления тестов или открытых Critical/Important.


Прогон [38974350109](https://github.com/mishaqp/Rikka-Root/actions/runs/38974350109) снова подтвердил RED всех пунктов 1–21 на `9b74e541`, включая расширенные условия пункта 16. Полный исправленный прогон выполнил **4797 тестов, 2 assertion failures, 0 errors/skips**; все UI goldens прошли. Оба оставшихся старых fixture (`WorkspaceToolErrorsTest`, `BugReportBuilderTest`) бросали CancellationException при активном родителе, хотя проверяли остановку родителя. Они теперь реально отменяют Job и сохраняют исходную причину, cleanup, отсутствие возвращённого результата; все проверки ZIP/privacy/последней ошибки сохранены. Отдельный новый тест Workspace проверяет отменённый дочерний Deferred, точную русскую ошибку и отсутствие повторного исполнения/утечки private text. На DeviceToolSupport до исправления обычной CE он даёт ожидаемый AssertionError; после восстановления production **оба класса — 24/24 PASS, BUILD SUCCESSFUL, 31s**. Независимый review подтвердил отсутствие ослабления. Production после `f959c69c` не менялся; следующему прогону нужны обычные полные tests и lint, повторная компиляция исходного baseline не требуется. APK во всех завершённых проверочных прогонах отсутствует: assemble пропущен из-за unit failures.
