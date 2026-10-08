# Пакет C: личные данные и безопасность

> **For agentic workers:** Use superpowers:executing-plans to implement this plan inline; one fresh whole-package reviewer at the end.

**Goal:** Перенести location, contacts, call_log, sms_inbox, sms_send, camera_photo, mic_recorder, speech_to_text, fingerprint и семь keystore_* операций из Agent, сохранив правила форка.

**Spec:** Требования пользователя в этой сессии: пакет C целиком после зелёной сборки исправлений B; все операции требуют одобрения, выключены по умолчанию, разрешения только под функцию, секреты только AndroidKeyStore. Нужные разрешения из Agent разрешены заранее, кроме перечисленных специальных доступов. База пакета: `9cefa0ba13e800272f8ca61be9186aaa54d15e21`.

**Architecture:** LocalToolOption с точными SerialName Agent, общая ToolPermissionPolicy без изменений ConversationConfig. Чтение через Android ContentResolver/LocationManager с ограниченными результатами; отправка SMS с подтверждениями SmsManager. Неэкспортируемый foreground-экран камеры, микрофона, речи, биометрии и защищённых криптографических форм; между инструментом и экраном передаётся только случайный ID, запросы находятся в памяти и отменяются с очисткой ресурсов. Ключи — в отдельном пространстве имён AndroidKeyStore, обычным инструментам недоступны внутренние ключи приложения.

**Tech Stack:** Kotlin, Android APIs 26+, Compose, kotlinx.coroutines, AndroidX Biometric 1.1.0 (stable).

## Ограничения и решения

- Ветка `ccr-e93188a6-grrqko`, один коммит/пуш готового пакета, без PR. Локально только compileDebugKotlin и выбранные unit-тесты; APK и полный набор проверок в Daily Build.
- Не более 40 изменённых файлов. Итог: 38; при превышении остановиться.
- SerialName: location, contacts, call_log, sms_inbox, sms_send, camera_photo, mic_recorder, speech_to_text, fingerprint, keystore. Все 18 имён инструментов регистрируются в разрешениях, включая чтение и verify/list.
- Добавить READ_CONTACTS, READ_CALL_LOG, READ_SMS, SEND_SMS, USE_BIOMETRIC; USE_FINGERPRINT приходит из библиотеки для Android 8. Переиспользовать CAMERA, RECORD_AUDIO, ACCESS_FINE_LOCATION/ACCESS_COARSE_LOCATION. Никаких ACCESS_BACKGROUND_LOCATION, foreground-service microphone или иных спецдоступов.
- Runtime-запрос только при включении функции. Приблизительная геолокация считается допустимым доступом, фактическая точность возвращается инструментом. Отказ/отзыв — русская ошибка; root-выдача только отдельной кнопкой, свой пакет/пользователь, RootCommandGuard.
- Геолокация использует LocationManager, работает без Google Play Services. Камера сохраняет фото в личных вложениях чата и возвращает file:// + /upload, без автоматически созданной копии в галерее. Запись/речь только на видимом экране, уход в фон их отменяет.
- Нельзя выдавать необоснованный success для SMS: подтверждения sent по каждой части; таймаут/частичный результат явно неопределённый, без автоматического повторения.
- Keystore: generate с реальными requested purposes, запрет перезаписи, namespace только ключей этих инструментов. Ограничить Base64 до выделения памяти, RSA-2048/SHA256withRSA и AES-256-GCM, новый случайный IV, проверка тега. Не экспортировать закрытые/симметричные ключи. encrypt запрашивает исходные данные на защищённом экране; decrypt показывает результат там же, возвращает модели только статус. Секретные байты обнуляются после использования, не попадают в Intent/сохранённое состояние/логи/Room/бэкапы.
- AGPL-происхождение отметить в адаптированных файлах, не удалять исходные авторские заголовки. Интерфейс на русском.

## Файлы

Изменить: LocalToolOption.kt, LocalTools.kt, ToolPermissionPolicy.kt, LocalToolPermissions.kt, LocalToolPermissionsTest.kt, AppModule.kt, AssistantLocalToolPage.kt, AndroidManifest.xml, app/build.gradle.kts, gradle/libs.versions.toml.

Создать в data/ai/tools/local/: LocationTool.kt, ContactsTool.kt, CallLogTool.kt, SmsInboxTool.kt, SmsSendTool.kt, CameraPhotoTool.kt, MicRecorderTool.kt, SpeechToTextTool.kt, FingerprintTool.kt, KeystoreTools.kt; PersonalToolArguments.kt, PersonalToolSession.kt, KeystoreCrypto.kt, AndroidToolKeyStore.kt.

Создать в ui/activity/: PersonalToolActivity.kt, PersonalAudioCapture.kt, PersonalSpeechCapture.kt, ProtectedSecretScreen.kt, SmsSentReceiver.kt. В ui/pages/assistant/detail/: PersonalLocalToolSettings.kt. Ресурсы values/strings_personal_tools.xml.

Тесты: PackageCApprovalTest.kt, PersonalToolArgumentsTest.kt, PersonalToolSessionTest.kt, KeystoreCryptoTest.kt, SmsDeliveryTest.kt.

## Review Focus

1. Отзыв разрешения после включения/провайдер отсутствует: ошибка вместо падения или ложного пустого результата.
2. Отмена во время запуска, уход в фон, поздний callback: ресурсы закрыты до освобождения сеанса, результат не попадает в другой запрос.
3. Большие/неправильные Base64, IV, purposes и алиасы: ограничение до выделения памяти, нельзя заменить или удалить внутренний ключ приложения.
4. Две SIM, частичный SMS, таймаут: результат не трактуется как подтверждённая доставка и не провоцирует дубль.
5. Android 8–9 биометрия с PIN и устройство без распознавания речи: штатный запасной путь/понятная ошибка, без новых спецдоступов.

## Task 1: контракты, параметры, чтение и отправка SMS

**Interfaces:** localToolRuntimePermissions(option, sdkInt), validatePersonalToolArguments helpers, SmsDeliveryBuffer callbacks с request ID/part index; factory регистрирует только выбранные options.

- [x] Написать тесты SerialName/defaults/одобрений, границ параметров и SMS receipt (duplicate/late/partial/failed).
- [x] Запустить выбранные классы и увидеть ожидаемое отсутствие новых имён/параметров.
- [x] Реализовать options/registry, строгие параметры, курсоры с use и защитным limit, параметризованные фильтры, LocationManager и SMS sent-callback receiver.
- [x] Проверить compileDebugKotlin и тесты задачи, записать результаты.

## Task 2: foreground-сеансы, камера, аудио, речь, биометрия

**Interfaces:** PersonalToolSessions.run(request, timeoutMs, isForeground, startUi); opaque ID в Intent, результат только в памяти; cancellation cleanup suspend до освобождения слота. Activity/controller consumes request, completes result only after resource cleanup.

- [x] Написать тесты занятости, неверного ID, отказа foreground, таймаута, отмены с ожидающей очисткой и обнуления секретного буфера.
- [x] Увидеть ожидаемые RED, реализовать broker и Android экран/контроллеры; все capture tools с runtime-проверкой и approval.
- [x] Проверить compileDebugKotlin и выбранные тесты; вручную прочитать ветки stop/release/destroy/cancel/late callbacks.

## Task 3: AndroidKeyStore и защищённые формы

**Interfaces:** ToolKeyStore backend с namespace, KeystoreCrypto.generate/sign/verify/encrypt/decrypt/delete/list. Android backend хранит только non-exportable ключи; JVM test backend использует реальные JCA алгоритмы, без Android API.

- [x] Написать тесты overwrite запрета, изоляции namespace, strict purposes, RSA verify/tamper, AES roundtrip/tamper/unique IV, pre-allocation limits.
- [x] Увидеть RED, реализовать backend/engine и семь инструментов с approval; секретный ввод/вывод через foreground broker и FLAG_SECURE, без текстовых результатов в чате.
- [x] Проверить compileDebugKotlin и тесты; проверить, что приватные ключи и секретные байты не сериализуются и не логируются.

## Task 4: настройки, итоговая проверка и публикация

- [x] Добавить русские настройки, enable-time runtime-запросы, отказ/отзыв, необязательную root-кнопку. Добавить только согласованные разрешения и non-exported Activity/Receiver, точечные intent queries.
- [x] Компиляция app + тесты новых классов и разрешений/регрессий A/B/ConversationConfig. Проверить фактические XML результаты и git diff --check, число файлов <=40.
- [x] Fresh-context review всего пакета. Исправить существенные замечания с RED→GREEN, без повторного review.
- [ ] Коммит и push `ccr-e93188a6-grrqko`, ждать зелёный Daily Build через публичный API, сверить подпись/nightly SHA, выдать отчёт и STOP до «дальше».

## Итог проверки перед публикацией

38 файлов. Компиляция и 61 выбранный тест прошли (0 ошибок/падений), XML и git diff --check — чистые. Review нашёл и устранил исключение GPS при coarse на Android 12+, отсутствие реального запрета Autofill и недоступную кнопку при длинном вводе. Двоичный результат с управляющими символами также показывается в Base64; две регрессии воспроизведены RED→GREEN. Нативные экраны Autofill/клавиатуры и OEM-оборудование дополнительно проверяются на телефоне.

CI: первая попытка остановилась из-за таймаута JitPack; повторная выполнила 978 тестов без ошибок и нашла один NewApi в проверке SMS-подписки. Вызов API 29 заменён на совместимый с API 26 выбор неотрицательного ID. Компиляция и 62 выбранных теста прошли; порог lint не изменялся.
