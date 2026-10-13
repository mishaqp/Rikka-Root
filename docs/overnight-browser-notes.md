# Ночная проверка браузера, 13 октября 2026

## Наблюдение и важность

POCO / Android 16 / HyperOS: `browser_open` → `browser_launch_failed` (окно не подключилось за 5 секунд), затем `browser_not_open`. Это мешает браузерным инструментам, но исходная причина на телефоне пока не установлена. Телефон и его системный журнал в этой среде недоступны.

Подтверждённый пробел диагностики: успешный возврат `startActivity` и истечение `awaitBind(5_000)` не позволяют различить отсутствие Activity, незавершённое создание Compose/WebView и закрытие окна. `BrowserController` подключается из `BrowserView.onWebViewReady`, а не непосредственно из `BrowserActivity.onCreate`. Это поведение уже есть в Agent; таймаут не доказывает отказ HyperOS или необходимость новых разрешений.

## Воспроизведение

JVM/Robolectric: `ContextWrapper.startActivity` принимает Intent, но не создаёт Activity. `browser_open` через 5000 мс управляемого времени выдаёт прежний код `browser_launch_failed`; последующий `browser_current_url` — `browser_not_open`. Новые проверки требуют отдельного наблюдаемого этапа, честного русского объяснения неизвестной причины и записи диагностики в существующий ZIP.

На телефоне: в одном разговоре сравнить ручной «Браузер» и `browser_open`, сначала при видимом приложении, затем после ухода в фон. После неудачи создать существующий отчёт об ошибке, до перезапуска процесса. Содержимое сайтов для этого не требуется.

## Изменение и границы

Добавлен процессный журнал этапов запуска и lifecycle: идентификатор попытки в Intent, идентификатор экземпляра Activity, монотонное время, main/other thread, app-resumed при запросе, finishing/changing-configurations/window-focus. Он различает отсутствие начала onCreate, незавершённое создание WebView, готовый WebView без bind, bind и закрытие Activity; регистрирует тип исключения и повторно выбрасывает прежнее исключение. Русская причина строится из того же immutable snapshot, который возвращается инструментом. В диагностику не входят URL, заголовки/содержимое страниц, Intent payload, conversationId, cookies, секреты или сообщения исключений. Разрешения, безопасность, cookies, headless-режим, владельцы WebView и таймаут 5 секунд не меняются.

## Сверка с Agent

| Agent | Rikka-Root | Сверка |
| --- | --- | --- |
| `browser/BrowserActivity.kt` | `browser/BrowserActivity.kt` | Исходное создание Activity/Compose/WebView и подключение контроллера сохранены; диагностика добавлена по явной задаче пользователя. |
| `data/ai/tools/local/BrowserTools.kt` | `data/ai/tools/local/BrowserTools.kt` | Исходная foreground-ветка и ожидание 5000 мс сохранены; русские ошибки уже были в форке. |
| `browser/BrowserController.kt` | `browser/BrowserController.kt` | Сверены подключение, отключение и `awaitBind`; изменение владельцев не требуется для диагностики. |
| `reliability/BugReportBuilder.kt` | `reliability/BugReportBuilder.kt` | Диагностика включена в существующий ZIP без новых инструментов и разрешений. |
| `browser/BrowserView.kt` | `browser/BrowserView.kt` | Добавлены только диагностические callbacks начала/ошибки создания WebView; исходное создание, настройки и загрузка сохранены. |
| Нет аналога | `browser/BrowserLaunchDiagnostics.kt` | Новый ограниченный журнал по явной задаче пользователя. |

Прочитаны `AGENTS.md`, браузерная часть `docs/ui-customization-drawer-report.md`, diff `c5fb5c70..HEAD` для браузера и исходные Activity/BrowserTools Agent.

## Проверки

Первый настоящий RED: `/tmp/browser-red-5.log`, **18 проверок, 3 ожидаемых падения** — отсутствие русского объяснения этапа на SDK 28/35 и отсутствие `browser-lifecycle.txt` в ZIP. Остальные 15 прошли. Предыдущие попытки были заблокированы чужими незавершёнными test-harness imports/generics или Maven mirror 503/отсутствием mirror cache и не считаются RED.

Команда RED: `flock /tmp/rikka-overnight-gradle.lock ./gradlew :app:testDebugUnitTest --tests 'me.rerere.rikkahub.browser.BrowserLaunchFailureTest' --tests 'me.rerere.rikkahub.reliability.BugReportBuilderTest' --no-configuration-cache`.

GREEN: **BUILD SUCCESSFUL, exit 0, 20 секунд**, `/tmp/browser-green.log`; выполнены браузерный пакет `me.rerere.rikkahub.browser.*`, helpers `me.rerere.rikkahub.data.ai.tools.local.Browser*Test` и `BugReportBuilderTest`. Количество тестов не приписывается по XML следующего параллельного SAF-прогона, который уже перезаписал общий каталог отчётов. Добавлены проверки этапов, раздельных поздних callbacks, отсутствия сообщений исключений, границ истории и сохранения известного этапа после обрезки. Прежние коды ошибок, отмена и 5000 мс проверяются исходными тестами. Команда: `flock /tmp/rikka-overnight-gradle.lock ./gradlew :app:testDebugUnitTest --tests 'me.rerere.rikkahub.browser.*' --tests 'me.rerere.rikkahub.reliability.BugReportBuilderTest' --tests 'me.rerere.rikkahub.data.ai.tools.local.Browser*Test' --no-configuration-cache`. `git diff --check` без замечаний. Основной агент просмотрел diff без пробелов и журнал: прежние операции Activity/WebView сохранены, исключения повторно выбрасываются, сырые данные не добавляются в диагностику.

## Остатки

Причину сбоя на POCO без lifecycle-журнала устройства не заявляем доказанной, запуск на нём не заявляем исправленным. Процессный журнал исчезает при перезапуске приложения; хранит до 12 попыток и 64 событий на попытку. Старый Intent после перезапуска/вытеснения истории создаёт новую manual-попытку. Это не наблюдение причин Android system_server: успешный возврат startActivity не равен подтверждению разрешения запуска. Проверка lifecycle на настоящем Android 16 необходима; Robolectric не моделирует решения HyperOS по фоновым Activity. APK локально не собирается, push не выполняется этим агентом.
