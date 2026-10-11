package me.rerere.rikkahub.ui.screenshot

import android.net.Uri
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.paging.PagingData
import com.dokar.sonner.rememberToasterState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.asr.ASRState
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.root.RootShellManager
import me.rerere.rikkahub.root.RootStatus
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.service.MessageQueueState
import me.rerere.rikkahub.ui.context.LocalASRState
import me.rerere.rikkahub.ui.context.LocalSharedTransitionScope
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.hooks.CustomAsrState
import me.rerere.rikkahub.ui.hooks.CustomTtsState
import me.rerere.rikkahub.ui.pages.chat.ChatDrawerVM
import me.rerere.rikkahub.ui.pages.chat.ChatPage
import me.rerere.rikkahub.ui.pages.chat.ChatVM
import me.rerere.rikkahub.ui.pages.chat.ConversationListItem
import me.rerere.rikkahub.ui.pages.chat.DrawerConversationSort
import me.rerere.rikkahub.ui.pages.chat.VoiceSessionController
import me.rerere.rikkahub.ui.pages.chat.VoiceSessionState
import me.rerere.rikkahub.utils.NightlyUpdateState
import me.rerere.rikkahub.utils.SoundEffectPlayer
import me.rerere.rikkahub.utils.UpdateChecker
import okhttp3.OkHttpClient
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import java.io.IOException
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** A single fixed dialogue; only external boundaries are replaced, never the UI renderer. */
internal class ChatBeforeFixture(
    image: File,
    private val actualVM: ChatVM? = null,
    private val filesManager: FilesManager? = null,
) {
    private val instant = Instant.parse("2026-01-02T12:00:00Z")
    private val timestamp = LocalDateTime.parse("2026-01-02T12:00:00")
    private fun id(index: Int) = Uuid.parse("40000000-0000-0000-0000-${index.toString().padStart(12, '0')}")
    private fun message(index: Int, role: MessageRole = MessageRole.ASSISTANT, vararg parts: UIMessagePart) = UIMessage(
        id = id(index), role = role, parts = parts.toList(), createdAt = timestamp,
        finishedAt = LocalDateTime.parse("2026-01-02T12:00:02"),
        modelId = if (role == MessageRole.ASSISTANT) ScreenshotFixtures.model.id else null,
        usage = if (role == MessageRole.ASSISTANT) TokenUsage(promptTokens = 120, completionTokens = 80, totalTokens = 200) else null,
    )
    private fun tool(name: String, state: ToolApprovalState = ToolApprovalState.Auto, output: String? = "{\"success\":true,\"result\":\"Готово\"}", input: String = "{}") =
        UIMessagePart.Tool("call_$name", name, input, output?.let { listOf(UIMessagePart.Text(it)) }.orEmpty(), state)

    /** Start each capture on its message so even 1.3x text stays visible. */
    val viewports = linkedMapOf(
        "overview" to 0, "markdown" to 2, "media" to 3, "reasoning" to 4,
        "terminal" to 5, "browser_screen" to 6, "automation" to 7, "approval" to 8,
        "mcp_pending" to 9, "ask_user" to 10, "chart" to 11, "server_legacy" to 12,
        "memory_search" to 13, "scrape_time" to 14, "clipboard_speech" to 15,
        "screen_calendar" to 16, "calendar_skill" to 17, "history" to 18,
        "workspace_edit" to 19, "workspace_files" to 20, "workspace_shell" to 21,
        "generic" to 22,
    )
    private val messages = listOf(
        message(1, MessageRole.USER, UIMessagePart.Text("Проверь файлы и инструменты, покажи результаты.")),
        message(2, parts = arrayOf(UIMessagePart.Text("**Готово.** Найдены файлы и ссылки [1]."))).copy(
            annotations = listOf(UIMessageAnnotation.WebContentUsed, UIMessageAnnotation.UrlCitation("Документация", "https://example.com/docs")),
            translation = "Done. Files and links found.",
        ),
        message(3, parts = arrayOf(UIMessagePart.Text("```kotlin\nval answer = 42\nprintln(answer)\n```\n\n| Тип | Статус |\n|---|---|\n| Документ | Готово |\n\nФормула: $" + "E=mc^2$\n\n> Цитата\n\n- Первый пункт\n- Второй пункт"))),
        message(4, MessageRole.USER, UIMessagePart.Image(image.toURI().toString()), UIMessagePart.Document("file:///audit/report.pdf", "Отчёт.pdf", "application/pdf"), UIMessagePart.Audio("file:///audit/voice.wav"), UIMessagePart.Video("file:///audit/video.mp4")),
        message(5, parts = arrayOf(UIMessagePart.Reasoning("Проверяю результат и выбираю безопасную команду.", instant, instant + kotlin.time.Duration.parse("2s")), UIMessagePart.Text("Проверка завершена."))),
        message(6, parts = arrayOf(
            tool("root_exec", input = """{"command":"id"}""", output = """{"success":true,"stdout":"uid=0(root)","exit_code":0}"""),
            tool("termux_run_command", input = """{"command":"false"}""", output = """{"success":false,"error":"Команда завершилась с кодом 1"}"""),
            tool("ssh_exec", input = """{"host":"example.com","user":"audit","command":"uptime"}""", output = """{"success":true,"stdout":"up 2 days","exit_code":0}"""),
        )),
        message(7, parts = arrayOf(
            tool("browser_screenshot").copy(output = listOf(UIMessagePart.Text("""{"success":true}"""), UIMessagePart.Image(image.toURI().toString()))),
            tool("read_window_tree"),
        )),
        message(8, parts = arrayOf(
            tool("workflow_list"),
            tool("skill_get_content").copy(output = listOf(UIMessagePart.Text("Открыть результат навыка", buildJsonObject {
                put("rikkahub.webview", buildJsonObject { put("url", "https://example.com/skill"); put("iframe", false) })
            }))),
            tool("subagent_dispatch"), tool("list_jobs"),
        )),
        message(9, parts = arrayOf(tool("root_exec", ToolApprovalState.Pending, null, """{"command":"ls /sdcard"}"""), tool("browser_eval_js", ToolApprovalState.Denied("Пользователь отказал"), null, """{"code":"document.title"}"""))),
        message(10, parts = arrayOf(tool("mcp_add", ToolApprovalState.Pending, null,
            """{"name":"Аудит MCP","transport":"sse","url":"https://example.com/mcp","headers":[{"name":"Authorization","value":"Bearer fixture-secret"}],"enabled":true}"""))),
        message(11, parts = arrayOf(tool("ask_user", ToolApprovalState.Pending, null,
            """{"questions":[{"id":"q1","question":"Продолжить проверку?","options":["Да","Нет"],"selection_type":"single"}]}"""))),
        message(12, parts = arrayOf(
            tool("chart_display", input = """{"style":"bar","title":"Результаты","x_axis":{"data":["А","Б"]},"series":[{"name":"Проверки","values":[2,3]}]}"""),
            // Success uses ChartBlock; this failed call exercises the registry renderer too.
            tool("chart_display", input = """{"style":"bar","title":"Ошибка графика"}""", output = """{"success":false,"error":"Нет данных"}""").copy(toolCallId = "chart_failure"),
        )),
        message(13, parts = arrayOf(UIMessagePart.ServerTool("server_1", "web_search", status = ServerToolStatus.FAILED), UIMessagePart.ToolCall("legacy_1", "legacy", "{}"), UIMessagePart.ToolResult("legacy_1", "legacy", buildJsonObject { put("result", "Готово") }, buildJsonObject {}), UIMessagePart.Search, UIMessagePart.Text("Мини-приложение", buildJsonObject { put("rikkahub.webview", buildJsonObject { put("url", "https://example.com"); put("iframe", true) }) }))),
        message(14, parts = arrayOf(
            tool("memory_tool", input = """{"action":"create"}""", output = """{"id":1,"content":"Пользователь предпочитает краткие ответы."}"""),
            tool("search_web", input = """{"query":"Compose"}""", output = """{"answer":"Краткий ответ о Compose.","items":[{"id":"abc123","url":"https://example.com/docs","title":"Compose","text":"Документация интерфейса"}],"images":[]}"""),
        )),
        message(15, parts = arrayOf(
            tool("scrape_web", input = """{"url":"https://example.com"}""", output = """{"urls":[{"url":"https://example.com","content":"## Страница\nТекст документации"}]}"""),
            tool("get_time_info", output = """{"time":"2026-01-02T12:00:00Z","timezone":"UTC"}"""),
        )),
        message(16, parts = arrayOf(
            tool("clipboard_tool", input = """{"action":"read"}""", output = """{"text":"Текст из буфера"}"""),
            tool("text_to_speech", input = """{"text":"Проверка речи"}"""),
        )),
        message(17, parts = arrayOf(
            tool("get_screen_time", output = """{"total_minutes":100,"apps":[{"app_name":"Rikka","package":"me.rerere.rikkahub","total_minutes":40,"total_ms":2400000}]}"""),
            tool("calendar_query", output = """{"events":[{"title":"Проверка интерфейса"}]}"""),
        )),
        message(18, parts = arrayOf(tool("calendar_create", input = """{"title":"Проверка интерфейса"}"""), tool("use_skill", input = """{"name":"demo","path":"SKILL.md"}"""))),
        message(19, parts = arrayOf(
            tool("recent_chats", output = """[{"title":"Аудит экрана чата"}]"""),
            tool("conversation_search", input = """{"query":"интерфейс"}""", output = """[{"title":"Аудит экрана чата"}]"""),
        )),
        message(20, parts = arrayOf(tool("workspace_edit_file", ToolApprovalState.Pending, null,
            """{"path":"/workspace/a.kt","old_text":"val x = 1","new_text":"val x = 2"}"""))),
        message(21, parts = arrayOf(
            tool("workspace_read_file", input = """{"path":"/workspace/a.kt"}""", output = """{"text":"fun main() { println(42) }"}"""),
            tool("workspace_write_file", input = """{"path":"/workspace/b.kt","text":"val result = 42"}"""),
        )),
        message(22, parts = arrayOf(
            tool("workspace_shell", input = """{"command":"pwd"}""", output = """{"exitCode":0,"timedOut":false,"stdout":"/workspace","stderr":""}"""),
            tool("workspace_shell", input = """{"command":"false"}""", output = """{"exitCode":1,"timedOut":false,"stdout":"","stderr":"Ошибка команды"}"""),
        )).let { it.copy(parts = it.parts.mapIndexed { index, part -> (part as UIMessagePart.Tool).copy(toolCallId = "workspace_shell_$index") }) },
        message(23, parts = arrayOf(tool("list_files", input = """{"path":"/sdcard"}"""), tool("unregistered_fixture_tool", output = "Вывод без JSON: подробности выполнения."))),
    )
    val conversation = Conversation(id = id(100), assistantId = ScreenshotFixtures.assistant.id,
        title = "Аудит экрана чата", config = ConversationConfig(chatModelId = ScreenshotFixtures.model.id),
        messageNodes = messages.mapIndexed { index, item -> MessageNode(id = id(200 + index), messages = if (index == 1) listOf(item.copy(id = id(99), parts = listOf(UIMessagePart.Text("Предыдущий ответ"))), item) else listOf(item), selectIndex = if (index == 1) 1 else 0) },
    )
    val conversationFlow = MutableStateFlow(conversation)
    val errors = MutableStateFlow<List<ChatError>>(emptyList())
    val vm = actualVM ?: mockk<ChatVM>(relaxed = true)
    private val asr = mockk<CustomAsrState>(relaxed = true)
    private val tts = mockk<CustomTtsState>(relaxed = true)
    private lateinit var application: KoinApplication

    fun install() {
        stopKoin()
        if (actualVM == null) {
        val voice = mockk<VoiceSessionController>(relaxed = true)
        every { voice.state } returns MutableStateFlow(VoiceSessionState())
        every { vm.voiceSession } returns voice
        every { vm.conversation } returns conversationFlow
        every { vm.settings } returns MutableStateFlow(ScreenshotFixtures.settings)
        every { vm.currentChatModel } returns MutableStateFlow(ScreenshotFixtures.model)
        every { vm.assistant } returns MutableStateFlow(ScreenshotFixtures.assistant)
        every { vm.conversationJob } returns MutableStateFlow<Job?>(null)
        every { vm.processingStatus } returns MutableStateFlow<String?>(null)
        every { vm.errors } returns errors
        every { vm.inputState } returns ChatInputState()
        every { vm.chatListInitialized } returns true // capture starts at the first message, no initial jump
        every { vm.messageQueue } returns MutableStateFlow(MessageQueueState())
        every { vm.conversationJobs } returns MutableStateFlow(emptyMap())
        every { vm.generationDoneFlow } returns MutableSharedFlow()
        }
        val drawer = mockk<ChatDrawerVM>(relaxed = true)
        every { drawer.conversations } returns flowOf(PagingData.empty<ConversationListItem>())
        every { drawer.folders } returns MutableStateFlow(emptyList())
        every { drawer.selectedFolderId } returns MutableStateFlow(null)
        every { drawer.showAllFolders } returns MutableStateFlow(true)
        every { drawer.presentationSort } returns MutableStateFlow(DrawerConversationSort.NEWEST)
        val root = mockk<RootShellManager>(relaxed = true)
        every { root.status } returns MutableStateFlow(RootStatus.UNCHECKED)
        val checker = mockk<UpdateChecker>(relaxed = true)
        every { checker.nightlyState } returns MutableStateFlow(NightlyUpdateState())
        every { asr.state } returns MutableStateFlow(ASRState())
        every { tts.isAvailable } returns MutableStateFlow(false)
        every { tts.isSpeaking } returns MutableStateFlow(false)
        application = startKoin { modules(module {
            single { vm }; single { drawer }
            single { filesManager ?: mockk<FilesManager>(relaxed = true) }
            single { mockk<WorkspaceRepository>(relaxed = true) }
            single { mockk<ConversationRepository>(relaxed = true) }
            single { mockk<SkillManager>(relaxed = true) }
            single { mockk<SoundEffectPlayer>(relaxed = true) }
            single { root }; single { checker }; single { AppEventBus() }; single { OkHttpClient() }
        }) }
    }
    fun showError() { errors.value = listOf(ChatError(id = id(500), title = "Ошибка провайдера", error = IOException("HTTP 429: превышен лимит запросов"), conversationId = conversation.id, timestamp = 1767355200000L)) }
    fun close() { stopKoin() }

    @Composable
    fun Content(text: String? = null, files: List<Uri> = emptyList()) {
        val toaster = rememberToasterState()
        SharedTransitionLayout {
            CompositionLocalProvider(LocalSharedTransitionScope provides this, LocalASRState provides asr,
                LocalTTSState provides tts, LocalToaster provides toaster) {
                ChatPage(actualVM?.conversation?.value?.id ?: conversation.id, text, files)
            }
        }
    }
}
