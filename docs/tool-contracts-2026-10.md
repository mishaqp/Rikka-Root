# Контракты инструментов, октябрь 2026

Часть 2 начата после полного [Daily Build39101163177](https://github.com/mishaqp/Rikka-Root/actions/runs/39101163177) на `b89203ef`: 5325 обычных тестов без failures/errors/skips, 13 lint-отчётов, 0 ошибок. [Часть 1 и 60 пунктов](tools-audit-2026-10-status.md), [13 регрессий независимого ревью](tools-review-regressions-2026-10.md). Часть 3 ещё не начата.

Статус: работа идёт; этот документ не объявляет завершёнными ещё не опубликованные группы. Первоначальные RED воспроизводились на производственном коде `b89203ef`; последующие адресные RED — на соответствующих кандидатах, указанных в evidence; параллельный `c56c52a8` добавляет только RootMode.GUIDE и его проверки и сохранён при pull --rebase. Код и тесты режимов «Всё через root» и private hardlink fallback не откатываются.

## Реальный реестр

Общий тест вызывает настоящие `LocalTools` и `ChatToolFactory`, а не отдельный ручной список схем. Покрытие: все 61 варианта `LocalToolOption`, обычный режим, root-режим и недоступный root; контекстные memory/conversation/workspace/skills/cron/workflow/subagent/management; 20 поисковых провайдеров и дополнительный вариант CustomJS со scrape. Всего 24 представления: обычный — 193 инструмента (191 встроенный и 2 внешних MCP fixture), root — 132, fallback — 193; 194 различных имени вместе с поисковыми представлениями. Числа описывают тестовый реестр, а не включённые по умолчанию функции пользователя.

Проверяются русские непустые описания встроенных инструментов и каждого параметра, включая вложенные; имена `[a-zA-Z0-9_-]{1,64}`; типы, required, массивы и композиции; реальные преобразования запроса Claude/Gemini. Внешние MCP имена и описания сохраняются точно, включая язык сервера. Все 20 SDK-поисковиков проверяются на сохранение реальных типов, enum, required и ограничений после русской адаптации metadata. Тест сохраняет JSON inventory в `app/build/reports/tool-contracts-inventory.json`.

## Группы и границы

| Группа | Исправление / проверка | Статус |
|---|---|---|
| Общий runtime и providers | Однозначные строки чисел/boolean перед permissions, карточкой и execute; required/type/enum/nested; неизвестные инструменты и битый JSON возвращаются модели. Уникальные ID до склейки потока и полный ответ; неоднозначные сохранённые вызовы не наследуют одобрение. | Daily Build39107670074: GREEN5419/0; lint13/0 |
| Сохранённые одобрения | Свежая доступная definition и корректные аргументы до chat/Always grant; одобряется конкретная текущая карточка, скрытые альтернативы сохраняются. Защищённые root-команды не получают persistent grants. | Daily Build39107670074: GREEN5419/0; lint13/0 |
| RootMode | Продвинутый root_exec проходит live feature guard только пока режим действительно активен; отзыв режима и все command/headless approval guards остаются. | Daily Build39107670074: GREEN5419/0; lint13/0 |
| Файлы/архивы/download/document | Русские metadata и реальные defaults/bounds; некорректные необязательные флаги и compression отклоняются до файлового/network effect; в подсказках настоящее workspace_shell. | Daily Build39107670074: GREEN5419/0; lint13/0 |
| Root/SSH/Termux/browser/MCP | Русские metadata, фактические limits. Усечение определяется счётчиками, а не строкой внешнего вывода; продолжение не советует повторять изменяющую команду. Browser content/scripts/permissions не усиливаются. | Daily Build39107670074: GREEN5419/0; lint13/0 |
| Телефон/экран и новые локальные tools | Русские metadata, фактические ограничения, проверка параметров до root/Android effects; корректное усечение listings. | Daily Build39110934464: GREEN5504/0; lint13/0 |
| Личные данные/KeyStore | Русские схемы, N+1 для честного truncated/has_more; ограниченные детали и top apps показывают усечение. Scoped секреты и permissions сохраняются. | Daily Build39110934464: GREEN5504/0; lint13/0 |
| Management/workspace/cron/workflow | Русские schemas, строгие leaf-типы, компактные результаты, числовое время, честное продолжение. Старые history-массивы UI остаются читаемыми. | Daily Build39110934464: GREEN5504/0; lint13/0 |
| Остальные info/media/UI и contextual/search/skills | Русские metadata и отдельные runtime/model-prompt исправления при сохранении effects/approval-политик; фактические лимиты, точные альтернативы. | Пакет 2C: локальный GREEN2145/0, lint выполняется; полный CI ожидается |
| Мягкое предупреждение цикла | Повтор того же canonical вызова в текущем ходе помечается только в provider-copy, внутри общего cap; действия не блокируются, сохранённый JSON не изменяется. | Daily Build39107670074: GREEN5419/0; lint13/0 |

Публикация разбита на пакеты до 60 уникальных путей с отдельным зелёным Daily Build. Общий тест, который проверяет ещё не исправленные группы, будет опубликован вместе с последней группой; существующие проверки не отключаются и не ослабляются.

## Доказательства RED

Каждый прогон проверен по свежим JUnit XML. Эти пересекающиеся наборы **не суммируются** как уникальные тесты/ошибки.

| Прогон | Тесты / failures / errors / skips | Срез и назначение |
|---|---|---|
| batch1 | 33 / 20 / 0 / 0 | execution metadata + файлы/download |
| batch2 | 27 / 23 / 0 / 0 | RootMode live guards + телефон/экран |
| batch3 | 71 / 52 / 0 / 0 | Реальный общий реестр, runtime, management/workspace/workflow, file alternative |
| batch4 | 13 / 12 / 0 / 0 | Info/media/personal metadata + service; одна неверная fixture protected command исправлена отдельно, не считается производственным багом |
| batch5b | 161 / 52 / 0 / 0 | Runtime unions, исправленная protected fixture (контроль GREEN), execution messages/truncation, search preservation, personal N+1, история workflow/cron |
| batch6-ai | 4 / 4 / 0 / 0 | Claude/Gemini/Responses/Interactions: одинаковые provider IDs склеивают разные вызовы |
| batch7-cap | 11 / 11 / 0 / 0 | Включая настоящий DAO/repository: при limit=100 старый cap мешал прочитать 101-ю строку истории |
| batch9-ai | 5 / 5 / 0 / 0 | Дополнительно Responses без необязательного item.id |
| batch9-phone | 24 / 9 / 0 / 0 | Переполнение display_id подтверждено; два HTTP header-preservation контроля GREEN на исходном коде |
| batch6-app | 39 / 33 / 0 / 0 | Cross-message pending IDs, скрытая альтернатива, большое повторение, top apps и scalar clipping |

В первом batch5 компиляция новой PersonalPagination fixture остановилась до выполнения тестов: смешанный массив получил неподходящий inferred reified type. Явный `Any?` исправил только fixture; assertions не менялись. Этот запуск и первоначально ошибочно собранные старые XML обозначены `NOT-RED` и не используются как доказательство. Подтверждённые результаты — batch5b. В batch6 тесты модулей выполнялись раздельно после того, как падение ai остановило общий граф; старые app XML не учитывались.

## Проверка на телефоне после полного GREEN части 2

1. Обычный режим: попросить «Покажи заряд и свободное место», затем включить «Всё через root» и повторить. При отключении режима старый root-вызов не должен получать новые автоматические права.
2. «Создай ZIP из /scratch/проверка.txt в /scratch/проверка.zip»; некорректный overwrite/compression должен вернуть русскую ошибку до изменения файла. Повтор без overwrite сохраняет прежний ZIP.
3. Попросить несколько независимых чтений в одном ответе у Claude/Gemini/OpenAI-compatible. Результаты должны относиться к своим карточкам, остановка не должна запускать оставшиеся действия.
4. Попросить ограниченный список контактов/событий, экранного времени или истории задач: сокращение явно обозначено; не обещается несуществующий cursor.
5. Получить длинный root/Termux/SSH/browser вывод: видны truncated и способ прочитать нужный фрагмент, без автоматического повтора изменяющей команды.
6. Открыть старый чат с результатами recent_chats/conversation_search: прежние карточки по-прежнему читаются. Проверить собственные разрешения и изоляцию разных ассистентов.

Итоговые коммиты, GREEN и APK будут добавлены после публикации соответствующих пакетов. Полный read-only аудит приложения из части 3 ещё не выполнялся.


## Независимая проверка черновиков до публикации

Ревью runtime выявило и воспроизвело склейку ID в четырёх дополнительных декодерах, конфликт сохранённых pending ID между сообщениями и исчезновение repeat warning при длинном выводе. Они закрываются в той же группе. Обычный завершённый вызов в прошлом ходе с тем же ID не блокирует единственный новый вызов.

Консервативная проверка union не выдаёт неизвестные ограничения (pattern/minimum/$ref и подобные) за доказательство неоднозначности. Три native-value контроля прошли на исходном runtime и упали на первом черновике нормализатора; исправление сохраняет native JSON, не запускает regex и не добавляет валидатор всех JSON Schema. Отдельный контроль запрещает выбирать ветку преобразования строкового числа, если неподдерживаемое ограничение может сделать выбор неоднозначным: на central01 GREEN, на central02 RED (53 теста/1 assertion failure), затем минимальная корректировка. Эти случаи отмечены как предотвращённые регрессии черновика, а не дефекты прежнего опубликованного HEAD.

Первый интеграционный прогон: 283 теста/5 failures/0 errors/skips. Исправлены пропущенное metadata description MCP, неверная protected-command fixture и ожидание мгновенной задачи в трёх новых service tests. Фикстуры теперь используют настоящий reboot bootloader и retained error/factory gate; все security assertions и 15-секундные таймауты сохранены. В предыдущем batch4 protected fixture тоже исправлена таким же отдельным образом.

Ревью телефонного черновика до его применения выявило случайную вставку description в runtime headers HTTP: два настоящих network-контроля сохраняют исходные заголовки и предотвращают это изменение. Переполнение display_id за Int отдельно воспроизведено до root effect. Ни один дефект черновика не объявляется опубликованным исправлением до собственного GREEN.


## Пакет 2A: runtime, файлы и выполнение команд

Пакет содержит 47 уникальных путей: runtime/одобрения/RootMode/provider identity —17, файлы —9, выполнение команд/MCP/browser —17, документация —4. Это первая группа части 2, а не завершение всего реестра. Пакет 2B с телефонными/personal/management контрактами прошёл локальную проверку и ожидает собственный Daily Build; пакет 2C с оставшимися misc/contextual контрактами ожидается.

Расширенный локальный прогон `package-a-affected-green`: 1546 тестов в app/ai/workspace, 0 failures/errors/skips. В него входят все тесты изменённых путей, 23 runtime-контракта, 6 service approvals, 7 RootMode boundary, 5 decoder identity, прежние command/privacy/file/cancellation/ownership controls и все unit-тесты ai. Исправлены обе предотвращённые union-регрессии черновика; все assertions и таймауты сохранены. Полный `./gradlew lint` успешно завершён за 9м03с: 13 отчётов, 0 ошибок без подавлений. Полный [Daily Build39107670074](https://github.com/mishaqp/Rikka-Root/actions/runs/39107670074) этого пакета завершился успешно: **5419 обычных тестов,0 failures/errors/skips;13 lint-отчётов,0 ошибок**. Исходный SHA `3d902421`, отчёты проверены независимо по SHA256.


Коммиты исходного кода пакета 2A: runtime — `e024cb92`, файлы — `a74ec782`, выполнение команд — `b17f6ea1`. Новый полный Daily Build обязателен перед публикацией следующей группы.

## Пакет 2B: телефон, личные данные и management

Пакет содержит 57 уникальных путей: телефон/экран —23, личные данные/KeyStore —13, management/workspace/cron/workflow —20, этот документ —1. Пакет опубликован после полного GREEN пакета 2A; локальные проверки 2B завершены. Собственный полный Daily Build2B обязателен перед публикацией 2C. Часть 2 ещё не завершена: пакет 2C и финальная проверка общего реестра остаются впереди. Часть 3 не начата.

- Телефон/экран: русские описания 39 создателей инструментов и их параметров, реальные defaults/limits/альтернативы. Некорректные screenshot-параметры, включая переполнение display_id, отклоняются до root-вызова; поддерживается только display_id=0. Clipboard не превращает произвольный JSON в текст и проверяет reveal; NFC принимает язык по умолчанию только при отсутствии поля. Списки приложений/activities сообщают усечение. HTTP сохраняет настоящие headers; разрешения, root-guards, приватное хранение снимков и подтверждения сохранены.
- Личные данные: русские metadata для 21 создателя; contacts/calls/SMS/calendar читают дополнительную строку и сообщают фактические count/truncated/has_more без выдуманного общего размера. Вложенные phones/emails ограничены 20 с явным признаком усечения; сокращённые имена/номера/адреса помечаются отдельно. ScreenTime сообщает returned/total_apps, сохраняя total_ms по всем приложениям. Продолжение предлагает только существующие фильтры или увеличение ограниченного limit/top, без обещания cursor. Scoped KeyStore handles, plaintext/zeroing, permissions и crypto effects не менялись.
- Management/workspace: русские схемы и ошибки; неверные leaf-типы отклоняются до изменения памяти, файлов и сценариев. Произвольные JSON-значения customBodies сохраняются через anyOf всех JSON-типов. recent_chats/conversation_search возвращают компактный объект items/count/truncated/total; total=null при неизвестном полном размере. Чтение N+1 сохраняет owner-фильтры; UI понимает прежние массивы истории и новые items. Workflow-время возвращается числами, усечение сводки и фонового workspace-вывода обозначено явно.
- Cron/workflow history: workflow_get читает11/возвращает10; cron history читает limit+1/возвращает limit, включая границу100 и default20. Публичный repository.recent по-прежнему ограничен100. Полный размер обрезанной истории неизвестен и возвращается null; сообщения прямо указывают пределы продолжения. list_jobs использует реальный count, не обещает paging или полный список через UI активных задач. Ownership, Undo, RootMode, approval-политики и Room schema сохранены.
- Workflow follow-up: признак усечения теперь приходит от настоящего runner через RunResult/FireOutcome, включая вывод одного действия длиннее200 и успешную суммарную сводку длиннее2000. Tool также проверяет собственный предел2000 для совместимости с прежним FireOutcome. Во всех трёх местах ограничения остаются в единицах UTF-16; корректная surrogate pair не разрезается, поэтому безопасный префикс иногда на одну единицу короче лимита. Русское предупреждение о прерванном/неопределённом выполнении сохраняется вместе с сообщением об усечении и запретом автоматического повтора ради вывода. Короткий пользовательский текст с буквальным `[truncated]` не создаёт ложного флага.
- Язык workflow-ошибок: отсутствующий сценарий, удаление между чтением и записью, неизвестный action.tool и запрещённая цепочка workflow_run возвращают русские app-owned detail. Stable codes, номер действия/условия и имя недоступного инструмента сохранены; parser/parseStored не менялись. Raw decoder exception не выдаётся модели вместо пояснения. Английские пользовательские name/description/args и внешние данные не переводятся.

### RED и локальная проверка 2B

Ниже — срезы фактических прогонов; пересекающиеся наборы не суммируются. Ссылки ведут на локальные evidence вне репозитория; рядом с каждым JSON сохранены свежие JUnit ZIP и log.

| Evidence | Тесты / failures / errors / skips | Проверяемый срез |
|---|---|---|
| [batch2-red](/workspace/.reliability-state/part2/batch2-red.json) | 20 /18 /0 /0 | PhoneScreenContract11/10 + PhoneScreenParameter9/8; полный batch27/23 |
| [batch3-red](/workspace/.reliability-state/part2/batch3-red.json) | 31 /28 /0 /0 | Management schema10/9, data13/11, workflow/cron8/8; полный batch71/52 |
| [batch5b-red](/workspace/.reliability-state/part2/batch5b-red.json) | 9 /8 /0 /0 и10 /10 /0 /0 | PersonalPagination9 и WorkflowCron10, включая обе history-регрессии; полный batch161/52 |
| [batch6-app-red](/workspace/.reliability-state/part2/batch6-app-red.json) | 14 /13 /0 /0 | PersonalPagination с ScreenTime/scalar controls; полный batch39/33 |
| [batch7-cap-red](/workspace/.reliability-state/part2/batch7-cap-red.json) | 11 /11 /0 /0 | Реальный cron tool/repository запросил DAO100 вместо101; это assertion RED на прежнем API |
| [batch9-phone-review-red](/workspace/.reliability-state/part2/batch9-phone-review-red.json) | 24 /9 /0 /0 | Display overflow RED; прежние HTTP controls и два новых header-preservation контроля GREEN |
| [package-b-followup-red](/workspace/.reliability-state/part2/package-b-followup-red.json) | 17 /10 /0 /0 | Owned errors10/7 (3 контроля GREEN); workflow summary7/3 (4 контроля GREEN): потерянное предупреждение, заранее обрезанная успешная сводка, одно длинное действие с surrogate pair |

Первый локальный [package-b-initial-green](/workspace/.reliability-state/part2/package-b-initial-green.json): 87 тестов, 0 failures/errors/skips. Расширенный [package-b-affected-green](/workspace/.reliability-state/part2/package-b-affected-green.json): 602 теста, 0 failures/errors/skips. После follow-up финальный [package-b-final-affected-green](/workspace/.reliability-state/part2/package-b-final-affected-green.json): **638 тестов, 0 failures/errors/skips**, включая все прежние602, всю группу workflow.execution,10 owned-error и7 summary/cancellation тестов. Сохранены ownership/race/Undo, memory-isolation, privacy, permissions, approval, RootMode, path traversal, cron/workflow и transport controls.

[Предыдущий lint](/workspace/.reliability-state/part2/package-b-lint-summary.json) до follow-up завершился за8м25с:13 отчётов,0 ошибок. Его результат не выдается за проверку новых изменений. **Финальный полный lint завершился успешно за9м05с:13 отчётов,0 ошибок**, без подавлений: [package-b-final-lint-summary.json](/workspace/.reliability-state/part2/package-b-final-lint-summary.json). Это локальные проверки 2B, не полный Daily Build и не завершение части2.

До follow-up сверка17 management-путей с переданными артефактами дала побайтное совпадение; follow-up расширил группу до20 путей и проверен финальным GREEN638. В трёх новых тестклассах сохранены все101 строки assertions/verify из RED. Две прежние fixture изменены только для дополнительного search-limit, нового history-reader и чтения items; точный owner в FTS-моке и все isolation assertions сохранены. Существующие проверки не отключались и не ослаблялись.

Коммиты исходного кода 2B: телефон — `4540b4b8`, личные данные — `1a630c9b`, management — `579f1dd8`. Полный CI2A39107670074 подтверждён. [CI2B39110934464](https://github.com/mishaqp/Rikka-Root/actions/runs/39110934464) полностью GREEN:5504 обычных теста,0 failures/errors/skips;13 lint-отчётов/0 ошибок. Подпись, единственный arm64 ABI и SHA256 APK совпали с опубликованным release asset.

## Пакет 2C: общий реестр, оставшиеся metadata и модельные результаты

**Кандидат 2C собран локально; финальный локальный GREEN2145/0 подтверждён, lint13/0 подтверждён, полный CI ещё ожидается.** Промежуточные проверки отдельных классов приведены отдельно и не означают завершения пакета. Ниже описан scope кандидата и фактический RED, а не опубликованный результат. Итоговый manifest содержит53 уникальных пути; три коммита исходного кода указаны ниже; пересекающиеся слои нельзя складывать как число файлов. Пакет 2A имеет подтверждённый полный GREEN; пакет 2B опубликован, полный CI39110934464 GREEN5504/0 и lint13/0 подтверждены. Часть2 и общий read-only аудит части3 не объявляются завершёнными.

| Слой 2C | Контракт кандидата | Локальная проверка |
|---|---|---|
| GLOBAL: настоящий реестр и schema transforms | Восемь тестов вызывают LocalTools/ChatToolFactory во всех описанных выше представлениях. Проверяются RU metadata встроенных tools, names, required/types/enum/nested/compositions и реальные Claude/Gemini преобразования; все20 SDK search providers и CustomJS scrape-вариант сохраняют недескриптивные schema nodes. Внешний MCP metadata/dispatch остаётся точным. | Локальный GREEN2145/0; полный CI2C ожидается |
| Misc metadata:17 source paths |26 создателей info/media/UI/storage/sensor/notification/JS и связанных tools: русские описания, реальные defaults, caps и альтернативы. Dynamic значения не объявляются константными defaults; empty chart arrays не получают выдуманный minItems. Metadata-слой сохраняет execute/approval/systemPrompt. | Локальный GREEN2145/0; полный CI2C ожидается |
| Contextual metadata:7 source paths | Search, skills/install/run_js, subagent и external automation: перевод встроенных descriptions. Search adapter меняет только descriptions13 существующих input keys, сохраняя required, типы, enum и ограничения SDK; effects не меняются. | Локальный GREEN2145/0; полный CI2C ожидается |
| Встроенные model prompts:3 source paths | Русское framing lazy skills, статические инструкции MiMo и workspace reminder (Bash/POSIX, AGENTS.md). Пользовательские skill/body/system instructions, имена/пути, XML/API markers, китайские MiMo tags и exact examples сохраняются. RootMode.GUIDE и attachment prompt не меняются. | Локальный GREEN2145/0; полный CI2C ожидается |
| Runtime results:8 source paths | Русские owned skill/install/run_js/JavaScript diagnostics; compact JSON для пустого успешного run_js; реальные flags/счётчики storage и subagent. Лимиты, permissions, path/namespace/network guards и approvals сохраняются. | Локальный GREEN2145/0; полный CI2C ожидается |
| Metadata accuracy:3 source paths | Машинные bounds/defaults субагента соответствуют реальным константам. run_js data — до65536 единиц UTF-16, skill_install_from_text content — до262144 единиц UTF-16, а не байтов. Установщик не обещает второго review итогового имени: подтверждается вызов, имя вычисляется при исполнении и возвращается в результате. | Локальный GREEN2145/0; полный CI2C ожидается |
| Notification accuracy:1 source path | post_notification.id описывает фактический диапазон0..2147483647; dynamic generated id не получает schema default. Прежнее отклонение переполнения до Android effect сохраняется. | Локальный GREEN2145/0; полный CI2C ожидается |
| Provider output / archive | Generic JSON-конверт в пределах прежнего provider cap, безопасный архив с непрозрачным именем и привязанным дескриптором каталога; исходный результат и полномочия сохраняются. | Локальный GREEN2145/0; полный CI2C ожидается |
| Numeric integer guard | Дробный BigDecimal отклоняется до потенциально дорогого преобразования масштаба; native/union/freeform семантика сохраняется. | Локальный GREEN2145/0; полный CI2C ожидается |

Эти числа описывают слои, а не непересекающиеся группы: например SkillsTools входит в metadata, runtime и model-prompt изменения. Итоговый manifest: 53 уникальных пути (28 misc/prompt,18 contextual/реестр,5 runtime/output,2 docs). Коммиты: `6dbd80af`, `e8332789`, `32be5b9a`.

### Результаты и границы данных

Runtime draft локализует только текст, которым приложение объясняет операцию. JavaScript result/stdout и пользовательские исключения остаются точными; английский текст навыка, его metadata и пользовательские instructions не переводятся. Для known owned JS timeout/output-limit причины меняются в месте создания; фактический logs_truncated сопровождает прежние ограничения вывода. Старые literal-only locale expectations обновлены отдельной fixture-поправкой с сохранением size/recovery/cancellation/user-data assertions; их проверки входят в GREEN2145/0. Импортёр навыков и его сетевые проверки не меняются; tool boundary различает известный owned diagnostic и внешнюю ошибку.

Storage listings сохраняют caps64 volumes/256 directories. count — выданные строки, total — размер фактического snapshot, для grants только readable tree permissions. truncated вычисляется по этим данным, сообщение честно объясняет отсутствие paging; не создаются новые разрешения или доступ.

Subagent draft сохраняет максимум65536 единиц UTF-16, не разрезая корректную surrogate pair. Engine передаёт известные result_truncated/result_total_chars в сериализуемую запись, затем проверяется настоящий Registry→AES-GCM storage→reload→subagent_get путь. Для нового результата ровно на границе флаг false, для превышения true; отсутствие результата не используется для догадок. Старые зашифрованные записи без полей остаются с неизвестными metadata даже при длине65536. При noResult тело и производные длина/флаг не сохраняются и не выдаются; result_suppressed остаётся. Это additive nullable metadata в существующем envelope, без Room migration и изменения cipher/owner/status/cancellation.

В draft review обнаружено, что errorCode перезаписывает подсказку об усечённом частичном результате. После actual RED объяснение ошибки для родительского ассистента объединено с truncation/non-replay hint: subagent_get не восстанавливает отброшенный хвост, повтор задачи с побочными действиями не предлагается ради вывода. Семь subagent-тестов прошли в промежуточном output-proposal-check и входят в финальный локальный GREEN2145/0; полный CI2C ещё требуется.

### Общий provider cap, архив и числовая граница

Generic overflow теперь представляет provider-copy как компактный JSON `{truncated:true,total_chars,preview,message}`. Сериализованный конверт целиком, включая escaping и русскую подсказку, помещается в прежние32768 единиц UTF-16. preview — точный безопасный префикс без разрыва surrogate pair; total_chars измеряет исходный объединённый текст, включая настоящий repeat warning и разделитель. Полный вход не парсится и не сериализуется целиком ради обрезки. Structured-search/citation обработка остаётся первой, изображения и прочие nontext части сохраняются; сохранённые сообщения, ID/input/approval/web-taint не меняются. Подсказка не обещает несуществующего retrieval path и не советует повторять изменяющее действие.

Архив полного вывода использует отдельный непрозрачный UUID basename, эксклюзивное создание и тот же открытый канал для записи; toolCallId не становится частью пути. JSON-квитанция содержит `/tool_outputs/` path, UTF-16-safe preview до4096, truncated/total_chars и русское сообщение, не подменяя успех исходного инструмента. Проверка parent-directory закрывает как заранее подставленный symlink, так и замену каталога между проверкой и открытием. Кандидат привязывает относительный CREATE_NEW/NOFOLLOW write к SecureDirectoryStream, сверяет идентичность каталога по fd/fileKey и NOFOLLOW до/после записи. При IOException/неподдерживаемой файловой системе возвращается точный исходный результат; удаление потенциально подменённого pathname не выполняется. Media, исходный raw текст, идентификаторы и подтверждения сохраняются. Целевые96/0 и расширенные2145/0 подтвердили эту версию; полный CI2C ожидается.

Отдельный числовой RED использует безопасный witness `1e-10000`: при объявленном integer старый путь дошёл до перехваченного scale-expanding conversion, хотя обязан был отклонить ненулевую дробь раньше. Huge exponent/OOM не исполнялся и on-device исчерпание ресурсов не заявляется. Минимальная правка проверяет `stripTrailingZeros().scale() > 0` перед `toBigIntegerExact()`; zero,5.0,1e2,-3.000 остаются корректными. Number/scientific form, native unions, произвольные строки и approvals не получают нового глобального exponent limit. Genuine RED27/1 включает26 GREEN controls; финальный локальный GREEN2145/0 подтверждён; полный CI2C ожидается.

### Фактические RED 2C

Срезы пересекаются; их нельзя суммировать как уникальные тесты или ошибки. Все приведённые результаты получены из свежих JUnit, без errors/skips.

| Evidence | Тесты / failures / errors / skips | Что действительно проверено |
|---|---|---|
| [package-c-extra-red](/workspace/.reliability-state/part2/package-c-extra-red.json) |39 /20 /0 /0 | Runtime/storage/skills/JS/subagent и встроенные prompts; runtime-срез22/17 с5 GREEN controls |
| [package-c-accuracy-red](/workspace/.reliability-state/part2/package-c-accuracy-red.json) |14 /12 /0 /0 | Accuracy7/7; Subagent7/5, включая частичный failed-result/message collision. Non-ASCII boundary и отсутствие effects проверены до metadata assertions |
| [package-c-notification-red](/workspace/.reliability-state/part2/package-c-notification-red.json) |1 /1 /0 /0 | Настоящие2147483648/Long.MAX_VALUE отклоняются без Context effects, затем падает assertion отсутствующего schema maximum |
| [output-boundary, corrected fixture](/workspace/.reliability-state/part2/package-c-output-boundary-red-corrected-fixture.json) |18 /14 /0 /0 | Generic provider7/5 и архив11/9;2 контроли каждого класса GREEN. Не смешивается с первоначальными невалидными fixtures |
| [archive parent RED](/workspace/.reliability-state/part2/package-c-archive-parent-red.json) |1 /1 /0 /0 | Настоящая запись UUID-файла за пределами архива через parent symlink |
| [archive race, private seam RED](/workspace/.reliability-state/part2/package-c-archive-parent-race-seam-red.json) |1 /1 /0 /0 | Выполнена подмена parent перед открытием, затем наблюдена настоящая запись во внешний каталог |
| [numeric compatible fixture RED](/workspace/.reliability-state/part2/package-c-numeric-red-compatible-fixture.json) |27 /1 /0 /0 | unsafeEntries ожидался0, получен1 для1e-10000;26 остальных controls GREEN |

В extra batch отдельные noResult и legacy-unknown контроли прошли на исходном коде; это не дефекты и не RED. Также прошли exact skill content, внешние JS text/errors и пользовательские JS logs/results. Model-prompt controls сохранили AGENTS.md/user body, имена/пути и MiMo protocol tags/examples. Их сохранность повторно подтверждена после применения всех слоёв в финальном локальном GREEN2145/0; полный CI2C ещё требуется. Первоначальная notification fixture остановилась на пропущенном import `io.mockk.Called`: это NOT-RED; исправлен только import, приведённый выше1/1 — последующий реальный assertion RED.

Первые archive fixtures потребовали исправления Robolectric Application/manifest (повторный запуск Koin) и ошибочного escaping в filename regex; ранние fixture ошибки не используются вместо соответствующих поведенческих доказательств. Исправленный прогон18/14 приведён отдельно. Дальнейшие варианты race со static mock `Files` ломались на classloading/MockK: эти запуски **NOT-RED**, независимо от числа отмеченных failures. Genuine race получен после private I/O seam, сохранившего прежний unsafe writer по умолчанию и все13 domain tests/assertions; положительное наблюдение — внешний UUID-файл после фактической подмены. Это воспроизведение на кандидате, а не утверждение об отдельном опубликованном исправлении. Первые numeric module-access/fixture ошибки также NOT-RED; учитывается только совместимый прогон27/1.

Промежуточные проверки: [proposal-initial](/workspace/.reliability-state/part2/package-c-proposal-initial.json)72/1, в том числе настоящий GLOBAL8/0; единственный отказ относился к partial-result hint. [output-proposal-check](/workspace/.reliability-state/part2/package-c-output-proposal-check.json)91/2: generic7/0 и subagent7/0, но parent archive ещё RED, а старый широкий substring assertion ошибочно считал фразу «Не повторяйте» в safety guidance признаком loop warning. Fixture теперь проверяет actual preview, точные correlation IDs/counts/full saved output и общий cap; security assertions не ослаблены. Эти промежуточные итоги не складываются с RED или между собой и не означают финальный GREEN2C.

Финальные результаты 2C: локальный **2145 тестов,0 failures/errors/skips** ([сводка свежих XML](/workspace/.reliability-state/part2/package-c-final-affected-green-v2.json)), полный lint **13 отчётов/0 ошибок,10м01с** ([сводка](/workspace/.reliability-state/part2/package-c-final-lint-summary.json)), Daily Build `ожидается после публикации2C`, APK `будет проверен по завершении CI2C`. До этих проверок общий реестр и вся часть2 остаются незавершёнными.


Дополнительное заключительное ревью воспроизвело две app-owned английские ошибки: `set_text` для нередактируемого элемента и `skill_get_content` с пустым/пробельным именем после настоящего `prepareArguments`. [RED2/2](/workspace/.reliability-state/part2/package-c-owned-errors-red.json), без errors/skips. Исправлены только recovery/detail, коды и отсутствие input/read effects сохраняются. Старый locale-only assertion заголовка навыков обновлён с `Skills` на `Навыки`, остальные lazy/user-body assertions сохранены. Расширенный промежуточный прогон2143/1 показал только этот устаревший literal; финальная проверка2145/0 завершена.


### Дополнительная проверка 2C на телефоне

1. В обычном режиме попросить заряд, Wi-Fi и список хранилищ; включить «Всё через root» и повторить. Описания и пояснения ошибок русские; root-команды сохраняют обязательные подтверждения.
2. Попросить `workspace_shell` вывести длинный тестовый текст, затем прочитать нужный фрагмент по возвращённому `path`. Ответ явно помечает усечение; повтор действия для получения хвоста не предлагается автоматически.
3. Получить большой результат без workspace: модель получает ограниченный JSON с `truncated`, пользователь видит полный исходный результат в истории; остановка ответа продолжает работать.
4. Попросить ввести текст в нередактируемую подпись на экране: видна русская подсказка, нажатие/ввод не выполняется. Пустое имя навыка возвращает понятную ошибку и доступные имена.
5. Повторить поиск у настроенного провайдера и загрузку собственного навыка. Поисковые citations, точные имена/пути и пользовательский текст навыка сохраняются.


Финальный локальный GREEN2C: app1869 + ai267 + workspace3 + speech6 =2145,0 failures/errors/skips; CI-скрипты34/0, YAML валиден, inventory добавлен в существующий artifact отчётов. `RootMode`, его исходный тест, mandatory/Hardline/Root/Termux policies побайтно сохранены относительно2B. Полный lint завершён успешно:13 отчётов/0 ошибок,10м01с; полный CI2C ещё обязателен.


Проверенный APK2B: `Rikka-Root-2.5.6-root.2-cf323874-arm64-v8a.apk`, SHA256 `cc7e512ff17a643fee0454e6839a06aaaaf16f64e6eba874feb507860a8de0be`; сертификат7050d80a…2a0db16. Следующая публикация2C требует собственной полной проверки.
