# Исправление отчёта и пакет F: перенос из RikkaHub Agent

Источник: `ExTV/rikkahub-agent`, локальная копия `/workspace/refs/rikkahub-agent`, ревизия `a88f5854`. Целевая ветка: `ccr-e93188a6-grrqko`, upstream RikkaHub 2.5.6 с `ConversationConfig`.

В этом выпуске пользователь разрешил исправление трёх предложений по отчёту и весь F одним push/одной Daily Build, с отдельными коммитами по подсистемам. Лимит 60 файлов для этого выпуска снят. Новые возможности сверх исходного Agent не добавлялись; дополнительные классы ниже обеспечивают обязательную совместимость, HARDLINE, изоляцию чатов и защиту секретов.

## Что изменено

- Отчёт называется `rikka-root-report-<дата-время>.zip`, имя резервируется атомарно. Сохранены logcat собственного процесса без root, полные redacted ошибки ChatService, русский README, кнопка отправки и копия `reports/<имя>.zip` в workspace чата.
- Захват logcat ограничен 5 секундами; отмена и закрытие процесса/потоков обработаны. Ошибки захвата редактируются до Android Log и ZIP. Cookie/Set-Cookie маскируется целиком; исходные паттерны Agent сохранены.
- Все шесть путей провайдеров `ai` выводят только метаданные: провайдер, безопасный идентификатор модели, код ответа, размер. Тела запросов/ответов, SSE-события, grounding, заголовки, URL и сырые исключения в этих журналах отсутствуют. Убран безусловный HTTP HEADERS logger приложения. Сами запросы/декодеры не переписаны.
- Перенесены Termux (6 tools), SSH/SFTP/хосты (8), MCP control (9), external automation (4). Имена инструментов, параметры и `@SerialName` сохранены; функции выключены по умолчанию и включаются для ассистента. Все 27 инструментов включены в существующую систему одобрений.
- Termux/SSH проходят RootCommandGuard и обязательное подтверждение защищённых команд; headless не обходит HARDLINE. Сброс доверенного SSH host key всегда требует отдельного подтверждения.
- Использованы актуальные исходные исправления Termux `1ea13e5b`, `87a9fdd9`, `6a0b6001` и MCP `6991b23e`: перенос сделан с текущего дерева Agent, в котором они уже присутствуют.
- Новые SSH/MCP секреты шифруются AES-GCM ключами AndroidKeyStore; шифротекст находится в `noBackupFilesDir`. В Room/FTS/settings сохраняются только метаданные/непрозрачные ссылки, экспорт их не раскрывает. Секреты вносятся через защищённые поля настроек; утраченный vault после переноса бэкапа требует повторного ввода.
- Проверка опубликованного release APK выявила удаление R8 реализаций JSch при сохранённых строках reflection. В `app/build.gradle.kts` Agent также включена оптимизация без keep-правил. Для работоспособности порта в Root добавлены правила сохранения классов/конструкторов JSch; логика SSH не изменена.
- SSH saved-host credentials связаны с конкретными host/port/user: конкурентная замена alias, ошибка записи Room или восстановление старой metadata не должны отправить пароль другого endpoint. Общий `sshCommandInputs` одинаково проверяет stdin при запуске shell через `env`/`sudo`/`su`/`busybox`/`toybox` в интерактивной и headless политике.
- MCP control пишет в текущий `ConversationConfig`, а не в выбранного глобально ассистента. В headless изменения allowlist остаются в side-context, creator-chat не меняется. Управляемые из чата серверы используют public-only DNS/URL проверку каждого запроса, включая SSE POST и OAuth, и не следуют redirects.

## Разрешения Android

| Разрешение/декларация | Изменение и условия |
| --- | --- |
| `com.termux.permission.RUN_COMMAND` | Добавлено как в Agent для Termux. Запрашивается при включении функции; отказ объясняется, функция остаётся выключенной. Termux должен быть установлен, в его настройках требуется `allow-external-apps=true`; есть проверка интеграции. |
| `<queries><package android:name="com.termux"/>` | Добавлена только видимость пакета Termux для состояния и проверки установки; это не `QUERY_ALL_PACKAGES`. |
| `android.permission.INTERNET` | Уже было в Root; используется SSH и MCP. Отдельного runtime запроса нет. |
| `android.permission.ACCESS_NETWORK_STATE` | Уже было в Root; используется исходным NetworkChangeMonitor SSH. |
| `android.permission.ACCESS_LOCAL_NETWORK` | Уже было в Root; для SSH запрашивается при включении на Android API 37+, с понятным отказом. На более ранних версиях не запрашивается. |
| Exported `ExternalAutomationActivity` и `ExternalAutomationReceiver` | Перенесены исходные actions `RUN_TASK`/`RUN_CHAT`; это точки интеграции, не новые Android permissions. Запуск разрешён только при включённой функции и проверенной Android личности доверенного пакета. |

Новых спецдоступов нет. Автоматическая выдача прав через root не выполняется.

## Таблица переноса

Все пути ниже относительны корню соответствующего репозитория. «Скопировано» означает файл Agent без изменений. «Адаптировано» указывает необходимые различия; знак «—» явно отличает существующую интеграцию/новую регрессию Root от перенесённого файла. Изменения провайдеров и отчёта здесь отдельно разрешены пользователем и не заявлены как буквальная копия Agent.

### Исправление отчёта и журналов провайдеров

| Файл Agent | Файл Rikka-Root | Статус и необходимые изменения |
| --- | --- | --- |
| `ai/build.gradle.kts` | `ai/build.gradle.kts` | адаптировано (существующий Root: Robolectric для перехвата Android Log в тестах провайдеров) |
| — (новая необходимая адаптация/регрессия Root) | `ai/src/main/java/me/rerere/ai/provider/ProviderLog.kt` | адаптировано (новая необходимая общая точка логирования: только фиксированные метаданные, безопасный идентификатор модели; без тела, URL, заголовков и исключений) |
| `ai/src/main/java/me/rerere/ai/provider/providers/claude/ClaudeProvider.kt` | `ai/src/main/java/me/rerere/ai/provider/providers/claude/ClaudeProvider.kt` | адаптировано (существующий провайдер Root: запросы, ответы и SSE заменены метаданными; обработка API сохранена) |
| `ai/src/main/java/me/rerere/ai/provider/providers/google/GoogleProvider.kt` | `ai/src/main/java/me/rerere/ai/provider/providers/google/GoogleProvider.kt` | адаптировано (существующий провайдер Root: запросы, ответы, SSE и grounding заменены метаданными; обработка API сохранена) |
| — (существующая интеграция/тест Root) | `ai/src/main/java/me/rerere/ai/provider/providers/google/InteractionsAPI.kt` | адаптировано (существующий Root API 2.5.6, отсутствующий в Agent: тела и события стрима заменены метаданными) |
| `ai/src/main/java/me/rerere/ai/provider/providers/openai/ChatCompletionsAPI.kt` | `ai/src/main/java/me/rerere/ai/provider/providers/openai/ChatCompletionsAPI.kt` | адаптировано (существующий провайдер Root: тела обычных запросов, ответов и SSE заменены метаданными) |
| `ai/src/main/java/me/rerere/ai/provider/providers/openai/OpenAIProvider.kt` | `ai/src/main/java/me/rerere/ai/provider/providers/openai/OpenAIProvider.kt` | адаптировано (существующий провайдер Root: сведения о моделях и генерации изображений логируются без содержимого API) |
| `ai/src/main/java/me/rerere/ai/provider/providers/openai/ResponseAPI.kt` | `ai/src/main/java/me/rerere/ai/provider/providers/openai/ResponseAPI.kt` | адаптировано (существующий провайдер Root: тела запросов/ответов и события Responses SSE заменены метаданными) |
| — (новая необходимая адаптация/регрессия Root) | `ai/src/test/java/me/rerere/ai/provider/ProviderLoggingTest.kt` | адаптировано (новый регрессионный тест: реальные HTTP/стримовые пути провайдеров, ошибка API и подмена model ID; перехват Android Log и stdout/stderr) |
| `app/src/main/java/me/rerere/rikkahub/reliability/BugReportBuilder.kt` | `app/src/main/java/me/rerere/rikkahub/reliability/BugReportBuilder.kt` | адаптировано (сохранён ранее перенесённый builder Agent; разрешённые исправления: имя rikka-root-report, атомарная уникальность, 5-секундный logcat, закрытие процесса/потоков, отмена и редактор ошибок/Cookie) |
| — (существующая интеграция/тест Root) | `app/src/test/java/me/rerere/rikkahub/reliability/BugReportBuilderTest.kt` | адаптировано (существующие тесты Root расширены: содержимое ZIP, уникальные имена, таймаут/отмена, безопасная диагностика и закрытие процесса) |
| `app/src/test/java/me/rerere/rikkahub/reliability/SecretRedactorTest.kt` | `app/src/test/java/me/rerere/rikkahub/reliability/SecretRedactorTest.kt` | адаптировано (исходные проверки Agent сохранены; добавлены проверки полного Cookie, ошибок захвата и ключей провайдеров) |


### Termux

| Файл Agent | Файл Rikka-Root | Статус и необходимые изменения |
| --- | --- | --- |
| `app/src/main/java/me/rerere/rikkahub/data/ai/limits/ToolRuntimeLimits.kt` | `app/src/main/java/me/rerere/rikkahub/data/ai/limits/ToolRuntimeLimits.kt` | скопировано (исходные ограничения шагов и времени инструментов) |
| — (новая необходимая адаптация/регрессия Root) | `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/TermuxCommandGuard.kt` | адаптировано (новая обязательная интеграция HARDLINE: команда/аргументы, накопление частей ввода и проверка видимой строки сессии) |
| `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/TermuxSessionTool.kt` | `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/TermuxSessionTool.kt` | адаптировано (исходные tmux-сессии, параметры и фиксы Agent; русские ошибки, RootCommandGuard и проверка составной/восстановленной строки перед Enter) |
| `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/TermuxTool.kt` | `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/TermuxTool.kt` | адаптировано (исходный сервис RUN_COMMAND, параметры и обработка результата; русские ошибки, RootCommandGuard, наша политика одобрения; отсутствующий Telegram tracker убран) |
| `app/src/main/java/me/rerere/rikkahub/data/preferences/TermuxDefaults.kt` | `app/src/main/java/me/rerere/rikkahub/data/preferences/TermuxDefaults.kt` | скопировано (исходные значения по умолчанию) |
| `app/src/main/java/me/rerere/rikkahub/data/preferences/TermuxPreferences.kt` | `app/src/main/java/me/rerere/rikkahub/data/preferences/TermuxPreferences.kt` | скопировано (исходные Preferences и загрузка настроек) |
| `app/src/main/java/me/rerere/rikkahub/data/preferences/TermuxRuntime.kt` | `app/src/main/java/me/rerere/rikkahub/data/preferences/TermuxRuntime.kt` | скопировано (исходное состояние интеграции и runtime) |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/termux/SettingTermuxPage.kt` | `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/termux/SettingTermuxPage.kt` | адаптировано (исходный экран настройки/проверки интеграции и лимитов; строки на русском, навигация/API 2.5.6, configuration-aware LocalResources) |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/termux/SettingTermuxViewModel.kt` | `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/termux/SettingTermuxViewModel.kt` | скопировано (исходный ViewModel) |
| `app/src/main/res/values/strings.xml` | `app/src/main/res/values/strings_termux.xml` | адаптировано (перевод строк исходного экрана Termux Agent; отдельный русский ресурс Root) |
| — (новая необходимая адаптация/регрессия Root) | `app/src/test/java/me/rerere/rikkahub/data/ai/tools/local/TermuxCommandGuardTest.kt` | адаптировано (новые регрессии HARDLINE: аргументы, составной/восстановленный ввод, клавиши и обязательное подтверждение) |
| `app/src/test/java/me/rerere/rikkahub/data/ai/tools/local/TermuxSessionToolTest.kt` | `app/src/test/java/me/rerere/rikkahub/data/ai/tools/local/TermuxSessionToolTest.kt` | скопировано (исходные тесты сессий Termux) |
| `app/src/test/java/me/rerere/rikkahub/data/ai/tools/local/TermuxToolTest.kt` | `app/src/test/java/me/rerere/rikkahub/data/ai/tools/local/TermuxToolTest.kt` | скопировано (исходные тесты Termux) |
| `app/src/test/java/me/rerere/rikkahub/data/preferences/TermuxDefaultsTest.kt` | `app/src/test/java/me/rerere/rikkahub/data/preferences/TermuxDefaultsTest.kt` | скопировано (исходные проверки значений по умолчанию) |
| `app/src/test/java/me/rerere/rikkahub/data/preferences/TermuxRuntimeWiringTest.kt` | `app/src/test/java/me/rerere/rikkahub/data/preferences/TermuxRuntimeWiringTest.kt` | скопировано (исходные проверки подключения runtime) |


### SSH / SFTP

| Файл Agent | Файл Rikka-Root | Статус и необходимые изменения |
| --- | --- | --- |
| — (новая необходимая адаптация/регрессия Root) | `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/AcceptNewHostKeyRepository.kt` | адаптировано (новая необходимая accept-new реализация для JSch 0.2.21: первый ключ сохраняется, изменённый отклоняется, host:port не смешиваются) |
| `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/DnsCache.kt` | `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/DnsCache.kt` | скопировано (исходный DNS cache) |
| `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/SshHostsTool.kt` | `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/SshHostsTool.kt` | адаптировано (исходные операции хостов; секреты разрешаются только на выполнение, сохранение в защищённый vault, русские ошибки) |
| `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/SshSftpTool.kt` | `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/SshSftpTool.kt` | адаптировано (исходный SFTP; наш LocalFileAccess для workspace и запрет доступа к приватному хранилищу приложения, KeyStore и одобрение, русские ошибки) |
| `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/SshTool.kt` | `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/SshTool.kt` | адаптировано (исходное выполнение SSH; общий sshCommandInputs/HARDLINE включая stdin через env/sudo/su/busybox/toybox, KeyStore references, рабочая accept-new политика JSch, раздельные ключи портов, русские ошибки, безопасный журнал auth) |
| `app/src/main/java/me/rerere/rikkahub/data/db/dao/SshHostDao.kt` | `app/src/main/java/me/rerere/rikkahub/data/db/dao/SshHostDao.kt` | скопировано (исходный DAO) |
| `app/src/main/java/me/rerere/rikkahub/data/db/entity/SshHostEntity.kt` | `app/src/main/java/me/rerere/rikkahub/data/db/entity/SshHostEntity.kt` | адаптировано (в Room остаются метаданные/флаги; password/privateKey/passphrase только временные @Ignore поля) |
| `app/src/main/java/me/rerere/rikkahub/data/repository/SshHostRepository.kt` | `app/src/main/java/me/rerere/rikkahub/data/repository/SshHostRepository.kt` | адаптировано (исходный repository и интерфейс операций; секреты вынесены из Room в защищённое noBackup хранилище и привязаны к host/port/user, чтобы отказать при смене endpoint/гонке/восстановлении metadata) |
| — (новая необходимая адаптация/регрессия Root) | `app/src/main/java/me/rerere/rikkahub/data/ssh/SshCredentialStore.kt` | адаптировано (новое обязательное хранилище AES-GCM с AndroidKeyStore; шифротекст в noBackup, вне Room/бэкапов; credential entry связан с endpoint) |
| — (новая необходимая адаптация/регрессия Root) | `app/src/main/java/me/rerere/rikkahub/data/ssh/SshToolSecretSanitizer.kt` | адаптировано (новая обязательная защита аргументов SSH в Room/FTS/экспорте: непрозрачные ссылки вместо паролей/ключей, обработка неполного JSON) |
| — (новая необходимая адаптация/регрессия Root) | `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/ssh/SettingSshPage.kt` | адаптировано (новая необходимая безопасная форма ввода исходных saved-host credentials: временные поля, FLAG_SECURE, без чата/clipboard/saved state) |
| `app/src/main/java/me/rerere/rikkahub/utils/NetworkChangeMonitor.kt` | `app/src/main/java/me/rerere/rikkahub/utils/NetworkChangeMonitor.kt` | скопировано (исходный монитор смены сети) |
| `app/src/test/java/me/rerere/rikkahub/data/ai/tools/local/DnsCacheTest.kt` | `app/src/test/java/me/rerere/rikkahub/data/ai/tools/local/DnsCacheTest.kt` | скопировано (исходные проверки DNS cache) |
| `app/src/test/java/me/rerere/rikkahub/data/ai/tools/local/SshCommandWrappingTest.kt` | `app/src/test/java/me/rerere/rikkahub/data/ai/tools/local/SshCommandWrappingTest.kt` | скопировано (исходные проверки упаковки SSH-команд) |
| — (новая необходимая адаптация/регрессия Root) | `app/src/test/java/me/rerere/rikkahub/data/ai/tools/local/SshHostKeyPolicyTest.kt` | адаптировано (новые проверки accept-new, изменения ключа, разных портов и forget) |
| — (новая необходимая адаптация/регрессия Root) | `app/src/test/java/me/rerere/rikkahub/data/ai/tools/local/SshSecurityTest.kt` | адаптировано (новые проверки HARDLINE для SSH и shell stdin, включая env/sudo/su/busybox/toybox wrappers) |
| — (новая необходимая адаптация/регрессия Root) | `app/src/test/java/me/rerere/rikkahub/data/repository/ConversationSshPrivacyTest.kt` | адаптировано (новые проверки Room/FTS/export SSH и MCP, возобновления сохранённого вызова и неполных аргументов) |
| — (новая необходимая адаптация/регрессия Root) | `app/src/test/java/me/rerere/rikkahub/data/ssh/SshCredentialStoreTest.kt` | адаптировано (новые проверки шифрования, непрозрачных ссылок и потери ключа) |
| — (новая необходимая адаптация/регрессия Root) | `app/src/test/java/me/rerere/rikkahub/data/ssh/SshHostRepositoryTest.kt` | адаптировано (новые проверки metadata-only Room, endpoint binding и восстановления секретов только на выполнение) |
| — (новая необходимая адаптация/регрессия Root) | `app/src/test/java/me/rerere/rikkahub/data/ssh/SshToolSecretSanitizerTest.kt` | адаптировано (новые проверки сохранения/экспорта, неполных аргументов и повторного использования ссылок) |


### MCP control и интеграция upstream 2.5.6

| Файл Agent | Файл Rikka-Root | Статус и необходимые изменения |
| --- | --- | --- |
| `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpConfig.kt` | `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpConfig.kt` | адаптировано (существующий upstream Root: publicAddressOnly marker; по умолчанию false для старых ручных серверов) |
| `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpManager.kt` | `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpManager.kt` | адаптировано (исходный buildMcpToolName перенесён; API 2.5.6/registry forceResync, защищённые transport/OAuth clients и injection vault) |
| `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpOAuthCoordinator.kt` | `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpOAuthCoordinator.kt` | адаптировано (существующий Root OAuth 2.5.6: client/access/refresh секреты в vault, hydrate только перед запросом; controlled URL guard) |
| `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpOAuthDiscoveryClient.kt` | `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpOAuthDiscoveryClient.kt` | адаптировано (существующий Root OAuth 2.5.6: удалены URL и внешние metadata из logcat, оставлены только счётчики/категории) |
| `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpSessionRegistry.kt` | `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpSessionRegistry.kt` | адаптировано (существующий Root API 2.5.6: выбор защищённого клиента, разрешение references только для подключения, безопасная ошибка при отсутствии секрета) |
| `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/control/McpApprovalRenderer.kt` | `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/control/McpApprovalRenderer.kt` | адаптировано (исходные ветви карточки подтверждения и маски заголовков; русский текст) |
| — (новая необходимая адаптация/регрессия Root) | `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/control/McpControlCaller.kt` | адаптировано (новая необходимая привязка к доверенному владельцу и текущему ConversationConfig; headless меняет только side-context) |
| — (новая необходимая адаптация/регрессия Root) | `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/control/McpControlSecretStore.kt` | адаптировано (новое обязательное AES-GCM AndroidKeyStore/noBackup хранилище header/OAuth секретов) |
| `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/control/McpControlTools.kt` | `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/control/McpControlTools.kt` | адаптировано (исходные 9 операций, параметры, CRUD/test/rollback; callbacks текущего ConversationConfig, KeyStore, public-only SSRF, русские ошибки, одобрение) |
| — (новая необходимая адаптация/регрессия Root) | `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/control/McpControlTransport.kt` | адаптировано (новая обязательная защита фактического DNS и каждой HTTP/SSE/OAuth цели; redirects отключены для controlled-серверов) |
| `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/control/McpControlValidation.kt` | `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/control/McpControlValidation.kt` | адаптировано (исходные ограничения имён/URL/заголовков; существующий контракт имён Root и защищённые ссылки, русские ошибки) |
| `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/control/McpHeaderRedactor.kt` | `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/control/McpHeaderRedactor.kt` | адаптировано (исходные маски/last-four; покрытие дополнительных секретных имён заголовков и непрозрачных ссылок) |
| — (новая необходимая адаптация/регрессия Root) | `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/control/McpToolSecretSanitizer.kt` | адаптировано (новая обязательная защита headers в истории Room/FTS и экспорте, включая неполный JSON) |
| `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/control/McpUrlGuard.kt` | `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/control/McpUrlGuard.kt` | адаптировано (исходный helper; обязательная защита SSRF: запрещены локальные/специальные IPv4/IPv6 и все приватные ответы фактического DNS) |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingMcpPage.kt` | `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingMcpPage.kt` | адаптировано (существующий Root экран: секретные headers/import/OAuth сохраняются как refs, временный защищённый ввод, FLAG_SECURE, русский отказ) |
| `app/src/test/java/me/rerere/rikkahub/data/ai/mcp/McpConnectionKeyTest.kt` | `app/src/test/java/me/rerere/rikkahub/data/ai/mcp/McpConnectionKeyTest.kt` | адаптировано (существующие проверки Root дополнены учётом publicAddressOnly в идентичности соединения) |
| — (новая необходимая адаптация/регрессия Root) | `app/src/test/java/me/rerere/rikkahub/data/ai/mcp/McpOAuthSecretsTest.kt` | адаптировано (новые проверки защищённого OAuth persistence и ephemeral hydrate) |
| `app/src/test/java/me/rerere/rikkahub/data/ai/mcp/McpToolNameTest.kt` | `app/src/test/java/me/rerere/rikkahub/data/ai/mcp/McpToolNameTest.kt` | адаптировано (исходные проверки dispatchable_name; приведение импортов/формата к Root) |
| — (новая необходимая адаптация/регрессия Root) | `app/src/test/java/me/rerere/rikkahub/data/ai/mcp/control/McpApprovalRendererTest.kt` | адаптировано (новые проверки исходной карточки подтверждения/маски секретов) |
| — (новая необходимая адаптация/регрессия Root) | `app/src/test/java/me/rerere/rikkahub/data/ai/mcp/control/McpControlCallerTest.kt` | адаптировано (новые регрессии изоляции двух ConversationConfig, owner change и отсутствия записи в creator-chat из headless) |
| — (новая необходимая адаптация/регрессия Root) | `app/src/test/java/me/rerere/rikkahub/data/ai/mcp/control/McpControlSecretStoreTest.kt` | адаптировано (новые проверки шифрования и потери/восстановления ссылки) |
| — (новая необходимая адаптация/регрессия Root) | `app/src/test/java/me/rerere/rikkahub/data/ai/mcp/control/McpControlTransportTest.kt` | адаптировано (новые проверки actual-target DNS/HTTP/SSE SSRF и запрета redirects) |
| `app/src/test/java/me/rerere/rikkahub/data/ai/mcp/control/McpControlValidationTest.kt` | `app/src/test/java/me/rerere/rikkahub/data/ai/mcp/control/McpControlValidationTest.kt` | адаптировано (исходные проверки плюс Root name/reference регрессии) |
| `app/src/test/java/me/rerere/rikkahub/data/ai/mcp/control/McpDispatchVisibilityTest.kt` | `app/src/test/java/me/rerere/rikkahub/data/ai/mcp/control/McpDispatchVisibilityTest.kt` | адаптировано (исходные проверки видимости dispatch; адаптированы импорты/формат) |
| `app/src/test/java/me/rerere/rikkahub/data/ai/mcp/control/McpErrorClassificationTest.kt` | `app/src/test/java/me/rerere/rikkahub/data/ai/mcp/control/McpErrorClassificationTest.kt` | адаптировано (исходные проверки классификации ошибок; адаптированы импорты/формат) |
| `app/src/test/java/me/rerere/rikkahub/data/ai/mcp/control/McpHeaderRedactorTest.kt` | `app/src/test/java/me/rerere/rikkahub/data/ai/mcp/control/McpHeaderRedactorTest.kt` | адаптировано (исходные проверки редактирования заголовков; адаптированы импорты/формат) |
| `app/src/test/java/me/rerere/rikkahub/data/ai/mcp/control/McpInputSchemaJsonTest.kt` | `app/src/test/java/me/rerere/rikkahub/data/ai/mcp/control/McpInputSchemaJsonTest.kt` | адаптировано (исходные проверки JSON schema; адаптированы импорты/формат) |
| — (новая необходимая адаптация/регрессия Root) | `app/src/test/java/me/rerere/rikkahub/data/ai/mcp/control/McpToolSecretSanitizerTest.kt` | адаптировано (новые проверки ссылок/неполного JSON/экспорта MCP headers) |
| `app/src/test/java/me/rerere/rikkahub/data/ai/mcp/control/McpUrlGuardTest.kt` | `app/src/test/java/me/rerere/rikkahub/data/ai/mcp/control/McpUrlGuardTest.kt` | адаптировано (исходные проверки адаптированы под разрешённый public-only SSRF, добавлены DNS/rebinding/IPv6/protocol query регрессии) |
| — (новая необходимая адаптация/регрессия Root) | `app/src/test/java/me/rerere/rikkahub/ui/pages/setting/McpSettingsSecretsTest.kt` | адаптировано (новые проверки защищённого сохранения/импорта MCP config) |


### External automation и headless

| Файл Agent | Файл Rikka-Root | Статус и необходимые изменения |
| --- | --- | --- |
| `app/src/main/java/me/rerere/rikkahub/automation/ExternalAutomationActivity.kt` | `app/src/main/java/me/rerere/rikkahub/automation/ExternalAutomationActivity.kt` | адаптировано (исходные RUN_TASK/RUN_CHAT и trust flow; Android-verified identity, русский интерфейс/API импорты; исходные недоделки сохранены) |
| `app/src/main/java/me/rerere/rikkahub/automation/ExternalAutomationConfig.kt` | `app/src/main/java/me/rerere/rikkahub/automation/ExternalAutomationConfig.kt` | скопировано (исходные Preferences, trust-list и журнал вызовов) |
| `app/src/main/java/me/rerere/rikkahub/automation/ExternalAutomationDispatcher.kt` | `app/src/main/java/me/rerere/rikkahub/automation/ExternalAutomationDispatcher.kt` | адаптировано (исходные dispatch/status/callback/dedup; наш guarded HeadlessTaskRunner/ConversationConfig вместо auto-approved Agent ledger, callbacks только проверенному caller) |
| `app/src/main/java/me/rerere/rikkahub/automation/ExternalAutomationReceiver.kt` | `app/src/main/java/me/rerere/rikkahub/automation/ExternalAutomationReceiver.kt` | адаптировано (исходная точка RUN_TASK; обязательная проверка реального sentFromPackage/UID, синтетический <adb> и extras не считаются личностью) |
| `app/src/main/java/me/rerere/rikkahub/automation/ExternalAutomationTools.kt` | `app/src/main/java/me/rerere/rikkahub/automation/ExternalAutomationTools.kt` | адаптировано (исходные 4 tools/параметры; одобрение и русские ошибки) |
| — (существующая интеграция/тест Root) | `app/src/main/java/me/rerere/rikkahub/data/ai/tools/RunExecutionContext.kt` | адаптировано (существующий Root: origin внешнего запуска и headless блокировка trust mutations/интерактивных действий, HARDLINE для SSH/Termux) |
| — (существующая интеграция/тест Root) | `app/src/main/java/me/rerere/rikkahub/service/HeadlessRuntimeBindings.kt` | адаптировано (существующий Root: live feature/caller recheck, текущая MCP allowlist и общий dispatch name, соответствие всех F tools их выключателям) |
| — (новая необходимая адаптация/регрессия Root) | `app/src/test/java/me/rerere/rikkahub/automation/ExternalAutomationCallerTest.kt` | адаптировано (новые проверки Android identity, отказа spoofed/unverified caller и callback scope) |
| `app/src/test/java/me/rerere/rikkahub/automation/ExternalAutomationDispatcherTest.kt` | `app/src/test/java/me/rerere/rikkahub/automation/ExternalAutomationDispatcherTest.kt` | скопировано (исходные проверки dispatcher) |
| — (существующая интеграция/тест Root) | `app/src/test/java/me/rerere/rikkahub/service/HeadlessRuntimeBindingsTest.kt` | адаптировано (новые регрессии live-switch/caller/owner, F tool mapping и текущего MCP side-context) |
| — (существующая интеграция/тест Root) | `app/src/test/java/me/rerere/rikkahub/service/HeadlessTaskRunnerTest.kt` | адаптировано (существующие тесты Root расширены для внешнего origin и запретов F в headless) |
| — (существующая интеграция/тест Root) | `app/src/test/java/me/rerere/rikkahub/service/HeadlessToolPolicyTest.kt` | адаптировано (существующие тесты Root расширены для shell/HARDLINE, trust mutations и интерактивного Termux) |


### Общая регистрация, интерфейс, DI и схема

| Файл Agent | Файл Rikka-Root | Статус и необходимые изменения |
| --- | --- | --- |
| `app/build.gradle.kts` | `app/build.gradle.kts` | адаптировано (исходная зависимость SSH/SFTP com.github.mwiede:jsch:0.2.21; kotlinx-coroutines-test для JVM проверки реальной регистрации LocalTools) |
| `app/build.gradle.kts` (оптимизация без правил JSch) | `app/jsch-rules.pro` | адаптировано (новая обязательная release-совместимость: сохранение имён классов и всех конструкторов, которые JSch загружает через reflection; только точечные R8-предупреждения об отсутствующих optional BC/JGSS/Windows/junixsocket/Log4j адаптерах, которые SSH-инструменты не включают) |
| `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/29.json` | `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/29.json` | адаптировано (сгенерированная схема Room Root для metadata-only таблицы SSH) |
| `app/src/main/AndroidManifest.xml` | `app/src/main/AndroidManifest.xml` | адаптировано (Agent RUN_COMMAND и query com.termux; исходные exported automation action surfaces с проверенным caller; новых спецдоступов нет) |
| `app/src/main/java/me/rerere/rikkahub/RikkaHubApp.kt` | `app/src/main/java/me/rerere/rikkahub/RikkaHubApp.kt` | адаптировано (подключение исходного NetworkChangeMonitor при старте) |
| `app/src/main/java/me/rerere/rikkahub/RouteActivity.kt` | `app/src/main/java/me/rerere/rikkahub/RouteActivity.kt` | адаптировано (регистрация исходного Termux экрана и необходимого защищённого SSH экрана) |
| `app/src/main/java/me/rerere/rikkahub/data/ai/GenerationLoop.kt` | `app/src/main/java/me/rerere/rikkahub/data/ai/GenerationLoop.kt` | адаптировано (существующий Root: метаданные provider retry; исходные Agent time/step caps и structured tool_not_found из фикса 6991b23e) |
| `app/src/main/java/me/rerere/rikkahub/data/ai/tools/ChatToolFactory.kt` | `app/src/main/java/me/rerere/rikkahub/data/ai/tools/ChatToolFactory.kt` | адаптировано (создание MCP caller из текущего чата/side-context, подключение F; исходный общий dispatch name из фикса 6991b23e) |
| — (существующая интеграция/тест Root) | `app/src/main/java/me/rerere/rikkahub/data/ai/tools/ToolPermissionPolicy.kt` | адаптировано (существующая система Root: все 27 F tools требуют одобрения; HARDLINE/mandatory confirmation для shell и отдельное подтверждение сброса host key) |
| `app/src/main/java/me/rerere/rikkahub/data/ai/tools/LocalTools.kt` | `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/LocalToolOption.kt` | адаптировано (исходные @SerialName termux/ssh/mcp_control/external_automation в существующем sealed class Root; default OFF) |
| — (существующая интеграция/тест Root) | `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/LocalToolPermissions.kt` | адаптировано (существующий Root permission adapter: RUN_COMMAND при Termux enable, существующий ACCESS_LOCAL_NETWORK для SSH Android 37+) |
| `app/src/main/java/me/rerere/rikkahub/data/ai/tools/LocalTools.kt` | `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/LocalTools.kt` | адаптировано (регистрация исходных 27 F tools только при соответствующей опции; DI, workspace scope, trusted MCP caller и secret-argument adapter) |
| `app/src/main/java/me/rerere/rikkahub/data/db/AppDatabase.kt` | `app/src/main/java/me/rerere/rikkahub/data/db/AppDatabase.kt` | адаптировано (интеграция исходной таблицы SSH в Room Root; версия 29 и AutoMigration 28→29) |
| `app/src/main/java/me/rerere/rikkahub/data/repository/ConversationRepository.kt` | `app/src/main/java/me/rerere/rikkahub/data/repository/ConversationRepository.kt` | адаптировано (существующий Root repository: безопасные snapshots до Room/FTS, SSH/MCP sanitizer для tool call/result, включая legacy parts) |
| `app/src/main/java/me/rerere/rikkahub/di/AppModule.kt` | `app/src/main/java/me/rerere/rikkahub/di/AppModule.kt` | адаптировано (DI исходных Termux/SSH/MCP/automation компонентов и безопасного headless adapter) |
| `app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt` | `app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt` | адаптировано (существующий Root: удалён unconditional HTTP HEADERS logger; MCP manager получает защищённый vault) |
| `app/src/main/java/me/rerere/rikkahub/di/RepositoryModule.kt` | `app/src/main/java/me/rerere/rikkahub/di/RepositoryModule.kt` | адаптировано (DI SSH repository/vault и SSH/MCP history sanitizers) |
| `app/src/main/java/me/rerere/rikkahub/di/ViewModelModule.kt` | `app/src/main/java/me/rerere/rikkahub/di/ViewModelModule.kt` | адаптировано (DI исходного Termux ViewModel) |
| `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt` | `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt` | адаптировано (скопирована ветка карточки MCP confirmation Agent с исходным renderer и масками до стандартных кнопок Root) |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantLocalToolPage.kt` | `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantLocalToolPage.kt` | адаптировано (подключение F switches к существующим Root настройкам ассистента) |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantLocalToolPage.kt` | `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/ShellLocalToolSettings.kt` | адаптировано (адаптация исходных переключателей Agent, RUN_COMMAND запрос только при включении/отказ оставляет OFF, Termux setup/status и ссылки настроек, configuration-aware LocalResources) |
| — (существующая интеграция/тест Root) | `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ConversationExport.kt` | адаптировано (существующий Root export: удаление секретов/ссылок из Markdown и image-export до передачи) |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPage.kt` | `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPage.kt` | адаптировано (ссылки настроек Termux и безопасного ввода SSH, русские подписи) |
| `app/src/main/res/values/strings.xml` | `app/src/main/res/values/strings_shell_tools.xml` | адаптировано (русские строки переключателей F и сообщений отказа в разрешении; translatable=false по правилам русских строк форка) |
| — (новая необходимая адаптация/регрессия Root) | `app/src/main/res/values/strings_ssh.xml` | адаптировано (русский интерфейс защищённой формы SSH; translatable=false по правилам строк форка) |
| — (существующая интеграция/тест Root) | `app/src/test/java/me/rerere/rikkahub/data/ai/GenerationLoopRootApprovalTest.kt` | адаптировано (существующие регрессии Root дополнены подтверждением shell и запретами headless F) |
| — (существующая интеграция/тест Root) | `app/src/test/java/me/rerere/rikkahub/data/ai/tools/ToolPermissionPolicyTest.kt` | адаптировано (существующие регрессии Root расширены registry F, SSH/Termux mandatory confirmation и SSH host-key reset) |
| — (существующая интеграция/тест Root) | `app/src/test/java/me/rerere/rikkahub/data/ai/tools/local/LocalToolOptionCompatibilityTest.kt` | адаптировано (существующие регрессии сериализации Root расширены точными @SerialName Agent для F) |
| — (существующая интеграция/тест Root) | `app/src/test/java/me/rerere/rikkahub/data/ai/tools/local/LocalToolPermissionsTest.kt` | адаптировано (существующие проверки Root расширены feature-specific RUN_COMMAND/ACCESS_LOCAL_NETWORK) |
| — (существующая интеграция/тест Root) | `app/src/test/java/me/rerere/rikkahub/data/ai/tools/local/LocalToolsRegistrationTest.kt` | адаптировано (существующие проверки Root расширены регистрацией всех F tools и отсутствием их при выключенных опциях) |

## Вынужденные отличия по безопасности

Эти отличия перечислены явно, поскольку они нужны по правилам пользователя, а не являются молчаливой переработкой Agent.

| Файл Agent / исходная проблема | Необходимая адаптация Root |
| --- | --- |
| `app/src/main/java/me/rerere/rikkahub/data/db/entity/SshHostEntity.kt`: password/privateKey/passphrase сохраняются в Room открытым текстом | Metadata-only entity, AndroidKeyStore/noBackup vault и ephemeral поля. |
| `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/SshTool.kt`: `StrictHostKeyChecking=accept-new` не реализован в используемой JSch 0.2.21; forced IPv4 alias смешивает разные SSH порты | `StrictHostKeyChecking=yes` с необходимым AcceptNewHostKeyRepository; постоянный первый ключ и отказ при изменении, отдельная идентичность `[host]:port`. |
| `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/SshSftpTool.kt`: `File(localPath)` допускает приватные файлы приложения | Существующий Root LocalFileAccess ограничивает файлы текущим workspace/разрешёнными источниками. |
| `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/control/McpControlTools.kt`, `McpHeaderRedactor.kt`: заголовки auth сохраняются открытым текстом | Header/OAuth refs в settings и истории, шифротекст вне бэкапов, разрешение только на сетевой запрос. |
| `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/control/McpUrlGuard.kt`: interactive loopback разрешён, фактический DNS не фильтруется | Public-only защита управляемых из чата серверов на каждой фактической цели и DNS; Ktor/OkHttp redirects отключены. Старые ручные local MCP не переопределяются молча. |
| `app/src/main/java/me/rerere/rikkahub/automation/ExternalAutomationReceiver.kt`: синтетический `<adb>` не подтверждает личность отправителя | Нужны предоставленные Android package + UID; непроверенный вызов отклоняется. |
| `app/src/main/java/me/rerere/rikkahub/automation/ExternalAutomationDispatcher.kt`: внешний `return_package` может использовать приложение для callback другому пакету; исходный headless автоодобряет инструменты | Callback только подтверждённому caller; используется существующий guarded HeadlessTaskRunner Root, live-проверки разрешений и HARDLINE. |

## Сохранённые ограничения Agent

- `RUN_CHAT` в `ExternalAutomationActivity.kt` зарезервирован, но не реализован; возвращает исходный отказ. Одноразовый диалог доверия неизвестному вызывающему приложению также не реализован: неизвестные приложения отклоняются, пакет сначала нужно явно добавить в доверенные.
- Обычный Tasker «Send Intent» зачастую не передаёт подтверждённую Android личность, даже если его пакет добавлен в доверенные. Такой вызов отклоняется. Activity caller должен использовать `startActivityForResult`, либо Android 34+ sharing identity; receiver требует Android 34+ проверенные `sentFromPackage`/UID. Подмена caller в extras/referrer не принимается. Совместимость конкретного Tasker сценария не заявлена без проверки на телефоне.
- Headless не может менять доверие вызывающим приложениям или `mcp_set_tool_approval`, показывать интерактивные инструменты либо сбрасывать SSH host key. MCP add/delete меняет только allowlist текущего side-context, не чат создателя. Сохраняются существующие лимиты Root 8 минут/12 шагов для headless.
- HARDLINE остаётся существующим запретным списком, а не полноценным интерпретатором произвольных shell-скриптов. Оpaque `eval`/скрипты не заявлены как изолированная песочница.
- Таймаут чтения logcat помещает понятную диагностику в ZIP; частично прочитанный logcat при таймауте не сохраняется. Полные ошибки ChatService по запросу сохранены: редактор секретов не гарантирует удаления произвольного личного текста из сообщений исключений.
- Внешний `su` в Termux может ожидать собственное подтверждение Magisk для пакета Termux; фоновые запуски такое подтверждение не выдают.
- Без общего мигратора неизменённые старые вручную созданные MCP configs могут сохранять прежние открытые секреты upstream. Новые/отредактированные/импортированные configs и OAuth используют vault; общий перенос legacy данных вынесен в предложения.

## Локальная проверка и Daily Build

Локально прошли `:app:compileDebugKotlin`, `:ai:compileDebugKotlin` и выбранные unit-тесты затронутых классов: app — 500, ai — 14; ошибок, пропусков и падений нет. Отдельно проверен цикл red/green регрессий отчёта и утечки журналов. Первая Daily Build прошла полный набор тестов, но выявила 104 новых замечания lint: 92 объявления русских строк и 12 обращений к ресурсам Compose; исправлены только эти адаптации без подавления существующих ошибок. При проверке опубликованного APK второй сборки обнаружено удаление R8 reflective SSH классов, не выявляемое JVM-тестами; добавлена обязательная release-совместимость JSch. Проверка DEX подтверждает наличие классов и конструкторов алгоритмов/аутентификации непосредственно в опубликованном APK. Полная сборка APK и lint выполняются только в Daily Build. Все подсистемы отправлены одним первоначальным push; последующие push содержат только исправления lint и release-совместимости. Точные коммиты, ссылка завершённого Actions run и APK этой ревизии приводятся в итоговом сообщении.

## Проверки на телефоне

1. После обновления проверить, что четыре F функции изначально выключены. Включить нужные по отдельности: Termux запрашивает RUN_COMMAND именно при включении; отказ оставляет понятное сообщение и выключатель OFF.
2. Отправить обычное сообщение с уникальным контрольным текстом, сгенерировать отчёт и открыть ZIP из `workspace_path`: имя `rikka-root-report-…zip`, logcat содержит API метаданные, но контрольного текста, prompt/workspace инструкций и тела ответа нет. Кнопка отправки отчёта работает. Не вводить настоящие секреты для этого теста.
3. Termux: включить `allow-external-apps=true`, проверить интеграцию; выполнить `echo`, затем session start/send/read/list/kill. Проверить одобрение, отказ и запомненные разрешения на безопасной команде; запрещённая команда отклоняется также из cron/субагента. Не запускать реальные destructive команды для проверки.
4. SSH: в защищённых настройках создать тестовый хост, выполнить `list_ssh_hosts`, `ssh_exec_saved` → `whoami`; перезапустить приложение и повторить. Изменённый ключ сервера должен отклоняться; `ssh_forget_host_key` требует отдельного подтверждения. Проверить разные порты одного адреса.
5. SFTP: upload/download небольшого тестового файла workspace, сверить размер/содержимое; приватное хранилище приложения недоступно. Восстановление бэкапа на другом устройстве объясняет необходимость заново ввести credentials.
6. MCP: добавить публичный тестовый сервер, проверить list/get/test/list_tools и вызов указанного `dispatchable_name`; все изменения спрашивают одобрение. Добавление в текущем чате не меняет другой чат. Проверить отказ для localhost/приватного адреса/DNS alias/redirect. Старый локальный MCP, настроенный вручную, продолжает работать.
7. MCP auth: ввести заголовок только в защищённом поле настроек, проверить переподключение после перезапуска и OAuth refresh, если сервер поддерживает OAuth. После переноса бэкапа без vault должна быть понятная просьба повторить ввод.
8. External automation: выключенная функция, недоверенный и поддельный caller отклоняются. Проверенным Android caller из доверенного пакета запустить простой RUN_TASK, проверить callback и результат в отдельном чате; отключение функции/отзыв доверия останавливает последующие действия. RUN_CHAT и обычный Send Intent без identity должны вернуть понятный отказ, как описано выше.

## Предложения по улучшению

Ниже только предложения: изменения из этого раздела не применялись. Оценка числа файлов включает целевые регрессионные тесты; окончательный объём зависит от выбранного решения.

| Файл Agent / проблема | Что предлагается и зачем | Оценка файлов | Риски |
| --- | --- | --- | --- |
| `ai/src/main/java/me/rerere/ai/util/KeyRoulette.kt`: LRU cache использует исходные provider keys как JSON keys в `cache/lru_key_roulette.json` | Заменить persisted идентификаторы ключей криптографическими отпечатками, сохранить ротацию и очистить прежний cache; исключить старые открытые ключи на диске | 2 | Необходима осторожная миграция; прежнее состояние порядка ключей может сброситься. |
| Старые Root/upstream MCP configs в `data/ai/mcp/McpConfig.kt`/settings, которые пользователь не редактировал | Однократно перенести все legacy header/OAuth секреты в AndroidKeyStore vault до записи очередного бэкапа | 2–3 | Ошибка миграции нарушит действующие MCP подключения; нужен безопасный rollback без возврата открытых секретов. |
| `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/SshTool.kt` (`probeReachability`), `SshSftpTool.kt`: успешная default network может быть принята за недоступную при ошибке alternate network | Явно различать «выбрана default network» и «нет рабочего маршрута» | 3 | Меняется выбор маршрута/VPN, нужно проверять телефон с несколькими сетями. |
| `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/SshSftpTool.kt` (`sshDownloadTool`): download сначала перезаписывает существующий файл, затем удаляет его при ошибке | Скачать во временный файл и заменить целевой только после успеха, чтобы сохранить старые данные | 2 | Требуется дополнительное свободное место, поведение замены/SAF нужно проверить. |
| `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/SshTool.kt`: sessionRef задаётся после connect | Сделать handshake отменяемым с гарантированным закрытием ещё до завершения connect | 2–3 | Меняется cleanup JSch; возможны гонки connect/cancel. |
| `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/TermuxTool.kt`: таймаут результата не останавливает уже запущенную Termux команду | Согласовать безопасное прекращение команды при timeout/cancel; сообщать, когда процесс ещё выполняется | 3 | Прерывание может повредить незавершённую операцию; не каждая фоновая команда контролируется сервисом. |
| `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/TermuxTool.kt`/`TermuxSessionTool.kt`: часть исходных диагностики/ошибок Termux может содержать пользовательский вывод | Применить единый redactor перед журналом ошибок, сохранив полезную диагностику | 2 | Избыточное редактирование ухудшает диагностику; это отдельная проверка вне журналов провайдеров ai. |
| `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/TermuxSessionTool.kt`: исходные kill/list не всегда отличают ошибку backend от нормального результата | Возвращать точный статус tmux команды вместо успешного вида при сбое | 2 | Меняется ожидаемый ответ инструментов, модели/старые сценарии могут трактовать его иначе. |
| `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/control/McpControlTools.kt`: parseHeaders пропускает malformed entries, проверка дубликата имени не атомарна | Строгая валидация аргументов и атомарное предотвращение дубликатов | 2 | Ранее частично принимаемые запросы будут отклоняться. |
| `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/control/McpControlTools.kt` (`mcp_update`): buildConfig теряет прежнее OAuth state | Сохранять защищённый OAuth при совместимом обновлении URL/транспорта | 2–3 | Нельзя переносить токены на чужую цель; нужно явно определить границы совместимости. |
| `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/control/McpControlTools.kt`/`McpControlValidation.kt`: auth headers разрешены на публичном HTTP как в Agent | Требовать HTTPS для секретных auth headers, чтобы токены не уходили открытым текстом по сети | 3 | Станут недоступны HTTP-only удалённые серверы; нужна отдельная договорённость. |
| Новые Root `data/ssh/SshCredentialStore.kt`, `data/ai/mcp/control/McpControlSecretStore.kt`: удаление хоста/сервера/истории оставляет осиротевшие зашифрованные references | Добавить явную безопасную очистку неиспользуемых vault entries | 3–4 | Нельзя удалить ссылку, которая ещё нужна сохранённому/pending вызову, восстановленной истории или другому config. |
| `app/src/main/java/me/rerere/rikkahub/automation/ExternalAutomationActivity.kt`: RUN_CHAT и одноразовый trust dialog оставлены недоделанными Agent | Реализовать исходно отложенные сценарии после отдельного решения пользователя | 3–5 | Требуется новый пользовательский flow и проверка identity/lifecycle/background restrictions. |
| `app/src/main/java/me/rerere/rikkahub/automation/ExternalAutomationActivity.kt`/`ExternalAutomationReceiver.kt`: обычный Tasker Send Intent не предоставляет проверенную identity | Отдельный Tasker/Locale plugin или проверенный Activity handoff без доверия к extras | 4–6 | Зависит от API/версии Tasker; требуется проверка package identity и совместимости нескольких Android версий. |
| `app/src/main/java/me/rerere/rikkahub/automation/ExternalAutomationDispatcher.kt`: dedup read/put не атомарен, подготовка conversation до try может прервать terminal callback | Атомарно резервировать request_id и охватить preparation ошибку контролируемым callback | 2 | Меняются гонки повторных запусков и статус callbacks; нужно сохранить существующий контракт Agent. |
