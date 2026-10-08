# Repository Guidelines

## Project Overview

RikkaHub is a native Android LLM chat client that supports switching between different AI providers
for conversations.
Built with Jetpack Compose, Kotlin, and follows Material Design 3 principles.

## Build, Test, and Development Commands

```bash
./gradlew assembleDebug          # 构建 Debug APK
./gradlew test                   # 运行所有模块的 JVM 单元测试
./gradlew lint                   # 运行 Android Lint
```

## Module Structure

- **app**: Main application module with UI, ViewModels, and core logic
- **ai**: AI SDK abstraction layer for different providers (OpenAI, Google, Anthropic)
- **common**: Common utilities and extensions
- **document**: Document parsing module for handling PDF, DOCX, PPTX, and EPUB files
- **highlight**: Code syntax highlighting implementation
- **material3**: Material color utility extensions used by the app UI
- **ui**: Reusable Compose UI components that do not depend on app logic
- **search**: Search functionality SDK for multiple providers (Exa, Tavily, Zhipu, Bing, Brave, SearXNG, and others)
- **speech**: Speech module for TTS and ASR implementations
- **web**: Embedded web server module that provides Ktor server startup function and hosts static frontend build files (
  built from web-ui/ React project)
- **workspace**: Sandboxed per-workspace file system and shell execution environment exposed to the AI as tools.

## Concepts

- **Assistant**: An assistant configuration with system prompts, model parameters, and conversation isolation. Each
  assistant maintains its own settings including temperature, context size, custom headers, tools, memory options, regex
  transformations, and prompt injections (mode/lorebook). Assistants provide isolated chat environments with specific
  behaviors and capabilities. (app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt)

- **Conversation**: A persistent conversation thread between the user and an assistant. Each conversation maintains a
  list of MessageNodes in a tree structure to support message branching, along with metadata like title, creation time,
  update time, pin status, chat suggestions, optional conversation-level system prompt, and prompt injection bindings.
  Once a conversation is persisted it also holds a `ConversationConfig` snapshot (chat model, reasoning level, search,
  MCP servers, workspace, skills) taken from the assistant; from then on chat-page changes to those settings stay on
  the conversation, and code should read them through `Settings.getAssistantOf(conversation)` /
  `Settings.getChatModelOf(conversation)` instead of the assistant directly. (
  app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt,
  app/src/main/java/me/rerere/rikkahub/data/model/ConversationConfig.kt)

- **UIMessage**: A platform-agnostic message abstraction that encapsulates chat messages with different types of content
  parts (text, images, documents, reasoning, tool calls/results, etc.). Each message has a role (USER, ASSISTANT,
  SYSTEM, TOOL), creation timestamp, model ID, token usage information, and optional annotations. UIMessages support
  streaming updates through chunk merging. (ai/src/main/java/me/rerere/ai/ui/Message.kt)

- **MessageNode**: A container holding one or more UIMessages to implement message branching functionality. Each node
  maintains a list of alternative messages and tracks which message is currently selected (selectIndex). This enables
  users to regenerate responses and switch between different conversation branches, creating a tree-like conversation
  structure. (app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt)

- **Message Transformer**: A pipeline mechanism for transforming messages before sending to AI providers (
  InputMessageTransformer) or after receiving responses (OutputMessageTransformer). Transformers can modify message
  content, add metadata, apply templates, handle special tags, convert formats, and perform OCR. Common transformers
  include:
  - TemplateTransformer: Apply Pebble templates to user messages with variables like time/date
  - ThinkTagTransformer: Extract `<think>` tags and convert to reasoning parts
  - RegexOutputTransformer: Apply regex replacements to assistant responses
  - DocumentAsPromptTransformer: Convert document attachments to text prompts
  - Base64ImageToLocalFileTransformer: Convert base64 images to local file references
  - OcrTransformer: Perform OCR on images to extract text

  Output transformers support `visualTransform()` for UI display during streaming and `onGenerationFinish()` for final
  processing after generation completes.
  (app/src/main/java/me/rerere/rikkahub/data/ai/transformers/Transformer.kt)

## Internationalization

- String resources are usually located in `app/src/main/res/values*/strings.xml`; feature modules such as `search`
  may also maintain their own `values*/strings.xml`
- Use `stringResource(R.string.key_name)` in Compose
- Page-specific strings should use page prefix (e.g., `setting_page_`)
- If the user does not explicitly request localization, prioritize implementing functionality without considering
  localization. (e.g `Text("Hello world")`)
- For `locale-tui` operations, use the `locale-tui-localization` skill.

## Rikka-Root: правила форка

Для этого форка правила ниже имеют приоритет над общими командами сборки выше.

- **Ветка и PR.** Рабочая ветка по умолчанию — `ccr-e93188a6-grrqko`; PR не создавать без запроса.
- **APK и проверки.** APK собирать только через GitHub Actions workflow [Daily Build](https://github.com/mishaqp/Rikka-Root/actions/workflows/daily-build.yml); nightly prerelease — [ссылка](https://github.com/mishaqp/Rikka-Root/releases/tag/nightly). Локально запускать только компиляцию затронутого модуля и unit-тесты затронутых классов. Полный `assembleRelease` локально не запускать без необходимости.
- **Цикл.** Правка → быстрые локальные проверки → commit и push в рабочую ветку → дождаться Actions. Статус смотреть через публичный [Actions API](https://api.github.com/repos/mishaqp/Rikka-Root/actions/runs). При падении прочитать лог упавшего шага, исправить и снова отправить изменения.
- **Lint.** В основе 51 унаследованная ошибка; новых не добавлять, существующие ошибки не подавлять.
- **Подпись и секреты.** Подписывать release только в GitHub Actions секретами `KEY_BASE64` и `SIGNING_CONFIG`. Ключи и пароли не коммитить и не выводить в логи.
- **Среда Codex.** `scripts/codex-setup.sh` устанавливает JDK, Android SDK/NDK, Node/pnpm и клонирует справочные репозитории. Firebase и `google-services` в проекте не используются.
- **Справочные репозитории.** `/workspace/refs/rikkahub-agent` — Kotlin, основа upstream 2.5.1; переносить с адаптацией под 2.5.6 и `ConversationConfig`. `/workspace/refs/Moru` — Flutter/Dart, только образец поведения; код переписывать на Kotlin.
- **Правила переноса из Agent.** Брать исходные файлы Agent и сохранять максимально близко их логику, классы, функции, имена инструментов, параметры, описания и `@SerialName`. Не переписывать рабочую логику и не добавлять возможностей, которых нет в Agent, без отдельного запроса пользователя. Допустимы необходимые изменения пакетов/импортов, API upstream 2.5.6, `ConversationConfig`, системы разрешений и HARDLINE, AndroidKeyStore, русского интерфейса и root вместо Shizuku/Accessibility. Сохранять авторские заголовки и лицензию.
- **Недоделки и ошибки Agent.** Недоделанные функции переносить как есть и отмечать в отчёте; дальнейшую разработку решает пользователь. Явные баги и проблемы безопасности не исправлять молча: перечислить файл Agent, проблему и предложение. Для каждого переноса включать в отчёт таблицу «файл Agent → файл Rikka-Root → скопировано / адаптировано (что изменено)».
- **Предложения по улучшению.** Не применять улучшения относительно Agent сразу: сначала перенести оригинальную логику. В конце отчёта добавить раздел «Предложения по улучшению»: что изменить, зачем, сколько файлов затронет и какие есть риски. Пользователь выбирает улучшения отдельной задачей.
- **Размер пакета.** До 60 файлов — без дополнительного вопроса. Более 60 — самостоятельно разделять на части, каждая с отдельным push и сборкой; не останавливаться ради согласования размера. Соблюдать явно заданные пользователем границы пакетов и остановки.
- **Android-доступы при переносе.** Обычные и опасные разрешения из манифеста Agent для того же инструмента можно добавлять без вопроса; опасные запрашивать только при включении функции, отказ объяснять без падения. Перечислять разрешения в отчёте. Согласовывать только спецдоступы `MANAGE_EXTERNAL_STORAGE`, `SYSTEM_ALERT_WINDOW`, `BIND_ACCESSIBILITY_SERVICE`, `BIND_NOTIFICATION_LISTENER_SERVICE`, `QUERY_ALL_PACKAGES`, `INTERACT_ACROSS_USERS_FULL`, `ACCESS_BACKGROUND_LOCATION` и вопросы безопасности. Выдавать разрешения через root только по явному нажатию пользователя.
- **Инструменты и данные.** Новые инструменты регистрировать в системе разрешений («Разрешения инструментов»). Для `LocalToolOption` задавать `@SerialName` по примеру `Agent` ради совместимости бэкапов. Секреты пользователя в приложении хранить только в AndroidKeyStore.
- **Интерфейс и отчёт.** Строки интерфейса писать на русском. После задачи сообщать, что сделано, коммиты, ссылку на Actions run и APK, а также что проверить на телефоне.
