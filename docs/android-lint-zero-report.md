# Устранение унаследованных ошибок Android Lint

Исходное состояние подтверждено локальным `./gradlew lint`: 45 ошибок в 13 отчётах
модулей — 41 LocalContextGetResourceValueCall, 3 NonObservableLocale и
1 ContextCastToActivity. Исходный коммит: db2c32f2ad12fe79bea822661a281cd61f3b7adc.

## Изменённые файлы

Пути UI-файлов ниже приведены относительно
`app/src/main/java/me/rerere/rikkahub/`.

| Файл | Исправлено | Изменение |
|---|---:|---|
| ui/pages/backup/tabs/S3Tab.kt | 8 | Актуальные LocalResources для отложенных сообщений. |
| ui/pages/backup/tabs/WebDavTab.kt | 8 | Актуальные LocalResources для отложенных сообщений. |
| ui/pages/backup/tabs/ImportExportTab.kt | 4 | Актуальные LocalResources для импорта и экспорта. |
| ui/pages/imggen/ImgGenPage.kt | 4 | Актуальные LocalResources, прежние аргументы форматирования. |
| ui/pages/mediacreation/MediaCreationRecordItem.kt | 4 | stringResource и rememberUpdatedState для обработчиков. |
| ui/components/richtext/Mermaid.kt | 2 | Актуальные строки в запомненном JS-интерфейсе. |
| ui/components/ui/TextArea.kt | 2 | Актуальные строки в результате выбора файла. |
| ui/pages/assistant/detail/AssistantImporter.kt | 2 | Актуальные строки в обработчике импорта. |
| ui/pages/assistant/detail/PropertyEditor.kt | 1 | Актуальные LocalResources для сообщения об ошибке JSON. |
| ui/pages/setting/SettingMediaPage.kt | 2 | Актуальные LocalResources при завершении проверки подключения. |
| ui/pages/setting/SettingProviderDetailPage.kt | 1 | Актуальная строка в обработчике сохранения. |
| ui/pages/setting/components/ProviderConnectionTester.kt | 2 | Актуальные LocalResources для результата проверки инструментов. |
| ui/components/ui/UpdateCard.kt | 1 | Актуальная строка в запомненном обработчике useThrottle. |
| ui/components/message/ChatMessageTranslation.kt | 1 | Наблюдаемая locale из LocalConfiguration для названия языка. |
| ui/pages/stats/StatsPage.kt | 1 | Наблюдаемая locale для подписей месяцев. |
| ui/pages/translator/TranslatorPage.kt | 1 | Наблюдаемая locale для названия языка. |
| ui/pages/chat/ChatList.kt | 1 | LocalActivity.current вместо приведения LocalContext. |

Дополнительные изменённые файлы:

- `.github/scripts/android_build.py`: MAX_LINT_ERRORS=0; любой ненулевой код
  завершения lint отклоняет CI, даже если XML не содержит ошибок.
- `.github/scripts/test_android_build.py`: проверки нулевого порога, одной ошибки,
  Fatal, прежних 51/52 ошибок и ненулевого кода завершения.
- `AGENTS.md`: правило отсутствия lint-ошибок без подавления.
- `docs/superpowers/plans/2026-10-17-zero-lint.md`: план исполнения.
- `docs/android-lint-zero-report.md`: этот отчёт.

## Сохранение поведения

Ресурсные файлы, ID строк, аргументы форматирования и действия обработчиков не
изменены. Отложенные обработчики используют rememberUpdatedState со строкой либо
LocalResources, поэтому после рекомпозиции читают актуальные значения. Зависимых
от locale кешей remember в исправленных местах нет. ChatList сохраняет nullable
RouteActivity, регистрацию и снятие слушателя кнопок громкости.

Файлы data/ai/tools/** не изменялись. Подавления, lint baseline и отключения
проверок не добавлялись. Независимый обзор не выявил изменений поведения.

## Проверки

Тесты CI-порога сначала падали на прежнем допуске ошибок; после исправления
18 Python-тестов проходят. Полный локальный `./gradlew lint` завершился с кодом 0;
все 13 XML-отчётов содержат 0 ошибок. Проверка реальных отчётов новым CI-порогом
также прошла. 31 целевых Android unit-тестов в трёх классах прошли без ошибок
и пропусков: ChatListScrollRuntimeTest, ChatTranslationTokenRuntimeTest,
PropertyEditorAtomicUpdateTest.

## Проверка на телефоне

Смените язык приложения и проверьте названия месяцев в статистике и языков в
переводчике. Выполните импорт/экспорт и проверку подключения: уведомления должны
соответствовать текущему языку. Проверьте прокрутку чата кнопками громкости при
включённой существующей настройке.
