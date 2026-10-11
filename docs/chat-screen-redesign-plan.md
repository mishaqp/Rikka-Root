# План редизайна экрана чата

> Это предложение для следующей задачи. В сдаче аудита редизайн не реализован.
> Для последующей реализации: использовать `superpowers:subagent-driven-development` или `superpowers:executing-plans`; каждый этап ниже заканчивается отдельной сборкой Daily Build и проверкой на телефоне.

**Цель:** сделать чтение и ввод компактнее, сохранив все действия, типы содержимого, ветки, разрешения и настройки, перечисленные в [аудите](chat-screen-audit.md).

**Архитектура:** оставить ChatVM → ChatService → ConversationSession → GenerationLoop владельцами поведения. Менять Compose-представление небольшими этапами; сначала выделить проверяемые модели отображения из существующих условий, затем изменять оформление. MessageNode/снимок ConversationConfig и формат UIMessagePart не мигрировать ради вёрстки.

**Стек:** Kotlin, Jetpack Compose/Material 3, Koin, Room/DataStore; JUnit/Robolectric/Roborazzi. Исходная точка аудита: `d0ae59ac`.

## Требования и границы

- Пользовательские сообщения: пузыри без имени и аватара. Ответ ассистента: без пузыря.
- Шапка: название чата, затем «ассистент · модель»; длинные значения сокращать с доступом к полному названию.
- Панель действий: только у последнего ответа. Предложенная трактовка — только постоянно видимая панель последнего завершённого ответа ассистента; для старых сообщений нужен новый доступ к существующему листу действий: например, долгое нажатие с сохранением выделения текста и доступная кнопка меню. Пользовательские edit/delete и селектор альтернатив тоже сохраняются. Это решение должно быть проверено при согласовании будущего редизайна.
- Статистика токенов: подробности по нажатию компактного индикатора, вместо постоянно развёрнутой строки NerdLine.
- Инструменты: компактные раскрывающиеся вызовы с группировкой, без перестановки реального порядка текста, reasoning, серверных и клиентских вызовов.
- Ожидающее одобрение и ошибки: карточки в ленте. Нужное пользователю подтверждение видно сразу, даже если соседние завершённые инструменты свёрнуты.
- Поле ввода: компактное, с сохранением вложений, диктовки/голоса, поиска, reasoning, модели, workspace/MCP/skills и очереди.
- Политики «Я ГЛУПЫЙ», «Для этого чата», «Всегда», HARDLINE, ограничения browser/MCP, лимиты токенов и ConversationConfig не менять ради нового UI.
- Обнаруженные баги аудита не исправлять скрыто в косметических этапах. Исправление потери истории, гонок или бюджета — отдельная согласованная задача со своими тестами и сборкой.
- Не делать параллельную замену HTML/Markdown движка, редактора, навигации или хранилища.

## Проверки, которые уже страхуют исходное состояние

| Проверка | Что фиксирует | Граница доказательства |
|---|---|---|
| `ChatBranchCharacterizationTest` | текущие candidates/selectIndex, merge/update/fork/delete | фиксирует в том числе неудобную текущую семантику; не доказывает отсутствие гонок Room |
| `ChatMessageRenderModelTest` | порядок Content/Thinking/Chart, все варианты частей, pending/executed, пустое сообщение | проверяет настоящие функции production, а не копию модели |
| `ChatActionAvailabilityTest` | реальные кнопки и sheet по ролям, ветки, callbacks, error UI | inline loading/checkpoint/last-message условия дополнительно фиксируются структурным контрактом |
| `ChatErrorCharacterizationTest` | классификация сетевых ошибок и данные ChatError | без запросов к реальному провайдеру |
| `ChatFunctionChecklistTest`, `ChatDrawerFunctionChecklistTest`, `ChatRichContentChecklistTest`, `ChatSketchFunctionChecklistTest` | перечень UI-действий, drawer, вложенные карточки, редактор рисунка и проводка к callbacks | структурная проверка существующего кода; её нельзя заменять тестовым списком, который проверяет сам себя |
| `ChatToolRenderingContractTest` | специализированные рендереры, generic fallback, chart/ask_user/skill-card маршруты | без выполнения команд или инструментов |
| `ChatBeforeScreenshotsTest` | настоящий ChatPage, четыре темы × два размера шрифта; несколько участков одного диалога | заменены VM/внешние зависимости; WebView, системные picker и реальные разрешения требуют устройства |
| Существующие `ToolApprovalActionTest`, `GenerationLoopRootApprovalTest`, `ConversationConfigTest`, `TokenBudgetTrackerTest`, `GenerationLoopBudgetTest`, `ConversationTitleGeneratorTest`, `ChatServiceTest`, `MessageQueueTest`, `VoiceSessionControllerTest` | политики, бюджеты, snapshot, автоназвания, очередь и голос | не заменяют визуальную проверку |

Эталоны «ДО» не перезаписывать в обычном CI. Для будущих намеренных визуальных изменений сохранять отдельный набор «ПОСЛЕ» или обновлять выбранные эталоны только после просмотра diff и утверждения соответствующего этапа. Добавлять semantics/testTag только для устойчивых наблюдаемых границ, не для управления поведением из теста.

## Этап 1 — выделение моделей отображения, сборка без визуальных изменений

**Файлы:** `ChatPage.kt`, `ChatList.kt`, `ChatMessage.kt`, `ChatMessageActions.kt`, `ChatMessageCot.kt`; новые небольшие файлы рядом с ними, только если нужны для модели.

- [ ] Перенести существующие условия доступности действий в чистую модель `ChatMessagePresentation`: роль, content/checkpoint, loading, last-message, TTS/translation и capabilities. Сохранить текущие результаты для всех combinations.
- [ ] Отдельно описать элементы ленты с устойчивыми ключами: message-node, pending-approval, error, loading, system-prompt. Не изменять сохранённые части и не добавлять fake UIMessage в историю провайдера.
- [ ] Перевести source-contract проверки на проверки этой реальной модели; структурный checklist оставить для проводки UI → callbacks.
- [ ] Прогнать characterization, родственные service/config/approval тесты и весь набор «ДО» в verify. Сдать Daily Build без изменения пикселей.

**Риски:** потеря действий из private Compose-веток, смешение last-node и last-assistant, пропуск checkpoint/TOOL/SYSTEM, случайный сброс remember-state и выбранной альтернативы. **Страховка:** branch/render/actions/checklist + все эталоны «ДО».

## Этап 2 — шапка и компактное поле ввода

**Файлы:** `ChatPage.kt` (TopBar), `ChatInput.kt`, `ChatAttachmentPicker.kt`, `FilesPicker.kt`, `AttachmentChips.kt`, `MessageQueuePanel.kt`; сохранить существующие model/reasoning/search/MCP/workspace/skills pickers.

- [ ] Оформить две читаемые строки шапки «название» и «ассистент · модель». Сохранить редактирование названия, drawer, новый чат и обзор/поиск сообщений; текущие ограничения пустого чата сохранить.
- [ ] Уменьшить визуальный объём поля, вынести редко используемые элементы в уже существующий sheet, сохранить доступность каждой функции из checklist. Вложения и очередь должны оставаться заметны до отправки.
- [ ] Сохранить обычную/долгую отправку, stop, редактирование, вставку image/URI, IME submit, voice state, все MIME-типы и разрешения. Не переинтерпретировать Enter и long-press.
- [ ] Проверить 411dp/узкий экран, font1.3, клавиатуру, поворот, аппаратную клавиатуру, аппаратные volume keys; на устройстве — camera/gallery/audio/video/document, отказ разрешения, диктовку и voice mode.
- [ ] Новые intentional screenshots только шапки/ввода + Daily Build. Сообщения до следующего этапа должны соответствовать прежним эталонам.

**Риски:** скрытый активный search/reasoning, потеря вложения/черновика/очереди, двойная отправка, неверное изменение assistant вместо ConversationConfig. **Страховка:** checklist, actions, config, queue, voice, input semantics и снимки восьми комбинаций темы/шрифта.

## Этап 3 — пользовательские пузыри и свободный ответ ассистента

**Файлы:** `ChatMessage.kt` (включая ChatMessageBubble), `ChatMessageAvatar.kt`, `ChatMessageTranslation.kt`, `ChatMessageBranch.kt`; темы/DisplaySetting только при явно необходимом согласованном переходе.

- [ ] Убрать имя/аватар из пользовательской строки; оставить правый пузырь, attachment chips и выбранную альтернативу.
- [ ] Убрать контейнер-пузырь вокруг ответа ассистента. Сохранить markdown, code, table, LaTeX, selection/citations, media, reasoning, chart и mini-app card, сохраняя их порядок.
- [ ] Решить судьбу старых настроек `showUserAvatar`, assistant bubble и `bubbleOpacity` в интерфейсе настроек: не оставлять переключатели, обещающие эффект, которого больше нет; формат бэкапа сохранять. Это часть согласования этапа, не повод менять текущий аудит.
- [ ] Проверить очень длинные слова/code/table, смешанное направление текста, большие изображения, font1.3, AMOLED/динамические цвета. Проверить Text selection после streaming и доступность ссылок.
- [ ] Сдать визуальный diff и отдельную Daily Build.

**Риски:** вылеты selection при streaming, слишком широкие таблицы, исчезновение attachments/translation/citations, переиспользование состояния от другой ветки. **Страховка:** render-model, branch/actions/checklist и медиа/markdown/reasoning скриншоты.

## Этап 4 — действия последнего ответа и статистика по нажатию

**Файлы:** `ChatMessage.kt`, `ChatMessageActions.kt`, `ChatMessageNerdLine.kt`, `ChatList.kt`, `ChatMessageCopySheet.kt`, `ChatMessageTranslation.kt`.

- [ ] Постоянную панель оставить только у последнего завершённого ответа ассистента. Во время генерации сохранять stop в поле ввода. Для исторических и пользовательских сообщений добавить новый способ открыть существующий ChatMessageActionsSheet: предложенное долгое нажатие и доступный явный пункт меню. Сейчас долгое нажатие выделяет текст и не открывает этот лист; согласовать жест так, чтобы сохранить выделение.
- [ ] Сохранить copy, select, regenerate, edit, delete, fork, share/export, favorite, TTS, translation/clear, Web preview и branch navigation. Не прятать pending approval/ask_user вместе с toolbar.
- [ ] Компактный token indicator открывает sheet с существующими usage/model/duration/speed/time данными. Не подменять отсутствующие usage нулём; model snapshot работает после удаления модели из настроек.
- [ ] Семантические тесты прямо утверждают число видимых панелей, привязку к messageId, полный набор действий меню и содержание token sheet. Отдельно проверить выделение текста и новый жест открытия листа, включая TalkBack и клавиатуру. Screenshot: historical/current/loading/checkpoint/branch.
- [ ] Сдать отдельную Daily Build.

**Риски:** потеря доступа к старым сообщениям, last-node вместо последнего ответа, показ чужой статистики, глобальное TTS-state. **Страховка:** actions, checklist, branch, model-snapshot и новые last-response/token semantics tests.

## Этап 5 — компактные инструменты и одобрения в ленте

**Файлы:** `ChatMessageCot.kt`, `ChainOfThought.kt`, `ChatMessageTools.kt` (включая AskUserToolStep), `ToolUI.kt`, `BuiltinToolUIs.kt`, `WorkspaceToolUIs.kt`, `ToolApprovalAction.kt` (submission state); политика остаётся в существующих service/permission classes.

- [ ] Группировать только соседние вызовы в текущем ThinkingBlock. В свёрнутом состоянии показывать название группы, количество и состояние; раскрытие даёт исходный порядок и прежние детали/результаты. Reasoning и успешный chart_display остаются отдельными определёнными границами.
- [ ] Сохранить все 17 specialized renderers, ask_user и fallback для 182 имён + динамических MCP; результаты-image/skill-card/edited-files по прежним правилам. Никакого выполнения команд из тестов.
- [ ] Pending approval вынести в заметную карточку в ленте, не скрывать за «ещё N шагов». Использовать существующие callbacks/scopes: запрет кнопки определяется ToolPermissionPolicy, browser_eval_js не получает Always, in-flight защищает от повторной отправки, deny reason сохраняется.
- [ ] Добавить семантические тесты разрешений scopes, повторного тапа, pending→approved/denied/answered→executed, mixed server/client calls, раскрытия и смены выбранной ветки. Тест отдельно фиксирует порядок text→tool→chart→text.
- [ ] Сдать diff compact/expanded/success/error/approval/ask_user/MCP и отдельную Daily Build.

**Риски:** скрытый pending, обход политики, двойное исполнение root/SSH/workflow, потеря оригинальных arguments/output, смешение нескольких toolCallId, перестановка частей. **Страховка:** render-model, tool contract, existing approval/policy tests, characterization callbacks и снимки.

## Этап 6 — ошибки как элементы ленты

**Файлы:** `ChatList.kt`, `ErrorCard.kt`, модель элементов этапа 1; ChatVM/ChatService — только если в отдельном согласованном изменении потребуется передать недостающий conversation/message identity.

- [ ] Вместо поверхностного стека карточек снизу показывать error item в ленте рядом с соответствующим запросом. Сохранять title/details, copy, dismiss, clear-all и ссылку CheckFastModelSettings.
- [ ] Определить явную привязку к conversationId, поведение глобальных ошибок и восстановления после поворота. Визуальная карточка не должна менять сохранённую историю запроса к провайдеру.
- [ ] Согласовать изменение текущего пятисекундного auto-dismiss: для новой карточки рекомендуется явное закрытие, чтобы дать время чтению/TalkBack. Это намеренное изменение UX следующего этапа, не молчаливое исправление текущего аудита.
- [ ] Покрыть provider/network/429/token-cap/fast-model ошибки, отсутствие error.message, cancellation, несколько ошибок и переключение чатов. Повторная отправка не должна запускаться из каждой рекомпозиции.
- [ ] Сдать screenshot/error semantics diff и отдельную Daily Build.

**Риски:** ошибка другого чата, дубли при rotation, бесконечная выдача error, скрытый отказ бюджета, ложное «успешное завершение». **Страховка:** error characterization, service/session/queue/budget tests, errors + loading скриншоты.

## Этап 7 — доступность, темы и длинные диалоги

**Файлы:** только затронутые UI компонентов предыдущих этапов, ImeAutoScroller/ChatSizeChecker при необходимости отдельного поведенческого согласования; не переписывать Room-загрузку истории в этом этапе.

- [ ] Проверить TalkBack labels и порядок фокуса, expanded/state description, кликабельные области не меньше 48dp, контраст, системный шрифт ≥1.3 и длинные русские строки. В Landscape/tablet вернуть доступ ко всем элементам.
- [ ] Прогнать восемь тем/шрифтов, клавиатуру, буфер, rotation и offline; WebView/LaTeX, camera/SAF, zoom/TTS/ASR — на устройстве.
- [ ] Измерить на одном устройстве исходную и новую вёрстку: 1 000 nodes, streaming длинного markdown и 50 соседних tools; сравнить frame time/jank, время открытия и память. Числа зафиксировать вместе с устройством/версией ОС/fixture. При ухудшении investigate отдельную cause, не отключать функции.
- [ ] Сверить каждую строку checklist с аудитом, все новые модели и snapshots; удалить только подтверждённый тестовый дубликат, сохранив «ДО» как историю.
- [ ] Финальная отдельная Daily Build, artifact, проверка на телефоне, затем остановка для решения пользователя.

**Риски:** красивый screenshot при непригодной навигации, layout clipping, регрессии IME и скролла, необоснованные заявления о производительности. **Страховка:** вся characterization suite, verify screenshots, доступность Compose semantics + воспроизводимые замеры на устройстве.

## Протокол каждой сдачи

1. Изменять только файлы своего этапа. Новый дефект поведения записывать отдельно и не смешивать с вёрсткой.
2. Выполнять локальную компиляцию затронутого модуля и связанные unit/UI tests по AGENTS.md. APK — через Daily Build.
3. Перед обновлением эталона посмотреть actual/diff для всех восьми комбинаций. Оставить доступными исходные «ДО».
4. Отдельный commit/push и зелёная Daily Build на SHA этапа; приложить ссылку `ui-screenshots`, что проверить на телефоне и известные ограничения.
5. Следующий этап начинать после проверки предыдущей сборки. Реализацию этого плана текущая задача не разрешает.
