# Ночной аудит чата — 13.10.2026

Рабочая база: `f1800f41`, изменения интерфейса сравниваются с `c5fb5c70`. Прочитаны `AGENTS.md`, `docs/ui-customization-drawer-report.md`, `docs/chat-screen-audit.md`; Moru используется как образец поведения, без переноса Dart-кода. Область: ChatPage/Header/List/Scroll, сообщения/rich text/composer/service. Проверки настроек/DataStore/hooks и drawer выполняются отдельно.

Снимки stage5 не записываются; архивы предыдущих этапов не меняются. APK локально не собирается, push не выполняется. Все Gradle-проверки используют `flock /tmp/rikka-overnight-gradle.lock`, `--no-configuration-cache`; отдельные логи — `/tmp/chat-*.log`.

## Баги и гипотезы

| Сценарий | Важность | Причина / воспроизведение | Статус |
| --- | --- | --- | --- |
| Настройка прокрутки клавишами громкости изменена при смонтированном чате | P2 | `ChatListNormal` устанавливает listener в `DisposableEffect(Unit)` и сохраняет первоначальные enable/ratio/inset. Изменить выключено → включено; вызвать зарегистрированный listener на настоящей RouteActivity. Он должен перехватить клавишу и прокрутить настоящий LazyList. | RED подтверждён; GREEN 2/2. Listener читает актуальные settings/padding, Activity/state/density управляют регистрацией. |
| Ссылка на источник в переводе | P2 | `ChatMessage` передаёт в перевод пустой `onClickCitation`, хотя исходный текст получает обработчик search_web. Свернуть/развернуть перевод, нажать citation; ожидать тот же Android VIEW intent, что в оригинале. | RED подтверждён; GREEN: original и translated intent проходят. Перевод использует прежний search_web resolver с актуальными parts. |
| Программная прокрутка и auto-follow | Риск | Moru отличает пользовательское намерение от программного scroll; Kotlin использует `isScrollInProgress` для паузы. Последствия для IME/анимаций пока не воспроизведены. | Не исправлено без runtime evidence. |
| Вибрация генерации после смены loading | Риск | `MessagePartsBlock` эффект зависит только от settings и захватывает loading. Требуется реальное воспроизведение события на переключении состояния. | Не исправлено без runtime evidence. |

## Живые сценарии

Новый `ChatVolumeKeyRuntimeTest` монтирует production ChatList и проверяет настоящий listener registry, текущий сдвиг LazyList, немедленное включение/выключение, изменение ratio и измеренного composer inset, удаление listener при dispose. RouteActivity attach выполняется без запуска app navigation.

Новый `ChatPageInteractionRuntimeTest` монтирует целый ChatPage: терминал → WorkspaceTerminal текущей рабочей области; браузер → BrowserActivity с conversation ID; вставка подсказки → actual composer и выход из редактирования; смена insert→send без пересоздания → callback с текстом подсказки при сохранении существующего черновика; отсутствие модели → существующая русская ошибка без отправки. Original/translated citation проверяют реальный Android intent.

Существующие runtime suites включены в проверку: ChatHeaderIntegrationTest (rename/model/avatar/files/preview), ChatComposerInteractionTest (normal/long send, Stop, queue, attachment, IME, model/search/reasoning, narrow width, context tokens), ChatActionAvailabilityTest (clipboard, confirm regeneration, TTS, translation, more/sheet/branch/edit), ChatTranslationTokenRuntimeTest (translation collapse/reopen и полная строка токенов), ChatCustomizationRuntimeTest (реальные переключения Markdown/header/collapse/reference links), ChatScrollNavigationTest и ChatScrollNavigationLayoutTest (навигация/пауза и touch targets), ChatListBottomReadabilityTest (финальный текст/actions выше composer/IME), ComposerCustomizationTest и ChatCustomizationPresentationTest.

Mocks заменяют внешние VM/аудио/файловые границы. Проверки этих сценариев доказывают UI→callback/intent, а не настоящее выполнение сетевого запроса, терминального процесса, браузерного WebView или TTS на устройстве.

## Выполненные проверки

- `/tmp/chat-runtime-red-local.log`: два ожидаемых volume assertions — enable не включается; новая дистанция 224 px остаётся 132 px. Дополнительные ошибки harness (inline badge без native rendering и отсутствующий Toaster) устранены в тестах; они не засчитываются как RED приложения.
- `/tmp/chat-runtime-followup.log`: original search citation открывает URL; translated citation после collapse/reopen возвращает null вместо Android intent. Volume 2/2 и прочие новые Page scenarios проходят.
- `/tmp/chat-runtime-green.log`: **104/104**, 43 с, 0 failures/errors. Включены все runtime/presentation suites из перечня выше, кроме нового haptic reproduction, который исследуется отдельно.
- Первые запуски имели compile failure нового test harness (`RuntimeEnvironment.getApplication` без generics), затем Robolectric SDK download `UnknownHostException` и временный Google Maven mirror 503. Это ошибки настройки/среды; RED приложения установлен только последующими runtime assertions. SDK jar получен через session proxy в локальный Maven-кеш; проектные build/network настройки не менялись.

RED означает ожидаемый runtime assertion на production пути; ошибки компиляции/среды не считаются RED.

## Остатки и телефон

- Проверить на телефоне клавиши громкости до/после изменения настройки и при разной высоте composer/IME.
- Проверить citation исходного и переведённого ответа при смене версии сообщения и повторном открытии перевода.
- Реальные OEM scroll/IME, TalkBack, WebView, звук и вибрация остаются устройственными проверками.
