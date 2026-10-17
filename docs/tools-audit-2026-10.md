# Аудит инструментов Rikka-Root (коммит 9b74e541, только чтение)

Пути от app/src/main/java/me/rerere/rikkahub/ (если не указано иное).

## Критично
1. service/HeadlessTaskRunner.kt:204 — Prompt-запуск в фоне (cron/субагент/workflow) с любым вызовом инструмента завершается FAILED "step_limit": все шаги пишутся в одно ASSISTANT-сообщение, последнее всегда содержит Tool-части. Считать лимит только по неисполненным инструментам; steps считать отдельным счётчиком. (подтверждено)
2. ai/.../claude/ClaudeProvider.kt:516 — tool.parameters()==null кодируется как "input_schema": null (workspace_background_list без parameters, WorkspaceTools.kt:89). Anthropic требует объект → 400 при готовом workspace. То же InteractionsAPI.kt:257, ProviderConnectionTester.kt:176. Подставлять пустую object-схему. (подтверждено)
3. data/ai/mcp/control/McpControlTools.kt:598 — mcp_update сохраняет OAuth при смене URL → Bearer-токен уходит на новый хост; карточка не показывает старый URL. Сбрасывать oauth/секретные заголовки при смене хоста, показывать «URL: было → стало», добавить mcp_add/mcp_update в trustMutations и mandatoryConfirmation.
4. send_sms можно одобрить «Всегда»/YOLO/в фоне (ToolPermissionPolicy.kt:190, RunExecutionContext.kt:55); входящие SMS/контакты не включают web-taint → prompt-injection из SMS отправляет платные SMS. Добавить send_sms в mandatoryConfirmation и HeadlessToolPolicy.interactive.

## Безопасность (важно)
5. RootTool.kt:64 — RootCommandGuard не проверяется для root_exec в чате при автоодобрении (invocation всегда не null). Добавить `|| invocation?.automatic == true`.
6. GenerationLoop.kt:257,359 — HardlineCommandGuard.checkTool вызывается только для browser_eval_js; ветки MCP/subagent мёртвые. Вызывать для всех инструментов.
7. root/RootCommandGuard.kt:5,10, TermuxCommandGuard.kt:10 — обходы: `sh -c reboot` без кавычек, `bash -lc`, busybox/env/nohup/timeout/su 0, ( ), { }, then/do, `svc power reboot`, `setprop sys.powerctl`; termux executable+["-lc",..]. Взять префикс из HardlineCommandGuard.CMD_POS, флаг `-[a-z]*c`.
8. WorkspaceTools.kt:473-494 — workspace_write_file: путь `/workspace/../etc/profile` проходит префикс-проверку → запись вне workspace без подтверждения. Отклонять `..`/`.` или normalize() до проверки.
9. MCP: McpControlTools.kt:84,94-99 — URL с токеном в path/query отдаётся модели без редактора; ссылки rikka-keystore:mcp:<uuid> в заголовках с нейтральными именами видны модели и могут быть подставлены в mcp_add на чужой хост. Редактировать URL, всегда маскировать ссылки, привязывать ссылку к serverId+хосту.
10. memory_tool update/delete по id без проверки владельца (ChatToolFactory.kt:101, MemoryRepository.kt:40-78); success:true на несуществующий id.
11. conversation_search ищет по чатам всех ассистентов (ConversationTools.kt:95) — передавать assistantId.
12. workflow/tools/WorkflowApprovalRenderer.kt:181-192 — аргументы действий в карточке обрезаны до 40/80 символов (скрывает `| sh`). Показывать полностью.
13. SSRF: browser_open и scrape_web (DuckDuckGoSearchService.kt:106) без подтверждения открывают 127.0.0.1/192.168.x. Для loopback/private/link-local (после DNS) требовать подтверждение.
14. WebContentGuard.kt:9 — download_file, mcp__* (fetch), curl в shell не включают web-taint.
15. Clipboard read без подтверждения и без SensitiveContentDetector (ClipboardTool.kt:51); calendar_query и get_screen_time отсутствуют в registry.
16. ScreenshotTool.kt:89 — каждый скриншот безусловно сохраняется в публичную галерею. По умолчанию только приватный кэш.
17. manage_assistants: нет «было → стало» и stale-проверки (ManageAssistantsTool.kt:45-65; массивы localTools/mcpServers/... заменяются целиком); UUID вместо имени в карточке. Сохранять _expected_before, показывать дифф, сверять при выполнении.
18. manage_assistants delete (ManageAssistantsTool.kt:163) — чаты удалённого ассистента остаются в БД, но не видны в UI; память/cron/workflow бесхозные. Запрещать delete при наличии чатов или переназначать.
19. Детектор секретов manage_assistants не ловит ключи в значениях с нейтральными именами (sk-, AIza, ghp_).
20. SkillUrlImporter.kt:165,348,299 — body.string() целиком до лимита (OOM), блокирующий execute() без callTimeout, редирект на 127.0.0.1 не перепроверяется.
21. ChatService.kt:922, ConversationRoutes.kt:382 — answer принимается для любого Pending-инструмента, не только ask_user.

## Надёжность (важно)
22. GenerationLoop.kt:450, DeviceToolSupport.kt:62, ToolMediaPlaybackService.kt:149 — TimeoutCancellationException собственного withTimeout инструмента воспринимается как Stop и молча обрывает ответ. Пробрасывать только при !isActive.
23. GenerationLoop.kt:786 — без workspace вывод инструментов не обрезается (MCP blob → мегабайты в истории).
24. McpManager.kt:236 — имена MCP-инструментов не очищаются (точки, слэши, >64 символов) → OpenAI 400.
25. GoogleProvider.kt:448, Request.kt:115 — removeElements удаляет свойства с именами format/enum/const внутри properties, required не чистится → Gemini 400; chart_display axis.format пропадает.
26. ChatCompletionsStreamDecoder.kt:56-60,68; ChatCompletionsAPI.kt:749-753 — запасной tool id одинаков между шагами (вызов теряется/аргументы портятся); пустой id затирает сохранённый; arguments-объект роняет ответ.
27. GenerationLoop.kt:148 — выход по лимиту времени/шагов без видимого сообщения.
28. GenerationLoop.kt:139 — битый JSON аргументов всё равно показывает карточку одобрения.
29. Termux (TermuxTool.kt:378,653) — по таймауту/Стоп команда продолжает работать, а модели советуют повторить. Добавить may_still_be_running и «не повторяйте».
30. workspace/.../WorkspaceShellRunner.kt:64,78 — destroyForcibly() (SIGKILL) proot; дочерние процессы, вероятно, остаются. Сначала SIGQUIT.
31. SshSftpTool.kt:173,187 — ssh_download при ошибке удаляет существующий локальный файл. Качать в .part + ATOMIC_MOVE.
32. root/RootProcessRunner.kt:45 — фоновые `cmd &` держат pipe, потоки чтения копятся.
33. CronJobTool.kt:53-95 — schedule_job не проверяет HeadlessToolPolicy.blockReason при создании.

## Браузер
34. BrowserTools.kt:241,311,313 — browser_open возвращает success:true при незагрузившейся странице; get_text читает старую страницу. Возвращать page_load_timeout, добавить url: document.URL в get_text/get_dom/get_links.
35. BrowserTools.kt:195 — URL без схемы (github.com/x) уходит в loadUrl как есть; host:8080 → scheme_not_allowed. Нормализовать через normalizeBrowserQuery.
36. BrowserTools.kt:1405 — таймаут Readability не останавливает скрипт, каскад таймаутов на тяжёлых страницах (GitHub). Не повторять, не запускать Readability на больших DOM.
37. BrowserTools.kt:1429,1286 — XML (releases.atom, RSS) без body → selector_not_found/js_failed. Фолбэк document.documentElement.
38. js_parse_failed (BrowserTools.kt:1328,1337) — тип исключения не логируется, модели уходит raw до 64 КБ.
39. DownloadTool.kt:151,174,185 — нет overwrite; рекомендованное фиксированное имя /scratch/module-update.json ломает повторную проверку обновления; имя `latest` без расширения. Также Download/Rikka-Root vs Download/Rikka, переименование MediaStore не отражается, имя >127 символов — ошибка.
40. browser_press_key Enter не отправляет форму (BrowserTools.kt:935) — form.requestSubmit().
41. browser_wait_for таймаут = toolTimeout (BrowserTools.kt:592); browser_select с несуществующим value → success (888); get_links обрезает до 100 без truncated (464).
42. DuckDuckGoSearchService.kt:169 — charset из <meta> игнорируется (cp1251 → кракозябры). search_web ссылается на scrape_web, которого нет у Bing и др.; ошибки Bing на английском.

## Файлы
43. ArchiveTools.kt:355-377 — zip_files молча перезаписывает существующий ZIP; нужен overwrite=false по умолчанию.
44. ArchiveTools.kt:177,385,510 — таймаут архива скрывает частичные файлы в общей памяти и резервные копии.
45. LocalFileAccess.kt:430,452,547 — лимит 5000 записей бросает ошибку вместо усечения: find_files по /sdcard, list_files DCIM/Camera, download/unzip в большую папку падают. findChild — прямой File(dir,name).
46. LocalFileAccess.kt:254,680 — move_file = копирование+удаление с промежуточной копией во внутренней памяти (лимит 512 МиБ, ENOSPC). Сначала Files.move.
47. FileManagerTools.kt:222 — create_directory с абсолютным путём в SAF-папке без «всей памяти» не работает.
48. Мелочи: set_wallpaper путь без LocalFileAccess.resolve (LocalImageSources.kt:196); строгий Base64 (FileManagerTools.kt:202); show_image копии не удаляются (292); list_zip_contents без limit; read_file папки → «не найдены», обрезка рвёт UTF-8; unzip в SAF не сверяет имя; SafToolActivity тайм-аут оставляет пикер открытым; рекурсивный batch_delete можно одобрить «Всегда».

## Телефон и экран
49. PersonalToolActivity.kt:194 — запись звука/распознавание/NFC обрываются при гашении экрана (нет FLAG_KEEP_SCREEN_ON).
50. FindNodeTool.kt:56-74 — устаревший node_id нажимает другой элемент; добавить отпечаток узла.
51. ScrollTool.kt:120,124 — scroll по x/y свайпает по центру окна (target ?: root).
52. ScreenTimeTool.kt:105,241 — end в будущем завышает время; ошибки на английском; сам открывает настройки.
53. RootScreenService.kt:165 — поворот по строке SurfaceOrientation из dumpsys (проверить на Android 14+).
54. Мелочи: ContextUtil.kt:41 буфер с картинкой → "null"/NPE; CalendarTool: описание end не совпадает с кодом, один begin → INVALID_RANGE, LIKE не экранирован; Battery/Audio ошибки на английском; wifi требует FINE, датчики требуют ACTIVITY_RECOGNITION для всех; wakeIfNeeded до проверки параметров вне мьютекса; text_to_speech без лимита.

## Управление (мелочи)
55. AppSettingsEngine.kt:177,333 — скрытые побочные изменения не показаны в «было → стало».
56. mcp_update без headers удаляет все заголовки, enabled по умолчанию true включает выключенный сервер.
57. TextReplacers.kt:146 — BlockAnchorReplacer + replace_all заменяет все похожие блоки. Запретить fuzzy при replace_all.
58. Тумблер needsApproval=false у MCP-инструмента ничего не делает (ToolApprovalDefaults.kt:9).
59. SshToolSecretSanitizer.kt:76 — startsWith наоборот (toolName in TOOLS).
60. ChartDisplayTool: числа-строки проходят валидацию, ошибки на английском. use_skill path "." → общая ошибка.

## Что выглядит надёжно
Одобрения пересчитываются перед запуском; root/ssh/termux/управление не обходятся YOLO; checkpoint против повторного запуска после убийства приложения; реестры согласованы (164 имени); разбор путей и SAF-гранты, zip-slip и zip-бомбы закрыты; SSH TOFU корректен, секреты в KeyStore; send_sms (кроме одобрения) аккуратен; камера/микрофон/датчики освобождаются; manage_app_settings с проверкой stale и белым списком полей.
