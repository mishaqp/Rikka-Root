package me.rerere.rikkahub.data.ai

import android.content.ContextWrapper
import java.io.File
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.rikkahub.data.ai.tools.local.buildRootTool
import me.rerere.rikkahub.data.ai.tools.protectToolArguments
import me.rerere.rikkahub.data.ssh.sanitizeSshToolMessages
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.root.RootShellManager
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** SSE -> decoder -> message merge/persistence -> canonical args -> root tool -> real sh. */
class GenerationLoopRootCommandPreservationTest {
    @get:Rule val folder = TemporaryFolder()

    @Test(timeout = 15_000) fun `streamed command reaches shell and stored card with all whitespace intact`() = runBlocking {
        val outputFile = File(folder.root, "result file")
        val command = "\n  printf '[%s]\\n' 'a b'\n" +
            "printf '%s\\n' \"quoted \\\"value\\\"\"\n" +
            "printf '%s\\n' 'error value' >&2\n" +
            "printf '%s\\n' 'file value' > '${outputFile.path}'\n" +
            "cat '${outputFile.path}' 2>/dev/null\n  "
        val card = execute(command)
        assertEquals(command, Json.parseToJsonElement(card.input).jsonObject["command"]!!.jsonPrimitive.content)
        val result = Json.parseToJsonElement((card.output.single() as UIMessagePart.Text).text).jsonObject
        assertEquals(JsonPrimitive(true), result["success"])
        assertEquals(JsonPrimitive(0), result["exit_code"])
        assertEquals("[a b]\nquoted \"value\"\nfile value\n", result["stdout"]!!.jsonPrimitive.content)
        assertEquals("error value\n", result["stderr"]!!.jsonPrimitive.content)
        assertEquals("file value\n", outputFile.readText())
    }

    @Test(timeout = 15_000) fun `already glued input is executed unchanged and stays a real failure`() = runBlocking {
        val card = execute("sleep40")
        assertEquals("sleep40", Json.parseToJsonElement(card.input).jsonObject["command"]!!.jsonPrimitive.content)
        val result = Json.parseToJsonElement((card.output.single() as UIMessagePart.Text).text).jsonObject
        assertEquals(JsonPrimitive(false), result["success"])
        assertEquals(JsonPrimitive(127), result["exit_code"])
        assertTrue(result["stderr"]!!.jsonPrimitive.content.contains("sleep40"))
    }

    @Test(timeout = 15_000) fun `approved command resumes from persisted arguments without changing shell input`() = runBlocking {
        val command = " \n\tprintf '[%s]\\n' 'a  b' 2>/dev/null\n  "
        val card = execute(command, resume = true)
        assertEquals(command, Json.parseToJsonElement(card.input).jsonObject["command"]!!.jsonPrimitive.content)
        val result = Json.parseToJsonElement((card.output.single() as UIMessagePart.Text).text).jsonObject
        assertEquals(JsonPrimitive(0), result["exit_code"])
        assertEquals("[a  b]\n", result["stdout"]!!.jsonPrimitive.content)
    }

    private suspend fun execute(command: String, resume: Boolean = false): UIMessagePart.Tool {
        val context = object : ContextWrapper(null) {
            override fun getCacheDir() = folder.root
            override fun getFilesDir() = folder.root
        }
        val bin = folder.newFolder()
        val shellInput = File(bin, "received-command")
        File(bin, "id").apply { writeText("#!/bin/sh\nprintf '0\\n'\n"); check(setExecutable(true)) }
        File(bin, "sh").apply {
            writeText("#!/bin/sh\nif [ \"\$1\" = '-c' ]; then printf '%s' \"\$2\" > '${shellInput.path}'; fi\nexec /bin/sh \"\$@\"\n")
            check(setExecutable(true))
        }
        val su = folder.newFile().apply {
            writeText("#!/bin/sh\nPATH='${bin.path}':\$PATH\nexport PATH\nexec /bin/sh -c \"\$2\"\n")
            check(setExecutable(true))
        }
        val manager = RootShellManager(suExecutable = su.path, controlDirectory = folder.root)
        val tool = buildRootTool(manager)
        val arguments = buildJsonObject { put("command", command); put("timeout_ms", 2_000) }.toString()
        fun event(delta: JsonObject, finish: String? = null) = "data: " + buildJsonObject {
            put("id", "reply"); put("model", "kimi-k3")
            putJsonArray("choices") { addJsonObject {
                put("index", 0); put("delta", delta)
                if (finish != null) put("finish_reason", finish)
            } }
        } + "\n\n"
        // Every space is its own argument fragment. Newline/quote JSON escapes are split as well.
        val wire = buildString {
            arguments.forEachIndexed { index, character ->
                append(event(buildJsonObject { putJsonArray("tool_calls") { addJsonObject {
                    put("index", 0)
                    if (index == 0) { put("id", "root-call"); put("type", "function") }
                    putJsonObject("function") {
                        if (index == 0) put("name", tool.name)
                        put("arguments", character.toString())
                    }
                } } }))
            }
            append(event(buildJsonObject {}, "tool_calls")); append("data: [DONE]\n\n")
        }
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            val body = if (requests == 1 && !resume) wire else event(buildJsonObject { put("content", "Проверено") }, "stop") + "data: [DONE]\n\n"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        try {
            val model = Model(modelId = "kimi-k3", abilities = listOf(ModelAbility.TOOL))
            val owner = Assistant(chatModelId = model.id, streamOutput = true)
            val provider = ProviderSetting.OpenAI(models = listOf(model), baseUrl = "https://fixture.invalid/v1")
            val settings = Settings(providers = listOf(provider), assistants = listOf(owner))
            val json = Json { ignoreUnknownKeys = true }
            val initial = listOf(UIMessage.user("Проверь")) + if (resume) listOf(UIMessage.assistant("").copy(parts = listOf(
                UIMessagePart.Tool("root-call", tool.name, arguments, approvalState = ToolApprovalState.Approved,
                    providerResponseComplete = true),
            ))) else emptyList()
            val restored = persist(initial)
            var latest = emptyList<UIMessage>()
            GenerationLoop(context, ProviderManager(client, context), json).generateText(settings, model,
                restored, assistant = owner, tools = listOf(tool), maxSteps = 2,
                isToolAutoApproved = { _, _ -> true }).collect { chunk -> when (chunk) {
                    is GenerationChunk.Messages -> latest = persist(chunk.messages)
                    is GenerationChunk.RootExecutionCheckpoint -> {
                        latest = persist(chunk.messages); chunk.ack.complete(Unit)
                    }
                } }
            assertEquals(if (resume) 1 else 2, requests)
            assertEquals("The original command must be the exact argument of inner sh -c", command, shellInput.readText())
            return latest.flatMap { it.getTools() }.single()
        } finally {
            manager.close(); client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll()
        }
    }

    /** Same sanitizer and JSON settings as ConversationRepository.saveMessageNodes/loadMessageNodes. */
    private fun persist(messages: List<UIMessage>): List<UIMessage> = JsonInstant.decodeFromString(
        JsonInstant.encodeToString(sanitizeSshToolMessages(messages) { name, input ->
            protectToolArguments(name, input, null, null)
        }),
    )
}
