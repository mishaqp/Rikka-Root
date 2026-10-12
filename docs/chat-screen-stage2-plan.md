# Правки этапа 1 и этап 2: реализация сообщений Moru

> Исполнение: superpowers:subagent-driven-development. Пользователь задал точное ТЗ и последовательность двух коммитов; этап 3 не выполняется. Единая публикация завершённого результата и зелёный Daily Build, затем остановка.

**Цель:** исправить проверенные на телефоне элементы шапки/ввода, затем перенести сообщения Moru в Compose с действующими настройками Rikka-Root.
**Архитектура:** чистые presentation-модели читают DisplaySetting; Compose потребляет их и вызывает прежние callbacks. Для существующих пользовательских настроек применяется совместимый default/migration при чтении. Backend, история, разрешения и ConversationConfig не меняются.
**Стек:** Kotlin, Compose, MaterialTheme, HugeIcons, Robolectric/Roborazzi.
**ТЗ:** сообщения пользователя в текущей сессии, docs/chat-screen-audit.md и эталон /workspace/refs/Moru.

## Ограничения и проверки

- Два коммита в порядке: пять правок этапа 1; сообщения/настройки этапа 2. Push после завершения обоих.
- Все новые тексты по-русски. Размеры Moru, цвета MaterialTheme. Полупрозрачность допускается по явному запросу для scroll buttons и bubbleOpacity; blur/Glass/фон/мини-карта отложены.
- Не потерять ни один из 250 пунктов checklist, callbacks, ветвление, read-only, approvals, code preview/edit, перевод, источники, media и chart.
- Показывать настоящий prompt usage; известное окно контекста получать из существующего ModelRegistry, неизвестное не выдумывать.
- Проверить 320/411dp и font1/1.3, RTL/IME/длинные имена, 0 и большие token counts, конец/начало ленты, streaming empty и complete, markdown/code/table, старый JSON настроек с отсутствующими и явными полями.

## 1. Правки этапа 1

Файлы: ChatHeader.kt, ChatInput.kt, ContextUsageRing.kt, ChatPage.kt, компонент scroll navigation и связанные runtime/presentation tests.
- [x] Из тестов зафиксировать центрирование header/title и отсутствие model в шапке; модель первая слева в composer, 20dp/32dp, tap picker и long-press tooltip.
- [x] Оставить аватар16dp и однострочное onSurface название по центру между drawer и правыми кнопками, сохранить rename/menu/new-chat/workspace.
- [x] Неизвестное окно: labelSmall/onSurfaceVariant compact prompt tokens; известное: заполнение кольца. Tooltip сохранить; placeholder «Сообщение».
- [x] Scroll buttons36dp, surface alpha0.85 light/0.4 dark, outline, показывать при прокрутке и скрывать после1500ms. Резервировать место справа, учитывать существующие настройки jumper.
- [x] Компиляция app + затронутые unit/Compose тесты; отдельные AFTER fixes baselines; review; первый commit.

## 2. Сообщения и настройки

Файлы: ChatMessage.kt/ChatMessagePresentation.kt и отдельные message visual components; PreferencesStore.kt/SettingPreferencesUIPage.kt; richtext и token/translation/source компоненты.
- [x] Сначала проверить реально существующие default/serialization/settings consumers и размеры/анимации Flutter Moru.
- [x] Каждое существующее display поле подключить к UI-модели. Новые установки showUserAvatar=false; явные и прежние значения существующих пользователей сохранять.
- [x] User справа: bubble radius16 и opacity, необязательные avatar/name/time; collapse по реальным9строкам с fade и reversible expansion.
- [x] Assistant: без bubble по выключенной настройке; при включении radius16/surfaceContainerHigh/opacity. Маленький заголовок icon/name/time управляется настройками.
- [x] Stream fade + waiting three dots; code/inline/table/translation/citations стиль Moru при сохранении функций.
- [x] Русская token row под showTokenUsage. Новые настройки Moru отдельным блоком того же экрана; backup defaults/roundtrip/legacy tests. Инструментальные настройки подготовить к этапу3 и не менять его timeline сейчас.
- [x] Отдельные tests всех переключателей через production UI-модель и runtime checks ключевых callbacks; защитить checklist.

## 3. Сдача

- [x] Focused compile/tests, AFTER для четырёх тем×двух font scales и настроек/stream/collapse; сохранить исторические BEFORE/stage1 PNG.
- [x] Независимый review, исправления и подтверждение; отчёт Moru→Kotlin, ограничения и телефонный checklist.
- [ ] Второй commit, один push в ccr-e93188a6-grrqko; Daily Build на итоговомSHA, при ошибке чинить и повторять.
- [ ] Ссылки APK/run/screenshots/report; остановка до проверки телефона.
