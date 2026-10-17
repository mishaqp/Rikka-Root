# Исправления аудита файловых и телефонных инструментов

**Goal:** исправить пункты 1–12 запроса от 2026-10-17 с регрессионными тестами.
**Architecture:** существующие LocalFileAccess, file tools, activity sessions и root screen API. Сохранить защиту SAF, отмену родителя, rollback/ownership и русский интерфейс.
**Tech Stack:** Kotlin, Android, coroutines, JUnit/Robolectric, Gradle, Daily Build.
**Spec:** полный запрос пользователя от 2026-10-17; начальный синхронизированный commit `8c55da43127ba7c89ab802e1c88ee4754e45c733`.

## Общие ограничения

- Ветка `ccr-e93188a6-grrqko`; pull --rebase выполнен до начала, повторить перед push и после любого non-fast-forward.
- Другой разработчик одновременно публикует в эту ветку. Его правки сохранять, force push запрещён.
- Не менять BrowserTools.kt, BrowserController.kt, ReadabilityRunner.kt, DownloadTool.kt, SearchTools.kt, WebContentGuard.kt, DuckDuckGoSearchService.kt, BingSearchService.kt, SkillUrlImporter.kt, HardlineCommandGuard.kt, RootCommandGuard.kt, TermuxCommandGuard.kt, GenerationLoop.kt, ClipboardTool.kt, ScreenshotTool.kt.
- В ToolPermissionPolicy.kt разрешены только добавленные строки внутри mandatoryConfirmation.
- Русские сообщения. Одна итоговая APK-сборка, только Daily Build; полные tests/lint перед assemble. Новых lint ошибок нет, тесты не отключать и не ослаблять.
- Общий checkout, исключительное владение production-файлами по задачам. Git commit/rebase/push и все Gradle-команды выполняет только root.
- Сначала только тесты на существующем API, общий RED; production менять после подтверждения RED. Ошибка компиляции/загрузки не считается RED.

## Проверки, важные для ревью

- Усечение чтения не превращается в неполное копирование/удаление, потерю ZIP entries или ложный success.
- Снятый SAF/direct-storage grant остаётся действенным; оптимизация IPC не выдаёт доступ за пределами разрешения.
- Ошибки/тайм-ауты архива сохраняют сведения о partial destination и recovery scratch; отмена родителя пробрасывается.
- Same-volume move не заменяет существующий target без согласия, не удаляет source после неудачи и не копирует большой файл через scratch.
- Устаревший node_id не выполняет действие; некорректные параметры не будят экран; мьютекс покрывает wake и действие.

### Task 1: LocalFileAccess — пункты 3,4

Files: LocalFileAccess.kt, при необходимости SharedStorageAccess.kt; отдельные FileAccessAuditRegressionTest/SharedStorage tests.

- [x] Исследовать list/walk/findChild, direct-storage resolver и movePath/move.
- [x] RED: каталог >5000 элементов возвращает ограниченный результат; локальный findChild находит поздний элемент без листинга; права/ограниченные папки сохранены.
- [x] RED: sparse файл >512 МиБ Local→Local на одном томе перемещается без копирования через scratch; existing target/source защищены.
- [x] GREEN: остановка обхода с флагом усечения; прямой локальный lookup; убрать volume scan/checkGrant на каждом элементе без кеширования отзыва прав.
- [x] GREEN: Files.move без REPLACE_EXISTING для подходящего Local→Local; fallback сохраняет исходную transaction safety.
- [x] Контракт с Task 3: обратно совместимые list/walk, добавить именованные limit и onTruncated; destructive/copy/archive потребители обязаны явно проверять усечение до эффектов. Уточнение интерфейса сообщить root до реализации.

### Task 2: Архивы — пункты 1,2 и архивная часть 6

Files: ArchiveTools.kt; отдельный ArchiveAuditRegressionTest и существующие архивные фикстуры.

- [x] RED: zip_files по умолчанию отказывает существующему ZIP, overwrite=true разрешает намеренную замену.
- [x] RED: собственный timeout при записи ZIP/распаковке в shared storage возвращает то же recovery предупреждение с partial/backup scratch путями, что обычная ошибка; parent cancellation остаётся cancellation.
- [x] RED: list_zip_contents принимает limit и сообщает truncated, сохраняя ZIP safety budgets.
- [x] RED: SAF provider изменил фактическое имя документа при extraction — отказ и безопасная cleanup только своего документа.
- [x] GREEN и проверка всех существующих rollback/race/archive bomb tests. Согласовать list/walk усечение Task 1; не терять исходные файлы молча.

### Task 3: FileManager и изображения — пункты 5 и файловая часть 6

Files: FileManagerTools.kt, LocalImageSources.kt; отдельные FileManagerAuditRegressionTest/LocalImage tests.

- [x] RED: create_directory absolute path внутри выданной SAF папки работает без all-files; родитель+имя планируются как planWritePath.
- [x] RED: write_binary_file принимает MIME base64 с переносами и data:...;base64,; лимит относится к декодированным данным.
- [x] RED: show_image удаляет собственные .shown-* после обработки, включая ошибку.
- [x] RED: read_file каталога даёт «Это папка, используйте list_files»; max_bytes на UTF-8 не создаёт разорванный символ.
- [x] RED: set_wallpaper использует LocalFileAccess.resolve для предоставленных путей.
- [x] RED: list/find через Task 1 возвращают truncated=true при ограниченном обходе >5000 файлов.
- [x] GREEN; использовать контракт list/walk Task 1, не менять LocalFileAccess.kt самостоятельно.

### Task 4: Экран — пункты 8,9,11 и wake из 12

Files: FindNodeTool.kt, WindowTreeTool.kt, ScrollTool.kt, RootScreenService.kt, TapTool.kt, SwipeTool.kt, GlobalActionTool.kt, WakeScreenTool.kt при необходимости; отдельные ScreenAuditRegressionTest.

- [x] RED: node_id содержит fingerprint class/text/bounds и отказывает при изменении дерева; ошибка «Экран изменился, прочитайте дерево заново».
- [x] RED: fallback scroll по x/y использует найденную область, а не центр root.
- [x] RED: rotation берётся у системы Display.rotation, а SurfaceOrientation из dumpsys игнорируется.
- [x] RED: invalid args не вызывают wake; wake вызывается внутри того же мьютекса, что действие.
- [x] GREEN; сохранить новые upstream правки RootScreenService из начального pull.

### Task 5: Activity lifecycle — пункты 7 и SAF picker из 6

Files: PersonalToolActivity.kt, NfcToolActivity.kt, SafToolActivity.kt; session/capture helpers только при необходимости; отдельные ActivityLifecycleAuditTest.

- [x] RED: audio capture, speech recognition, NFC держат FLAG_KEEP_SCREEN_ON на время запроса и снимают при завершении/отмене.
- [x] RED: тайм-аут SAF закрывает и наше Activity, и запущенный системный выбор папки; не терять cancellation/revocation cleanup.
- [x] GREEN; запрет фонового lifecycle-запроса/разрешения сохраняется.

### Task 6: Телефонные сведения — пункты 10 и оставшаяся часть 12

Files: ScreenTimeTool.kt, CalendarTool.kt, BatteryTool.kt, AudioInfoTool.kt, WifiInfoTool.kt, SensorTool.kt, SensorSampling.kt при необходимости, TextToSpeechTool.kt, utils/ContextUtil.kt, LocalToolPermissions.kt, ui/components/ai/tools/LocalToolAndroidAccess.kt; отдельные PhoneAuditRegressionTest.

- [x] RED: screen time end=min(end,now), русские ошибки; settings не открываются автоматически.
- [x] RED: clipboard image/coerceToText не создаёт literal null/NPE (ClipboardTool.kt не менять).
- [x] RED: calendar begin-only задаёт end=begin+1день; описание соответствует поведению; LIKE literal %, _ и escape безопасны.
- [x] RED: Battery/Audio ошибки русские; coarse-only Wi-Fi возвращает RSSI/IP без SSID; activity recognition нужен только step sensors.
- [x] RED: TTS отказывает слишком длинному тексту до побочного эффекта с понятной русской ошибкой; предел обоснован Android API.
- [x] GREEN, проверка обратной совместимости инструментов.

### Task 7: Подтверждения и интеграция — пункт 6

Owner: root.
Files: только дополнительные строки в ToolPermissionPolicy.mandatoryConfirmation; отдельный FileMandatoryConfirmationAuditTest.

- [x] RED/GREEN: recursive batch_delete и shared-storage move требуют отдельного подтверждения и не могут получить Chat/Always/YOLO обход.
- [x] Проверить весь собственный diff против forbidden file allowlist.
- [x] Независимое scoped review каждой области и итоговое review.
- [ ] Targeted GREEN. Commit, git pull --rebase origin ccr-e93188a6-grrqko; при конфликте сохранить чужую работу; перепроверить затронутую интеграцию.
- [ ] Push без force, дождаться обязательных full tests/lint и единственного подписанного APK. Сравнить lint XML по самим issues с исходным отчётом, а не только с gate 51.
- [ ] Финальный русский отчёт по пунктам, commit/run/APK и простые проверки на телефоне.

## Регрессии, уточнённые ревью

- Файловый read-only tool query использует единый ограниченный snapshot томов и SAF metadata; права проверяются заново до возврата, snapshot не сохраняется между вызовами. Тесты вызывают сам list_files, а не только низкоуровневый list.
- Rename папки проверяет исходные и будущие пути потомков: перенос не создаёт запрещённые Android/data или Android/obb.
- Fingerprint учитывает родителя, на котором фактически выполняется click/set_text, когда дочерняя подпись сама не является actionable.
- Wi-Fi status UI считает точную геолокацию необязательной; ошибка шагомера указывает действующий ручной путь выдачи Android permission.

## Проверки регрессий

Первый RED выполнен на `8c55da43`: 234 теста, 60 исходных падений, 164 прежних теста прошли. Ошибки фикстур отделены от доказательств: Android coerceToText не обязан возвращать URI картинки; календарь принимает документированный offset-format. После исправления этих фикстур CalendarTool и ContextUtil повторно проверены с исходным production-кодом: получены ожидаемые бизнес-ошибки, текущие файлы восстановлены автоматически. Проверки не отключались.

| Пункт | Регрессионные тесты |
|---|---|
| 1 | ArchiveAuditRegressionTest: отказ существующему ZIP, явный overwrite |
| 2 | ArchiveAuditRegressionTest: настоящий собственный TimeoutCoroutine ZIP/распаковки, пути recovery, отмена родителя |
| 3 | FileAccessAuditRegressionTest и FileManagerAuditRegressionTest: >5000, truncated, прямой child lookup, число IPC полного list_files, отзыв grant и истечение metadata |
| 4 | FileAccessAuditRegressionTest и FileManagerAuditRegressionTest: sparse >512 МиБ, inode файла/папки/batch, существующий target, запрещённые source/target descendants, fallback cleanup |
| 5 | FileManagerAuditRegressionTest: абсолютный SAF mkdir, root parent+name |
| 6 | FileManagerAuditRegressionTest, ArchiveAuditRegressionTest, ActivityLifecycleAuditTest, FileMandatoryConfirmationAuditTest: base64, UTF-8, read папки, show cleanup, wallpaper, ZIP limit/SAF имена, закрытие picker, обязательное подтверждение |
| 7 | ActivityLifecycleAuditTest: KEEP_SCREEN_ON record/speech/NFC и снятие при завершении/отмене |
| 8 | ScreenAuditRegressionTest: старый node_id и изменённый actionable ancestor, валидные handles |
| 9 | ScreenAuditRegressionTest: fallback swipe в выбранной области |
| 10 | PhoneAuditRegressionTest: end не позже now, русские ошибки, без автоперехода в настройки |
| 11 | ScreenAuditRegressionTest: Display.rotation вместо dumpsys |
| 12 | PhoneAuditRegressionTest, PhoneAuditServicesRegressionTest, ScreenAuditRegressionTest, LocalToolAndroidAccessTest: clipboard/calendar/LIKE/ошибки/Wi-Fi/датчики/wake/TTS |

Промежуточные GREEN: 364 и 366 выбранных тестов, 0 failures/errors/skips. Исходный CI lint: 46 Error/Fatal; итог проверяется по идентичности замечаний с учётом смещения строк. Полный test и lint выполняются Daily Build перед единственным итоговым APK.

Итог перед публикацией: 367 выбранных тестов прошли, 0 failures/errors/skips. Итоговое независимое ревью одобрено. Исправленный batch fast path сначала вызывает tryLocalMove, а при fallback сохраняет исходный owned-destination cleanup; отдельный RED/GREEN проверяет настоящее копирование между разными FileStore. Собственный пакет — 41 файл; запрещённых изменений нет, ToolPermissionPolicy содержит только 12 добавленных строк внутри mandatoryConfirmation.
