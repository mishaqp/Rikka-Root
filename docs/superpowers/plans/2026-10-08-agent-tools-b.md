# Перенос инструментов Agent: пакет B

> **For agentic workers:** Use superpowers:executing-plans. Выполнить пакет целиком в текущей рабочей ветке; commit и push после локальных проверок и обзора.

**Goal:** Перенести torch, vibrate, brightness, volume, set_wallpaper и NFC; добавить доступные названия SIM/сетевого оператора в telephony_info.

**Architecture:** Расширить существующие LocalToolOption, LocalTools и ToolPermissionPolicy без изменения ConversationConfig. Вынести оборудование в отдельную группу настроек. NFC получает собственную неэкспортируемую Activity и временные сессии в памяти; записи меток не попадают в Intent, saved state, БД или бэкапы.

**Tech Stack:** Kotlin, Android API 26+, Compose, kotlinx.serialization, JUnit; без новых зависимостей.

**Spec:** Запрос пользователя о пакете B, общее согласование разрешений и дополнение о названиях оператора в текущей сессии. Источник: ExTV/rikkahub-agent, a88f5854b4d2e2706ea2ae8304a7d66a3f9f2bbc.

## Global Constraints

- Только B и согласованная поправка telephony_info. Остановка после зелёного Daily Build до «дальше».
- Ветка ccr-e93188a6-grrqko; PR не создавать. До 40 изменённых файлов.
- Шесть функций выключены по умолчанию; SerialName точно как в Agent: torch, vibrate, brightness, volume, wallpaper, nfc.
- Все инструменты B, включая get_brightness, get_volume и nfc_read_tag, включить в общую систему одобрений. «Я ГЛУПЫЙ», «Всегда разрешать», «Для этого чата» и защита после веб-контента сохраняются.
- Новые разрешения по Agent: VIBRATE, WRITE_SETTINGS, ACCESS_NOTIFICATION_POLICY, SET_WALLPAPER, NFC. CAMERA уже есть; запросить только при включении фонарика. setStreamVolume не требует MODIFY_AUDIO_SETTINGS; лишнее разрешение не добавлять.
- WRITE_SETTINGS запрашивается при включении яркости, доступ к «Не беспокоить» — при включении управления громкостью звонка/уведомлений; медиагромкость доступна без DND. Отказ и отзыв доступа дают русское пояснение вместо падения.
- Root выдача только по кнопке пользователя, только своему пакету/пользователю, через RootCommandGuard. Для WRITE_SETTINGS использовать appops; DND открыть через Android, без автоматической выдачи.
- Сохранять AGPL и происхождение исходников. Никаких секретов или содержимого меток в логах.
- Локально только компиляция app и unit-тесты затронутых классов. APK, полный набор тестов и lint — Daily Build. Унаследованный лимит lint 51 не менять.

## Review Focus

- Отказ/отзыв CAMERA, WRITE_SETTINGS и DND: действие не выполняется, ошибка понятна; медиагромкость работает без DND.
- Вибрация: отрицательные/нечисловые/огромные значения, суммарная длительность и пустые шаблоны отвергаются до Android API; максимум 5 секунд и 20 интервалов.
- Обои: повреждённый/огромный файл не вызывает OOM; чтение ограничено локальным разрешённым URI, bitmap и потоки освобождаются; отказ WallpaperManager не выдаётся за успех.
- NFC: отмена генерации, кнопка отмены, уход с экрана, поворот, два запроса и поздний callback не приводят к записи после отмены или утечке сессии. TagTechnology закрывается в finally.
- NDEF: неверный TNF/base64/URI, большие записи и malformed payload дают ошибку до записи. Неизвестные записи сохраняют payload/type/id при чтении и записи.

## Task 1: совместимость, одобрения и оборудование

**Files:** LocalToolOption.kt, LocalTools.kt, ToolPermissionPolicy.kt; TorchTool.kt, VibrateTool.kt, BrightnessTool.kt, VolumeTool.kt, SetWallpaperTool.kt, HardwareToolArguments.kt; TelephonyInfoTool.kt. Тесты PackageBApprovalTest.kt, HardwareToolArgumentsTest.kt и LocalToolOptionCompatibilityTest.kt.

**Interfaces:** существующие Tool/ToolPermissionPolicy.apply; validateVibrationArguments(JsonObject): LongArray; volumeStep(percent: Int, min: Int, max: Int): Int; wallpaperSampleSize(width: Int, height: Int): Int; getNetworkOperatorName/getSimOperatorName добавляют отдельные name поля и сохраняют кодовые поля.

- [x] Написать тесты SerialName, выключенных функций и одобрений; выполнить и увидеть отказ до реализации.
- [x] Добавить варианты и registry, сохранив существующие функции.
- [x] Написать тесты границ вибрации/яркости/громкости/изображений, выполнить до реализации.
- [x] Реализовать оборудование с проверкой разрешений, ограничениями ввода и честным результатом Android API. Добавить названия операторов без новых разрешений.
- [x] Выполнить компиляцию app и выбранные тесты; ожидание BUILD SUCCESSFUL.

## Task 2: NFC и настройки разрешений

**Files:** NfcTools.kt, NfcResultBuffer.kt, NfcNdefCodec.kt, ui/activity/NfcToolActivity.kt; HardwareLocalToolSettings.kt, AssistantLocalToolPage.kt, LocalToolPermissions.kt, AndroidManifest.xml, values/strings_hardware_tools.xml. Тесты NfcResultBufferTest.kt, NfcNdefCodecTest.kt, LocalToolPermissionsTest.kt.

**Interfaces:** NfcResultBuffer.register/claim/complete/cancel; временная сессия хранит режим, timeout, records и deferred в памяти. Activity получает только случайный request ID, не экспортируется и проверяет живую сессию. NFC доступен при открытом приложении; фоновые/будущие headless вызовы не открывают экран.

- [x] Написать тесты изоляции сессий, отмены и позднего callback; тесты codec и grant-команд. Выполнить до реализации.
- [x] Реализовать NFC с ограниченным временем ожидания, отменой, reader mode только на активном экране, закрытием Ndef и NdefFormatable, проверкой вместимости/записываемости.
- [x] Добавить шесть настроек и русские пояснения. Возврат из Android настроек перепроверяет доступ и включает только явно выбранную функцию; отказ оставляет её выключенной. Для громкости предусмотреть использование медиа без DND.
- [x] Запрос CAMERA только по включению фонарика; WRITE_SETTINGS и DND только из соответствующего пользовательского действия. Root кнопки не вызываются автоматически.
- [x] Выполнить выбранные тесты и компиляцию; ожидание BUILD SUCCESSFUL.

## Task 3: обзор и доставка

- [x] Независимый обзор пакета целиком; существенные замечания исправить с проверкой.
- [x] git diff --check, число файлов до 40, быстрые локальные проверки.
- [ ] Commit и push в ccr-e93188a6-grrqko. Дождаться зелёного Daily Build для нового SHA; при падении прочитать лог и исправить.
- [ ] Публичным API подтвердить nightly tag и APK для нового SHA. Отчёт: коммит, Actions, APK, разрешения и ручные проверки. Остановиться.

## Запись выполнения

Начальная ревизия пакета B: f084b11e67f4908013be57a961afdb43857db6c9, чистая рабочая ветка совпадает с origin. Пакет A подтверждён пользователем на телефоне.

Согласованный пользователем цикл заменяет дополнительные промежуточные approvals навыков: планирование и реализация выполняются непрерывно, остановка нужна только по названным пользователем пределам файлов/спецдоступов и после публикации пакета. Работа ведётся в существующем checkout назначенной ветки; новый worktree и смена ветки не нужны.

RED: PackageBApprovalTest — два ожидаемых отказа (SerialName и registry) из трёх тестов. Тесты параметров, лимита изображения и NFC перед реализацией не компилировались из-за отсутствующих функций/классов; после реализации прошли. Из Agent исправлено SKIP_NDEF_CHECK: reader mode выполняет обнаружение NDEF, иначе Ndef.get может вернуть null для поддерживаемой метки.

Промежуточная компиляция обнаружила скрытую @SystemApi-константу ACTION_NOTIFICATION_POLICY_ACCESS_DETAIL_SETTINGS. По Android source API заменён на публичный ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS; возврат всегда перепроверяет фактический доступ, а не resultCode.

Проверка после интеграции UI: :app:compileDebugKotlin и девять выбранных JUnit-классов — BUILD SUCCESSFUL. Включены регрессии системы одобрений и пакета A. Независимый обзор выполняется до коммита; полный набор тестов, lint и подпись APK выполняются в Daily Build.

Независимый обзор: 3 Important и 1 Minor, Critical нет. Все исправлены в одном проходе: system защищён DND из-за связи STREAM_SYSTEM с громкостью звонка на телефонах; NFC физический обмен и завершение отмены согласованы общим gate, технология закрывается и операция завершается вне UI-потока до публикации результата; raw не обходит проверку известных RTD Text/URI; id_b64/type_b64 ограничены до декодирования. Замечание про метаданные оценено как существенное: необоснованное выделение памяти из внешнего ввода может завершить процесс. Оно исправлено, отложенных замечаний нет.

RED: отдельный запуск воспроизвёл три отказа для system, malformed RTD и metadata; интеграционный тест буфера воспроизвёл отсутствие закрытия/ожидания I/O при отмене. GREEN после исправлений: compileDebugKotlin + 43 теста в 10 выбранных классах, 0 failures/errors, BUILD SUCCESSFUL. git diff --check чистый; пакет затрагивает 27 файлов. APK локально не собирался.

Новые permissions пакета B: WRITE_SETTINGS (яркость, экран Android или явная root-кнопка); ACCESS_NOTIFICATION_POLICY (звонок/уведомления/системный звук, экран Android); VIBRATE, SET_WALLPAPER, NFC (обычные, устанавливаются Android). CAMERA уже объявлено и запрашивается при включении фонарика. Новых спецдоступов из списка пользователя нет.
