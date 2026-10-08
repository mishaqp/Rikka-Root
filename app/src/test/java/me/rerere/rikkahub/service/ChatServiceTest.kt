package me.rerere.rikkahub.service

import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.StreamChunk
import me.rerere.rikkahub.costguards.TokenBudgetExceededException
import me.rerere.rikkahub.costguards.TokenBudgetLedger
import me.rerere.rikkahub.data.ai.AutoContextCompression
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.limitContext
import me.rerere.rikkahub.data.ai.tools.shouldUseExternalWebSearch
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.uuid.Uuid

class ChatServiceTest {
    @Test fun `repair preserves completed root checkpoint and unstarted automatic sibling`() {
        val checkpoint = UIMessagePart.Tool("started", "root_exec", "{\"command\":\"id\"}",
            output = listOf(UIMessagePart.Text("root_execution_indeterminate")), approvalState = ToolApprovalState.Auto)
        val unstarted = UIMessagePart.Tool("unstarted", "root_exec", "{\"command\":\"id\"}", approvalState = ToolApprovalState.Auto)
        val user = UIMessage.user("run diagnostics").toMessageNode()
        val node = UIMessage.assistant("").copy(parts = listOf(checkpoint, unstarted)).toMessageNode()
        assertEquals(listOf(user, node), repairIncompleteToolMessages(listOf(user, node)))
        assertTrue(checkpoint.isExecuted)
        assertFalse(unstarted.isExecuted)
    }

    @Test fun `repair keeps root no replay evidence when only pending sibling remains`() {
        val checkpoint = UIMessagePart.Tool("started", "root_exec", "{}",
            output = listOf(UIMessagePart.Text("root_execution_indeterminate")), approvalState = ToolApprovalState.Auto)
        val waiting = UIMessagePart.Tool("waiting", "root_exec", "{}", approvalState = ToolApprovalState.Pending)
        val node = UIMessage.assistant("").copy(parts = listOf(checkpoint, waiting)).toMessageNode()
        assertEquals(listOf(node), repairIncompleteToolMessages(listOf(node)))
    }

    @Test fun `repair preserves fresh automatic siblings and ordinary approval`() {
        val pendingRoot = UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Tool("pending", "root_exec", "{}", approvalState = ToolApprovalState.Pending))).toMessageNode()
        val unstartedOther = UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Tool("auto", "other_tool", "{}", approvalState = ToolApprovalState.Auto))).toMessageNode()
        val approvedRoot = UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Tool("approved", "root_exec", "{}", approvalState = ToolApprovalState.Approved))).toMessageNode()
        assertEquals(listOf(unstartedOther, approvedRoot), repairIncompleteToolMessages(listOf(pendingRoot, unstartedOther, approvedRoot)))
    }

    @Test fun `repair preserves interrupted automatic and approved non root attempts with pending siblings`() {
        for (state in listOf(ToolApprovalState.Auto, ToolApprovalState.Approved)) {
            val started = UIMessagePart.Tool("started", "workspace_write_file", "{}",
                output = listOf(UIMessagePart.Text("tool_execution_indeterminate")), approvalState = state)
            val pending = UIMessagePart.Tool("pending", "workspace_write_file", "{}", approvalState = ToolApprovalState.Pending)
            val node = UIMessage.assistant("").copy(parts = listOf(started, pending)).toMessageNode()
            assertEquals(listOf(node), repairIncompleteToolMessages(listOf(node)))
        }
    }

    @Test fun `repair preserves automatic workspace tool for live permission recheck`() {
        val node = UIMessage.assistant("").copy(parts = listOf(
            UIMessagePart.Tool("write", "workspace_write_file", "{}", approvalState = ToolApprovalState.Auto),
        )).toMessageNode()
        assertEquals(listOf(node), repairIncompleteToolMessages(listOf(node)))
    }

    @Test
    fun `fork conversation inherits folder and workspace context`() {
        val source = Conversation(
            assistantId = Uuid.random(),
            title = "Source conversation",
            messageNodes = emptyList(),
            config = ConversationConfig(chatModelId = Uuid.random(), reasoningLevel = ReasoningLevel.HIGH),
            workspaceCwd = "/workspace/project",
            folderId = Uuid.random(),
        )

        val fork = createForkConversation(source, emptyList())

        assertNotEquals(source.id, fork.id)
        assertEquals(source.assistantId, fork.assistantId)
        assertEquals(source.config, fork.config)
        assertEquals(source.workspaceCwd, fork.workspaceCwd)
        assertEquals(source.folderId, fork.folderId)
        assertEquals("Source conversation(1)", fork.title)
        assertFalse(fork.isPinned)
    }

    @Test
    fun `context checkpoint is inserted after the anchor node without touching other nodes`() {
        val nodes = List(4) { UIMessage.user("message $it").toMessageNode() }
        val source = Conversation(assistantId = Uuid.random(), messageNodes = nodes)

        val result = insertContextCheckpoint(source, afterNodeId = nodes[1].id, summary = "summary")!!

        assertEquals(nodes.subList(0, 2), result.messageNodes.subList(0, 2))
        assertEquals(nodes.subList(2, 4), result.messageNodes.subList(3, 5))
        val checkpoint = result.messageNodes[2].currentMessage
        assertTrue(checkpoint.isContextCheckpoint)
        assertEquals("summary", checkpoint.toText())
        // 只有检查点之后的消息会继续发送给模型
        assertEquals(result.currentMessages.subList(2, 5), result.currentMessages.limitContext(0))
    }

    @Test
    fun `context checkpoint is not inserted when the anchor node is gone`() {
        val source = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(UIMessage.user("message").toMessageNode()),
        )

        assertNull(insertContextCheckpoint(source, afterNodeId = Uuid.random(), summary = "summary"))
    }

    @Test
    fun `fork title increments existing numeric suffix instead of stacking`() {
        assertEquals("Chat(2)", forkConversationTitle("Chat(1)", emptySet()))
        assertEquals("Chat(4)", forkConversationTitle("Chat(1)", setOf("Chat(2)", "Chat(3)")))
        assertEquals("Chat(1)", forkConversationTitle("Chat", emptySet()))
        assertEquals("Chat(2)", forkConversationTitle("Chat", setOf("Chat(1)")))
        assertEquals("Chat(abc)(1)", forkConversationTitle("Chat(abc)", emptySet()))
    }

    @Test
    fun `background generation params include model custom request configuration`() {
        val headers = listOf(CustomHeader(name = "X-Gateway-Token", value = "test-token"))
        val bodies = listOf(CustomBody(key = "gateway_mode", value = JsonPrimitive("strict")))
        val model = Model(
            modelId = "custom-chat-model",
            customHeaders = headers,
            customBodies = bodies,
        )

        val conversationId = Uuid.random()
        val params = backgroundTextGenerationParams(model, conversationId)

        assertEquals(model, params.model)
        assertEquals(ReasoningLevel.AUTO, params.reasoningLevel)
        assertEquals(headers, params.customHeaders)
        assertEquals(bodies, params.customBody)
        assertEquals(conversationId.toString(), params.sessionId)
    }

    @Test
    fun `external web search is disabled when assistant preference is disabled`() {
        val assistant = Assistant(enableWebSearch = false)
        val model = Model()

        assertFalse(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `external web search is enabled when assistant preference is enabled`() {
        val assistant = Assistant(enableWebSearch = true)
        val model = Model()

        assertTrue(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `built-in search suppresses enabled external web search`() {
        val assistant = Assistant(enableWebSearch = true)
        val model = Model(tools = setOf(BuiltInTools.Search))

        assertFalse(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `built-in search remains exclusive when external web search is disabled`() {
        val assistant = Assistant(enableWebSearch = false)
        val model = Model(tools = setOf(BuiltInTools.Search))

        assertFalse(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `unrelated built-in tools do not suppress external web search`() {
        val assistant = Assistant(enableWebSearch = true)
        val model = Model(tools = setOf(BuiltInTools.UrlContext))

        assertTrue(shouldUseExternalWebSearch(assistant, model))
    }
}

/** Exercises the background SDK request boundary without constructing Android ChatService. */
class BudgetedBackgroundGenerationTest {
    private val setting: ProviderSetting = ProviderSetting.OpenAI()
    private val model = Model(modelId = "background-test")

    @Test fun `cancellation survives a failed cleanup write and later requests stay blocked`() = runBlocking {
        var writes = 0
        val budget = TokenBudgetLedger(enabled = true, hardCap = 100,
            durableCallback = { _, _ -> if (++writes > 1) throw IOException("Cleanup write failed") })
        val cancelled = kotlinx.coroutines.CancellationException("Request cancelled")
        val provider = RecordingProvider { _, _ -> throw cancelled }
        try {
            generateBackgroundWithBudget(provider, setting, listOf(UIMessage.user("title")),
                TextGenerationParams(model = model, maxTokens = 20), budget)
            fail("Cancellation was swallowed")
        } catch (error: kotlinx.coroutines.CancellationException) {
            assertTrue(error === cancelled)
            assertTrue(error.suppressed.any { it is me.rerere.rikkahub.costguards.TokenBudgetPersistenceException })
        }
        try {
            generateBackgroundWithBudget(provider, setting, listOf(UIMessage.user("title")),
                TextGenerationParams(model = model), budget)
            fail("A failed durable write allowed another request")
        } catch (_: me.rerere.rikkahub.costguards.TokenBudgetPersistenceException) { }
        assertEquals(1, provider.requests.size)
    }

    @Test fun `disabled cost controls never write or block background requests`() = runBlocking {
        var writes = 0
        val budget = TokenBudgetLedger(enabled = false, hardCap = 1,
            durableCallback = { _, _ -> writes++; throw IOException("Unavailable budget storage") })
        val provider = RecordingProvider { _, _ ->
            TextGenerationResult("uncontrolled", "background-test", UIMessage.assistant("title"), usage = TokenUsage(5, 5))
        }
        val params = TextGenerationParams(model = model, maxTokens = 50,
            customBody = listOf(CustomBody("max_tokens", JsonPrimitive(80))))
        assertEquals("title", generateBackgroundWithBudget(provider, setting,
            listOf(UIMessage.user("title")), params, budget).message.toText())
        assertEquals(0, writes)
        assertEquals(params, provider.requests.single().second)
    }

    private class RecordingProvider(
        private val response: suspend (List<UIMessage>, TextGenerationParams) -> TextGenerationResult,
    ) : Provider<ProviderSetting> {
        val requests = mutableListOf<Pair<List<UIMessage>, TextGenerationParams>>()
        override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = emptyList()
        override suspend fun streamText(providerSetting: ProviderSetting, messages: List<UIMessage>,
            params: TextGenerationParams): Flow<StreamChunk> = error("Background requests must not stream")
        override suspend fun generateText(providerSetting: ProviderSetting, messages: List<UIMessage>,
            params: TextGenerationParams): TextGenerationResult {
            requests.add(messages to params)
            return response(messages, params)
        }
    }

    @Test fun `title compression and suggestion requests share usage and cap request overrides`() = runBlocking {
        val budget = TokenBudgetLedger(enabled = true, hardCap = 300)
        val provider = RecordingProvider { messages, _ ->
            TextGenerationResult(id = "response", model = "background-test", message = UIMessage.assistant("generated"),
                usage = TokenUsage(promptTokens = AutoContextCompression.estimateTokens(messages).toInt(), completionTokens = 10))
        }
        val params = TextGenerationParams(model = model, maxTokens = 9999, customBody = listOf(
            CustomBody("max_tokens", JsonPrimitive(99999)),
            CustomBody("max_completion_tokens", JsonPrimitive(99999)),
            CustomBody("generationConfig", buildJsonObject { put("maxOutputTokens", 99999) }),
            CustomBody("gateway_mode", JsonPrimitive("strict")),
        ))
        var expectedSpent = 0L
        val prompts = listOf("Suggest a short conversation title", "Compress the old messages into a reusable summary", "Suggest three follow-up questions")
        for (prompt in prompts) {
            val messages = listOf(UIMessage.user(prompt))
            val estimate = AutoContextCompression.estimateTokens(messages)
            val expectedMax = (300L - expectedSpent - estimate).toInt()
            val result = generateBackgroundWithBudget(provider, setting, messages, params, budget)
            assertEquals("generated", result.message.toText())
            val sent = provider.requests.last()
            assertEquals(messages, sent.first)
            assertEquals(expectedMax, sent.second.maxTokens)
            val overrides = sent.second.customBody.associate { it.key to it.value }
            assertEquals(expectedMax, overrides.getValue("max_tokens").jsonPrimitive.int)
            assertEquals(expectedMax, overrides.getValue("max_completion_tokens").jsonPrimitive.int)
            assertEquals(expectedMax, overrides.getValue("generationConfig").jsonObject.getValue("maxOutputTokens").jsonPrimitive.int)
            assertEquals(JsonPrimitive("strict"), overrides.getValue("gateway_mode"))
            expectedSpent += estimate + 10L
            assertEquals(expectedSpent, budget.snapshot().spentTokens)
            assertEquals(0L, budget.snapshot().reservedTokens)
        }
        assertEquals(3, provider.requests.size)
        assertEquals(300L - expectedSpent, budget.snapshot().remainingTokens)
        // The shared params/model configuration must remain reusable and unmodified.
        assertEquals(9999, params.maxTokens)
        assertEquals(JsonPrimitive(99999), params.customBody.first().value)
    }

    @Test fun `exhausted history and unaffordable input stop before the provider`() = runBlocking {
        for (spent in listOf(100, 90)) {
            val budget = TokenBudgetLedger(enabled = true, hardCap = 100,
                initialMessages = listOf(UIMessage.assistant("history").copy(usage = TokenUsage(promptTokens = spent))))
            val provider = RecordingProvider { _, _ ->
                TextGenerationResult("unexpected", "background-test", UIMessage.assistant("unexpected"), usage = TokenUsage(1, 1))
            }
            try {
                generateBackgroundWithBudget(provider, setting, listOf(UIMessage.user("next title")),
                    TextGenerationParams(model = model), budget)
                fail("A background request crossed the hard budget")
            } catch (_: TokenBudgetExceededException) { }
            assertTrue(provider.requests.isEmpty())
            assertEquals(spent.toLong(), budget.snapshot().spentTokens)
            assertEquals(0L, budget.snapshot().reservedTokens)
        }
    }

    @Test fun `failed and unmetered paid requests consume reservation and block hidden retries`() = runBlocking {
        val messages = listOf(UIMessage.user("background compression"))
        val cap = AutoContextCompression.estimateTokens(messages).toInt() + 20
        for (failRequest in listOf(true, false)) {
            val budget = TokenBudgetLedger(enabled = true, hardCap = cap)
            val provider = RecordingProvider { _, _ ->
                if (failRequest) throw IOException("Provider request failed after submission")
                TextGenerationResult("unmetered", "background-test", UIMessage.assistant("summary"), usage = null)
            }
            val params = TextGenerationParams(model = model, maxTokens = 20)
            if (failRequest) {
                try {
                    generateBackgroundWithBudget(provider, setting, messages, params, budget)
                    fail("The provider failure was swallowed")
                } catch (_: IOException) { }
            } else {
                assertEquals("summary", generateBackgroundWithBudget(provider, setting, messages, params, budget).message.toText())
            }
            assertEquals(cap.toLong(), budget.snapshot().spentTokens)
            assertEquals(0L, budget.snapshot().reservedTokens)
            assertEquals(0L, budget.snapshot().remainingTokens)
            try {
                generateBackgroundWithBudget(provider, setting, messages, params, budget)
                fail("Retry evaded the shared background budget")
            } catch (_: TokenBudgetExceededException) { }
            assertEquals(1, provider.requests.size)
        }
    }
}
