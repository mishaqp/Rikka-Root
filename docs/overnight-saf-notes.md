# Ночная проверка SAF

## Область

Проверены правила `AGENTS.md`, раздел SAF в `docs/ui-customization-drawer-report.md`, изменения `c5fb5c70..HEAD` файловых инструментов и исходные `WriteFileTool.kt`, `FileManagerContentOps.kt`, `MediaScannerTool.kt` в `/workspace/refs/rikkahub-agent`. Задача отдельно разрешает довести создание отсутствующего документа по абсолютному пути внутри уже выданной SAF-папки. Разрешения, security policy, спецдоступы и манифест не меняются; APK локально не собирается.

## Найденная ошибка

**Важность:** P2, пользователь не может сохранить новый документ через `write_text_file`, `write_binary_file`, `copy_file` или `zip_files` по полному пути `Download`, хотя папка уже выбрана и имеет read/write grant.

**Воспроизведение:** выдать SAF-tree `primary:Download`; вызвать `write_text_file(path="/sdcard/Download/new.txt", content="Новый")` при отсутствии документа. Для Android Downloads DocumentsProvider аналогичен корневой tree `downloads`.

**Причина:** `LocalFileAccess.stagedWrite` безусловно открывает исходный документ для резервной копии. Отсутствующий native-path документ нельзя открыть. У Downloads с непрозрачными ID `resolveGrantedStoragePath` прекращает обработку ещё при поиске отсутствующего ребёнка. Прежний отчёт прямо указывал это ограничение.

**Исправление:** отдельный план назначения через существующую папку и provider lookup не создаёт документ во время адресации. Новый direct child создаётся только в операции с явным владением; при ошибке или отмене удаляется только свой созданный результат. Для существующих документов сохранены прежнее резервирование и rollback. Отсутствующие родительские папки не создаются автоматически. Полный путь повторно проходит прежний `grantedStoragePathPlan`, включая конечный симлинк и лимит глубины. Автопереименование провайдером отклоняется с удалением только нового документа.

## Проверки и результаты

- `saf-red.log`: до выполнения тестов не хватало Robolectric API 28/35; direct `repo1.maven.org` давал `UnknownHostException`. Кэш восстановлен через штатный proxy с проверкой SHA512. Это инфраструктурный сбой, не RED.
- `saf-red-actual.log`: локальный init временно переводил Gradle на mirror с HTTP 503; root исправил только локальную инфраструктуру. Это инфраструктурный сбой, не RED.
- `saf-red-network-fixed.log`: настоящий RED — 26 выполнений, 14 ошибок в семи новых сценариях на API 28/35; 12 прежних проверок проходят. Text/binary/copy/ZIP/Downloads не создают отсутствующий документ; сценарии сбоя и отмены не доходят до создания.
- `saf-red-rename.log`: дополнительный RED для владения документом при автопереименовании провайдером.
- `saf-green.log`: общий compile временно остановился на незавершённой соседней правке `BrowserTools.EXTRA_DIAGNOSTIC_LAUNCH_ID`; это не падение SAF-теста.
- `saf-green-2.log`: GREEN — **81/81**, восемь связанных классов, ошибок/пропусков нет. Компиляция `app` и JVM-тестов включена в задачу. JUnit XML сохранены в `/tmp/saf-green-results`, сводка — `/tmp/saf-green-summary.json`.

| Класс | Выполнений | Результат |
| --- | ---: | --- |
| `SafAbsolutePathRoutingTest` | 34 | GREEN на API 28 и 35 |
| `GrantedStoragePathsTest` | 5 | GREEN |
| `LocalFileAccessTest` | 14 | GREEN; включая восстановление существующего файла после сбоя/отмены |
| `LocalFileAccessScopesTest` | 2 | GREEN |
| `FileManagerToolsTest` | 3 | GREEN |
| `ArchiveToolsTest` | 10 | GREEN |
| `MediaScannerPathTest` | 8 | GREEN |
| `SafToolSessionTest` | 5 | GREEN |

Новые сценарии отдельно проверяют текст, binary, copy, ZIP, выдачу нового непрозрачного Downloads ID (`msf:…`), отказ без `overwrite`, read-only grant, отсутствие родителя, `..`, соседнюю папку, конечный симлинк, неподдерживаемый SAF `append`, автопереименование провайдером, ошибку commit, отказ cleanup и реальный `Job.cancel()` сразу в `createDocument` и при закрытии output. При отмене scratch остаётся пустым. При отказе провайдера удалить новый документ сохраняется прежний структурированный ответ `creation_cleanup_failed` с `created_path`; исходный документ не затрагивается.

Полный локальный `./gradlew test` не запускался: правила форка в `AGENTS.md` разрешают только компиляцию затронутого модуля и unit-тесты затронутых классов. Проверены связанные классы файлов, архивов, медиапутей и SAF-сессий.

Новые тесты используют настоящий `ContentResolver`, persisted grants и `DocumentsProvider` на API 28 и 35. Байты не существуют по raw Linux-пути; алиас `sdcard` указывает на известный primary volume, как системные алиасы на телефоне.

Локальные Gradle-проверки проходят только через `flock /tmp/rikka-overnight-gradle.lock` и `--no-configuration-cache`; логи `/tmp/saf-*.log`.

## Остатки

Удаление нового документа и восстановление старого зависят от ответа DocumentsProvider; при отказе возвращается ошибка, созданный документ не удаляется через сторонний или raw путь. Внезапное завершение процесса Android не покрывается корутинным rollback. Среда не подключена к телефону. Индексирование Android MediaScanner, реальные реализации DocumentsProvider, отзыв grant системным UI и поведение после перезапуска необходимо проверить на устройстве. Медиасканер поддерживает известный external-storage provider; opaque Downloads и облачные документы не получают угаданный физический путь и возвращают прежнюю объяснимую ошибку.

## Соответствие Agent

| Файл Agent | Файл Rikka-Root | Адаптация в этой задаче |
| --- | --- | --- |
| `local/WriteFileTool.kt` | `local/WriteFileTool.kt` | Сохранены имя, параметры и ответ инструмента; новое absolute-path SAF назначение идёт через транзакцию владения. |
| `local/FileManagerTool.kt`, `local/FileManagerContentOps.kt` | `local/FileManagerTools.kt`, `local/LocalFileAccess.kt` | Binary/copy используют план нового документа; прежние границы, grants, размеры, staged локальная запись и rollback существующего документа сохранены. |
| `local/ArchiveTools.kt` | `local/ArchiveTools.kt` | ZIP сначала полностью готовится в scratch, затем новый SAF-документ создаётся с явным владением; rollback существующего ZIP сохранён. |
| `local/MediaScannerTool.kt` | `local/MediaScannerTool.kt` | Без изменений; проверены известные volume mapping и ограничения provider. |

## Предложения по улучшению

Новых улучшений вне явно разрешённого создания SAF-документа не внедрено. `move_file` и автосоздание отсутствующих SAF-папок не входили в эту задачу; при необходимости это отдельное изменение с проверками владения и восстановления.

## Проверить на телефоне

1. С уже выбранной read/write SAF-папкой `Download` создать новый текст по `/sdcard/Download/overnight.txt`, затем прочитать по полному пути и URI из результата. Повтор без `overwrite` должен сохранить прежние байты.
2. Создать новый binary и копию по полному пути, собрать ZIP в отсутствующий `Download/overnight.zip`, проверить его содержимое и извлечение в уже существующую выбранную папку.
3. Для настоящего фото через external-storage SAF вызвать `scan_media` и проверить `indexed`; для opaque Downloads/облачного provider сохраняется понятный отказ без угадывания физического пути.
4. Отменить большой copy/write и проверить отсутствие частичного нового файла; существующий файл должен восстанавливаться. После отзыва SAF grant инструменты должны отказать без запроса спецдоступа.

## Дополнительная регрессия из независимого review

**P2:** новый `planWritePath` из `00e2901a` пытался разрешить родителя до проверки существующего назначения. Поэтому `copy_file(src="/scratch/source", dst="/sdcard/Download", overwrite=true)` отказывал при единственном grant `primary:Download`: родитель `/sdcard` не входит в него. Та же ошибка затрагивала корень Downloads provider.

Существующее назначение теперь сначала проходит прежний `resolve(fullPath, write=true)` и возвращается с его собственным grant. План через родителя используется для отсутствующего документа. Отсутствие opaque-ребёнка отличается отдельным `SafStoragePathNotFoundException`; ошибки разрешений, проверки границ и `CancellationException` не перехватываются этим fallback. Grants и проверки симлинков не расширены.

- `/tmp/saf-root-copy-red.log`: до тестов остановился общий test compile незавершённой соседней правки screenshot harness; это не RED.
- `/tmp/saf-root-copy-red-2.log`: настоящий RED — четыре ошибки для external-storage и opaque Downloads roots на API 28/35.
- `/tmp/saf-root-copy-green.log`: GREEN всех восьми связанных классов; JUnit результаты успела заменить следующая общая screenshot-проверка.
- `/tmp/saf-root-copy-green-final.log`: финальный GREEN — **85/85**, восемь классов, failures/errors/skipped 0; `SafAbsolutePathRoutingTest` — **38/38** на API 28/35. JUnit сохранены внутри общего lock в `/tmp/saf-root-copy-green-results`, сводка — `/tmp/saf-root-copy-green-summary.json`.

Регрессия проверяет настоящий `copy_file`: копирование локальной папки с одним документом в разрешённый корень, доступ к детям только через провайдер, отсутствие raw-файла в Download, сохранение источника и соседнего документа. Родитель по-прежнему запрещён. Вызов без `overwrite` отказывает и не создаёт детей. Новое создание, фактическая отмена, cleanup и старый rollback входят в тот же повторный набор тестов.
