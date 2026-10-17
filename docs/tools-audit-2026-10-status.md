# Статус аудита 60 инструментов

Источник: [аудит на 9b74e541](tools-audit-2026-10.md). Повторная проверка кода и тестов выполнена на `e7e8ee3d` после pull --rebase. «До» означает этот срез, а не исходный коммит аудита. Документ обновляется по мере исправлений; незавершённые строки явно отмечены.

Пути Kotlin без префикса — относительно `app/src/main/java/me/rerere/rikkahub/`. Имена тестов — реальные классы в `app/src/test` либо в указанном модуле. Наличие старого теста проверено чтением; новый зелёный прогон всей части будет указан отдельно.

| № | Статус до | Проверено / что сделано | Коммит |
|---|---|---|---|
| 1 | исправлено | `HeadlessTaskRunner.kt:137,189,208,214`: отдельные steps и признак лимита; завершённый Tool не означает лимит. `GenerationLoopAuditRegressionTest`: cron/subagent/workflow → Tool → текст, настоящий лимит, пустой финальный ответ. | a469dd09, dd38a53a |
| 2 | исправлено | `ai/core/Tool.kt:22 parametersOrEmpty`; Claude/Google/Interactions/Responses/ChatCompletions используют один helper. `EmptyToolParametersRegressionTest` проверяет запросы всех пяти протоколов. | a469dd09 |
| 3 | исправлено | `McpControlTools.kt:573–617`: сброс OAuth/секретов при смене origin, проверка позднего OAuth refresh; `McpApprovalRenderer.kt:69` показывает старый/новый URL. trustMutations/mandatory сохранены. `McpAuditRegressionTest`, `McpOwnershipAndApprovalTest`, `ApprovalAuditPolicyTest`. Исключение режима см. ниже. | a469dd09, dd38a53a |
| 4 | исправлено | send_sms в mandatory и headless interactive; сохранённые grants не обходят обычное обязательное одобрение. `ApprovalAuditPolicyTest`, `HeadlessToolPolicyTest`. Согласованный интерактивный режим «Я ГЛУПЫЙ» сохранён. | a469dd09, 54ad1290 |
| 5 | исправлено | `RootTool.kt`: RootCommandGuard проверяет automatic invocation. `RootToolTest` и `RootCommandGuardTest`. | a469dd09 |
| 6 | исправлено | `GenerationLoop.kt` применяет HARDLINE ко всем новым и возобновлённым вызовам. `GenerationLoopAuditRegressionTest`: MCP/subagent, auto/pending/approved. | a469dd09 |
| 7 | исправлено | Root/Termux guards распознают shell-флаги, wrappers, разделители, svc/setprop; используют общий CMD_POS. `RootCommandGuardTest`, `TermuxCommandGuardTest`, `HardlineCommandGuardShellTest`. | a469dd09, 51372475 |
| 8 | исправлено | Workspace paths проверяются до разрешения/префикса; отклоняются `.` и `..`. `WorkspacePathTraversalAuditTest`, тесты workspace-модуля. | a469dd09 |
| 9 | исправлено | URL проходит SecretRedactor; query/userinfo скрыты; secret refs скрываются при любом header name и привязаны к serverId+origin в vault. `McpAuditRegressionTest`, `McpControlSecretStoreTest`, `McpOwnershipAndApprovalTest`. Граница эвристики path отмечена ниже. | a469dd09, dd38a53a |
| 10 | исправлено | Memory DAO/repository изменяют только id+assistant_id и проверяют affected rows; чужой/несуществующий id → not_found. `AssistantDataIsolationAuditTest`. | a469dd09 |
| 11 | исправлено | conversation_search получает assistantId и фильтрует владельца. `AssistantDataIsolationAuditTest`. | a469dd09 |
| 12 | исправлено | `WorkflowApprovalRenderer.kt` выводит полные аргументы, UI прокручивается. `WorkflowApprovalRendererAuditTest`, `WorkflowApprovalScrollAuditTest`. | a469dd09 |
| 13 | исправлено; browser_open исключён | scrape_web проверяет DNS и фактический адрес каждого соединения/redirect. Browser SSRF-подтверждения исключены прямым решением пользователя. `DuckDuckGoSearchService.kt:123–190`; шесть новых `DuckDuckGoScrapeNetworkAuditTest` проверяют private/mixed DNS, IPv6 и redirects, все проходят. | 8c55da43, 54ad1290, 98e0674b |
| 14 | частично | download/MCP/http и все shell результаты сохраняют границу внешнего содержимого, включая восстановление истории; консервативная классификация не пытается разобрать shell. `WebContentGuardTest`: RED→GREEN. | 504f30d5 |
| 15 | исправлено с уточнением | Clipboard read проходит detector и по умолчанию маскирует секреты; reveal — отдельное действие обычной системы разрешений. calendar_query/get_screen_time в registry. `ClipboardSensitiveContentTest`, `ApprovalAuditPolicyTest`. Новое подтверждение обычного маскированного чтения не требуется. | a469dd09, dd38a53a |
| 16 | исправлено | Скриншоты приватные по умолчанию, gallery opt-in, недописанный IS_PENDING удаляется, вложения перенесены в private uploads. `ScreenshotStoragePrivacyTest`. | a469dd09, dd38a53a |
| 17 | открыто | manage_assistants: нет trusted before→after/stale для массивов/имени. `ManageAssistantsAuditRegressionTest`: RED подтверждён, исправление выполняется. | ожидает |
| 18 | открыто | Удаление ассистента не проверяет chats/memory/cron/workflow. RED подтверждён; вводятся консервативные dependency counts и fail-closed без checker. | ожидает |
| 19 | открыто | Нейтральные поля с sk-/AIza/ghp_ не распознаются управлением ассистентами. `ManageAssistantsAuditRegressionTest`: RED подтверждён. | ожидает |
| 20 | исправлено | Bounded stream, закрытие response, timeout60s, проверка redirect; дополнительно закрыт подтверждённый баг отмены blocking execute: enqueue+invokeOnCancellation cancel. `SkillUrlImporterNetworkAuditTest`, `SkillUrlImporterCancellationAuditTest`, `SkillUrlImporterTest` проходят. | 8c55da43, 98e0674b |
| 21 | исправлено | ChatService и ConversationRoutes принимают answer только для Pending ask_user. `ChatServiceMetadataTest`: состояние в памяти/Room, запрет поддельного grant и реальный Ktor HTTP400. | a469dd09 |
| 22 | исправлено | GenerationLoop/DeviceToolSupport/media различают собственный timeout и отмену родителя через ensureActive. `GenerationLoopAuditRegressionTest`, `DeviceToolTimeoutTest`, `ToolMediaCommandTimeoutTest`. | a469dd09, f959c69c, dd38a53a |
| 23 | исправлено | `boundToolOutputsForModel` ограничивает каждый provider request даже без workspace, сохраняя полный JSON для UI. `GenerationLoopAuditRegressionTest`, `BoundedSearchOutputTest`; изображения и citation URLs сохранены. | a469dd09, dd38a53a |
| 24 | исправлено | MCP names только разрешённые символы, ≤64, SHA-256 suffix включает server UUID/исходное имя; dispatch хранит обратную связь. `McpToolNameAuditTest`, `McpToolDispatchAuditTest`. | a469dd09, dd38a53a |
| 25 | исправлено | Schema filtering отличает properties от keywords и чистит required. `SchemaFilteringRegressionTest`, `GoogleSchemaFilteringRegressionTest`. | a469dd09 |
| 26 | исправлено | Уникальный fallback id, пустой id не затирает прежний, arguments-object → JSON. `ChatCompletionsStreamDecoderNullTest`, `ChatCompletionsToolSchemaTest`, `StreamTraceReplayTest`. | a469dd09 |
| 27 | исправлено | Видимое сообщение при лимите времени/шагов, notice не отправляется модели как её ответ. `GenerationLoopAuditRegressionTest`. | a469dd09, dd38a53a |
| 28 | исправлено | Невалидный JSON/object возвращается модели без карточки и побочного действия, включая восстановленный вызов. `GenerationLoopAuditRegressionTest`. | a469dd09 |
| 29 | открыто | Termux timeout возвращает may_still_be_running и русское «Проверьте… Не повторяйте…», сохраняя partial stdout/stderr. `TermuxTimeoutAuditTest`: RED→GREEN; старые failure/lifecycle tests сохранены. | 504f30d5 |
| 30 | открыто | Foreground PRoot получает QUIT по проверенным PID/PPID/starttime, grace500ms, затем bounded KILL fallback. Реальные Process timeout/interruption/fallback tests проходят; телефон нужен для самого Android PRoot. | 504f30d5 |
| 31 | открыто | SFTP скачивает в соседний .part и публикует атомарно; при сбое старый target сохраняется. Recheck scope и inode перед commit/cleanup, upload recheck после handshake. Девять `SftpAtomicDownloadAuditTest` проходят, включая пять атакующих RED. | 504f30d5 |
| 32 | открыто | Root readers заканчиваются с owned process: после exit один final snapshot≤1МиБ, непрерывный вывод потомка не продлевает reader. Четыре `RootBackgroundPipeAuditTest` и прежние runner/manager tests проходят. | 504f30d5 |
| 33 | открыто | schedule_job проверяет HeadlessToolPolicy для каждого direct action до сохранения и не создаёт запрещённое задание. `CronCreationPolicyAuditTest` RED→GREEN, `CronJobToolTest`/headless policy проходят. | 504f30d5 |
| 34 | частично | Open timeout уже был исправлен; text/selector/Readability/DOM/links теперь возвращают document.URL в том же JS result. Пять nativeURL≠documentURL регрессий проходят. | 8c55da43, 98e0674b |
| 35 | исправлено | browser_open использует normalizeBrowserQuery (без схемы и host:port). `BrowserReadReliabilityTest`, `BrowserUrlSafetyTest`. | 8c55da43 |
| 36 | частично | После timeout не ставится raw-попытка позади parser; тот же документ больше не запускает auto-Readability, новая identity разрешает его. Большой DOM пропускается; Chrome JS физически не прерывается API. Адресные тесты проходят. | 8c55da43, 98e0674b |
| 37 | исправлено | JS text/DOM fallback на documentElement для XML/no-body сохранён; адресные XML text/DOM/links тесты добавлены и проходят. | 8c55da43, 98e0674b |
| 38 | исправлено | Тип js_parse_failed логируется, raw_preview≤300 и отсутствие полного raw проверяются реальным JS fixture, тест проходит. | 8c55da43, 98e0674b |
| 39 | частично | Имена/расширение/нумерация/MediaStore сохранены. Private overwrite пишет отдельный stage и заменяет target только атомарно; failed write сохраняет прежние bytes. `DownloadNamingTest` и policy tests проходят. | 8c55da43, 98e0674b |
| 40 | исправлено | Enter requestSubmit, textarea и отменённый keydown проверены адресными JS regression cases; все проходят. | 8c55da43, 98e0674b |
| 41 | исправлено | Запас wait_for, select missing и links total/truncated сохранены; адресные missing-option/101links cases и прежние predicate/timeout tests проходят. | 8c55da43, 98e0674b |
| 42 | частично | Meta charset/доступный scrape уже исправлены. Bing HTTP/DNS/timeout теперь имеют русскую обёртку с cause; три RED→GREEN. Search-модуль21test проходит, включая cp1251 и DNS/redirect guards. | 8c55da43, f7260f24, 98e0674b |
| 43 | исправлено | zip overwrite=false по умолчанию, повторная проверка до commit. `ArchiveAuditRegressionTest`, неверный тип отвергается. | 93f00d25, f1778439 |
| 44 | исправлено | Собственный archive timeout сообщает partial/stage/backup paths как обычная ошибка; rollback NonCancellable. `ArchiveAuditRegressionTest` с timeout/error/external cancellation. | 93f00d25 |
| 45 | частично | SAF findChild ищет точное имя streaming cursor до найденного child/EOF, без общего cap и лишних metadata IPC; grant перепроверяется. Оба remaining45 RED→GREEN; старые list/find truncated и path guards проходят. | 93f00d25, f1778439, 5e2636fe |
| 46 | частично | Same-volume regular overwrite сохраняет старый inode в sibling backup, moves без REPLACE_EXISTING, guarded rollback/recovery; создаёт отсутствующих parents после preflight. Пять новых tests (>512МиБ, parent, failure, race, cancel) проходят. | 93f00d25, 5e2636fe |
| 47 | исправлено | create_directory использует planWritePath для абсолютного SAF path. `FileManagerAuditRegressionTest`, held grant без raw File fallback. | 93f00d25 |
| 48 | исправлено | Все подпункты: wallpaper resolve; MIME base64; cleanup shown copies; zip limit/truncated; read directory/UTF-8; фактическое SAF имя; picker cancel; mandatory recursive delete. `FileManagerAuditRegressionTest`, `FileReviewRegressionTest`, `ArchiveAuditRegressionTest`, `ActivityLifecycleAuditTest`, `FileMandatoryConfirmationAuditTest`. | 93f00d25, f1778439 |
| 49 | исправлено | KEEP_SCREEN_ON на record/speech/NFC и освобождение по complete/cancel. `ActivityLifecycleAuditTest`. | 93f00d25 |
| 50 | частично | Семантические цифры узла и текст реального click ancestor входят в fingerprint; нормализуется лишь явный seconds countdown. Изменённый заказ/сумма/parentlabel отказывает до input; собственные правки editable и keyboard shift сохранены. `ScreenAuditRegressionTest`:21/0. | 93f00d25, f1778439, 5e2636fe |
| 51 | исправлено | Fallback swipe использует bounds найденного x/y target. `ScreenAuditRegressionTest`. | 93f00d25 |
| 52 | исправлено | end clamp к now, русские ошибки, настройки без пользовательского запроса не открываются. `PhoneAuditRegressionTest`. | 93f00d25 |
| 53 | исправлено | Поворот берётся из DisplayManager/Display.rotation, не dumpsys SurfaceOrientation. `ScreenAuditRegressionTest`; Android14+ требует телефона. | 93f00d25 |
| 54 | исправлено | Все подпункты: clipboard coerceToText; calendar day/LIKE; RU Battery/Audio; approximate Wi-Fi; activity recognition только шаги; wake после validation внутри mutex; TTS cap. `PhoneAuditRegressionTest`, `ScreenAuditRegressionTest`, `ToolActivityPayloadTest`. | 93f00d25, f1778439 |
| 55 | открыто | Preview показывает changes[] и полный before→after всех реальных побочных display/glass изменений; stale повторно проверяет полный frozen diff в update. Sideeffect/end-to-end/glass регрессии RED→GREEN, старые management tests проходят. | 8209f1a8 |
| 56 | исправлено | mcp_update без headers/enabled сохраняет старые значения; origin change намеренно очищает секреты. `McpAuditRegressionTest`. | a469dd09, dd38a53a |
| 57 | открыто | replace_all допускает только точное совпадение, fuzzy single сохранён. Две новые RED→GREEN и прежние `TextReplacersTest` проходят. | 8209f1a8 |
| 58 | частично; баг политики не подтвердился | Тумблер называется дополнительным требованием сервера; видимое пояснение объясняет общую политику, grants и выбранный режим. Флаг/callback/policy не меняются. Два реальных Compose regression tests проходят. | 8209f1a8 |
| 59 | открыто | SSH sanitizer использует точное membership, как resolveArgs; чужие короткие префиксы не изменяются. Старые vault/export protections и новые regression cases проходят. | 8209f1a8 |
| 60 | частично; строки-числа разрешены новым запросом | Chart допускает однозначные строки-числа по новому запросу, отклоняет NaN/Infinity/overflow, success/errors русские. use_skill различает директорию path="."/"./" до read; native file/traversal tests сохранены. `ChartDisplayAuditTest`, `SkillsToolsTest` проходят. | 8209f1a8 |

## Уточнения при проверке

- Сохраняется явно выбранный позднее режим «Я ГЛУПЫЙ». Он допускает интерактивные действия без обычных карточек, но не отменяет HARDLINE, защищённые root/Termux/SSH-команды и headless запреты. Старый аудит не является основанием откатывать решение пользователя.
- По пункту15 исходное отсутствие SensitiveContentDetector устранено. Обычное чтение возвращает маскированный текст; reveal использует существующую систему разрешений. Новые одобрения не вводятся без подтверждённого требования.
- По пункту9 невозможно определить произвольный короткий секрет только из формы обычного URL path. Проверены реальные правила редактора и scoped secret refs; эвристика не является гарантией распознавания любого публично выглядящего пути.
- По пункту23 полный результат сохраняется для UI, но каждый запрос модели ограничен. Слепое усечение сохранённого JSON сломало бы карточки/ссылки; существующая защита контекста проверяется интеграционным HTTP-тестом.
- По пункту58 общая политика MCP сохранена. Исправляется обещание интерфейса, а не защитное поведение.

## Ход проверки

- Обязательный initial pull: `b573dd89 → e7e8ee3d`.
- Первый новый WebContentGuard regression: RED на workspace_shell; исправленная классификация проходит в общем batch1.
- Batch1: 86 тестов, 34 ожидаемых падения в новых регрессиях до production-исправлений; существующие проходящие сценарии сохранены. Полный локальный лог `/tmp/reliability-batch-red.log`, XML-снимок `/tmp/reliability-part1-red-batch1.zip` (локальные артефакты сессии).
- Workspace cleanup RED: 3 теста, 2 ожидаемых падения (QUIT при timeout/interruption), bounded fallback проходит.
- Расширенный прогон пакетаA: **550 тестов без failures/errors/skips** (app494, ai21, workspace14, search21). В том же запуске отдельно выполнялись два ожидаемо красных DI-теста пакетаB; они не включены в550. XML `/tmp/reliability-package-a-tests-with-di-red.zip`, сводка `/tmp/reliability-package-a-tests-with-di-red.json`.
- Чистый checkout пакета A, без незавершённого B: **550 тестов, 0 failures/errors/skips** (`/tmp/reliability-package-a-clean-tests.zip`).
- Перед публикацией выполнен повторный pull --rebase: сохранён параллельный коммит `4767e3c6` (QR из /scratch и ранний отказ HTTP для частных IP). После него прошли ещё **50 затронутых тестов, 0 failures/errors/skips**. `./gradlew lint` успешно завершён: **13 отчётов, 0 ошибок**. Daily Build пока **ожидается**.
- Пакет A: 52 файла, четыре логических исправляющих коммита выше и отчёт; APK собирает только GitHub Actions.
- Первый полный lint пакета A обнаружил две MissingTranslation в новых строках MCP. Русские ресурсы добавлены во все шесть локалей без подавлений; повторный `./gradlew lint` прошёл с 0 ошибок (`/tmp/reliability-package-a-lint-green.zip`).
- ПакетB:17–19 — trusted diff/stale, реальные dependency counts, общий ownership mutex для всех четырёх writers/ручного удаления, современные ключи. Дополнительно проверяются authoritative DataStore вместо UI-кэша и секреты в обычных текстовых полях. ПакетB ещё не опубликован и закрытым не объявлен.
- Части2/3 ещё не начинались.

## Ограничения, требующие дальнейшей проверки

- SFTP при одновременных timeout и подмене/revoke сохраняет файлы безопасно, но общий timeout envelope может потерять cleanup metadata. Независимое ревью классифицировало это как Minor диагностики; учесть при унификации результатов в части2. Обычные error envelopes содержат recovery fields, parent cancellation остаётся отменой.
- Java Files.move без REPLACE_EXISTING сохраняет требуемый API-контракт, но Android/Java не предоставляет здесь отдельный renameat2(RENAME_NOREPLACE). Детерминированные race/rollback tests пройдены; абсолютную защиту от подмены между отдельными native syscall заявлять нельзя.
- Физические Android14+/PRoot/SAF/provider проверки не выполнены: adb-устройств нет.

## Проверка пакета A на телефоне

Эти сценарии подготовлены для ручной проверки; на устройстве в этой сессии не выполнялись.

1. Открыть файл через SAF в папке с более чем 5000 документами, затем найти файл в конце списка. Поиск точного имени не должен зависеть от лимита показа списка.
2. Переместить большой обычный файл с заменой внутри одного тома общей памяти. При отказе доступа исходник и прежний файл назначения должны сохраняться; при неполном откате ответ должен показывать путь резервной копии.
3. Запустить workspace-команду, принимающую SIGQUIT, и остановить ответ. Проверить завершение процесса и отсутствие бесконечного ожидания. Отдельно проверить foreground PRoot на используемом телефоне.
4. В браузере прочитать страницу после redirect, затем медленную страницу с Readability. URL должен соответствовать прочитанному документу; после timeout повтор не должен снова ставить тот же parser.
5. Скачать файл поверх существующего и оборвать сеть. Прежний файл должен остаться читаемым.
6. В дереве экрана сохранить node_id строки заказа, изменить номер/сумму заказа и вызвать click_node старого id. Ожидается отказ с предложением заново прочитать дерево.
7. Попросить изменить тему/стекло настройками: карточка должна показывать все связанные изменения. В настройках MCP проверить пояснение дополнительного подтверждения сервера.
