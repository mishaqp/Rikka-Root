# Надёжность действий с созданными файлами

> **For agentic workers:** Use superpowers:executing-plans to implement the tasks in this session; keep the user's existing publication workflow.

**Goal:** Исправить подтверждённые сбои открытия, экспорта и отправки файлов из чата, сохранив текущий интерфейс.

**Architecture:** Оставить существующие карточки и SAF-пикер. Для абсолютных путей использовать `WorkspaceRepository.exportRootfsFile`, как в инструментах; для открытия документов — уже проверенный `Context.openLocalFile`. Копирование вынести в IO, ошибки показывать пользователю, кеш отправки делать отдельным для каждого вызова.

**Tech Stack:** Kotlin, Compose, Android Activity Result API, coroutines, Robolectric, JUnit4.

**Spec:** Пользователь: «Проверяй дальше, интерфейс чата ищи баги и неровности, но не ломай»; «Продолжай поиска и исправления багов». Исправления существующего поведения, без нового дизайна и переноса upstream.

## Global Constraints

- Ветка `ccr-e93188a6-grrqko`, один пакет commit/push, без PR.
- Никакой локальной сборки APK. Только затронутые unit-классы/компиляция; полный набор и lint через Daily Build.
- Существующие `KEY_BASE64`/`SIGNING_CONFIG`, сертификат и `applicationId` без изменений.
- Не менять команды оболочки, разрешения и архивы снимков `chat-after-stage5`.
- Новые строки на русском во всех обязательных каталогах ресурсов.

## Review Focus

- Удалённый документ: объяснение ошибки без открытия отсутствующего файла.
- Ошибки источника/приёмника SAF: не выдавать пустой файл за успешный экспорт.
- Одинаковые имена в разных папках: первая выданная URI сохраняет первые байты.
- `/workspace`, обычный Rootfs и bind mount: одинаковый файл в инструменте и в экспорте.
- Открытие content URI, отмена пикера и разрешения чтения у получателя без новых разрешений приложения.

## Task 1: Воспроизведение и исправление действий

**Files:**
- Test: `app/src/test/java/me/rerere/rikkahub/ui/components/message/ChatProducedFileActionsRuntimeTest.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageEditedFiles.kt`
- Create if needed: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ProducedFileActions.kt`
- Modify: обязательные `values*/strings.xml` и существующий checklist только при переносе обработчиков.

**Interfaces:** `EditedFilesList(parts, assistant)` остаётся прежним. Экспорт потребляет `ProducedFilePresentation`, сохранённый ID рабочей среды и URI назначения; отправка создаёт ACTION_SEND с читаемой content URI. Реальный `WorkspaceRepository`/`WorkspaceManager` остаётся границей доступа к файлам.

- [x] Добавить runtime/Compose-тесты на удалённый документ, экспорты трёх видов путей, ошибки чтения/записи, отмену, отдельные копии при отправке, полезный MIME и read grant. Ожидания — буквальные байты фикстур.
- [x] Запустить новый класс на исходной реализации, сохранить RED и отделить ошибки тестовой среды от регрессий.
- [x] Исправить подтверждённые дефекты минимально: единое разрешение абсолютных путей, отдельный кеш отправки, IO и видимые ошибки; отмену coroutine не поглощать.
- [x] Запустить новый класс и затронутые существующие классы. Результат: 61 actions/presentation/checklist tests, 0 failures/errors.
- [x] Проверить неизменность карточек/модальных окон целевым screenshot-тестом, без записи baseline. Результат: 32 cases, 136 снимков, все пиксели совпали. Frozen screenshots вызывают реальный accessibility OnClick с сохранением прежнего 16мс интервала touch input; это устраняет недетерминированный native ripple, сохраняя фазу shimmer.

Дополнение после воспроизведения: сохранять pending export (источник + исходный workspaceId) через пересоздание композиции. После независимого review отдельно проверить реальный `content://FileProvider` из generate_bug_report: удалённый кеш должен объясняться до открытия/отправки, проверка читаемости — в IO.

## Task 2: Проверка и публикация

- [x] Независимый обзор готового diff, исправить подтверждённые важные замечания с RED → GREEN. Один Important (удалённый FileProvider report): два подтверждённых RED, затем все 18 runtime-сценариев GREEN.
- [ ] Проверить diff/ресурсы, commit/push; дождаться Daily Build и исправить реальные ошибки CI.
- [ ] Проверить сертификат, applicationId и SHA-256 опубликованного nightly, дать ссылки и краткий отчёт.
