package me.rerere.rikkahub.skills

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.ai.tools.RunExecutionContext
import me.rerere.rikkahub.data.ai.tools.RunOrigin
import me.rerere.rikkahub.data.model.bindConfig
import me.rerere.rikkahub.service.HeadlessTaskRequest
import me.rerere.rikkahub.service.HeadlessTaskRunner
import me.rerere.rikkahub.service.HeadlessTaskResult
import me.rerere.rikkahub.service.HeadlessTaskStatus
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.model.Conversation
import java.io.IOException
import kotlin.uuid.Uuid

/**
 * Phase 19B — run a single skill against a user-supplied prompt in isolation, harvest the
 * model's final text reply, return.
 *
 * The runner:
 *  1. reads the skill's body via [SkillManager.readSkillBody] (or the test-injected
 *     [skillBodyReader] seam)
 *  2. creates a fresh, ephemeral conversation under the user's currently-selected assistant
 *  3. supplies a fresh SKILL_TEST context to [HeadlessTaskRunner] before generation;
 *     live capabilities, persistent approval grants and HARDLINE apply to every tool
 *  4. dispatches the test prompt + the skill body inlined as the user message
 *  5. waits up to [timeoutMs] (default 2 min) for the generation flow to settle
 *  6. harvests the last assistant message's text + image parts
 *  7. cancels and releases the transient side-context run; no test transcript enters Room
 *
 * The skill body is inlined directly into the test prompt (rather than relying on the
 * `use_skill` tool surface) so the tester works regardless of whether the skill is in
 * `assistant.enabledSkills` — it's a pure "what would this prompt + this body produce"
 * smoke test.
 *
 * Hard timeout: 2 minutes by default (overridable for tests). If the generation hasn't
 * settled by then, the run returns [TestRunState.Error] with code `tester_timeout` and
 * cancels/joins the side-context run. Completed external effects cannot be rolled back.
 *
 * Testability seams: the [Driver] interface abstracts everything the runner needs from
 * the `HeadlessTaskRunner` + `SettingsStore` bindings. JVM tests
 * supply a fake driver; production wires through [defaultDriver] which delegates to the
 * Koin-provided real services. This avoids pulling Robolectric or a mocking framework
 * into the test module just for tester coverage.
 */
class SkillTestRunner(
    private val driver: Driver,
    private val skillBodyReader: (String) -> String?,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {

    /** Agent tester orchestration with Rikka-Root's live headless permission engine. */
    constructor(
        runner: HeadlessTaskRunner,
        skillManager: SkillManager,
        settingsStore: SettingsStore,
        appScope: CoroutineScope,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ) : this(
        driver = defaultDriver(runner, settingsStore, appScope),
        skillBodyReader = { name -> skillManager.readSkillBody(name) },
        timeoutMs = timeoutMs,
    )

    companion object {
        private const val TAG = "SkillTestRunner"
        const val DEFAULT_TIMEOUT_MS: Long = 2 * 60 * 1_000L

        /** Every test uses its own side context; no parent chat or Room messages are changed. */
        fun defaultDriver(
            runner: HeadlessTaskRunner,
            settingsStore: SettingsStore,
            appScope: CoroutineScope,
        ): Driver = object : Driver {
            val conversations = ConcurrentHashMap<Uuid, Conversation>()
            val jobs = ConcurrentHashMap<Uuid, Job>()
            val results = ConcurrentHashMap<Uuid, HeadlessTaskResult>()

            override suspend fun currentAssistantId(): Uuid =
                settingsStore.settingsFlow.first { !it.init }.getCurrentAssistant().id

            override suspend fun startConversation(conv: Conversation) {
                val settings = settingsStore.settingsFlow.first { !it.init }
                check(settings.assistants.any { it.id == conv.assistantId }) { "Ассистент для проверки навыка удалён." }
                conversations[conv.id] = conv.bindConfig(settings)
            }

            override fun send(conv: Conversation, parts: List<UIMessagePart>) {
                val bound = checkNotNull(conversations[conv.id])
                val job = appScope.launch(start = CoroutineStart.LAZY) {
                    val context = RunExecutionContext(
                        runId = bound.id,
                        ownerAssistantId = bound.assistantId,
                        origin = RunOrigin.SKILL_TEST,
                        callerConversationConfig = bound.config,
                        modelId = bound.config?.chatModelId?.toString(),
                        maxSteps = 12,
                        timeoutMillis = DEFAULT_TIMEOUT_MS,
                        runStillAllowed = { conversations.containsKey(bound.id) },
                    )
                    results[bound.id] = runner.run(
                        HeadlessTaskRequest.Prompt(parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }),
                        context,
                    )
                }
                jobs[bound.id] = job
                job.start()
            }

            override suspend fun awaitGenerationDone(conversationId: Uuid, timeoutMs: Long): Boolean =
                withTimeoutOrNull(timeoutMs) { checkNotNull(jobs[conversationId]).join(); true } ?: false

            override suspend fun harvest(conversationId: Uuid): HarvestResult {
                val result = checkNotNull(results[conversationId]) { "Проверка навыка завершилась без результата." }
                check(result.status == HeadlessTaskStatus.SUCCEEDED) {
                    "Проверка навыка: ${result.status}; ${result.errorCode ?: "неизвестная ошибка"}. Проверьте разрешения инструментов."
                }
                return HarvestResult(result.output.orEmpty(), result.imageUrls)
            }

            override suspend fun cleanup(conv: Conversation) {
                conversations.remove(conv.id)
                jobs.remove(conv.id)?.cancelAndJoin()
                results.remove(conv.id)
            }
        }
    }

    /**
     * Narrow seam over HeadlessTaskRunner + SettingsStore. Tests supply a
     * fake; production uses [defaultDriver]. Allows pure-JVM coverage without Robolectric.
     */
    interface Driver {
        suspend fun currentAssistantId(): Uuid
        suspend fun startConversation(conv: Conversation)
        fun send(conv: Conversation, parts: List<UIMessagePart>)
        /** Returns true if the generation finished, false if [timeoutMs] elapsed first. */
        suspend fun awaitGenerationDone(conversationId: Uuid, timeoutMs: Long): Boolean
        suspend fun harvest(conversationId: Uuid): HarvestResult
        suspend fun cleanup(conv: Conversation)
    }

    data class HarvestResult(val text: String, val imageUrls: List<String>)

    sealed class TestRunState {
        data object Idle : TestRunState()
        data class Running(val elapsedMs: Long) : TestRunState()
        data class Done(val text: String, val imageUrls: List<String>) : TestRunState()
        data class Error(val error: String, val detail: String?) : TestRunState()
    }

    /**
     * Run the skill once. Returns a cold [Flow] that emits [TestRunState.Running] when the
     * generation kicks off, then exactly one terminal state ([TestRunState.Done] or
     * [TestRunState.Error]). The flow terminates after the terminal state is emitted.
     */
    fun runOnce(skillName: String, prompt: String): Flow<TestRunState> = flow {
        // readSkillBody now enforces a size cap and throws SkillFileTooLargeException (an
        // IOException) for oversized files; reads can also fail with a plain IOException.
        // Catch both here so the body read surfaces a clean terminal Error instead of an
        // unhandled exception escaping the flow.
        val skillBody = try {
            skillBodyReader(skillName)
        } catch (e: SkillManager.SkillFileTooLargeException) {
            emit(TestRunState.Error("skill_too_large", "skill body exceeds the size cap (${e.lengthBytes} bytes)"))
            return@flow
        } catch (e: IOException) {
            emit(TestRunState.Error("read_failed", e.message ?: "skill body could not be read"))
            return@flow
        }
        if (skillBody.isNullOrBlank()) {
            emit(TestRunState.Error("missing_skill", "skill body could not be read"))
            return@flow
        }
        if (prompt.isBlank()) {
            emit(TestRunState.Error("empty_prompt", "prompt is empty"))
            return@flow
        }

        emit(TestRunState.Running(0L))

        val assistantId = driver.currentAssistantId()
        val conv = Conversation.ofId(
            id = Uuid.random(),
            assistantId = assistantId,
            newConversation = true,
        ).copy(title = "[Skill test] $skillName")

        // Bind trusted owner/config before send; the production driver supplies the
        // isolated headless context before any model/tool callback can execute.
        try {
            driver.startConversation(conv)

            val composed = buildString {
                appendLine("You are running the skill below in test mode. Apply it to the user prompt and respond as the skill instructs.")
                appendLine()
                appendLine("---- SKILL ($skillName) ----")
                appendLine(skillBody)
                appendLine("---- END SKILL ----")
                appendLine()
                appendLine("User prompt:")
                append(prompt)
            }
            driver.send(conv, listOf(UIMessagePart.Text(composed)))

            val finishedInTime = driver.awaitGenerationDone(conv.id, timeoutMs)
            if (!finishedInTime) {
                emit(TestRunState.Error("tester_timeout", "exceeded ${timeoutMs / 1_000}s cap"))
                return@flow
            }

            val harvested = driver.harvest(conv.id)
            if (harvested.text.isBlank() && harvested.imageUrls.isEmpty()) {
                emit(TestRunState.Error("no_response", "the model returned no text or image parts"))
            } else {
                emit(TestRunState.Done(harvested.text, harvested.imageUrls))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            // Log via a runCatching so JVM tests (which don't stub android.util.Log) don't
            // explode. The error envelope below carries the same info to the UI.
            runCatching { Log.w(TAG, "Skill test failed: ${t::class.simpleName}") }
            emit(TestRunState.Error(t::class.simpleName ?: "unknown", t.message))
        } finally {
            withContext(NonCancellable) { runCatching { driver.cleanup(conv) } }
        }
    }
}
