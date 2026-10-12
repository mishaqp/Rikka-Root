# Этап 1: модели отображения, шапка и ввод Moru

> Для реализации: superpowers:subagent-driven-development; независимые части выполняются параллельно в заданной рабочей ветке. После единой проверки этапа — один push, зелёный Daily Build, отчёт и остановка для проверки на телефоне.

**Цель:** перенести визуальный язык и UX шапки/ввода Moru в Compose, сохранив логику Rikka-Root и пиксели компонентов сообщений.

**Архитектура:** чистые presentation-модели выражают уже существующие условия Compose. ChatVM, ChatService, GenerationLoop, ConversationConfig, разрешения, формат истории и tool execution остаются владельцами поведения. Новые компоненты вызывают существующие callbacks.

**Стек:** Kotlin, Jetpack Compose, MaterialTheme, HugeIcons, JUnit/Robolectric/Roborazzi.

**ТЗ:** сообщение пользователя 12.10.2026 заменяет визуальные этапы [исходного плана](chat-screen-redesign-plan.md); [аудит](chat-screen-audit.md) задаёт сохраняемые функции.

## Общие ограничения

- Этап 1 включает только presentation-модели, шапку, composer, лист «+» и полоску работающей команды. Сообщения и их действия визуально не менять.
- Поверхности непрозрачные, цвета из MaterialTheme. Не включать градиенты, анимацию фона, Glass, blur, обои и мини-карту.
- Размеры и отступы брать из Flutter Moru; код переписать на Kotlin. Иконки HugeIcons, интерфейс на русском.
- Сохранить draft/edit, clipboard/URI, IME, обычную и долгую отправку, очередь, stop, диктовку и голос, вложения, поиск/reasoning, модель, workspace/MCP/skills/extensions, сжатие, drawer, переименование и поиск по сообщениям.
- Не добавлять выдуманные данные: неизвестное окно контекста обозначать явно; превью вывода использует только доступные данные текущего исполнения.
- Существующие 208 PNG «ДО» сохранить. Новый отдельный набор «ПОСЛЕ» проверяется в CI и публикуется как ui-screenshots.

## Задача 1: модели отображения

Файлы: ChatMessage.kt, ChatMessageActions.kt, ChatList.kt и небольшие presentation-файлы рядом; ChatMessagePresentationTest/ChatListPresentationTest, существующие model/action/checklist tests.

- [x] Зафиксировать текущие комбинации checkpoint/роль/пустое содержимое/loading/последний узел/read-only/capabilities, выбранную альтернативу и ключи элементов ленты.
- [x] Выделить ChatMessagePresentation и ChatListPresentation; pending/error описывать без переноса карточек или добавления сообщений в историю.
- [x] Использовать модели в Compose, сохранив порядок частей и callbacks; перенести проверки условий в реальные модели, оставить проверки проводки.
- [x] Выполнить characterization, presentation, branch/action/checklist и связанные policy/config/service тесты.

## Задача 2: шапка

Файлы: ChatPage.kt, новый ChatHeader.kt; ChatHeaderTest.

- [x] Одна строка «аватар · название · модель»: аватар 16dp, название 13sp, модель 12sp, gap6dp; длинные строки ellipsis, модель открывает существующий ModelListSheet.
- [x] Сохранить drawer, новый чат справа, переименование и обзор/поиск сообщений (доступный пункт меню).
- [x] Переключатель workspace высотой40dp/radius capsule с padding2dp и glyph19dp: Файлы/Терминал/Браузер, только при привязанном workspace и существующих экранах. Использовать текущие маршруты и BrowserActivity.intent с conversationId.
- [x] Проверить callbacks, пустой чат, read-only, отсутствие модели/workspace, узкую ширину и font1.3.

## Задача 3: composer и «+»

Файлы: ChatInput.kt, ContextUsageRing.kt, FilesPicker.kt, нужные compact picker options; input presentation/runtime tests.

- [x] Composer: горизонтальный отступ12dp, верх4dp/низ8dp; radius20dp, surfaceContainerHigh, border1dp; текст15sp/1–5 строк, отступы16/4/16/8dp.
- [x] Ряд действий с отступом8dp, glyph20dp, визуальная кнопка32dp, акцент включённых режимов; микрофон, круглая отправка/stop, контекстный индикатор. Переход отправки200ms.
- [x] «+»: три крупные плитки Камера/Фото/Файлы; остальные существующие функции строками с прежними visibility/callbacks, включая дочерние MCP/extensions/workspace/compression sheets.
- [x] Сохранить очередь с её панелью и обычной/долгой отправкой, attachments, edit, clipboard, completion, dictation и voice.
- [x] Проверить существующий функциональный checklist и runtime-callbacks, новые UI состояния снять Roborazzi.

## Задача 4: работающая команда

Файлы: новые RunningToolPresentation.kt/ChatRunningToolStrip.kt и тесты; интеграция ChatPage.kt.

- [x] Проекция только реально исполняющихся root/Termux/SSH/workspace-команд, не ожидающих разрешения; выбранная ветка и текущий разговор.
- [x] Морu: radius14dp, padding8/6/2/6dp, icon24dp/glyph13dp, команда12sp, последняя доступная строка10.5sp, таймер и stop32dp.
- [x] Stop вызывает существующую отмену; не менять execution, permissions, retry и хранение. Недоступный live stdout обозначать честно.
- [x] Проверить pending/denied/completed/loading/parallel/branch и отсутствие инструментов.

## Задача 5: сдача

- [x] Локальная компиляция app и связанные unit/Compose проверки по AGENTS.md.
- [x] Сохранить «ПОСЛЕ» для light/dark/AMOLED/dynamic × font1.0/1.3; просмотреть actual/diff; проверить сохранность «ДО» и неизменность компонентов сообщений.
- [x] Независимый review общей реализации, исправление замечаний и повторная проверка затронутых путей.
- [x] Отчёт: Moru → Kotlin, проверки, ограничения, что проверить на телефоне. Commit/push только завершённого этапа; при падении CI исправить и снова push.
- [ ] Дождаться зелёного Daily Build на SHA этапа, приложить APK/run/ui-screenshots, остановиться до проверки пользователя.
