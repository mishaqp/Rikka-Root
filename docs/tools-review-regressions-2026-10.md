# Повторное ревью инструментов: 13 регрессий

Исходный срез для этого ревью — `46a15151e3e6020924772e600e2f549ca3539f20`. Номера ниже относятся к новому списку пользователя, а не к [исходным 60 пунктам](tools-audit-2026-10-status.md). Это дополнительная работа части 1. Она завершена и подтверждена полным GREEN до начала части 2; часть 3 ещё не начата.

Статус: все 13 исправлений и отдельная защита транспортной диагностики MCP опубликованы. Полный [Daily Build39101163177](https://github.com/mishaqp/Rikka-Root/actions/runs/39101163177) на `b89203ef`: 5325 обычных тестов, 0 failures/errors/skips; 13 lint-отчётов, 0 ошибок. Артефакт отчётов проверен отдельно (SHA256 `14c9b92902dd4ea214c5d45a21422aca05ab814e9919ac18279f9b986c46d476`). [Подписанный APK](https://github.com/mishaqp/Rikka-Root/releases/download/nightly/Rikka-Root-2.5.6-root.2-b89203ef-arm64-v8a.apk) проверен по размеру, SHA256, единственному сертификату и arm64 ABI.

| № нового списка | Подтверждённая проблема / требуемое поведение | Реальные regression-тесты; локальная группа патчей | Исходное доказательство |
|---|---|---|---|
| 1 | COUNT учитывает только `deleted=0`, включая выключенные живые задания; удаление ассистента очищает его tombstones и run-history. Workflow блокирует удаление по реальному owner/action reference, без UUID-подстрок в prose. | [AssistantRemovalReferenceRegressionTest][removal]; `ownership/tests-red.patch` → `production-v2.patch`. | batch2: класс 7/5; supplemental: расширенный класс 9/6, включая п7. |
| 2 | Обычные name/systemPrompt/presetMessages/regexes (`Basic helper`, заголовки, prose) читаются и редактируются. Настоящие credentials остаются защищёнными. | [AssistantOrdinaryTextRegressionTest][ordinary], [AssistantAbsoluteUrlProseRegressionTest][url-prose]; `management/01-tests.patch`, `independent-followups/management-strict-url-tests.patch`. | batch1 и повтор batch2: 7/5; отдельный URL-prose класс supplemental: 3/1. |
| 3 | MCP display names с пробелами/дефисом/кириллицей, ordinary metadata и aliases сохраняются точно; секретные значения и header credentials остаются защищёнными. | [McpMetadataCompatibilityReviewTest][mcp]; `mcp/tests-only.patch`, `tests-extra-short-path-controls.patch`. | batch1 и повтор batch2: общий для п3/4 класс 12/9; три security controls уже проходят. |
| 4 | Обычные длинные/короткие URL paths и opaque IDs не скрываются лишь из-за длины или короткого префикса. Реальные credential URLs/API/JWT/header/ref сохраняют защиту. | [McpMetadataCompatibilityReviewTest][mcp]; та же MCP-группа. | Общие с п3 12/9, не отдельный набор. |
| 5 | После отклонённого preview `manage_app_settings` возвращает исходный русский `invalid_type`/`invalid_range`/`invalid_choice`/`manual_only`, без бесконечного `approval_required`; валидная запись всё равно требует trusted approval. | [AppSettingsValidationTransportRegressionTest][settings]; `management/01-tests.patch` → `02-production.patch`. | batch1 и повтор batch2: 7/4; три approval/stale controls уже проходят. |
| 6 | History undo восстанавливает реально удалённый orphan-чат к явно выбранному живому владельцу; private receipt защищает от произвольной вставки, повторного overwrite и потери snapshot при отказе. | [HistoryUndoOwnershipRegressionTest][undo]; `ownership/tests-red.patch` → `production-v2.patch`. | batch2: 6/3. |
| 7 | Описание `manage_assistants.delete` честно сообщает об отказе при chats/memory/живых cron/реальных workflow refs; прежнее обещание сохранения истории удалено. | [AssistantRemovalReferenceRegressionTest][removal]: `actual management description explains deletion dependencies in Russian`; `ownership/tests-followup-red.patch`. | supplemental: целевой description assertion RED; контроль фактического отказа по четырём зависимостям GREEN. |
| 8 | Same-volume regular overwrite выполняет один `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)` без промежуточного исчезновения target. Ошибка/отмена до операции сохраняет оба файла, смена target перед последней проверкой отклоняется. | [AtomicLocalMoveReviewRegressionTest][move], [FileMoveOverwriteRemainingRegressionTest][move-old]; `move/01-new-red-tests.patch` → `03-atomic-move-production.patch`; отдельный `02-old-contract-tests.patch`. | batch1: общий для п8/9 класс 3/2. |
| 9 | Case-only destination с тем же fileKey успешно обрабатывается без `overwrite=true`; unrelated-name collision остаётся отказом. | [AtomicLocalMoveReviewRegressionTest][move]; та же move-группа. | Второй RED в общем классе 3/2; unrelated same-inode control GREEN. |
| 10 | Относительный возраст, процент progress, clock countdown и счётчик сообщений в реальном promoted action ancestor не делают handle устаревшим. Semantic digits и fingerprint наблюдаемого child остаются строгими. | [ScreenAuditRegressionTest][screen], методы `review 10 …`; `screen-browser-shell/screen-tests.patch`, `screen-ancestor-scope-test.patch` → `screen-production.patch`. | batch1: 5/4; supplemental: 6/4, включая дополнительный child-scope control. |
| 11 | Отсутствующая Readability document identity до запуска parser даёт same-call raw fallback, а explicit article mode — `readability_failed`. Настоящий parser timeout сохраняет карантин и запрет queued fallback. | [BrowserReadReliabilityTest][browser], методы `review 11 …`; `screen-browser-shell/browser-tests.patch` → `browser-production.patch`. | batch1: 4/4. |
| 12 | Foreground PRoot получает прямой Android `Process.sendSignal(QUIT)` после PID/PPID/starttime recheck; сохраняются grace, bounded force, descendants cleanup и cancellation. | [WorkspaceForegroundSignalReviewTest][shell]; `screen-browser-shell/shell-tests.patch` → `shell-production.patch`; отдельный `hostfixture-contract.patch`. | batch1: 4/2; forged identity и finished process controls GREEN. |
| 13 | SFTP/HTTP незавершённые bytes находятся в app-owned cache, без второго sibling stage возле target. Startup cleanup ограничен собственным cache и сохраняет чужие файлы/links. | [TransferStagingIsolationRegressionTest][staging], [TransferStagingCacheTest][cache], [TransferStagingCacheRootRegressionTest][cache-root]; `staging/01-tests-red.patch` → `02-production.patch`; helper/follow-up слои отдельно. | batch2: actual-tool класс 3/3. Helper-only тесты не включены в исходные 3/3; cache-root behavioral RED2/2; отказ компиляции со stale XML не включён в доказательство. |

## Фактический RED

| Запуск до соответствующего исправления | Тесты | Assertion failures | Errors / skips | Артефакты сессии |
|---|---:|---:|---|---|
| batch1 | 42 | 30 | 0 / 0 | `review-regressions/batch1-red.{json,zip,log}` |
| batch2 | 42 | 29 | 0 / 0 | `review-regressions/batch2-red.{json,zip,log}` |
| supplemental | 18 | 11 | 0 / 0 | `review-regressions/supplemental-red.{json,zip,log}` |
| staging-root follow-up (новый helper до исправления симлинков) | 2 | 2 | 0 / 0 | `review-regressions/staging-root-behavioral-red.{json,zip,log}` |
| malformed workflow / file URI / Unicode age follow-up | 17 | 6 | 0 / 0 | `review-regressions/last-followups-red.{json,zip,log}` |
| граница transport diagnostics MCP на опубликованном7769d6d1 | 15 | 3 | 0 / 0 | `review-regressions/mcp-diagnostics/red.{json,zip,log}` |

batch1, batch2 и supplemental выполнены на исходном production46a15151. Cache-root follow-up выполнен на новом helper до проверки корня-симлинка; last-followups — на интегрированных основных правках до уточнений malformed workflow/file URI/Unicode boundary. Последние две группы не объявляются запуском чистого46a15151.

Каталог артефактов — `/workspace/.reliability-state/`. Классы `AssistantOrdinaryTextRegressionTest`, `AppSettingsValidationTransportRegressionTest` и `McpMetadataCompatibilityReviewTest` повторены в batch1/batch2; supplemental повторяет и расширяет часть прежних классов. Эти числа **не суммируются как уникальные тесты или уникальные падения**. Дополнения, ещё не запущенные на момент сохранения черновика, не выдаются за RED/GREEN.

## Патчи и изменения прежних test contracts

Локальные patches и отчёты лежат в `review-regressions/{ownership,management,mcp,move,screen-browser-shell,staging,independent-followups}/`. Это артефакты подготовки, не исправляющие коммиты. Исправления интегрированы; коммиты и результаты приведены ниже.

- `ownership/contract-tests.patch`: tombstone больше не блокирует удаление, UUID в prose больше не считается workflow reference. Сохраняются проверки живых заданий за пределами LIMIT, реальных ссылок и очистки run-history.
- `mcp/test-contract.patch`, `test-contract-url-fixtures.patch`: разрешённые пользователем readable labels принимаются; короткие ordinary `sk-*` paths проверяются отдельно, а секретные fake fixtures используют однозначную credential shape. Защитные assertions сохраняются.
- `move/02-old-contract-tests.patch`: отменено требование промежуточного sibling backup. Три теста теперь проверяют ошибку до atomic operation, смену target до финального recheck и отмену до операции, сохраняя contents/inodes обоих файлов и отсутствие лишних файлов. Два прежних large-file/parent теста сохранены.
- `screen-browser-shell/hostfixture-contract.patch`: прежние host JVM tests получают тестовый QUIT transport; проверки реального tracer/reap/bounded-force/cancellation сохранены. Новые app tests проверяют default Android boundary.
- `staging/03-existing-test-adapters.patch`, `04-helper-tests-supplemental.patch`: атаки на stage перенесены в настоящий cache; target/ancestor/foreign-inode/cancellation/cleanup checks сохранены. Новые helper tests не заменяют actual-tool RED.

## Ограничения проверки

Case-only test использует Linux same-fileKey hardlink aliases как детерминированный surrogate; он проверяет успешный ответ и сохранность данных, а не физическое изменение регистра на Android casefold. Android PRoot/SAF/removable-volume cache и поведение при фактическом убийстве процесса требуют устройства; такие запуски здесь не заявлены. При отсутствии app-owned cache на нужном томе SFTP должен отказать с сохранением target. Сознательно сохранены RootMode `69b96231`/`15c16089`, private hardlink fallback `c62dd58c`/`730b9939`, действующие политики и подтверждения. Отдельные syscall rechecks не означают абсолютной защиты от произвольной внешней подмены в любой момент.

[removal]: ../app/src/test/java/me/rerere/rikkahub/data/ai/tools/local/AssistantRemovalReferenceRegressionTest.kt
[ordinary]: ../app/src/test/java/me/rerere/rikkahub/data/ai/tools/local/AssistantOrdinaryTextRegressionTest.kt
[url-prose]: ../app/src/test/java/me/rerere/rikkahub/data/ai/tools/local/AssistantAbsoluteUrlProseRegressionTest.kt
[mcp]: ../app/src/test/java/me/rerere/rikkahub/data/ai/mcp/control/McpMetadataCompatibilityReviewTest.kt
[settings]: ../app/src/test/java/me/rerere/rikkahub/data/ai/tools/local/AppSettingsValidationTransportRegressionTest.kt
[undo]: ../app/src/test/java/me/rerere/rikkahub/ui/pages/history/HistoryUndoOwnershipRegressionTest.kt
[move]: ../app/src/test/java/me/rerere/rikkahub/data/ai/tools/local/AtomicLocalMoveReviewRegressionTest.kt
[move-old]: ../app/src/test/java/me/rerere/rikkahub/data/ai/tools/local/FileMoveOverwriteRemainingRegressionTest.kt
[screen]: ../app/src/test/java/me/rerere/rikkahub/data/ai/tools/local/ScreenAuditRegressionTest.kt
[browser]: ../app/src/test/java/me/rerere/rikkahub/browser/BrowserReadReliabilityTest.kt
[shell]: ../app/src/test/java/me/rerere/rikkahub/data/ai/tools/local/WorkspaceForegroundSignalReviewTest.kt
[staging]: ../app/src/test/java/me/rerere/rikkahub/data/ai/tools/local/TransferStagingIsolationRegressionTest.kt
[cache]: ../app/src/test/java/me/rerere/rikkahub/data/ai/tools/local/TransferStagingCacheTest.kt
[cache-root]: ../app/src/test/java/me/rerere/rikkahub/data/ai/tools/local/TransferStagingCacheRootRegressionTest.kt

Дополнительные проверки после интеграции: complete action objects повреждённого workflow сохраняют реальные ссылки; посторонний assistant_id и fake fields внутри строк игнорируются. Абсолютные file URI с query/fragment credentials защищены и в обычных, и в специализированных полях. Граница возраста использует явную Unicode-проверку, чтобы не маскировать «назад2» или «назад_метка». Все эти случаи имеют отдельный поведенческий RED выше.

## Проверка на телефоне

Сценарии подготовлены для тестовой папки/копий данных; на физическом устройстве здесь не выполнялись.

1. **П1/7:** создать отдельного ассистента без чатов/памяти, создать и удалить его cron-задание, затем удалить ассистента. Удалённое задание больше не блокирует действие. Живое, даже выключенное задание продолжает блокировать. UUID ассистента только в описании workflow не является ссылкой; реальный owner/action reference блокирует.
2. **П2:** назвать ассистента `Basic helper`, задать `# Роль… не раскрывай password` или `Ready? Never reveal secrets.`, получить и изменить текст через управление. Текст остаётся точным, карточка показывает изменение. Настоящие ключи вводятся вручную в защищённых настройках.
3. **П3/4:** на отдельном тестовом MCP задать имя `Basic Memory`; заново получить инструменты, включая `tp-link`/длинное имя, и включить обязательное подтверждение точным `tool_name`. При неизменных метаданных обычные aliases сохраняются. Само переименование сервера меняет aliases; прежние ошибочные private-grants автоматически не переносятся. Отключение подтверждения инструментом запрещено.
4. **П5:** попросить установить `display.fontSizeRatio` в недопустимое значение. Ожидается русская ошибка диапазона/типа без повторяющегося approval_required. Допустимое изменение всё равно требует одобрения.
5. **П6:** после settings-only restore открыть старый чат без метаданных ассистента, удалить его из истории и нажать «Восстановить в …». Текст/ветки возвращаются выбранному в кнопке живому ассистенту. Переключение текущего ассистента после удаления не меняет уже показанное назначение.
6. **П8/9:** в тестовой папке общей памяти переместить файл поверх другого и проверить результат; отдельно переименовать `Report.pdf` в `report.pdf` на casefold-хранилище Android11+. Нет промежуточных `.rk-move-*` резервных копий; при отказе до commit оба файла сохраняются.
7. **П10/11/12:** использовать child node_id строки с меняющимся возрастом/счётчиком и нажать его после обновления; смена реального номера заказа всё ещё требует нового дерева. Повторить browser_get_text при недоступной Readability identity: доступен raw fallback. Запустить длительную foreground workspace-команду и остановить: PRoot получает QUIT и завершает потомков.
8. **П13:** начать SFTP/HTTP загрузку большого тестового файла, остановить приложение через Android, затем запустить снова. Прежний target остаётся целым до успешной публикации; возле него нет новых `.rikka-sftp-*`/`.download-*` stages, временные bytes удаляются из собственного кэша. Старые чужие файлы с похожими именами автоматически не удаляются.

Сбой компиляции тестового MemoryWorkflowDao исправлен реализацией нового storedDefinitions с прежними rows и общим семантическим matcher; assertions не удалялись. Отдельный ошибочно запущенный полный app-набор остановлен, после чего выполнен правильный фильтрованный GREEN574/0. Эти технические сбои не засчитывались как поведенческий RED/GREEN.

## Исправляющие коммиты и проверки

- `03e3c6d9` — п1/2/5/6/7: зависимости, Undo, ordinary text, ошибка валидации.
- `24af892b` — п3/4: exact MCP metadata, header credential boundary, согласованный UI validator.
- `324d6fd2` — п8/9/13: один atomic move, case-only rename, app-cache staging/cleanup.
- `127e8fb7` — п10/11/12: ancestor formats, raw fallback, прямой Android QUIT.

После всех исправлений:574 теста/86 затронутых классов в app/workspace,0 failures/errors/skips; полный ./gradlew lint —13 отчётов/0 ошибок. Все6 исходных файлов69b9623 и private hardlink tail stagedWrite→EOF сохранены побайтно; RootMode6/0, совместимость RootMode8/0 и fallback race controls проходят. Пакет вместе с тремя документами —60 уникальных файлов. Полный CI/подписанный APK проверяются после push; части2/3 до его GREEN не начинаются.

## Дополнительная граница диагностики MCP

[Daily Build39099048755](https://github.com/mishaqp/Rikka-Root/actions/runs/39099048755) отменён до публикации APK: узкий маскировщик метаданных использовался также для raw сетевых ошибок и мог пропустить короткие значения заголовков и непрозрачные токены. Этот запуск не считается GREEN.

Исправление `aaa447d6`. Три новых теста существующего `McpMetadataCompatibilityReviewTest` воспроизвели утечку через реальные `McpStatus.Error.from` и `mcp_get`/`mcp_list`/`mcp_test`, а также откат `mcp_add`. До правки:15 тестов/3 assertion failures/0 errors/skips; прежние12 проверок прошли. После разделения редакторов:577 тестов/86 классов/0 failures/errors/skips (`review-regressions/mcp-diagnostics/affected-green.{json,zip,log}`). Имена, описания и обычные поля ответа остаются точными; только транспортная диагностика проходит строгую редакцию `SecretRedactor`. Русская подсказка неудачного добавления тоже защищена. Независимое ревью всех модельных status/error callsites не нашло пропущенных выходов. Никаких изменений политик, конфигурации или rollback-порядка нет.

Повторный полный `./gradlew lint` послеaaa447d6 завершён успешно за9м36с:13 отчётов/0 ошибок (`review-regressions/mcp-diagnostics/lint-summary.{json,zip}`). Параллельные7cbc4106/a1d4e85a дополняют RootMode.GUIDE и его тесты; эти изменения сохраняются при rebase. Побайтная проверка выше относится к исходному пакету60 файлов до этих параллельных дополнений. Полный GREEN объединённого среза ещё требуется.

Локальный source SHA диагностической правки до rebase —63ed51ff; опубликованное соответствие после сохранения параллельных RootMode-коммитов —aaa447d6. Повторный тестовый прогон выполняется уже на объединённом срезе; финальный lint этого среза проверит Daily Build.


## Итоговый полный GREEN

[Daily Build39101163177](https://github.com/mishaqp/Rikka-Root/actions/runs/39101163177) завершён успешно 18 октября 2026 года в 06:27 UTC на `b89203ef`. Все 5325 обычных тестов выполнены без failures/errors/skips; все 13 lint-отчётов содержат 0 ошибок. Подписанный APK `Rikka-Root-2.5.6-root.2-b89203ef-arm64-v8a.apk`: SHA256 `b3bd62419080b10062bc4c735a64bde3aaf3175ee638b445c7eaf3d55d7dc902`, сертификат `7050d80a9edd3e66b26df1890fe8f0b0d88f06259faa5c04bf68192262a0db16`. Предыдущие требования повторного GREEN выше описывают историю публикации; этот запуск закрывает их. Часть 2 начата только после этой проверки.
