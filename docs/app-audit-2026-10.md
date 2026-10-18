# Аудит приложения Rikka-Root, октябрь 2026

Сначала выполнено только чтение. Исходное состояние — `d7f6ec1a1409c85d7ae76ac56371d235aa8e119a`, после полного [Daily Build39113991394](https://github.com/mishaqp/Rikka-Root/actions/runs/39113991394):5586 обычных тестов без failures/errors/skips,13 lint-отчётов/0 ошибок, подписанный arm64 APK проверен. Этот отчёт записан **до любых производственных исправлений и новых тестов части3**.

Проверены чат и очередь/остановка/ветки/восстановление; OpenAI-compatible/Claude/Gemini, сетевые ошибки/стримы/вложения/reasoning; S3/WebDAV/локальные и сторонние бэкапы; настройки и все зарегистрированные переходы Room; фоновые сервисы/задачи/Doze; память, большие документы/изображения и работа на главном потоке.

Исходный итог: **1 критичная,35 важных,11 небольших находок**. Это доказательства по коду и конкретные сценарии, ещё не результаты RED/GREEN. Зависящие от устройства последствия (CursorWindow, ANR, принудительное завершение Android) обозначены явно; они не выдаются за наблюдавшийся сбой телефона. Не подтвердившиеся leads отдельно перечислены в исходных разделах. Исключение пользователя для обычных plaintext-настроек в бэкапах не считается нарушением.

## Текущий статус обязательных исправлений

Пока для всех пунктов ниже: **открыто; регрессионный RED и исправление ещё не выполнены**. После проверки здесь появятся тест, коммит и результат. Нумерация PERF отделена от provider P.

| ID | Приоритет | Проблема | Статус / коммит |
|---|---|---|---|
| SROOM-01 | критично | Миграция 15→16 безвозвратно теряет непривязанные результаты старых инструментов | Открыто; RED ожидается |
| SROOM-02 | важно | Пропущенная в 11→12 большая история остаётся вне активного представления и не защищена от последующего очищения | Открыто; RED ожидается |
| SROOM-03 | важно | V3 настроек заменяет непарсимые quickMessages пустым массивом и подтверждает миграцию | Открыто; RED ожидается |
| SROOM-04 | важно | Сбой резервного сохранения повреждённого DataStore всё равно разрешает уничтожение оригинала | Открыто; RED ожидается |
| SROOM-05 | важно | Переход 6→7 не применяет известное преобразование старых discriminator имён и блокирует корректную старую историю | Открыто; RED ожидается |
| CHAT-01 | важно | Осиротевший чат A получает память и scoped tools другого ассистента B | Открыто; RED ожидается |
| CHAT-02 | важно | History Undo восстанавливает ссылки уже удалённых вложений | Открыто; RED ожидается |
| CHAT-03 | важно | Две параллельные правки теряют одну принятую версию сообщения | Открыто; RED ожидается |
| CHAT-04 | важно | Удалённое через Web сообщение возвращается следующим stream chunk | Открыто; RED ожидается |
| CHAT-05 | важно | Stale user regenerate может очистить всю историю | Открыто; RED ожидается |
| CHAT-06 | важно | Удалённый целиком генерирующий чат воссоздаётся final save | Открыто; RED ожидается |
| P-01 | важно | HTTP-ошибка SSE с пустым/не-JSON body превращается в успешное завершение | Открыто; RED ожидается |
| P-02 | важно | EOF без terminal event считается полным ответом, включая tool call | Открыто; RED ожидается |
| P-03 | важно | Отмена после HTTP headers не освобождает nonstream body read | Открыто; RED ожидается |
| P-04 | важно | Claude adaptive thinking отправляется моделям, которые его не поддерживают | Открыто; RED ожидается |
| P-05 | важно | Claude redacted_thinking теряется и ломает tool-result roundtrip | Открыто; RED ожидается |
| P-06 | важно | Допустимый отказ модели refusal не отображается | Открыто; RED ожидается |
| P-07 | важно | Responses built-in image generation без streaming теряет изображение | Открыто; RED ожидается |
| P-08 | важно | HTTP image URL вставляется в поле raw base64 у Claude и Gemini generateContent | Открыто; RED ожидается |
| B1 | важно | локальный экспорт сообщает успех без файла; reminder обновляется до сохранения | Открыто; RED ожидается |
| B2 | важно | после 1000 S3 backups последние резервные копии не видны | Открыто; RED ожидается |
| B3 | важно | WebDAV GET/DELETE адресует displayName вместо фактического href | Открыто; RED ожидается |
| B4 | важно | Cherry import объявлен завершённым до сохранения настроек | Открыто; RED ожидается |
| B5 | важно | native DB restore не переносит абсолютные attachment URLs на другой Android profile | Открыто; RED ожидается |
| B6 | важно | Cherry import печатает API credentials в logcat | Открыто; RED ожидается |
| B7 | важно | Chatbox streaming failure оставляет неотмеченный частичный импорт | Открыто; RED ожидается |
| PERF-1 | важно | Импорт вложений полностью копирует данные на главном потоке | Открыто; RED ожидается |
| PERF-2 | важно | Декодирование и сохранение изображений генерации выполняются на главном потоке | Открыто; RED ожидается |
| PERF-3 | важно | Вложения чата обходят существующие ограничения извлечения документов | Открыто; RED ожидается |
| PERF-4 | важно | Допустимый большой результат хранится в одной Room TEXT-строке; чтение зависит от CursorWindow устройства | Открыто; RED ожидается |
| PERF-5 | важно | Нормализация base64-изображения декодирует полноразмерный Bitmap | Открыто; RED ожидается |
| BG-1 | важно | старый RELEASE media FGS останавливает сервис при уже поставленном новом ACQUIRE | Открыто; RED ожидается |
| BG-2 | важно | ошибка/отмена live lookup удаляет durable workflow occurrence как отсутствующий workflow | Открыто; RED ожидается |
| BG-3 | важно | ошибка terminal persistence workflow скрывается, оставляя RUNNING и обход cooldown для следующего запуска | Открыто; RED ожидается |
| BG-4 | важно | recovery ставится completed навсегда после пропуска временно нечитаемого чата | Открыто; RED ожидается |
| BG-5 | важно | boot workflow после receiver handoff не имеет durable Android execution owner | Открыто; RED ожидается |

## Подробности исходного аудита

Далее зафиксировано состояние **до исправлений**. Указанные файл:строка относятся к `d7f6ec1a`; формулировки дефектов и предлагаемых тестов сохранены для проверки. Актуальный статус определяется таблицей выше, а не прошедшим временем в исходных заметках. Исходные разделы не означают, что новые тесты уже запускались.


---

# Part 3 — настройки, значения по умолчанию и миграции Room

Дата: 2026-10-18. База: текущие исходники после d7f6ec1a (полный GREEN сообщил root). Проверка только чтением. Main, Git, Gradle, зависимости и тесты не менялись и не запускались. Ниже **доказательства по коду**, а не заявления об уже полученном RED. Пути относительно `/workspace/Rikka-Root`.

Вердикт: целостность данных требует исправлений. Critical — 1; Important — 4; Minor — 1. Предположение об успешном удалении встроенного ассистента снято: реальный путь уже запрещает это действие.

## Critical

### SROOM-01. Миграция 15→16 безвозвратно теряет непривязанные результаты старых инструментов

**Места:** `ai/src/main/java/me/rerere/ai/ui/Message.kt:461`, `:482`, `:529`, `:536`, `:425`; `app/src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_15_16.kt:62`, `:73`, `:84`.

**Вход:** БД v15, два корректно сериализуемых узла одного разговора: ASSISTANT с `ToolCall(toolCallId="call1")`, затем TOOL с `ToolResult(toolCallId="call2", content="единственная сохранённая копия результата")`. Вариант: USER, затем TOOL с таким результатом без предыдущего ASSISTANT. Это неполная связь, а не неразбираемый JSON; миграция обязана сохранить доступные исходные данные либо отказать без записи.

**Путь:** `migrateToolNodes` для первого варианта обнаруживает предыдущий ASSISTANT, не находит совпадение call2, но всё равно пропускает весь TOOL-узел на :529–531. Во втором варианте вызывает `migrateToolMessages`; он исключает TOOL-сообщение на :425–427 независимо от успешного переноса, оставляя узел с пустым списком сообщений. `Migration_15_16` видит изменение, удаляет все исходные строки разговора и записывает полученный результат; транзакция успешно завершается. В первом случае исчезает строка, во втором её recoverable content. Позднее `ConversationRepository.kt:614` также отфильтрует пустой узел. Никакой повторный запуск/forward migration не восстановит уже удалённый текст без резервной копии.

**Существующие проверки:** `ai/src/test/java/me/rerere/ai/ui/MessageToolMigrationTest.kt:300` (`without preceding ASSISTANT`) проверяет только `result.size == 2`, поэтому пропускает опустошение второго узла. Тесты совпадающих call IDs и разветвлений полезны, их сохранить. `app/src/test/java/me/rerere/rikkahub/data/db/migrations/LegacyHistoryMigrationPreservationTest.kt` проверяет откат на неразбираемом соседнем узле, но не корректно декодируемый unmatched result.

**Предлагаемый RED:** настоящая SQLite v15 с обоими сценариями; вызвать реальную миграцию, проверить сохранение каждого sentinel-текста, message ID, исходного узла/индекса либо полный откат. Отдельно проверить совпадающие результаты, несколько TOOL-узлов, mixed-role узел и альтернативы сообщений: сохранить все payload (включая unmatched результат), исходные message/node UUID и выбранную альтернативу select_index, либо полностью откатить исходные строки. Проверить реальное чтение выбранного сообщения после миграции. Нельзя считать сохранением только число узлов.

## Important

### SROOM-02. Пропущенная в 11→12 большая история остаётся вне активного представления и не защищена от последующего очищения

**Места:** `app/src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_11_12.kt:92`; `app/src/main/java/me/rerere/rikkahub/data/repository/ConversationRepository.kt:414`, `:590`, `:614`, `:695`, `:704`, `:720`; `app/src/main/java/me/rerere/rikkahub/data/db/dao/ConversationDAO.kt:71`.

**Вход:** результат предусмотренной самой миграцией ветки `SQLiteBlobTooBigException`: `ConversationEntity.nodes` содержит прежний непустой JSON, а `message_node` для этого разговора пуст. Миграция считает переход успешным и повышает версию. Такая сохранённая БД/резервная копия может затем открываться на устройстве с достаточным CursorWindow для чтения исходной строки; это условие отдельно существенно для сценария последующего стирания.

**Путь:** ни одна последующая миграция 12→29 не обрабатывает оставшийся `ConversationEntity.nodes`. Текущий репозиторий читает историю только из `message_node`; пустая таблица означает `HistoryLoadState.COMPLETE`, не INCOMPLETE. Поле `entity.nodes` вообще не используется. Если исходную строку удаётся прочитать, чат показывается пустым и допускает `updateConversation`/checkpoint; `conversationToConversationEntity` всегда записывает `nodes="[]"`, стирая последнюю сохранённую legacy-копию. Если строку прочитать по-прежнему нельзя, результатом будет недоступность чата, а не успешное восстановление. Непосредственно в catch 11→12 байты ещё сохранены — это не немедленное удаление.

**Существующие проверки:** `app/src/androidTest/java/me/rerere/rikkahub/data/db/migrations/Migration_11_12_Test.kt:418` (`handlesVeryLargeConversations`) допускает и успешный перенос, и skip; количество и состояние большой истории только выводятся в лог. После перехода тест не открывает чат через текущий репозиторий и не проверяет последующую запись. `LegacyHistoryMigrationPreservationTest` покрывает malformed JSON, но не этот остаток legacy-колонки.

**Предлагаемый RED:** детерминированно получить предусмотренное состояние skipped row (точечный отказ чтения узла, без глобального Files mock), затем открыть обновлённую БД реальным репозиторием: исходное содержимое должно либо загрузиться, либо чат должен остаться явно только для чтения. Проверить, что update/checkpoint не очищает raw `nodes`. Отдельная actual Android-проверка с большой строкой должна подтверждать поведение реального backend. При исправлении учитывать уже установленные v12…29: одной правки старого 11→12 недостаточно; нужна проверяемая forward-only стратегия восстановления/защиты остатков, без destructive fallback.

### SROOM-03. V3 настроек заменяет непарсимые quickMessages пустым массивом и подтверждает миграцию

**Места:** `app/src/main/java/me/rerere/rikkahub/data/datastore/migration/PreferenceStoreV3Migration.kt:29`, `:32`, `:46`, `:47`.

**Вход:** реальный Preferences DataStore с `data_version=2`, `assistants="[]"`, `quick_messages` — усечённый JSON с ещё восстанавливаемым пользовательским текстом, например `[{"title":"Исходный текст"`. Контрольный вариант — корректный JSON неожиданной структуры `{...}`.

**Путь:** ошибка parse/jsonArray превращается в `JsonArray(emptyList())`. Затем код безусловно записывает сериализованное `[]` поверх исходного значения и повышает версию до 3; последующая V4 повысит её до 4. Исключение не выходит в DataStore, отката и сохранения исходного key нет. Обработчик protobuf corruption здесь не участвует: контейнер Preferences корректен, повреждён только JSON внутри строкового значения.

**Существующие проверки:** тесты V4 (`PreferenceStoreV4MigrationTest`) отдельно требуют сохранения непарсимого assistants, но для V3 такого контракта нет. `SettingsStoreAtomicUpdateIntegrationTest` проверяет ошибки записи и serialization/dummy, не этот этап инициализации.

**Предлагаемый RED:** вызвать реальную `PreferenceStoreV3Migration` на Preferences с указанным key; ожидать отказ миграции с неизменными оригинальными байтами и версией либо сохранение recoverable оригинала. Контроли: обычная экстракция inline quickMessages, merge существующих глобальных записей и повторный запуск. Исправление не должно молча превращать повреждённую конфигурацию в пустую.

### SROOM-04. Сбой резервного сохранения повреждённого DataStore всё равно разрешает уничтожение оригинала

**Места:** `app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt:94`, `:98`, `:99`, `:102`.

**Вход:** protobuf settings повреждён, но содержит recoverable данные; копирование `.corrupt-<timestamp>` завершается IOException, например на заполненном накопителе большой оригинал не помещается второй раз. Маленький пустой заменяющий Preferences при этом может записаться; либо ошибка копирования носит временный характер.

**Путь:** `runCatching(file.copyTo(...))` только логирует ошибку и возвращает `emptyPreferences()` в `ReplaceFileCorruptionHandler`. Для DataStore это успешная обработка повреждения: он может заменить исходный файл пустым. Полной резервной копии нет (при частичной записи остаётся лишь неполный файл), а исходный recoverable файл утрачивается. Это отличается от уже корректного поведения обычных IO-ошибок чтения (:507–517), где пустая конфигурация специально не подставляется.

**Существующие проверки:** `SettingsStoreAtomicUpdateIntegrationTest` не проверяет реальный corruption handler и отказ копирования. Тесты ошибок update не проходят через эту ветку.

**Предлагаемый RED:** повреждённый Preferences-файл с sentinel-байтами, адресный fault injection на копирование оригинала при доступной записи маленькой замены. Запрос чтения должен отказать с сохранением исходного файла; отдельно контроль успешного копирования и видимого восстановления. Без глобальных моков файловой системы, задержек и изменения политики секретов.

### SROOM-05. Переход 6→7 не применяет известное преобразование старых discriminator имён и блокирует корректную старую историю

**Места:** `app/src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_6_7.kt:52`, `:75`; `app/src/main/java/me/rerere/rikkahub/data/db/migrations/MigrationUtils.kt:11`; `ai/src/main/java/me/rerere/ai/ui/UIMessagePart.kt:77`; `app/src/main/java/me/rerere/rikkahub/data/db/AppDatabaseFactory.kt:19`.

**Вход:** БД v6 с корректным `messages` JSON и прежним именем части `"type":"me.rerere.ai.ui.UIMessagePart.Text"` (также `"Text"`/`"UIMessagePart.Text"`). Это именно имена, которые собственная карта `partTypeMapping` признаёт legacy и переводит в `text` для переходов 11→12 и 13→14.

**Путь:** 6→7 напрямую вызывает текущий serializer UIMessage; у Text сейчас `@SerialName("text")`, а JsonInstant имеет только ignoreUnknownKeys/encodeDefaults, без алиаса discriminator. Десериализация выбрасывает исключение до последующих преобразований; `error(...)` прерывает открытие БД. Транзакция откатывается, старые данные не удаляются, но приложение не может открыть эту старую БД. Более поздняя корректная миграция 13→14 недостижима. Это ограниченный дефект совместимости конкретного формата, не утверждение, что любая v6 база не открывается.

**Существующие проверки:** нет actual теста 6→7/6→29; instrumented `Migration_11_12_Test` начинается с v11, JVM `supportedVersion11HistoryStillMigratesThroughVersion16` использует уже текущий serializer. Нужен реальный старый JSON fixture, не новый UIMessage, сериализованный текущим кодом.

**Предлагаемый RED:** MigrationTestHelper/SQLite со схемой 6 и ручным legacy JSON: обновление до актуальной схемы, затем точное сохранение текста, UUID сообщения/чата, владельца, времён и порядка. Контроли текущих discriminator и повреждённого JSON с откатом. Схему совместимого forward-only пути выбрать отдельно: простой новый шаг 29→30 не достигается, пока старый 6→7 завершается ошибкой. Версию уже существующей БД не понижать, destructive fallback не применять; старые payload сохранять при любой ошибке.

## Minor

### SROOM-06. Удаление встроенного TTS AiHubMix доступно в UI, но провайдер появляется снова

**Места:** `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingSpeechPage.kt:347`, `:702`; `app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt:434`, `:1019`.

**Воспроизведение:** настройки речи → удалить AiHubMix с UUID `e36b22ef-ca82-40ab-9e70-60cad861911c` → подтвердить → перечитать настройки/перезапустить приложение. Кнопка запрещает удалять только DEFAULT_SYSTEM_TTS_ID. update сохраняет список без AiHubMix, но каждый decode добавляет отсутствующие DEFAULT_TTS_PROVIDERS; AiHubMix вновь появляется с исходными настройками. Это не удаление ассистента и не нарушение правил хранения обычных ключей.

**Предлагаемый RED:** реальный SettingsStore, удаление указанного ID по существующему transform UI, persisted read + новая decode/следующее несвязанное update; удаление должно сохраняться либо UI явно должен запрещать его. Контроли custom TTS и защищённого system TTS. Требуется продуктовое решение о возможности удаления builtin, не произвольное изменение defaults.

## Проверенные области и снятые предположения

- Обнаружены ровно две Room БД: AppDatabase v29, WorkflowDatabase v1. Проверены все 29 экспортированных основных схем и схема workflow, все ручные миграции 6→7, 11→12, 13→14, 14→15, 15→16, AutoMigrationSpec 8→9, 16→17, 22→23 и полная регистрация цепочки. Отсутствующих переходов и `fallbackToDestructiveMigration` не найдено.
- Автопереходы 1→2, 3→4, 4→5, 5→6, 7→8, 9→10, 10→11, 12→13, 17→18, 18→19, 19→20, 20→21, 21→22, 23→24, 24→25, 25→26, 26→27, 27→28, 28→29 добавляют таблицы/поля; 2→3 не меняет entity schema. Удаляемые явно объявленные колонки — usage, truncate_index, shell_enabled. Доказательств удаления chat/message rows этими автопереходами не найдено; реальный сквозной migration suite в этой проверке не запускался.
- `AppDatabaseFactory` используется и для текущей БД, и для проверки staged backup; WAL/SQLite extensions учтены. Workflow v1 не требует цепочки миграций, destructive fallback отсутствует. Смена backup БД — отдельный протокол; этот отчёт не является повторным полным аудитом restore.
- SettingsStore: проверены decode/write/update, атомарный fresh transform DataStore, init/dummy guard, сохранение неизвестных отдельных Preferences keys, MCP persistence boundary, retry обычных IO, нормализация ссылок и default-провайдеров. Ошибка ordinary read не подменяется пустыми Settings. По stale intent helpers дополнительно прочитаны provider/proxy/списочные слияния и существующие atomic-update integration tests; новой конкретной потери конкурентных полей не доказано.
- Проверены DataStore V1–V4, MessageDisplay migration и SettingsJsonMigrator для backup; V2/V4 сохраняют непарсимый assistants JSON. MCP migration сохраняет fail-closed поведение и защищённое хранилище. Предписанное пользователем plaintext хранение обычных настроек/backup не является находкой.
- Defaults display, `fileToolsAllStorage=false`, approval grants/Yolo и Termux/browser preferences просмотрены без изменений. RootMode, GUIDE, поля новых настроек и browser policy не менялись и не предлагаются к изменению этим отчётом.
- **Lead builtin assistant delete снят:** `ManageAssistantsTool.kt:273` запрещает DEFAULT_ASSISTANTS_IDS до dependency counts (:226) и повторно в транзакционном transform (:239); `AssistantPage.kt:509` отключает ручное удаление builtin. Поэтому `decodeSettings` reinsertion не создаёт обещанного успешного tool-delete/reappearance в актуальном коде.

## Граница следующего шага

Никаких исправлений и новых тестов пока нет. Для подтверждения приоритета сначала реальные RED перечисленных минимальных случаев, затем отдельное решение по исправлению. Изменения миграций должны сохранять существующие истории и иметь actual migration-проверку; для уже обновлённых установок нельзя полагаться только на изменённый старый переход. Утраченные старой миграцией TOOL-данные нельзя обещать восстановить без резервной копии.

---

# Part 3 — чат, жизненный цикл и ownership

Дата: 2026-10-18. Только read-only source review текущего `/workspace/Rikka-Root` после переданного root C FULLGREEN `d7f6ec1a` / run39113991394. Git/Gradle, новые тесты, изменения main и исправления не выполнялись. Наличие тестов ниже проверено по исходникам; их отдельный запуск или новые actual RED здесь не заявляются. Это вход для общего `docs/app-audit-2026-10.md` **до** исправлений.

Итог: **0 критических, 6 важных, 1 небольшой**. Важные находки имеют конкретную достижимую последовательность и доказательство по control flow. Ни одна не требует ослаблять RootMode69b/15, protected shell/HARDLINE или систему одобрений.

## Важные

### CHAT-01. Осиротевший чат A получает память и scoped tools другого ассистента B

**Доказательство.** `app/src/main/java/me/rerere/rikkahub/data/model/ConversationConfig.kt:95–100`: отсутствующий `conversation.assistantId` подменяется `getCurrentAssistant()`, затем к B применяются conversation fields. `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt:1052–1056` использует этот Assistant для generation/budget; :1102–1109 передаёт его в реальную фабрику, а :1139–1142 читает память по `assistant.id` B. `app/src/main/java/me/rerere/rikkahub/data/ai/tools/ChatToolFactory.kt:95–108` создаёт memory mutation closures с ID B; :153–155 создаёт recent-chat tools B. Исходный Conversation при этом остаётся с owner A.

**Сценарий.** Восстановить только настройки, в которых A отсутствует, но его история осталась в Room (поддерживаемая compatibility). У живого B включены неглобальная память и recent chats; B выбран текущим. Открыть старый чат A и отправить сообщение/повторить генерацию. Модель в истории A получает память B и инструменты чтения его чатов; memory add/update/delete работают в области B. Для старой config=null `bindConfig` дополнительно зафиксирует fallback-конфигурацию B (`ConversationConfig.kt:126–139`). Для уже фиксированной конфигурации ID/enableMemory/localTools B всё равно наследуются от B, так что проблема не ограничена legacy config=null.

**Границы.** Это не утверждение об обходе обычных approvals и не отмена сознательного global memory. Cron/subagent branches фабрики уже сравнивают `stored.assistantId == assistant.id` (:181–207); memory/recent-chat branches такой привязки не имеют. Не следует «чинить» это восстановлением удалённого A в настройках или молчаливой миграцией owner на B.

**Тесты.** `app/src/test/java/me/rerere/rikkahub/data/sync/ConversationRestoreOwnershipCompatibilityTest.kt:107,115,154,164` проверяет безопасные same-owner записи/запрет новых чужих bindings, но не реальную generation/tool factory для осиротевшего чата. Предложение: actual ChatService + фабрика/spy MemoryRepository, legacy A и живой B с уникальной памятью/чатами; проверить отсутствие B-memory в provider request и отсутствие B-scoped closures до явного выбора владельца. Контроль: normal B и разрешённая явная rebind работают как прежде. Минимальное направление — отделить fallback для отображения/модели от обязательного owner resolution перед scoped data/tools.

### CHAT-02. History Undo восстанавливает ссылки уже удалённых вложений

**Доказательство.** `app/src/main/java/me/rerere/rikkahub/ui/pages/history/HistoryVM.kt:55–64`: сначала `conversationRepo.deleteConversation(snapshot)`, только затем snapshot помещается в `undoDeletions`. `HistoryPage.kt:126–135` предлагает Undo **после** этого вызова. `app/src/main/java/me/rerere/rikkahub/data/repository/ConversationRepository.kt:541–556` удаляет строки и сразу выполняет cleanup файлов. :87–95 проверяет оставшиеся ссылки и вызывает удаление. `app/src/main/java/me/rerere/rikkahub/data/files/AttachmentReferenceCoordinator.kt:49–54` учитывает только runtime references и собственные reservations. Map `undoDeletions` среди них отсутствует; HistoryVM не создаёт reservation ни до delete, ни в течение snackbar. `FilesManager.kt:220–230` фактически удаляет оставшийся уникальный локальный файл.

**Сценарий.** Единственная фотография/документ/вложенный tool media хранится только в удаляемом чате, не используется settings/другими чатами/очередью. На экране History удалить чат и немедленно нажать Undo. Room-сообщения вернутся через :106, но файл уже удалён; media URL указывает на отсутствующие bytes. Даже открытый session не удерживает именно удалённую историю: runtime provider `ChatService.kt:315–324` исключает IDs из deletedHistories; очередь при этом сохраняется отдельно.

**Тесты.** `ui/pages/history/HistoryUndoOwnershipRegressionTest.kt:69–121` проверяет snapshot/owner/one-shot, но использует только текст и mocked FilesManager. `service/SharedAttachmentFilesTest.kt:141` и :271 подтверждают немедленное освобождение последнего history reference, включая открытый чат; :301/:387 проверяют reservation у fork, не Undo. Предложение: реальный upload file + Room + HistoryVM delete/Undo; bytes должны существовать весь Undo window и после восстановления; discard/timeout освобождает только последний reference. Контроли — shared references, invalid target/retry, nested media. Минимальное направление — резервировать snapshot bytes до удаления и до окончательного Undo/discard/lifecycle settlement либо отложить destructive cleanup до окончания Undo.

### CHAT-03. Две параллельные правки теряют одну принятую версию сообщения

**Доказательство.** `ChatService.kt:1952–1959` читает snapshot до persistence lock, :1975–1989 строит новую версию на нём. `saveConversation` берёт lock только :1816; при записи в :1830–1832 защищает свежую metadata, но безусловно заменяет `messageNodes` узлами старого caller snapshot. Это сериализует записи, но не трансформации содержимого. Достижимый API: `web/routes/ConversationRoutes.kt:309–317`, оба edit request возвращают Accepted.

**Сценарий.** Чат содержит user node `[original]`. Запустить edit A, задержать первый `existsConversationById` (:1818) до optimistic publication. Запустить edit B: он читает ту же исходную историю и ждёт lock. Отпустить A: в Room `[original,A]`; затем B сохраняет `[original,B]`. Принятая версия A исчезает. Тот же lost-update возможен между разными узлами; это не обычный выбор последней версии, поскольку пропадает вся branch version.

**Тесты.** `ConversationSessionTest.kt:148` проверяет упорядоченные pin toggles, `ChatServiceMetadataTest.kt:729,745,791,817` — сохранность metadata при старых saves. `ChatServiceMessageDeleteTest.kt:95–178` — indices/checkpoints последовательно. Параллельные content transforms не покрыты. Предложение: actual public `editMessage` + Room/обёртка DAO с двумя deferred, проверка обеих новых версий и текущего выбора после reopen; контроль metadata и разных node IDs. Минимальное направление — выполнять content transform над последним состоянием под общим persistence lock, сохранив существующую metadata merge semantics.

### CHAT-04. Удалённое через Web сообщение возвращается следующим stream chunk

**Доказательство.** UI явно запрещает delete во время generation (`ui/pages/chat/ChatPage.kt:486–490`), но `web/routes/ConversationRoutes.kt:332–340` вызывает delete без такого условия. `ChatService.kt:2075–2085` не проверяет активную generation. GenerationLoop посылает весь messages snapshot (`data/ai/GenerationLoop.kt:279–286,335`). `ChatService.kt:1259–1267` сливает его с текущей историей, а `data/ai/AutoContextCompression.kt:108–126` считает отсутствующий ID новым и добавляет удалённое сообщение обратно.

**Сценарий.** Включить обычный Web API, начать ответ с контролируемой задержкой между chunks. Удалить ранее отправленное user message через DELETE endpoint, получить 200. Следующий chunk содержит прежнее user message; оно снова появляется в live/Room, зачастую после более поздних узлов, поскольку insertion начинается с `nodes.size`. Последний generation save сохраняет восстановленную запись.

**Тесты.** `ChatServiceMessageDeleteTest` проверяет последовательные удаления; `ChatProcessDeathTest.kt:448` подтверждает durable streaming partials, но не delete между chunks. Предложение: actual generation flow + public delete/HTTP route, provider gate между chunks; удаление либо явно отклоняется до mutation, либо остаётся удалённым после всех chunks/reopen. Не следует принимать 200 с последующей молчаливой отменой операции. Минимальное направление — единая service boundary семантика message mutations во время generation, чтобы UI и Web не расходились.

### CHAT-05. Stale user regenerate может очистить всю историю

**Доказательство.** `data/model/Conversation.kt:70–72` ищет узел через `node.messages.contains(message)` — равенство всего объекта, не ID. `ChatService.kt:823–834` после ожидания предыдущего job берёт свежую conversation, но продолжает использовать старый переданный UIMessage. Если target уже удалён/изменён, lookup возвращает null, `indexOf(null)` даёт -1, `subList(0, indexAt + 1)` становится пустым списком, который немедленно сохраняется. Проверки «target существует» перед destructive save нет.

**Сценарий.** Сохранить UIMessage user Q из обычного существующего чата с другими сообщениями. Второй клиент удаляет Q. Нажать Retry в ещё не обновившейся карточке либо допустить удаление между Web route lookup (`ConversationRoutes.kt:361–369`) и выполнением generation job. `regenerateAtMessage(id, staleQ)` очищает все messageNodes и затем начинает запрос на пустом контексте. Сохранённый непустой title позволяет записи сохраниться как пустой чат; очистка attachments дополнительно необратима.

**Тесты.** `SharedAttachmentFilesTest.kt:122`, `ChatDisplayBehaviorServiceRuntimeTest.kt:139`, `ChatServiceMetadataTest.kt:286` проверяют существующие targets/regen trailing semantics. Нет stale/missing user target control. Предложение: seed `[earlier,Q,later]`, удалить Q, вызвать реальный public regenerate со старым Q; оставшиеся строки/файлы должны сохраниться, provider не вызываться, ошибка RU. Отдельный контроль — существующий message с тем же ID, но обновлённым объектом: сначала resolve canonical target, затем безопасно повторить/отказать. Минимальное направление — lookup по ID и отказ при отсутствующем target до subList/save.

### CHAT-06. Удалённый целиком генерирующий чат воссоздаётся final save

**Доказательство.** Drawer commit (`ui/pages/chat/ChatDrawer.kt:349–354`) и History (`HistoryVM.kt:63`) удаляют через repository, не останавливая generation/session. Следующий chunk вызывает checkpoint; production ownership guard включён (`di/RepositoryModule.kt:36`), `ConversationRepository.kt:481–482` отклоняет update отсутствующей строки. Но `ChatService.kt:1190–1194` в flow onCompletion всегда вызывает `session.finishGeneration { saveConversation(...) }`. `saveConversation` обнаруживает отсутствие строки (:1818), при непустой истории публикует session snapshot и вызывает **insert** (:1841–1844). Это тот же механизм первичного сохранения нового чата, без различия «ещё не существовал» и «был удалён во время job».

**Сценарий.** Длинный ответ со стримингом. Удалить чат в drawer и не нажимать Undo (либо удалить в History). Запись и уникальные вложения уже удалены repository cleanup. Отпустить следующий chunk/завершить provider. Checkpoint fails, onCompletion вставляет старый nonempty snapshot: чат возвращается без явного Undo; media bytes могут быть потеряны. Финальный cleanup `launchGenerationJob` (:443–451) также сохраняет живой session snapshot, поэтому одного изменения stream checkpoint недостаточно.

**Тесты.** `ChatServiceMetadataTest.kt:833,846` проверяет, что **metadata** result не создаёт удалённую строку; это отдельный CAS path. `SharedAttachmentFilesTest.kt:271` проверяет удаление открытого чата и queue retention без активного stream. Предложение: actual public service/provider delayed flow + repository delete/drawer coordinator; завершить и stop job, проверить отсутствие строки после всех cleanup/join и отсутствие повторного provider вызова. Контроль: первичная запись нового чата по-прежнему работает; явный Undo — отдельная операция. Минимальное направление — координировать deletion с generation либо иметь проверяемый deletion/lifecycle marker, запрещающий устаревший insert.

## Небольшая

### CHAT-07. Остались app-owned китайские/английские сообщения UI и модели

- `ui/pages/chat/ChatVM.kt:270`: `请先停止生成再删除消息` — нормальный UI path при delete во время generation (`ChatPage.kt:488`).
- `service/ChatService.kt:1284`: pending tool cancellation содержит app-owned English sentence в `error`; этот JSON попадает в persisted tool output и последующий model context. В отличие от кода `cancelled`, это естественный язык, который должен быть RU.
- `ChatService.kt:1063`: `No chat model selected` — собственная ошибка поколения при отсутствующей модели; :1230–1237 выводит failure в ChatError. Соседние `Message not found` (:2004/:2080), `Invalid selectIndex` (:2051) также являются собственными сообщениями, не внешними provider details.

Предложение: проверить реальные error/result objects через existing service/VM fixtures (включая cancelled pending tool), RU объяснение при неизменных machine codes/status и exact сохранении внешнего thrown text. Не переводить произвольный пользовательский/provider output. Это языковая корректность, не причина ослаблять Root approvals.

## Проверенные leads без подтверждённого дефекта

**Undo перезаписывает внешнюю вставку с тем же UUID — не подтвердилось.** `ConversationDAO.kt:76–77` использует обычный `@Insert` с ABORT по умолчанию. `ConversationRepository.kt:459–465` делает insert до messageNodes в одной транзакции. При существующем UUID исключение возникает до замены nodes; `HistoryVM.kt:107` не снимает undo receipt при ошибке. Имеющийся one-shot test (`HistoryUndoOwnershipRegressionTest.kt:108`) относится к повторному Undo после успеха, но source также исключает replace до первого Undo. Полезный дополнительный regression — delete→другая вставка того же UUID→Undo, сохранить новую строку и её nodes. Бага overwrite по текущему коду нет.

**Drawer cleanup до Undo — не подтвердилось для drawer.** `ChatDrawerDeleteCoordinator.kt:21–27` откладывает commit до отказа/завершения Undo и выполняет NonCancellable commit при закрытии drawer. UI скрывает pending ID, а не удаляет bytes заранее. CHAT-02 относится к отдельному HistoryVM path.

**Reservations вложений существуют и работают по коду**, поэтому blanket finding «все cleanup гонки» не делается. Fork reserving до suspend load/copy (`ChatService.kt:1998–2007`), repository writes с `withFileReservation` (:457), queued/submitting/incoming references (:315–324,:593–598,:709) защищены. Пробел CHAT-02 — конкретно Undo snapshot вне этих наборов.

## Проверенные области без новых самостоятельных находок

| Область | Код / существующий regression evidence |
|---|---|
| Send order, answer=false, pending approval gates | ChatService:623–786, MessageQueue:113–145; MessageQueueTest:57,70,87,121,161,171. Durable publish только после записи; submitting handoff удерживает вложения. |
| Queue process death | MessageQueueStore:51–138; MessageQueue.restoreFromStore:63–80. Стабильный ID удаляется из queue только после Room save; восстановление paused; observer/edit flags не запускают старый ввод автоматически. MessageQueueProcessDeathTest:31,51,63,81,94,128,150. |
| Stream checkpoint и stop | ChatService:1253–1270,2168–2181; ConversationSession.finishGeneration и cancelJobs. Сохранение partial/reasoning NonCancellable; Stop ждёт producer/child cleanup. ChatServiceMetadataTest:330,369,413,453,458; ConversationSessionTest:265,294,400,441. Исключения — конкретные concurrent mutations/deletion выше. |
| Reopen активного session / branch variants | ConversationSession:49–74; AutoContextCompression.mergeGenerated:100; retainCheckpoints:92. Session приоритетнее поздней загрузки, branch IDs и selected indices сохраняются. ConversationSessionTest:188,207,234,250; ChatServiceMessageDeleteTest:95–178; SharedAttachmentFilesTest:64,98,122. |
| Process-death restore | ChatProcessRecovery:52–113, interruptConversation:179 и далее; app-private receipt содержит только IDs/tokens, interrupted tool calls не исполняются повторно, completed results/branches/attachments сохраняются. ChatProcessDeathTest:113,154,170,188,235,267,329,352,376,394,415,428,448. |
| Persisted owner writes / orphan compatibility | Repository:474–489 валидирует live owner при rebinding, same-existing-owner orphan history остаётся совместимой. ConversationRestoreOwnershipCompatibilityTest:107–179. Пробел — использование fallback B для scoped runtime CHAT-01, не сами same-owner writes. |
| Tool approvals / Root | Existing ChatServiceArgumentApprovalBoundaryTest и ConversationSessionTest:36,67,343,366,400; текущее RootMode/YOLO поведение не менялось, approvals/security/root browser scope не расширялся. |

Новые тесты и fixes **не создавались**. Для каждого предлагаемого regression сначала нужен отдельный actual RED по решению root после объединения audit docs.

---

# Part 3 — read-only аудит провайдеров

18.10.2026. База: опубликованный пакет C d7f6ec1a (состояние main прочитано; Git не вызывался). Область: OpenAI-compatible Chat Completions/Responses, Claude Messages, Gemini generateContent/Interactions; сеть, потоковый протокол, изображения/файлы, reasoning, ограничения. Изменений main, новых тестов, Gradle, dependencies, политик безопасности нет. Новые RED/воспроизведение с credentials не выполнялись: ниже доказательства по фактическому control flow и официальному wire contract. Указанные тесты прочитаны, заново не запускались.

**Критических находок не подтверждено. Важных: 8; небольших: 3.** Отдельно сохранены неподтверждённые compatibility leads. Важная находка означает воспроизводимый сценарий потери ответа/ошибки или неверного завершения запроса, а не установленный обход разрешений.

Пути в отчёте относительно `/workspace/Rikka-Root/`.

## Важные

### P-01. HTTP-ошибка SSE с пустым/не-JSON body превращается в успешное завершение

**Где:** `ai/src/main/java/me/rerere/ai/provider/providers/openai/ResponseAPI.kt:177`; `ai/src/main/java/me/rerere/ai/provider/providers/claude/ClaudeProvider.kt:399`.

**Сценарий и доказательство:** reverse proxy отвечает 401 с пустым body либо 502 HTML. Реально установленный okhttp-sse5.5.0 на `!response.isSuccessful` вызывает `onFailure(eventSource, null, response)` — проверено чтением bytecode установленного jar, снимок `okhttp-RealEventSource-bytecode.txt:84–98`. Наш listener начинает с `exception=t` (null). Пустое body не создаёт ошибку; при HTML JSON parse падает, но catch пустой. `close(exception)` поэтому равен close(null). Flow заканчивается штатно; `GenerationLoop.kt:758` считает запрос completed, затем :321–337 ставит finishedAt и может закончить на пустом ответе. HTTP code только логируется, пользовательская ошибка теряется. Сценарий не требует сетевого разрыва/невалидного SSE.

**Контроли:** Chat listener при parse failure закрывается исключением; Google/Interactions имеют fallback Exception. Валидный JSON error здесь parseErrorDetail возвращает исключение — этот путь не сломан.

**Существующие тесты / пробел:** `ResponseApiStreamDecoderTest.kt:76` покрывает `response.failed` внутри HTTP200 stream, не listener HTTP rejection. `ProviderLoggingTest` проверяет логирование, не успешность Flow.

**Минимальный следующий regression:** MockWebServer401empty/502HTML отдельно Responses и Claude, collect обязан бросать с HTTP code; 401JSON и штатный SSE остаются controls. Минимальная будущая правка — обязательное fallback исключение HTTPfailure, без повторной отправки/новых политик.

### P-02. EOF без terminal event считается полным ответом, включая tool call

**Где:** `openai/ChatCompletionsStreamDecoder.kt:92`, `openai/ResponseApiStreamDecoder.kt:39`, `claude/ClaudeStreamDecoder.kt:156`, `google/GoogleStreamDecoder.kt:64`, `google/InteractionsStreamDecoder.kt:77` (все под `ai/src/main/java/me/rerere/ai/provider/providers/`). Их provider listeners вызывают onClosed и close(null).

**Сценарий и доказательство:** сервер/совместимый gateway корректно закрывает HTTP200 SSE после части текста или function-call arguments, без `[DONE]`/response.completed/message_stop/interaction.completed и без finishReason. OkHttp на EOF вызывает onClosed (bytecode снимок :211–215). Все декодеры без terminal-state проверки вызывают finish(), закрывают открытые parts и эмитят StreamChunk.Finish(null). `GenerationLoop.kt:758` завершает request как completed; :337 берёт неисполненные tools для обычной проверки/исполнения. Если уже пришли name и валидный JSON `{}`, отсутствие provider completion не препятствует дальнейшему tool loop. Это **не** обход approval/валидации: они по-прежнему выполняются. Текст может быть показан как законченный, хотя окончание провайдера отсутствует.

**Контроли:** явные error events и IOException идут через исключение; Responses response.incomplete сохраняется как incomplete и не равен этому EOF. Для generateContent стандартно нет отдельного DONE: необходимо различать EOF после candidate.finishReason и до него, не требовать несуществующего события. У совместимых endpoints особенности completion нужно учитывать явно.

**Связанный подтверждённый resume-риск даже при явной ошибке:** stream ToolCallStart материализуется с default Auto (`StreamChunkHandler.kt:155–165`, `UIMessagePart.kt:189`), а complete/terminal-флаг tool в persisted модели отсутствует. `ChatService.kt:1215–1218` сохраняет частичные chunks; onCompletion вызывает `ConversationSession.finishGeneration` (:167–179), который только finishReasoning+save; onFailure (:1230–1237) pause/addError, но не блокирует unfinished streamed Auto. `repairIncompleteToolMessages` (:201) специально сохраняет Auto, и следующий generation/resume `GenerationLoop.kt:239–242` выбирает его как canResumeAutomatic. Поэтому валидный JSON префикс tool, за которым пришёл явный provider error/Stop, может позже пойти на обычное исполнение без нового model request. Invalid JSON отсекается validateArguments, live needsApproval повторяется :473 — **обход этих guards не заявляется**. Это отдельная часть модели завершённости потока; одного fail-on-EOF недостаточно. В чат-аудите CHAT-01..07 такого отдельного finding нет; parent и chat-agent уведомлены. Предложенный service regression: actual persisted ToolCallStart+validJSON → provider failure → resume, tool executor=0 до нового полного вызова; отдельные controls на approved/completed mixed batch/process recovery сохранить.

**Тесты / пробел:** `StreamTraceReplayTest.kt:145` всегда вызывает onClosed после полного trace; `ResponseApiStreamDecoderTest.kt:34` проверяет incomplete terminal и idempotent onClosed, но нет обрезанного trace. Предложение: усечённые реальные traces на text и complete-JSON tool args, assert нет успешного Finish/исполнения; intact terminal/Google finishReason controls. Будущая правка — сообщать неполный stream, не придумывать успех и не ослаблять tool policies.

### P-03. Отмена после HTTP headers не освобождает nonstream body read

**Где:** `common/src/main/java/me/rerere/common/http/Request.kt:14–25`; nonstream read, например `openai/ResponseAPI.kt:105–115`, `openai/ChatCompletionsAPI.kt:102–112`, `claude/ClaudeProvider.kt:312–321`, `google/GoogleProvider.kt:205–215`, `google/InteractionsAPI.kt:92–102`; `app/.../di/DataSourceModule.kt:145`.

**Сценарий и доказательство:** headers200 уже доставлены, сервер завис на body, пользователь нажимает Stop. await() suspendCancellableCoroutine завершилась при onResponse; её cancellation hook покрывает ожидание headers/гонку доставки, а не следующий синхронный `ResponseBody.string()`. Синхронное чтение в Dispatchers.IO не прерывается обычной отменой coroutine. use закрывает body только после выхода из чтения. Основной client имеет readTimeout10мин, callTimeout не задан: socket/IO worker могут оставаться до сетевого timeout. Ошибка в границе времени жизни Call, не отсутствие connect/read timeout вообще. Отдельный Google service-account token path `vertex/ServiceAccountTokenProvider.kt:108` использует blocking execute() в IO и также не связывает отмену с Call.

**Тесты / пробел:** `common/.../RequestTest.kt:17,26` покрывает отмену ДО response и late-delivery body-close; :37 — быстрое успешное чтение. Сценарий после headers не покрыт. Предложение: gated real body read после доставленных headers + cancel, assert Call canceled/body closed до отпускания gate; штатный read и pending-header cancellation controls. Будущий fix должен держать cancellation ownership до завершения body, не уменьшать произвольно общий timeout.

### P-04. Claude adaptive thinking отправляется моделям, которые его не поддерживают

**Где:** `ai/.../claude/ClaudeProvider.kt:482–504`; `ai/src/main/java/me/rerere/ai/registry/ModelRegistry.kt:200–210`; `app/.../data/model/Assistant.kt:39`.

**Сценарий и доказательство:** зарегистрированная Claude Opus4.5/Haiku4.5 либо ранняя4 имеет REASONING (toolReasoningAbility), ассистент с default AUTO. Builder без проверки модели отправляет `thinking:{type:adaptive,display:summarized}`; явный enabled effort делает то же. Официальный актуальный [Anthropic extended-thinking](https://platform.claude.com/docs/en/build-with-claude/extended-thinking#migrating-to-adaptive-thinking) прямо: для Claude4.5/ранних4 adaptive unavailable и `type:adaptive` returns400. Снимок `claude-thinking.txt:421–428`. До сети уже построен неподдерживаемый контракт.

**Контроли:** OFF отправляет disabled; новые модели с adaptive не следует переводить обратно в legacy enabled/budget. Не blanket-менять любые OpenAI-compatible reasoning параметры по аналогии.

**Тесты / пробел:** `ClaudePromptCacheRequestTest`, `ClaudeRequestMessageTest` проверяют request history/cache, но не модельную матрицу thinking. Нужен actual buildMessageRequest для legacy4/4.5 и adaptive4.6+ × OFF/AUTO/effort, с документированным допустимым контрактом. Минимальная будущая правка — model-aware supported thinking, не глобальная замена режима.

### P-05. Claude redacted_thinking теряется и ломает tool-result roundtrip

**Где:** `ai/.../claude/ClaudeProvider.kt:810`, :754–758; `claude/ClaudeStreamDecoder.kt:55–67`, :170.

**Сценарий и доказательство:** Claude отвечает redacted_thinking `{data:encrypted}` рядом с tool_use. Nonstream parseMessage явно делает `{}` и выбрасывает блок. Stream трактует redacted_thinking как обычный ReasoningStart, не сохраняет data/type; replay превращает его в `{type:thinking,thinking:""}` без нужного encrypted data. В следующем запросе с tool_result исходный assistant content не совпадает. Официальный [thinking-tool-workflows](https://platform.claude.com/docs/en/build-with-claude/thinking-tool-workflows) прямо говорит filtering redacted_thinking triggers400 (`claude-thinking-workflows.txt:163–171`); [thinking](https://platform.claude.com/docs/en/build-with-claude/thinking#thinking-encryption) описывает encrypted data (`claude-thinking-current.txt:2050+`).

**Контроли:** обычные thinking/signature и server tools сохраняются существующей metadata; их нельзя заменять/удалять. Не требуется выдавать расшифрованный thinking пользователю, нужна точность транспорта.

**Тесты / пробел:** `ClaudeRequestMessageTest.kt:235–282` — обычные signatures; `ClaudeServerToolTest.kt:120` — server block replay. Предложение: nonstream+SSE redacted block + ordinary thinking + tool, обработать handler и вернуть tool_result; raw type/data/order должны совпасть. Новые regression ещё не созданы.

### P-06. Допустимый отказ модели refusal не отображается

**Где:** `ai/.../openai/ResponseAPI.kt:629–643`; `openai/ResponseApiStreamDecoder.kt:180–183`; `openai/ChatCompletionsAPI.kt:709–776`; `openai/ChatCompletionsStreamDecoder.kt:109–136`.

**Сценарий и доказательство:** OpenAI возвращает легальный Responses message.content `{type:refusal,refusal:"…"}`. Nonstream parser принимает только output_text, иначе throws `unknown part type refusal`. Stream не обрабатывает response.refusal.delta/done, затем completed даёт пустой ответ. В Chat Completions `message.refusal`/delta.refusal также нигде не читается: content=null + refusal=text превращается в пустые parts. Это транспорт/UX, не предложение отменять отказ. Официальные [Responses reference](https://developers.openai.com/api/reference/resources/responses/methods/create) содержат ResponseOutputRefusal, снимок :1683–1699; актуальный официальный SDK `response_refusal_delta_event.py` задаёт event name, `chat_completion_message.py` — refusal:string|null. Снимки рядом.

**Тесты / пробел:** `ResponseApiRequestMessageTest`, `ResponseApiStreamDecoderTest`, `ChatCompletionsRequestMessageTest` покрывают text/tool/reasoning, отказа нет. Предложение: один и тот же refusal nonstream+stream обоих API, assert объяснение видно пользователю, нормальный текст/tool controls, никаких повторов с обходящим prompt. Будущий fix — сохранить и показать refusal как полученный ответ.

### P-07. Responses built-in image generation без streaming теряет изображение

**Где:** `ai/.../openai/ResponseAPI.kt:299–303` рекламирует image_generation; :557–657 parseResponseOutput не имеет image_generation_call; :685 явно исключает его из fallback server-tool types. Stream ветка `ResponseApiStreamDecoder.kt:133–140` его обрабатывает.

**Сценарий и доказательство:** модель с BuiltInTools.ImageGeneration, streamOutput=false, успешный Responses output содержит `image_generation_call.result=base64`. Parser пропускает output целиком: нет Image и нет ошибки. Официальный Responses reference описывает result как generated image base64 (`openai-responses-reference.txt:8505–8538`). Это не отдельный OpenAIProvider.generateImage endpoint: пользователь выбирает built-in tool в обычном Responses чате, builder реально его отправляет.

**Тесты / пробел:** `OpenAIProviderImageEditTest` — другой endpoint; stream image logic есть, nonstream такого parity test нет. Предложение: actual parseResponseOutput image_generation_call + обычный текст, assert Image bytes/mime и порядок; image_generation_call failed/null-result должен обрабатываться без выдуманного изображения. Минимальная правка — nonstream parity для поддержанного output variant.

### P-08. HTTP image URL вставляется в поле raw base64 у Claude и Gemini generateContent

**Где:** `ai/src/main/java/me/rerere/ai/util/FileEncoder.kt:78–80`; `ai/.../claude/ClaudeProvider.kt:740–747`; `google/GoogleProvider.kt:778–785`.

**Сценарий и доказательство:** история/изображение инструмента содержит `UIMessagePart.Image("https://…/photo.jpg")`, допустимый для общего encoder и OpenAI. encodeBase64(false) возвращает URL как успешный EncodedImage с mime image/png, без скачивания. Claude затем пишет `source:{type:base64,data:"https://…"}`; Google пишет `inlineData.data:"https://…"`. Это URL, не base64-encoded bytes; wire не соответствует объявленному типу. Официальный [Claude vision](https://platform.claude.com/docs/en/build-with-claude/vision) различает base64 и URL source; корректный source.type=url показан в `claude-vision.txt:282–285`. Gemini content.proto InlineData/blob.data — bytes, и base64 JSON protobuf также не URL. У Google Interactions уже есть ветка uri (`InteractionsAPI.kt:405–415`), её не затрагивает эта находка. На локальные/data изображения сценарий не распространяется.

**Тесты / пробел:** `FileEncoderDataUrlTest.kt`, `ClaudeRequestMessageTest.kt:57`, `GoogleRequestMessageTest.kt:46` покрывают только data URL. Нужен actual request builder с HTTPS Image (также image tool output), assert URL не попадает в base64 поле; known-good inline data controls. Минимальный будущий путь — поддержанный URL transport или явная обработка неподдержанного URL; без новых произвольных загрузок/фильтров.

## Небольшие

### P-09. Chat Completions stream принудительно помечает image как PNG

`ai/.../openai/ChatCompletionsStreamDecoder.kt:133` отрезает только PNG prefix, :234 создаёт ImageStart без mimeType (default image/png), :236 снимает любой base64 prefix. Для `data:image/jpeg;base64,...` JPEG bytes сохраняются, но handler материализует `data:image/png;base64,<JPEG>`. WebP аналогично. Nonstream `ChatCompletionsAPI.kt:772` сохраняет полный исходный URL, parity нарушен. Сценарий применим к совместимому endpoint, который реально отдаёт такой images variant; blanket-поддержка этого extension всеми OpenAI endpoints не заявляется. Существующий `StreamTraceReplayTest.kt:304` проверяет JPEG MIME только для Gemini. Предложение: Chat trace с non-PNG image и nonstream parity; сохранить исходный mime, не конвертировать байты. Влияние — неверные MIME/extension последующего сохранения/экспорта, а не доказанная потеря pixels во всех Android renderers.

### P-10. Gemini model discovery скрывает HTTP error как пустой список

`ai/.../google/GoogleProvider.kt:140–168`: non2xx возвращает emptyList, тогда как OpenAI/Claude listModels бросают HTTP error. При invalid API key401/403 либо quota429 UI видит отсутствие моделей, а не причину. Основной generateText правильно бросает HTTP error — контроль. Предложение: listModels MockWebServer401/429 vs200empty, assert ошибки различимы; не требует автоматического retry/новой политики. Существующего provider-listModels negative test не найдено.

### P-11. Gemini model discovery обрезается на первой странице

`ai/.../google/GoogleProvider.kt:131` запрашивает pageSize100; :148–166 возвращает единственную страницу и не читает nextPageToken. Официальный ListModelsResponse содержит nextPageToken с обязанностью передать pageToken для следующей страницы (`gemini-listModels-official.json`, из официального v1beta discovery). Если сервер возвращает 2 страницы с нужной моделью на второй, список неполон без указания truncation. Это условный по размеру каталога, но конкретный подтверждённый control flow; не утверждается, что текущий публичный каталог каждого аккаунта уже >100. Предложение:2page MockWebServer, exact request pageToken и сохранение фильтра supportedGenerationMethods. Без arbitrary лимита/обрезания пользовательских models.

## Проверенные области без отдельной подтверждённой находки

- SSE backpressure: все5 путей используют Channel.UNLIMITED, trySend не теряет delta из-за bounded buffer. Это не утверждение о memory cap бесконечного провайдера.
- Network failure/cancellation ДО HTTP headers: await связывает отмену с Call.cancel; awaitClose отменяет EventSource. GenerationLoop проверяет cancellation перед retry и ограничивает retries IOException; downstream handler failure не повторяет запрос. Существующие RequestTest и GenerationLoopAuditRegressionTest — controls. HTTP retry-after/rate-limit policy не выдумывалась: отсутствие feature само по себе не классифицировано багом.
- Responses response.failed/error stream пробрасывает provider error, response.incomplete сохраняет reason/usage; metadata-only encrypted reasoning сохраняется и replay присутствует (`ResponseApiStreamDecoderTest.kt:34,76,101`). Это отличается от P-01/P-02/P-06.
- Parallel client-tool identity: текущие Chat/Responses/Claude/Gemini/Interactions decoders и StreamChunkHandler alloc IDs отделяют параллельные вызовы. `ProviderParallelToolIdentityPart2Test`, ChatCompletionsToolIdentityRegressionTest, GoogleFunctionCallArgsTest. Не найдено нового сбоя сверх известных cases.
- Claude pause_turn: ограничение5 continuations, суммирование usage, server-tool index rebasing и исходные server blocks; `ClaudeServerToolTest.kt:255,321`. Не предлагается обходить предел или увеличивать его без отдельного scope.
- Images data/local paths: encoder реально снимает data prefix для raw-byte полей; EXIF/downsample бережёт bitmap memory, tests FileEncoderDataUrl/ExifTransform. Не дублируется уже закрытая Part1/2 raw-base64 ошибка. Многоразовые snapshots Responses не конкатенируются как delta.
- Документы на основном ChatService пути проходят DocumentAsPromptTransformer (:261) и отправляются как извлечённый Text, а не native document blocks. Поэтому отсутствие native Document branch в providers само по себе не заявлено потерей прикреплённых документов. Ошибки/лимиты парсеров документов — соседняя область; raw encoded audio/video limit/MIME hardcoding требует отдельных настоящих fixtures, не записано как доказанный массовый сбой всех форматов.
- Request schema: Responses strict=false явный; Chat strict omitted соответствует non-strict default. Whole-registry property/schema tests и actual previous wire probes подтверждают сохранение fixed named fields. Local execution validation и mandatoryConfirmation/RootApprovalPolicy/HARDLINE/RootCommandGuard остаются обязательными. RootMode в этом аудите не изменялся.

## Неподтверждённые ограничения, не production blockers

### OpenAI «максимум128 tools»

Фактическая цепочка допускает >128: UI включает категории без числового cap (`AssistantLocalToolPage.kt:284–286`); `ChatToolFactory.kt:132–235` собирает local+memory/search+workspace+skills+MCP+cron/subagent и в :237 применяет permission policy, не take128; `GenerationLoop.kt:294` передаёт полный список; ChatCompletionsAPI:425 и ResponseAPI:271 сериализуют params.tools целиком. Registry tests фиксируют193/132/193 при полном выбранном наборе (не утверждается, что default TimeInfo выдаёт193).

Но скачанные 18.10.2026 [Chat create reference](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create), [Responses create reference](https://developers.openai.com/api/reference/resources/responses/methods/create), [function calling guide](https://developers.openai.com/api/docs/guides/function-calling) и [официальный Python SDK tools declaration](https://github.com/openai/openai-python/blob/main/src/openai/types/chat/completion_create_params.py#L342) не содержат max128 для tools. В Chat reference maximum128 находится у n, его нельзя переносить на tools. Живого API proof нет. Поэтому прежний max128 lead **UNCONFIRMED**, не объявлен багом, никакого общего silent cap/drop/ограничения всех compatible endpoints не предлагается. Проверка лимита конкретного endpoint — только по его актуальному контракту/actual response и с явной понятной ошибкой, если подтвердится.

### Gemini legacy freeform schema

Из предыдущего `/workspace/.reliability-state/part2/gemini-compatibility/report.md`: bare object без properties действительно уходит по legacy parameters, но официальный Schema.properties OPTIONAL; требования non-empty properties и живого400 нет. additionalProperties удаляется намеренно как unsupported legacy constraint; локальная валидация сохраняется. `customBodies` array.items:{} — protocol-shape lead, не actual rejection. `parametersJsonSchema` документирован как отдельный mutually-exclusive путь, но switch не внесён и без targeted proof не предлагается во время C CI. Исследование не переоткрыто как подтверждённый defect.

### Новые Claude signatures, привязанные к prefix

Актуальный official thinking-troubleshooting описывает prefix binding для новых моделей/аккаунтов и 400 при изменении system/tools/messages перед подписанным thinking. В приложении есть динамические input transformers/tool assembly. Для доказательства конкретного несовместимого roundtrip нужны actual effective request prefixes выбранной модели; в этом проходе это не воспроизводилось и отдельным finding не объявлено. Parent уведомлён для корреляции с общим audit, без правок prompt/policy.

## Материалы и предел доказательств

Рядом сохранены official HTML+parsed TXT, SDK snapshots, manifest URL и байткод реально установленного okhttp-sse5.5.0. Official URL main/master изменяемые; вывод относится к снимкам18.10.2026. Нет live credentials/remote server responses. Ни один proposed regression не назван RED/пройденным; root должен сначала объединить этот аудит в docs/app-audit-2026-10.md и решить scope test-first работ.

---

# Part3 — резервные копии: read-only аудит

Дата: 2026-10-18. Репозиторий `/workspace/Rikka-Root`, после подтверждённого root Part2C GREEN d7f6ec1a / Actions39113991394. Здесь **не запускались Git, Gradle или тесты, не менялись production/fixtures**, новые tests/fixes не подготовлены. Доказательства ниже — прочитанный control flow; предложения regression-тестов ещё не являются воспроизведённым RED. Для сокращённых production путей префикс — `app/src/main/java/me/rerere/rikkahub/`; для перечисленных test anchors — `app/src/test/java/me/rerere/rikkahub/`. Пути с `ai/src/` даны от корня репозитория.

**Critical: не подтверждены.** Для обычной ошибки staged native restore потери существующей БД не найдено. **Important: B1–B7; Minor: B8–B10.** B5 — проблема импорта между разными Android user/profile files roots, не заявление об испытании на устройстве. B7 — честность частичного streaming import, а не заявление об удалении старых чатов.

## B1 — Important: локальный экспорт сообщает успех без файла; reminder обновляется до сохранения

- `ui/pages/backup/tabs/ImportExportTab.kt:75–89`: `openOutputStream(targetUri)?.use` пропускает копирование при null; после этого archive удаляется и показывается backup_success.
- `ui/pages/backup/BackupVM.kt:97–102`: `exportToFile` записывает lastBackupTime до того, как URI вообще открыт и bytes скопированы. При IOException/Disk full в output UI показывает ошибку, но reminder всё равно считает backup свежим.
- **Сценарий:** SAF provider возвращает null для output stream, что разрешено nullable API. Код не бросает исключение, ни один байт в URI не записан, temporary ZIP удалён, показывается успех. Отдельный контроль: provider бросает IOException после первой порции — успеха нет, но timestamp уже обновлён.
- **Риск:** пользователь доверяет несуществующей/неполной резервной копии и свежему reminder. Старые чаты этим действием не удаляются.
- **Предложение regression:** реальный ContentResolver→test ContentProvider с null/open failure/mid-write failure; успех и reminder только после завершённой записи, источник не теряется до результата. Existing backup UI/VM tests для этого отсутствуют. Main-thread copy в этой же ветке (`scope.launch`→FileInputStream.copyTo без IO context) пересекается с отдельным performance audit; объединять будущую правку, не дублировать.

## B2 — Important: после 1000 S3 backups последние резервные копии не видны

- `data/sync/S3Sync.kt:49–66`: один `listObjects(maxKeys=1000)`, затем сортировка только первого page.
- `data/sync/s3/S3Client.kt:249–262`: continuationToken уже поддержан; `S3ListResult` содержит isTruncated/nextContinuationToken (`:454–459`). Sync эти поля игнорирует.
- **Сценарий:** bucket содержит 1001 ключ `rikkahub_backups/backup_YYYYMMDD_HHMMSS_...zip`. Для обычного S3 bucket ListObjectsV2 возвращает lexicographic order: первый page содержит старейшие1000, свежий1001-й находится на следующем page. UI сортирует старые записи и выдаёт их как полный список; свежий backup нельзя выбрать для восстановления/удаления.
- **Предложение regression:** Ktor mock engine отдаёт первый XML page c IsTruncated=true/NextContinuationToken и следующий page с самым свежим backup; actual S3Sync.listBackupFiles должен включать его и сделать подписанный token request. Существующий `data/sync/s3/AwsSignatureV4Test.kt` проверяет подпись, не traversal страниц.

## B3 — Important: WebDAV GET/DELETE адресует displayName вместо фактического href

- `data/sync/webdav/WebDavClient.kt:375–386`: href и displayName сохраняются раздельно; fallback displayName берётся из href только при отсутствии отдельного свойства.
- `data/sync/webdav/WebDavSync.kt:60–67`: фильтрация backup и перенос href в item; `:80` и `:97`: restore/delete используют `item.displayName`, сохранённый href игнорируется.
- **Сценарий:** валидный DAV response для объекта `/backups/backup_20261018_a.zip` содержит displayname=`backup_daily.zip`. UI показывает его; restore запрашивает `/backups/backup_daily.zip` вместо объекта a. При наличии другого объекта с таким именем delete затрагивает другой backup; иначе404. Если displayname вообще не соответствует backup_*.zip, настоящий backup исключается уже фильтром.
- **Предложение regression:** actual PROPFIND/list→restore/delete через Ktor mock engine, с отличающимися href/displayname; проверить точный request path и сохранность другого ресурса. Известных текущих WebDavClient/Sync tests нет.
- **Ограничение будущего дизайна:** нельзя просто передать недоверенный absolute href в authenticated GET. Проверить scheme/host/port и принадлежность настроенному DAV root до передачи credentials; сохранить pathname encoding. Cache filename должен оставаться отдельно сгенерированным File.createTempFile, не строиться из href/displayName.

## B4 — Important: Cherry import объявлен завершённым до сохранения настроек

- `ui/pages/backup/BackupVM.kt:174–185`: non-suspend restoreFromCherryStudio вызывает updateSettings, которое создаёт отдельный viewModelScope.launch (`:52–54`); Job не возвращается вызывающему restore и не join/await.
- `ui/pages/backup/tabs/ImportExportTab.kt:159–171`: сразу после возврата удаляется temporary archive, показывается restore_success и restart dialog.
- `ui/pages/backup/components/BackupDialog.kt:19–20`: кнопка вызывает exitProcess(0).
- **Сценарий:** DataStore write задержан либо завершается ошибкой; restoreFromCherryStudio уже вернулся и UI разрешил завершить процесс. До commit пользователь нажимает Restart: импортированные провайдеры не попадают на диск. Ошибка фонового Job также не попадает в runCatching UI, поэтому возможно ложное сообщение об успехе.
- **Предложение regression:** suspend реальный settings write перед commit (существующий PreferencesStore test/DataStore fixture), вызвать import, проверить что completion/success ещё не объявлены; освободить write и проверить provider после повторного чтения. Error control должен вернуть ошибку импорта. Отдельного BackupVM/Cherry import completion test сейчас нет.

## B5 — Important: native DB restore не переносит абсолютные attachment URLs на другой Android profile

- `data/sync/BackupManager.kt:147–158`: staged main DB нормализуется/мигрируется, но message payload URLs не переписываются. `:168–175`: remap только Settings.
- `data/sync/SettingsBackupFilePaths.kt:24–31`: обрабатываются аватары/фон настроек, а не message_nodes.
- `data/repository/ConversationRepository.kt:722`: сообщения читаются прямым decodeFromString; `data/ai/transformers/DocumentAsPromptTransformer.kt:70–74` открывает document.url как исходный локальный File. `data/files/FilesManager.kt:312–314` также использует исходный file URL при сохранении изображения.
- **Сценарий:** backup DATABASE+FILES из Android work profile10 содержит message Image/Document URL `file:///data/user/10/<package>/files/upload/a.pdf`. Restore в profile0 ставит bytes в `/data/user/0/<package>/files/upload/a.pdf`; исходный URI в message_nodes остаётся10 и недоступен. Чат открывается, но документ/изображение сломан, хотя bytes есть в backup и на новом месте. Одинаковый filesDir на обычном restore того же profile — проходящий контроль.
- **Предложение regression:** actual native SQLite message fixture + BackupManager stage/apply между двумя ContextWrapper files roots, аналогично существующему `SettingsBackupFilePathsTest`. Проверить все выбранные/staged app attachment URLs в сообщениях, включая альтернативы, и что новые paths реально читаются. Не переписывать внешние URL или отсутствующие/невыбранные файлы; не расширять доступ за filesDir.
- **Текущие tests:** `SettingsBackupFilePathsTest.kt:36` переносит global wallpaper/avatars; `:74` сохраняет ссылки при settings-only restore. Это хорошие controls, но chat payload migration не покрывают. Здесь device runtime не проверялся.

## B6 — Important: Cherry import печатает API credentials в logcat

- `ui/pages/backup/BackupVM.kt:181`: `Log.i(... "$importProviders")` выводит полный список ProviderSetting.
- `ai/src/main/java/me/rerere/ai/provider/ProviderSetting.kt:57–70`: OpenAI — data class, apiKey — поле первичного конструктора; default toString включает его. Claude/Google аналогично.
- **Сценарий:** импортировать валидный Cherry backup с canary API key; успешный parse возвращает ProviderSetting с ключом, интерполяция списка пишет `apiKey=<canary>` в TAG BackupVM.
- **Предложение regression:** реальный CherryStudioProviderImporter→BackupVM path и Robolectric ShadowLog; ключ должен сохраниться в разрешённых settings, но отсутствовать в app logs. Можно логировать только число/type/id без private values. Текущих tests этого пути нет.
- **Важно:** разрешённое пользователем plaintext хранение provider/search/speech/media/proxy/S3/WebDAV/web credentials в settings/backup **не является finding**. Здесь отдельное распространение этих значений в diagnostic log, а не архив.

## B7 — Important: Chatbox streaming failure оставляет неотмеченный частичный импорт

- `data/sync/importer/ChatboxImporter.kt:118–143`: sessions обрабатываются по очереди, onConversation вызывается до чтения/валидации следующей session. `:146–155`: counters/result возвращаются только в конце.
- `ui/pages/backup/BackupVM.kt:125–135`: image files и conversations сохраняются сразу; `:139–152`: providers и allowConversationSystemPrompt обновляются только после нормального возврата импортера.
- **Сценарий:** manifest описывает две session: первая валидна и имеет изображения/системный промпт, вторая отсутствует либо содержит испорченный JSON. Первая conversation и image bytes уже сохранены, затем импорт throws. UI получает только общий restore_failed (`ImportExportTab.kt:172–177`), не сообщает количество сохранённых чатов и не применяет связанное allowConversationSystemPrompt/providers update. Результат не является «ничего не импортировано», хотя UI не раскрывает partial state.
- **Риск:** частичная/логически неполная новая история и непонятный retry, но не подтверждённое удаление старых чатов. Существующие ID предварительно пропускаются (`BackupVM.kt:120–123`), а `ConversationDAO.kt:76` — обычный @Insert с default ABORT, поэтому конкурентная вставка существующего id не является silent REPLACE. Такой overwrite здесь **не заявляется**.
- **Предложение regression:** реальный importStreaming/Room/files fixture: valid first session + invalid second. Зафиксировать фактически сохранённое и требовать либо полностью безопасную atomic staging стратегию, либо явный partial result/counts и согласованность настроек. Existing `ChatboxImporterTest.kt:22` покрывает только успешный импорт providers/resources/forks.

## B8 — Minor: failed upload/local native restore оставляет большие temp archives

- `data/sync/S3Sync.kt:33–46` и `data/sync/webdav/WebDavSync.kt:33–49`: удаление temporary backup стоит после getOrThrow, не в finally. При сетевом отказе файл остаётся в cache.
- `ui/pages/backup/tabs/ImportExportTab.kt:114–127`: native local temp удаляется только после успешного restore; parse/stage failure пропускает delete. Chatbox ветка рядом использует finally, native — нет.
- **Сценарий:** несколько offline upload attempts большого DATABASE+FILES архива либо повторный import испорченного большого ZIP накапливают полные/частичные cache copies. Live DB не изменяется; расход cache может мешать последующим операциям.
- **Предложение regression:** controlled HTTP error/invalid ZIP, actual temp directory inspection: все принадлежащие операции temporary files удалены, исходный пользовательский archive и live data сохранены. Download restore S3/WebDAV уже имеет finally cleanup (`S3Sync.kt:82–87`, `WebDavSync.kt:86–91`) — положительный контроль.

## B9 — Minor: собственные restore failures показываются не по-русски

- `RikkaHubApp.kt:77–79`: catch RestoreFailedException показывает hardcoded Chinese toast вместо RU, хотя именно это штатный rollback failure path.
- `data/sync/PendingRestore.kt:32`, `:38`, `:173` и `BackupManager.kt:121–123`, `:143–145`: собственные английские reasons могут дойти до UI error e.message. Это не внешние сообщения серверов.
- **Сценарий:** settings apply IOException приводит к безопасному rollback и китайскому уведомлению после restart; repeated stage before restart даёт английское «A backup restore is already pending».
- **Предложение regression:** real rollback/pending UI boundary, русский actionable текст, стабильное разделение failed-but-safe и interrupted-pending. Алгоритм rollback/секреты не менять ради локализации.

## B10 — Minor: JSON settings archives читаются без ограничения распакованного размера

- `data/sync/BackupManager.kt:125–128`: settings.json → bufferedReader.readText целиком без size/byte limit.
- `data/sync/importer/CherryStudioProviderImporter.kt:20–23`: data.json также unbounded readText; затем ещё JSON strings persist/llm разобраны как дополнительные целые документы (`:26–37`).
- **Сценарий:** небольшой DEFLATE ZIP с settings.json/data.json, состоящим из очень большого JSON string/whitespace. Даже до проверки корректности приложение выделяет память для всего распакованного текста. OutOfMemoryError не перехватывается catch(Exception), и restore process завершается. Live native DB ещё не публиковалась, поэтому loss существующих данных здесь не заявляется.
- **Предложение regression:** объявленный/фактически прочитанный JSON entry сверх явного согласованного лимита отклоняется до декодирования и publish, без необходимости реально исчерпывать heap. Counterexample/образец уже есть у ChatboxImporter (`:46–47`, `:608–620`): размер и фактическое число прочитанных bytes ограничены. Не вводить общий маленький cap на весь backup с легитимными большими attachments.

## Проверенные области без новых подтверждённых findings

- **Standalone snapshots и WAL:** DatabaseBackup.createSnapshot использует VACUUM INTO (`data/sync/DatabaseBackup.kt:17–19`), normalize открывает только staged copy, делает checkpoint и integrity_check (`:23–42`), sidecars удаляются после checkpoint (`:54–59`). Main DB проходит AppDatabaseFactory с теми же миграциями, без destructive fallback. Workflows проходят отдельную Room/schema/content validation и санитизацию до publish (`WorkflowDatabaseBackup.kt:35–80`).
- **Установка поверх существующих данных:** PendingRestore journal пишется до изменений (`:49–60`), originals сохраняются до установки (`:63–73`), rollback возвращает файлы и sidecars (`:115–121`). Commit — снятие pending имени до открытия Room (`:89–94`). Startup запускает apply до Koin/потребителей (`RikkaHubApp.kt:69–87`); invalid journal/rollback failure не приводит к тихому запуску с частично установленной БД. Это control-flow review, не новый crash-injection run.
- **Обычное повреждение/оборванный ZIP download:** downloads идут во временный cache file, а stageRestore читает ZipFile и проверяет selected settings/DB до publish. Префикс ZIP без central directory не становится pending restore. S3/WebDAV restore temp удаляется finally. Для WebDAV channel loop (`WebDavClient.kt:168–177`) и S3 copyTo (`S3Client.kt:181–185`) нет собственной проверки against listed size/checksum; здесь не заявляется, что это уже доказало установку повреждённой БД или CRC corruption. Такой runtime scenario не выполнялся.
- **Safe file paths/duplicates:** только явные database/settings entries и whitelist attachment folders (`BackupManager.kt:109–121`, `:183–199`); PendingRestore.resolveInside отвергает absolute/backslash/dot/dotdot и canonical escape (`:143–151`). Не предлагалось отключать ограничения или передавать href в путь локального файла.
- **Старые backups:** опциональная workflow DB не трогается, если её нет в архиве; old WAL принимается только вместе с соответствующей БД (`BackupManager.kt:144–145`); SettingsJsonMigrator применяется до decode; old customization defaults/избранные restored image paths покрыты tests. Не найдено отдельной подтверждённой destructive schema fallback/migration.
- **Секреты и RootMode:** архив Settings plaintext upstream credentials разрешён пользователем. ATTACHMENT_FOLDERS не содержит noBackup/SharedPreferences secret stores. MCP/SSH/Codex encrypted stores находятся в noBackup (`McpControlSecretStore.kt:25`, `SshCredentialStore.kt:32`, `CodexCredentialStore.kt:25`); JS store encrypted AndroidKeyStore, sharedpref исключён из Android backup rules. Legacy MCP headers в restored settings защищаются до публикации (`BackupManager.kt:133`), workflow credential arguments — до установки DB. Secret references, недоступные на другом устройстве, не превращаются в plaintext export; потребность вручную переавторизоваться не объявляется багом. RootMode/approval policy не изменялись и их ослабление не предлагается.

## Существующие regression anchors, прочитаны, но не запускались в этом аудите

- `data/sync/PendingRestoreTest.kt`: publishingDoesNotTouchLiveFiles; installsStandaloneDatabaseAndDoesNotReplayOnLaterLaunches; failedSettingsWriteRestoresOriginalDatabaseAndSidecarsAndCanRetry; failedInitialJournalWriteKeepsOriginalDataAndDoesNotBlockLaterLaunches; invalidJournalAfterInterruptionKeepsOriginalsForRecovery; restartResumesInstallationInterruptedBeforeCommit; filesOnlyRestoreLeavesDatabaseAndWalUntouched; rejectsArchiveTraversalPaths.
- `data/sync/PendingRestoreMultipleDatabaseTest.kt`: twoDatabasesInstallWithoutOldSidecars; settingsFailureRollsBackBothDatabasesAndTheirSidecars; restartResumesBothDatabaseInstallationWithoutReplay; oldBackupLeavesWorkflowDatabaseAndSidecarsUntouched; unrelatedDatabaseCannotBeInstalled.
- `data/sync/WorkflowDatabaseBackupTest.kt:76,90,109,131,142,156`: standalone snapshot, credential/error sanitization including freed pages, future/wrong schema refusal, malformed conditions refusal.
- `data/sync/SettingsBackupFilePathsTest.kt:36,74,88,109`: selected wallpaper/avatar rebasing, settings-only preservation, only existing selected file remap, legacy defaults.
- `data/sync/ConversationRestoreOwnershipCompatibilityTest.kt:107–123`: actual settings-only restore/legacy existing-chat update+checkpoint continuity, owner guards сохранены.
- `data/sync/AndroidBackupPolicyTest.kt:23`, `data/sync/s3/AwsSignatureV4Test.kt`, `data/sync/importer/ChatboxImporterTest.kt:22` — имеющаяся смежная проверка whitelist/signature/success import. Новых tests или production patches по Part3 не создавалось.

---

# Память, изображения и главный поток — исходные технические доказательства

2026-10-18. Baseline reported by root: `d7f6ec1a`, full CI `39113991394`: ordinary tests 5586/0, lint 13 reports/0, APK verified. This audit did not run Git, Gradle, tests, profiling, dependencies or mutations in the repository. Findings below are control-flow/source findings, not newly observed device crashes or measured timings. Only this external report was written. No fixes or tests are authorized by this report.

Scope: long chat history/session retention, attachment import, document extraction, generated image persistence and large tool-output persistence. Critical findings: none established. No permanent managed-object leak established. Five important source-level problems and one minor resource-lifetime problem are recorded below; the device-specific CursorWindow consequence of P4 is explicitly conditional.

## Important P1 — attachment import performs the entire stream copy on Main

**Locations:** `app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatAttachmentPicker.kt:115`, `:119`, `:126`, `:130`, `:149`, `:157`, `:165`, `:171`; `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt:164`; `app/src/main/java/me/rerere/rikkahub/data/files/FilesManager.kt:110`, `:125`, `:129`.

Activity-result callbacks directly call non-suspending `createChatFilesByContents` / `importChatFiles`. The latter queries the provider, opens its stream, and copies until EOF synchronously. The share-entry `LaunchedEffect` also calls this function directly; launching a Compose coroutine does not move its non-suspending work off Main. The one-image crop branch performs its own copy and HEIF decode/compress before launching the crop activity. Asynchronous managed-file bookkeeping after the copy does not move the copy itself.

**Concrete trigger / proof:** select a large video/document, several images, or a document from a slow/cloud SAF provider, or share it into ChatPage. Provider read and destination write occupy the main callback until EOF; UI input/painting cannot proceed during this work. No exotic payload is needed. ANR duration is device/provider-dependent and was not measured. The bounded/sampled HEIF conversion still performs substantial CPU/I/O on Main; this finding does not assert every HEIF decode is unbounded.

**Existing coverage:** `app/src/test/java/me/rerere/rikkahub/ui/screenshot/ChatPageAttachmentImportTest.kt` uses the real page/provider import and checks MIME, ordering and byte preservation with small fixtures. `service/SharedAttachmentFilesTest.kt` protects ownership/reference cleanup. Neither establishes that import runs off Main.

**Proposed regression:** actual picker/share paths with a recording provider, assert the stream-copy thread is not Main; use an explicit read gate and an independently scheduled UI marker rather than a timing threshold. Retain MIME, ordering, partial-import error, original bytes and attachment-reference assertions. Proposed direction only: perform copy/conversion in cancellable IO work; publish draft changes on Main and retain URI permissions for the full job lifetime.

## Important P2 — image-generation preview and final base64 persistence runs on Main

**Locations:** `app/src/main/java/me/rerere/rikkahub/ui/pages/imggen/ImgGenVM.kt:151`, `:195`, `:255`, `:258`, `:274`, `:307`, `:324`; `app/src/main/java/me/rerere/rikkahub/data/files/FilesManager.kt:280`, `:287`, `:290`.

`generateImage` and `editImage` start ordinary `viewModelScope.launch` jobs. Their collector synchronously saves every preview and final image. `createImageFileFromBase64` allocates/decodes the full byte arrays and writes the file without `withContext`; `saveImageToStorage` being suspend does not change this: the first repository suspension is after the file write at ImgGenVM:336. An upstream provider `flowOn(IO)` does not change the downstream collector's Main context.

**Concrete trigger / proof:** normal image-generation/edit response with large base64 data, especially repeated partial previews. Each emitted preview/final causes base64 conversion and disk writes on Main before UI state is updated. Actual stall duration has not been measured.

**Existing coverage:** no dedicated ImgGenVM threading/persistence test located. Small attachment/image-source tests do not exercise this collector.

**Proposed regression:** actual VM with a controlled provider flow and a recording persistence boundary; assert preview and final byte processing occurs off Main while emitted UI state/order and final database metadata remain correct. Cover cancellation during a held save without asserting elapsed time. Proposed direction: isolate preview/final filesystem and base64 work on IO; keep Main state updates and existing media data intact.

## Important P3 — chat attachment extraction bypasses the bounded document-tool path

**Locations:** `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt:256`, `:261`; `app/src/main/java/me/rerere/rikkahub/data/ai/transformers/DocumentAsPromptTransformer.kt:20`, `:27`, `:46`, `:70`, `:82`; `document/src/main/java/me/rerere/document/DocxParser.kt:22`, `:25`, `:29`, `:52`; `document/src/main/java/me/rerere/document/PptxParser.kt:23`, `:34`, `:48`, `:59`; `document/src/main/java/me/rerere/document/EpubParser.kt:25`, `:27`, `:28`, `:34`; `document/src/main/java/me/rerere/document/PdfParser.kt:54`.

This transformer correctly executes on IO, but automatically extracts attached documents into complete strings. DOCX's attachment overload streams the archive entry directly into an unbounded text builder. PPTX collects all selected slides/notes then builds another full result. EPUB accumulates all spine items. PDF extracts all pages. Plain text falls through to `File.readText()`. There is no source-size/expanded-byte/extracted-text budget or cooperative cancellation check in these loops. Selected historical document parts are parsed again on each generation that includes them; no extracted-content cache is used here.

**Concrete trigger / proof:** attach an otherwise valid, highly compressible DOCX whose `word/document.xml` expands to a very large repeated text, then send. The compressed import size does not bound the decompressed stream or resulting strings. The transformer finishes full extraction before producing its prompt; a later model/context-output cap cannot protect that allocation. Stop cannot interrupt the non-suspending parser loop promptly. OOM is heap/input-dependent; no destructive/OOM fixture was executed.

**Control:** this does **not** apply to the separate protected `read_document` tool. `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/ReadDocumentTool.kt:24` defines source/expanded/entry/PDF-text limits; `:132` uses cancellable `parseRange`; `:149` explicitly limits preflight to this tool. Attachment input never calls that preflight.

**Existing coverage:** `app/src/test/java/me/rerere/rikkahub/data/ai/tools/local/ReadDocumentToolTest.kt:135` verifies ZIP/oversized-source rejection and `:160` cancellation, only for the tool. No equivalent DocumentAsPromptTransformer budget/cancellation regression located.

**Proposed regression:** real attachment transformer with valid, safely sized high-expansion DOCX/PPTX/EPUB fixtures and an instrumented parser/read boundary; prove a defined budget stops expansion before allocation grows, and an explicit cancellation gate terminates work. Cover plain text and multipage PDF separately. Preserve the uploaded original, MIME/permissions, and visible partial/refusal status. Reuse existing bounded parser capabilities where suitable, but select attachment product limits explicitly rather than silently inventing limits or truncating user data.

## Important P4 — a permitted full tool result is persisted as a single oversized Room TEXT value

**Locations:** `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/HttpRequestTool.kt:131`, `:132`, `:280`, `:292`; `app/src/main/java/me/rerere/rikkahub/data/ai/GenerationLoop.kt:532`, `:534`, `:900`, `:902`, `:1018`; `app/src/main/java/me/rerere/rikkahub/data/db/entity/MessageNodeEntity.kt:29`; `app/src/main/java/me/rerere/rikkahub/data/repository/ConversationRepository.kt:526`, `:708`, `:711`, `:722`, `:745`; `app/src/main/java/me/rerere/rikkahub/data/db/dao/MessageNodeDAO.kt:22`.

The full UI tool output remains in `UIMessagePart.Tool.output` without workspace_shell. This configuration is reachable: `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/LocalTools.kt:233` registers HTTP from its independent option; `app/src/main/java/me/rerere/rikkahub/data/ai/tools/ChatToolFactory.kt:131` adds local tools separately from workspace tools at `:156`. `boundToolOutputsForModel` only shortens the provider-request copy. HTTP permits 8 MiB response bytes; a binary body is encoded to 11,184,812 base64 characters before JSON overhead. Checkpoint/save serializes the complete node message list directly into one `messages: String` Room column. No compression/external payload reference is applied. LIMIT 64 paginates rows, not an individual row value.

**Concrete reproduction to run:** assistant without workspace_shell calls `http_request` with the supported maximum byte count against a controlled public response, returns a large nonsecret body, checkpoints successfully, then the conversation is reopened on Android with a CursorWindow smaller than that row. The row cannot fit the cursor; `loadMessageNodes` catches `SQLiteBlobTooBigException`, marks the history INCOMPLETE, and subsequent repository writes are refused (`ConversationRepository.kt:63`). The SQL data is retained by the existing safety guard. This is an availability/readability problem, **not evidence of data deletion**.

**Evidence boundary:** raw row shape and reachable full-result size are confirmed by source. Actual Android exception for this end-to-end fixture was not run in this read-only audit. The consequence is conditional on the platform/window size; do not state that every device fails at a universal 2 MiB threshold. This was the requested unconfirmed lead: compression does not resolve it, but an instrumented/native Room regression is still needed to confirm the target-device manifestation.

**Existing coverage:** `data/repository/IncompleteHistoryTest.kt:131` injects a cursor exception and proves original rows/title survive; it does not insert a genuinely oversized HTTP result into a real Android cursor. `data/ai/GenerationLoopOutputEnvelopePart2Test.kt` deliberately verifies stored full output and bounded provider copies.

**Proposed regression:** real Android Room save/reopen with large valid tool output; require complete history, byte-exact retained output and continued writes, while keeping injected-failure no-rewrite controls. Do not weaken INCOMPLETE protection or truncate the stored result. A compatible external-payload/chunked storage design would require separate approval and migration/backup/attachment-reference planning; this audit proposes no schema change.

## Important P5 — chat base64-image normalization expands the entire image to a full-resolution bitmap

**Locations:** `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt:266`, `:269`; `app/src/main/java/me/rerere/rikkahub/data/ai/transformers/Base64ImageToLocalFileTransformer.kt:8`, `:14`; `app/src/main/java/me/rerere/rikkahub/data/files/FilesManager.kt:191`, `:198`, `:199`, `:200`; `app/src/main/java/me/rerere/rikkahub/data/files/FileUtils.kt:92`.

The normal Google response decoder creates these parts from inlineData (`ai/src/main/java/me/rerere/ai/provider/providers/google/GoogleStreamDecoder.kt:137`, `:144`), without a decoded-dimension check. On generation finish, data:image parts are base64-decoded, decoded by BitmapFactory without bounds or sample options, and re-encoded to PNG in a ByteArrayOutputStream (then copied to another byte array). The path runs on IO, not Main. A small compressed image can require a much larger bitmap allocation determined by its pixel dimensions. Original base64/decoded image bytes, bitmap and PNG output can coexist. This normalization has no dimension/pixel guard and does not explicitly recycle its temporary bitmap.

**Concrete trigger / proof:** an accepted provider returns a valid data:image image with very large pixel dimensions and highly compressible pixels. The unconditional `decodeByteArray` expands it before any size-aware presentation code such as Coil can help. Exact allocation/failure depends on format and Android decoder; no huge image was decoded in this audit. This is an unbounded decoded-memory path, not a claimed permanent bitmap leak.

**Existing coverage:** `data/ai/tools/local/LocalImageSourcesTest.kt` covers path/source selection; it does not cover this generated data-image transformation. Tool-specific QR/wallpaper image guards are not used here.

**Proposed regression:** actual normalization with a controlled bounds/decode boundary, asserting that an image exceeding the approved decoded-pixel budget is not fully decoded, or proving normalization can preserve valid original image bytes without a bitmap round trip. Retain supported formats, original visual data and local attachment registration; do not silently downsample originals merely to make a test pass. A moderate valid image control must round-trip correctly. No specific new pixel limit is asserted by this audit.

## Minor P6 — attachment PDF parser defers native cleanup to finalizers

**Locations:** `document/src/main/java/me/rerere/document/PdfParser.kt:55`, `:59`, `:65`; `document/src/main/java/com/artifex/mupdf/fitz/Document.java:44`, `Page.java:40`, `StructuredText.java:39`.

The legacy attachment parser opens a Document, loads each Page and obtains StructuredText without `destroy` or finally blocks; the Page reference is immediately discarded. These wrappers do have native finalizers, so a permanent leak is **not** established. However, resources survive each parse until finalization, and native allocations are not deterministically released on normal completion or parser failure. Repeated long-PDF extraction from P3 increases this pressure.

**Control:** `PdfParser.parseRange` already releases StructuredText, Page and Document in nested finally blocks (`:39`, `:42`, `:49`). It is the tool path, not the attachment path. Severity stays Minor absent measured native-heap growth; unbounded text work is covered by P3 rather than counted again.

**Proposed regression:** track open/destroy counts with a narrow native-parser seam for normal/mid-page failure/cancellation, and validate real-device native heap behavior separately. Preserve attachment text/page semantics if switching parser implementations; do not turn finalizer reliance into an unsubstantiated claim of permanent leakage.

## Audited areas without a new finding / limits

- ConversationSessionManager reference release/idle-session removal and ChatVM.onCleared release: no confirmed activity/session retention leak found in the traced path.
- Long-history UI already has focused actual Compose coverage: `ui/pages/chat/ChatLongHistoryRuntimeTest.kt:74` (512 mixed messages, navigation/scrolling), `:134` and `:151` (latest assistant usage lookup). Source inspection did not establish a new full-history recomposition scan in those paths. Tests were not rerun here.
- History JSON loading occurs inside Room's suspend transaction; checkpoints explicitly use IO. GenerationLoop uses `flowOn(Dispatchers.IO)` (`GenerationLoop.kt:618`). These are not reported as Main-thread CPU paths merely because the service/AppScope originates on Main.
- DocumentAsPromptTransformer and generated chat-image normalization explicitly use IO. Their findings concern work/allocation bounds, not an incorrect dispatcher claim.
- Protected read_document source/ZIP limits, cancellation, file-access guards and native parseRange cleanup are present. Existing guards must remain intact.
- Attachment reference coordination, INCOMPLETE history protection, RootMode and symlink/hardlink/archive security controls were treated as preservation requirements; no weakening is suggested.
- No exhaustive heap profiling, arbitrary document-format fuzzing or device-specific ANR/CursorWindow threshold measurement was performed. The report does not claim whole-app leak freedom or real crash reproduction. Follow-up should first implement genuine regression fixtures and obtain RED before any production change.

---

# Часть 3 — фоновые задачи, уведомления и восстановление

Проверка 2026-10-18, опубликованный снимок C `d7f6ec1a1409c85d7ae76ac56371d235aa8e119a`. Только чтение текущего кода и существующих тестов. Main, Git, Gradle, зависимости, тесты, Actions и RootMode не изменялись. Новые воспроизведения не запускались: ниже указаны доказательства control flow и адресные предлагаемые регрессии. Это аудит, а не утверждение RED новых тестов.

Итог: **Critical — 0; Important — 5; Minor — 2**. У BG-5 подтверждён разрыв lifecycle ownership; фактическая вероятность kill и момент потери boot-события требуют телефона. Общие ограничения Android отдельно и не представлены как дополнительные баги.

Пути далее относительно `/workspace/Rikka-Root/`. Все классы основного кода находятся в `app/src/main/java/me/rerere/rikkahub/`, если не указано иное.

## BG-1 — Important: старый RELEASE media FGS останавливает сервис при уже поставленном новом ACQUIRE

**Код:** `app/src/main/java/me/rerere/rikkahub/service/MediaCreationForegroundService.kt:83`, `:115`, `:145`; фактический запуск/освобождение в `service/MediaCreationService.kt:218` и `:224`.

`onStartCommand` получает startId, но не передаёт его в release. Когда activeRecords опустел, release вызывает stopService → stopForeground + безусловный stopSelf(). Новый MediaCreationForegroundService.acquire уже может выполнить startForegroundService и поставить ACQUIRE B в очередь, пока main thread обрабатывает RELEASE A. Безусловный stopSelf не учитывает более новый startId: новый foreground-start может быть остановлен до своего startForeground. Возможны потеря защиты новой генерации и ForegroundServiceDidNotStartInTimeException. Это не общее предположение о FGS: в соседнем `service/ChatGenerationForegroundService.kt:112`–`:118` именно этот race уже документирован и закрыт `stopSelf(startId)`.

**Воспроизведение/control:** запустить ACQUIRE A; до обработки RELEASE A поставить ACQUIRE B через startForegroundService; обработать старый RELEASE с пустым набором A. Он вызывает stopSelf независимо от B. На телефоне — быстро завершить короткую media-generation и сразу запустить следующую; расписание Binder нужно контролировать, случайный ручной прогон не доказывает отсутствия race.

**Тесты:** специализированного теста lifecycle MediaCreationForegroundService среди `app/src/test` не найдено. `data/model/MediaCreationTest.kt` проверяет модель, а не queued startId.

**Предлагаемая регрессия/минимальное направление:** Robolectric service test с последовательными startId и уже существующим более новым стартом; старый RELEASE не должен останавливать сервис и убирать notification нового владельца, последний RELEASE должен завершать сервис. Отдельный control обычного последнего release. Передать startId и использовать тот же корректный lifecycle приём, который уже есть у chat FGS; не менять выполнение media и разрешения.

## BG-2 — Important: ошибка/отмена live lookup удаляет durable workflow occurrence как отсутствующий workflow

**Код:** `app/src/main/java/me/rerere/rikkahub/workflow/trigger/WorkflowTimeCronWorker.kt:67`–`:69`; `workflow/trigger/TimeCronTrigger.kt:23`–`:26`, `:95`–`:98`, `:78`–`:81`; `workflow/trigger/WorkflowTimeCronWorker.kt:32`–`:39`.

TimeCronWorkerHelper.repositoryLookup оборачивает весь DI/Room getById в runCatching.getOrNull. Временная IOException/SQLiteException и CancellationException превращаются в null. Default lookupEnabled также возвращает null для действительно выключенного/удалённого workflow. onWorkerFired не различает эти случаи: cancelWork удаляет сохранённый occurrence, отменяет alarm/fallback и все tagged workers. После этого worker может закончить обычным Result.success; исходная ошибка до его catch уже не доходит. На отсутствие эффекта это fail-closed, но ошибка чтения уничтожает recovery plan и теряет должный запуск. Поздняя стартовая пересинхронизация создаст новый будущий occurrence и не восстановит удалённый due token.

**Конкретное воспроизведение:** реальный enabled time_cron workflow + due occurrence в SharedPreferences; один getById через зарегистрированный в Koin реальный repository/DAO wrapper бросает IOException (отдельно CancellationException). Вызвать actual TimeCronWorkerHelper/default family path. Текущий helper возвращает null, occurrence удаляется, execute callback не вызывается. Для cancellation удаление производится синхронно после swallowed cancellation.

**Существующие controls:** `app/src/test/java/me/rerere/rikkahub/workflow/trigger/WorkflowTimeCronOccurrenceTest.kt:101` проверяет законное удаление при live=null; `:65` — отсутствие повторных эффектов при двух dispatch; `:78` — сохранение due token при boot. Эти тесты передают собственный lookupEnabled и не проверяют actual Koin helper. `WorkflowTimeCronRearmAuditTest.kt:32` покрывает ошибку планирования следующего alarm, не ошибку чтения workflow.

**Предлагаемая регрессия/минимальное направление:** temporary read failure оставляет тот же durable due identity, не отменяет timers и не запускает эффекты; следующая здоровая попытка выполняет его ровно один раз. Cancellation выходит как cancellation без удаления token. Control реального missing/disabled workflow по-прежнему удаляет token и блокирует эффекты. Не сводить exception к отсутствующей записи; до claim допустима адресная retry, после claim не вводить повтор эффектов.

## BG-3 — Important: ошибка terminal persistence workflow скрывается, оставляя RUNNING и обход cooldown для следующего запуска

**Код:** `app/src/main/java/me/rerere/rikkahub/workflow/execution/WorkflowEngine.kt:164`–`:180`, `:208`–`:221`; `workflow/repository/WorkflowRepository.kt:50`–`:56`, `:174`–`:194`; `workflow/db/WorkflowRunDao.kt:61`.

Перед эффектами beginRun правильно сохраняет RUNNING и daily quota. После успешного action persistAndReturn ловит исключение repository.recordFire, только пишет Log.w и всё равно возвращает FireOutcome(SUCCESS). Терминальная запись не сохранена, RUNNING остаётся. Тот же repository уже recovered=true, поэтому очередной fire не закрывает оставшийся receipt. Cooldown читает только SUCCESS/FAILED, RUNNING исключён SQL-запросом. Поэтому после однократного сбоя terminal insert следующий триггер в пределах cooldown может снова выполнить действие. Daily maxRunsPerDay, если явно задан и достигнут, остаётся дополнительной защитой; его обход здесь не утверждается.

**Конкретное воспроизведение:** workflow cooldownSeconds=3600, без daily cap; real Room repository с WorkflowRunDao wrapper, чей первый terminal insert бросает IOException, insertStarted работает. Два engine.fire подряд. Первый action выполнен, outcome=SUCCESS, но history содержит RUNNING. Второй cooldown получает null и снова запускает action. Это контроль потери известного результата и cooldown, не симуляция Android kill.

**Существующие tests:** `app/src/test/java/me/rerere/rikkahub/workflow/execution/WorkflowProcessDeathTest.kt:94` проверяет receipt до action; `:110` — закрытие старого RUNNING новым repository. `WorkflowCancellationAuditTest.kt:139` и `:143` проверяют cancellation около terminal insert, а не IOException записи. У SubAgentRegistry уже есть положительный пример сохранения известного terminal outcome для storage-only retry (`subagent/SubAgentRegistry.kt:18`, `:93`); `subagent/SubAgentProcessDeathTest.kt:76`.

**Предлагаемая регрессия/минимальное направление:** one-shot и длительный terminal-write failure должны сохранять известный completed outcome, не возвращать немаркированную успешность и не разрешать повтор эффекта внутри cooldown; после восстановления хранилища повторяется только запись receipt. Existing daily-cap/cancellation/Root safety controls сохранить. Не повторять action ради восстановления статуса.

## BG-4 — Important: recovery ставится completed навсегда после пропуска временно нечитаемого чата

**Код:** `app/src/main/java/me/rerere/rikkahub/service/ChatProcessRecovery.kt:52`–`:54`, `:73`–`:85`, `:101`; повторный вызов при открытии чата `service/ChatService.kt:499`–`:506`; `begin/finish` receipts в `service/ChatProcessRecovery.kt:105`–`:138`.

Нечитаемый отдельный чат корректно не мешает остальным и его исходные байты не перезаписываются. Однако catch getConversationById/HistoryLoadState!=COMPLETE делает continue, а после цикла recovered=true. Старый marker остаётся, но этот же recovery object больше его не обрабатывает, даже если БД/история уже снова читается: loadConversation зовёт recoverInterrupted, который сразу выходит. Старые background cards остаются running; pending interrupted tool/result не закрывается recovery. При последующем begin к старому чужому process-token добавляется новый ticket; finish нового ticket оставляет старого владельца, и следующий restart может ошибочно пометить уже новый завершённый ответ как interrupted.

**Конкретное воспроизведение:** сохранить чат и старый active marker; повредить его message row или заставить один read завершиться IOException; первый recovery обрабатывает другой здоровый чат и пропускает этот. Восстановить исходные bytes/доступность в том же процессе и вызвать recoverInterrupted/loadConversation. Текущий recovered=true не даёт закрыть старый receipt. Проверять точные original rows до восстановления, затем terminal interrupted state и отсутствие phantom marker после новой успешно завершённой генерации. Повтор эффектов не заявляется: это баг recovery state/receipts; ChatService дополнительно закрывает pending tools перед новой отправкой (`service/ChatService.kt:725`).

**Существующий близкий control:** `app/src/test/java/me/rerere/rikkahub/service/ChatProcessDeathTest.kt:394` сохраняет повреждённую историю и восстанавливает соседний чат, но не ремонтирует исходную строку с повторной попыткой в том же процессе. `:209` закрывает phantom receipt неуспешного начала, а не пропуск старой истории.

**Предлагаемая регрессия/минимальное направление:** сохранить независимость здоровых чатов и нетронутые bytes повреждённых; при следующем успешном чтении адресно повторить recovery пропущенных IDs, не переобрабатывать реальные текущие generation tickets и не воспроизводить tools.

## BG-5 — Important: boot workflow после receiver handoff не имеет durable Android execution owner

**Код:** `app/src/main/java/me/rerere/rikkahub/service/CronBootReceiver.kt:23`–`:37`; `service/scheduling/ScheduleRecovery.kt:44`–`:45`; `workflow/trigger/BootTrigger.kt:40`–`:66`; `workflow/trigger/TriggerRegistry.kt:45`; receipt появляется только в `workflow/execution/WorkflowEngine.kt:165`.

Boot dispatch синхронно вызывает family.onBoot, но эта функция только запускает scope.launch и сразу возвращает. Receiver затем pending.finish; WorkManager request, startForegroundService или durable pending boot event при этом не создаются. Coroutine обычного triggerScope не сохраняет Android process priority/lifetime после завершённого broadcast. Если cold-boot процесс уйдёт под kill до beginRun, не остаётся даже receipt, который мог бы показать потерю. Если action уже начал выполняться, существующий RUNNING receipt будет честно закрыт process_interrupted при следующем запуске, без replay.

**Что подтверждено и что требует телефона:** доказан именно fire-and-forget выход за lifecycle receiver и отсутствие durable handoff, а не неизбежность kill на каждом Android. OS может достаточно долго оставить процесс живым. Телефонный repro: enabled boot workflow с медленным безвредным action, cold boot без открытия UI; после завершения receiver удалить процесс через обычную OS reclaim/контролируемый kill и проверить отсутствие pending execution либо interrupted history. Force-stop имеет отдельные запреты Android, его нельзя выдавать за обычный reclaim.

**Существующие tests:** `app/src/test/java/me/rerere/rikkahub/service/scheduling/ScheduleRecoveryTest.kt:10` проверяет routing boot вызова; `:43` — правильное прекращение recovery при cancellation. `WorkflowProcessDeathTest.kt:94` проверяет durable receipt уже внутри engine, но нет теста receiver→durable executor до engine.

**Предлагаемая регрессия/направление:** задержать engine до начала, завершить receiver/исходный coroutine scope и проверить, что принятый boot event имеет сохранённый неизменный ID и Android worker, который ещё может безопасно начать действия; duplicate dispatch не дублирует эффекты, interruption после claim не replay. Новый durable handoff должен продолжать вызывать существующие owner/enabled/permissions/HARDLINE checks. Не держать goAsync открытым на всё время LLM/actions.

## BG-6 — Minor: 32-bit hash не разделяет notification/навигационные identities

**Код:** `app/src/main/java/me/rerere/rikkahub/service/ChatNotificationManager.kt:187`–`:196`; `service/MediaCreationForegroundService.kt:63`–`:72`; media result notification `service/MediaCreationService.kt:495`–`:505`; route priority `RouteActivity.kt:262`–`:263`.

Chat и media PendingIntent используют один RouteActivity, одинаковые flags, отсутствующие action/data и requestCode=UUID/String.hashCode. Extras не участвуют в identity PendingIntent. Два разных UUID/hash collision либо одинаковые hash у chat/media могут получить один PendingIntent, а FLAG_UPDATE_CURRENT заменит extras. Нажатие старого уведомления открывает другой чат/медиасеанс. Отдельно media result notificationId=record.id.hashCode может совпасть с другим media record и с зарезервированными 2001/2002/2003/7001: результат заменит чужое уведомление. У local notification tool это уже предотвращается отдельным durable диапазоном (`data/ai/tools/local/NotificationIdAllocator.kt:15`).

**Адресные cases:** для Kotlin Uuid.hashCode есть точная коллизия `00000000-0000-0000-0000-000000000001` и `00000000-0000-0000-0000-000100000000`: hash обоих 1 (реализация stdlib XOR двух Long, затем Long.hashCode просмотрена javap, без исполнения новых тестов). Точная cross chat/media коллизия: media session `00000000-0000-0000-0000-000000000001` имеет String.hashCode=1428967489; chat conversation `00000000-0000-0000-0000-0000552c5041` имеет Uuid.hashCode=1428967489. Hash вычислен по фактическим String/Uuid алгоритмам; JVM/Robolectric регрессия пока не запускалась. Media notification record `00000000-0000-0000-0000-0000000007d2` имеет hash2002, совпадая с chat FGS.

**Тесты:** `app/src/test/java/me/rerere/rikkahub/data/ai/tools/local/NotificationIdAllocatorTest.kt:9`, `:17`, `:22` покрывают только local-tool allocator; тестов namespace PendingIntent/media результата не найдено.

**Предлагаемая регрессия/минимальное направление:** Robolectric получить два actual PendingIntent с colliding requestCode, проверить, что обе исходные цели сохраняются; cross chat/media control. Разделить stable identity через уникальные action/data либо иной полный идентификатор, не менять экранные данные. Для notification IDs использовать namespaced tag+ID/существующий безопасный allocator; проверить coexistence с FGS и двумя media results.

## BG-7 — Minor: неуспешный первый запуск web server оставляет FGS и вечное уведомление «запуск»

**Код:** `app/src/main/java/me/rerere/rikkahub/service/WebServerService.kt:45`–`:55`, `:116`–`:133`; `web/WebServerManager.kt:79`–`:82`, `:108`–`:110`.

Сервис сначала входит в foreground и начинает observer с wasRunning=false. При занятом порте manager публикует terminal error с isRunning=false/isLoading=false, но observer останавливает foreground только при wasRunning=true. Первый неуспешный запуск никогда не проходил running=true, поэтому сервис не останавливается, а его notification остаётся starting. Даже ACTION_STOP не меняет wasRunning, если сервер ни разу не запустился. Это подтверждённый ресурсный/lifecycle дефект, а не утверждение, что ошибочный сервер слушает порт.

**Repro/control:** занять выбранный порт ServerSocket, отправить ACTION_START, дождаться error. Notification2001 и FGS остаются; ACTION_STOP после такой ошибки тоже не освобождает сервис. Control успешный start→stop должен продолжать корректно убирать notification. Тестов WebServerService lifecycle не найдено.

**Предлагаемая регрессия/минимальное направление:** actual service с manager state flow loading→terminal error обязан stopForeground+stopSelf и сохранять видимую причину ошибки; начальный idle state до начала загрузки не должен преждевременно останавливать pending start. Не менять binding/auth/local-network checks сервера.

## Проверенные области без дополнительных подтверждённых находок

- Cron: `service/CronJobScheduler.kt:41`–`:50` и `:68`–`:75` — общий recovery barrier и Room outbox перед rearm; `:152`–`:170` — запись queued остаётся для восстановления при cancellation; `:174`–`:201` — immutable occurrence dispatch/следующий repeat; `data/repository/ScheduledJobRunRepository.kt:35`–`:49` — транзакционный claim/token, `:63`–`:88` — terminal/recovery. `CronWorkerSafetyTest.kt:23`, `:41`, `:59`, `:71` покрывают barrier/outbox/no replay; `CronRunPolicyTest`, `CronJobExactSchedulingTest` — ограничения/расписание. Нового доказанного повторного эффекта после process kill в этом пути не найдено.
- Exact alarm и Doze routing: `service/scheduling/ExactAlarmScheduler.kt:43`–`:55` сохраняет fallback при revoked access; identity использует domain+key+time+revision URI (`:66`–`:74`), alarm receivers не исполняют tools, handoff идёт в WorkManager. `service/CronJobAlarmReceiver.kt:25`–`:33`, `workflow/trigger/WorkflowTimeCronAlarmReceiver.kt:20`–`:29`; `workflow/trigger/WorkflowTimeCronScheduling.kt:85`–`:120` разделяет delayed fallback и KEEP executor. `WorkflowTimeCronOccurrenceTest.kt:65`, `:156`, `:177`, `:188` покрывает duplicate CAS, clock rollback и сохранность running executor.
- Subagents: описание честно ограничивает run_in_background временем жизни процесса (`subagent/SubAgentTools.kt:72`); DI запускает через AppScope (`di/AppModule.kt:81`–`:83`), поэтому отсутствие отдельного FGS здесь не объявлено новым багом/обещанием бесшовного продолжения. Registry при смене processId переводит active в INDETERMINATE (`subagent/SubAgentRegistry.kt:39`–`:54`), не воспроизводит effects; per-owner ограничения/ownership под общим lock; known terminal outcome сохранён для storage-only retry. Существующие `SubAgentProcessDeathTest.kt:28`, `:59`, `:76`, `:160`, `:203`, `:220`, `:243`–`:252`, `:282`, `:314` покрывают восстановление, fail-closed и сохранность итогов.
- Chat: generation effects начинаются после durable begin (`service/ChatService.kt:425`–`:433`); finally сохраняет live snapshot до снятия receipt (`:443`–`:455`); chat FGS защищает queued starts startId (`service/ChatGenerationForegroundService.kt:112`). Process recovery сохраняет ветви/attachments и не replay tools (`service/ChatProcessRecovery.kt:178`–`:223`); наблюдение BG-4 отдельно. Existing ChatProcessDeathTest проверяет durable partial stream и новые/старые markers.
- Media: восстановление active records с taskId повторяет query, без taskId помечает interrupted failed, не запускает create автоматически (`service/MediaCreationService.kt:194`–`:213`, `:255`–`:269`). taskId после create сохраняется NonCancellable. Нового подтверждённого автоматического повторного create при recovery не найдено; сетевой разрыв до получения taskId остаётся неопределённостью remote API и не превращён здесь в обещание exactly-once.
- Notification special-access triggers не достижимы как enabled workflows в этой сборке: `workflow/execution/WorkflowAvailability.kt:8`–`:15` явно отказывает notification_received/geofence/app events; repository и engine вызывают этот gate. Отсутствие NotificationListenerService/Accessibility не выдано за сломанный работающий background service. Browser/RootMode/approval guards не изменялись и не переопределялись.

## Ограничения, требующие Android/телефона, без дополнительных подтверждённых ошибок

Foreground service повышает priority процесса, но не гарантирует бессмертие, CPU wake lock или сетевой доступ в Doze. Exact wakeup не обещает мгновенное начало сети/LLM после WorkManager enqueue; квоты expedited/Doze и OEM battery policy могут задержать исполнение. Полезная проверка — ordinary reclaim во время harmless action, idle/Doze с/без exact access, background-start restriction и notification permission denied. Это не новые находки без наблюдаемого нарушения конкретного обещания приложения.

Android 15+ dataSync FGS имеет системный общий лимит. Chat onTimeout запрашивает остановку generation и завершает service (`service/ChatGenerationForegroundService.kt:91`–`:102`); media onTimeout снимает service, сохраняемые task IDs позволяют последующий recovery (`service/MediaCreationForegroundService.kt:101`–`:105`). Фактический OS callback/ANR/лимиты на телефоне этим статическим аудитом не проверялись. Force-stop, battery restricted mode и запрет BOOT_COMPLETED до ручного запуска после force-stop нужно отличать от обычного process reclaim. APK/CI C не служат доказательством этих телефонных сценариев.

Новых tests/fixes пока нет. Перед изменениями нужны адресные реальные RED указанных регрессий и согласованный root scope; security/approval/HARDLINE/replay защиты сохраняются.

## Дальнейшая проверка

Каждый обязательный пункт: сначала тест на текущем поведении и фактический RED, затем минимальное исправление и GREEN. Данные/история/вложения сохраняются; миграции только вперёд с проверкой. RootMode, подтверждения и защиты файлов не ослабляются. Пакеты до60 уникальных путей публикуются с pull/rebase перед каждым push и зеркалом; следующий пакет — после собственного полного GREEN. APK только Actions. Небольшие пункты не объявляются исправленными без отдельного доказательства.
