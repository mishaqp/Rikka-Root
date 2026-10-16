package me.rerere.rikkahub.service

import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.finishReasoning
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.HistoryLoadState
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File
import java.util.UUID
import kotlin.time.Clock
import kotlin.uuid.Uuid

internal const val PROCESS_INTERRUPTED_MESSAGE =
    "Прервано: приложение было закрыто. Часть действий могла выполниться. Проверьте результат и запустите действие вручную."

internal data class ChatGenerationRecoveryTicket(val conversationId: Uuid, val identity: String)

/** Only conversation UUIDs and random process/generation tokens; no messages, paths or credentials. */
internal class ChatProcessRecovery(
    private val file: File,
    private val repository: ConversationRepository,
    private val subAgents: me.rerere.rikkahub.subagent.SubAgentRegistry,
    private val reportRecoveryError: (Exception) -> Unit = {},
) {
    private val mutex = Mutex()
    private var recovered = false
    private var active = linkedMapOf<String, String>()
    private val backgroundFile = File(file.parentFile, "chat-background-executions.json")
    private var background = linkedMapOf<String, String>()

    suspend fun recoverInterrupted() = mutex.withLock {
        if (recovered) return@withLock
        withContext(Dispatchers.IO) {
            active.putAll(read(file))
            background.putAll(read(backgroundFile))
            val allRuns = try {
                subAgents.recoverInterrupted()
                subAgents.snapshot()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // Keep the independent encrypted history fail-closed without making all
                // ordinary chats unreadable. Never attach storage/decryption error details.
                reportRecoveryError(java.io.IOException(
                    "Не удалось восстановить историю субагентов. Чаты доступны. Повторите запрос или перезапустите приложение."))
                emptyList()
            }
            val restoredRuns = allRuns.filter { it.status.terminal }
            val legacyIds = repository.findInterruptedBackgroundChats().mapTo(hashSetOf()) { it.toString() }
            val ids = (legacyIds + active.filterValues { !belongsToCurrentProcess(it) }.keys +
                background.filterValues { !belongsToCurrentProcess(it) }.keys +
                restoredRuns.mapNotNull { run -> runCatching { Uuid.parse(run.owner.conversationId).toString() }.getOrNull() }).toSet()
            for (id in ids) {
                val hadActive = active[id]?.let { !belongsToCurrentProcess(it) } == true
                val hadBackground = background[id]?.let { !belongsToCurrentProcess(it) } == true
                val oldSnapshot = id in legacyIds && !belongsToCurrentProcess(active[id]) && !belongsToCurrentProcess(background[id])
                val conversation = try { repository.getConversationById(Uuid.parse(id)) }
                catch (error: Exception) {
                    if (error is CancellationException) throw error
                    // One unreadable history must not prevent recovery of other chats or replace its bytes.
                    continue
                }
                if (conversation != null) {
                    // Damaged history must retain its original bytes and recovery marker.
                    if (conversation.historyLoadState != HistoryLoadState.COMPLETE) continue
                    val finished = if (hadActive) interruptConversation(conversation, baselineMessageId(active[id])) else conversation
                    val restored = refreshBackgroundSnapshots(finished, allRuns, closeWorkspace = hadActive || hadBackground || oldSnapshot)
                    if (restored != conversation) repository.updateConversation(restored)
                }
                if (hadActive) {
                    val next = LinkedHashMap(active).apply { remove(id) }
                    write(file, next)
                    active = next
                }
                if (hadBackground) {
                    val next = LinkedHashMap(background).apply { remove(id) }
                    write(backgroundFile, next)
                    background = next
                }
            }
            recovered = true
        }
    }

    suspend fun begin(id: Uuid, baselineMessageId: Uuid? = null): ChatGenerationRecoveryTicket {
        recoverInterrupted()
        return mutex.withLock { withContext(Dispatchers.IO) {
            val baseline = baselineMessageId ?: repository.getConversationById(id)?.currentMessages?.lastOrNull()?.id
            val ticket = ChatGenerationRecoveryTicket(id, "$PROCESS_TOKEN/${Uuid.random()}/${baseline ?: ""}")
            val owned = active[id.toString()]?.lineSequence()?.filter { it.isNotBlank() }?.toList().orEmpty()
            val next = LinkedHashMap(active).apply {
                put(id.toString(), (owned + ticket.identity).joinToString("\n"))
            }
            write(file, next)
            active = next
            ticket
        } }
    }

    suspend fun finish(ticket: ChatGenerationRecoveryTicket, conversation: Conversation) = mutex.withLock { withContext(Dispatchers.IO) {
        val id = ticket.conversationId.toString()
        // Keep every owner: a cancelled successor can still be waiting for a predecessor's
        // non-cancellable cleanup. Neither operation may clear the other's durable receipt.
        val owned = active[id]?.lineSequence()?.filter { it.isNotBlank() }?.toList().orEmpty()
        if (ticket.identity !in owned) return@withContext
        val remaining = owned.filterNot { it == ticket.identity }
        val next = LinkedHashMap(active)
        if (remaining.isEmpty()) {
            val nextBackground = LinkedHashMap(background).apply {
                if (containsActiveBackground(conversation)) put(id, PROCESS_TOKEN) else remove(id)
            }
            write(backgroundFile, nextBackground)
            background = nextBackground
            next.remove(id)
        } else next[id] = remaining.joinToString("\n")
        write(file, next)
        active = next
    } }

    private fun belongsToCurrentProcess(identity: String?): Boolean = identity?.substringBefore('/') == PROCESS_TOKEN

    private fun baselineMessageId(identity: String?): Uuid? {
        // The oldest remaining owner predates any partial answer from a waiting successor.
        val pieces = identity?.lineSequence()?.firstOrNull { it.isNotBlank() }?.split('/') ?: return null
        val id = if (pieces.size >= 3) pieces[2] else pieces.getOrNull(1)
        return id?.let { runCatching { Uuid.parse(it) }.getOrNull() }
    }

    private fun read(target: File): Map<String, String> {
        val atomic = AtomicFile(target)
        if (!atomic.baseFile.exists() && !File(target.path + ".bak").exists()) return emptyMap()
        val json = JsonInstant.parseToJsonElement(atomic.openRead().bufferedReader().use { it.readText() })
        return when (json) {
            is JsonArray -> json.associate { Uuid.parse(it.jsonPrimitive.content).toString() to "legacy-process" }
            is JsonObject -> json.mapKeys { Uuid.parse(it.key).toString() }.mapValues { it.value.jsonPrimitive.content }
            else -> error("Не удалось прочитать состояние незавершённых ответов.")
        }
    }

    private fun write(target: File, ids: Map<String, String>) {
        check(target.parentFile?.let { it.isDirectory || it.mkdirs() } == true) {
            "Не удалось сохранить состояние ответа. Проверьте свободное место."
        }
        val atomic = AtomicFile(target)
        val stream = atomic.startWrite()
        try {
            stream.write(JsonObject(ids.mapValues { JsonPrimitive(it.value) }).toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
    }

    private companion object { val PROCESS_TOKEN = UUID.randomUUID().toString() }
}

/** Preserve every branch, attachment and completed result. Close pending calls without re-executing. */
internal fun interruptConversation(source: Conversation, baselineMessageId: Uuid? = null): Conversation {
    val finishedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    val lastNode = source.messageNodes.lastOrNull()
    val lastMessage = lastNode?.messages?.getOrNull(lastNode.selectIndex)
    val attachToLast = lastMessage?.role == MessageRole.ASSISTANT && lastMessage.id != baselineMessageId
    val nodes = source.messageNodes.map { node ->
        val messages = node.messages.map { message ->
            if (message.role != MessageRole.ASSISTANT) return@map message
            var changed = false
            val parts = message.parts.map { part ->
                if (part is UIMessagePart.Tool && !part.isExecuted) {
                    changed = true
                    part.copy(output = listOf(UIMessagePart.Text(buildJsonObject {
                        put("error", "process_interrupted")
                        put("message", PROCESS_INTERRUPTED_MESSAGE)
                    }.toString())))
                } else if (part is UIMessagePart.Reasoning && part.finishedAt == null) {
                    changed = true
                    part.copy(finishedAt = Clock.System.now())
                } else if (part is UIMessagePart.Tool) {
                    val output = part.output.map { result ->
                        if (result !is UIMessagePart.Text) result else {
                            val obj = runCatching { JsonInstant.parseToJsonElement(result.text) as? JsonObject }.getOrNull()
                            if (obj?.get("checkpointed_before_launch")?.jsonPrimitive?.booleanOrNull == true &&
                                obj["error"]?.jsonPrimitive?.contentOrNull in setOf("root_execution_indeterminate", "tool_execution_indeterminate")) {
                                val value = result.copy(text = JsonObject(obj + ("message" to JsonPrimitive(PROCESS_INTERRUPTED_MESSAGE))).toString())
                                if (value != result) changed = true
                                value
                            } else result
                        }
                    }
                    if (output == part.output) part else part.copy(output = output)
                } else part
            }
            val isCurrentLast = attachToLast && message.id == lastMessage?.id
            val alreadyNoted = message.parts.filterIsInstance<UIMessagePart.Text>().any { it.text == PROCESS_INTERRUPTED_MESSAGE }
            val updated = if (isCurrentLast && !alreadyNoted) parts + UIMessagePart.Text(PROCESS_INTERRUPTED_MESSAGE) else parts
            if (changed || updated !== parts) {
                message.copy(parts = updated, finishedAt = finishedAt).finishReasoning()
            } else message
        }
        if (messages == node.messages) node else node.copy(messages = messages)
    }
    return source.copy(messageNodes = if (attachToLast) nodes else nodes +
        UIMessage.assistant(PROCESS_INTERRUPTED_MESSAGE).copy(finishedAt = finishedAt).toMessageNode())
}

private fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull
private fun JsonObject.running(): Boolean = (get("running") as? JsonPrimitive)?.booleanOrNull == true
private fun nativeBackgroundTool(name: String) = name.startsWith("workspace_background_") ||
    name in setOf("subagent_dispatch", "subagent_get", "subagent_list")

private fun objects(value: JsonElement): List<JsonObject> = when (value) {
    is JsonObject -> listOf(value) + value.values.flatMap(::objects)
    is JsonArray -> value.flatMap(::objects)
    else -> emptyList()
}

private fun containsActiveBackground(conversation: Conversation): Boolean = conversation.messageNodes.any { node ->
    node.messages.any { message -> message.getTools().any { tool ->
        nativeBackgroundTool(tool.toolName) && tool.output.filterIsInstance<UIMessagePart.Text>().any { result ->
            runCatching { objects(JsonInstant.parseToJsonElement(result.text)).any { obj ->
                obj.running() || obj.text("status") in setOf("RUNNING", "PENDING")
            } }.getOrDefault(false)
        }
    } }
}

/** Saved result cards are snapshots, so bring active background receipts to their known terminal state. */
private fun refreshBackgroundSnapshots(
    source: Conversation,
    runs: List<me.rerere.rikkahub.subagent.SubAgentRun>,
    closeWorkspace: Boolean,
): Conversation {
    val ownedRuns = runs.filter { it.owner.conversationId == source.id.toString() &&
        it.owner.assistantId == source.assistantId.toString() }.associateBy { it.id }
    val completedProcesses = linkedMapOf<String, JsonObject>()
    source.messageNodes.flatMap { it.messages }.flatMap { it.getTools() }
        .filter { it.toolName.startsWith("workspace_background_") }.forEach { tool ->
            tool.output.filterIsInstance<UIMessagePart.Text>().forEach { result ->
                val root = runCatching { JsonInstant.parseToJsonElement(result.text) }.getOrNull()
                if (root != null) objects(root).filter { obj ->
                    obj.text("id") != null && (obj["running"] as? JsonPrimitive)?.booleanOrNull == false
                }.forEach { completedProcesses[it.text("id")!!] = it }
            }
        }
    fun refresh(value: JsonElement, workspace: Boolean): JsonElement = when (value) {
        is JsonArray -> JsonArray(value.map { refresh(it, workspace) })
        is JsonObject -> {
            val id = value.text("id")
            val activeSubAgent = value.text("status") in setOf("RUNNING", "PENDING")
            when {
                workspace && closeWorkspace && value.running() -> {
                    val known = completedProcesses[id]
                    if (known != null) JsonObject(value + known) else JsonObject(value + mapOf(
                        "running" to JsonPrimitive(false), "status" to JsonPrimitive("indeterminate"),
                        "error" to JsonPrimitive("process_interrupted"), "message" to JsonPrimitive(PROCESS_INTERRUPTED_MESSAGE)))
                }
                !workspace && activeSubAgent && ownedRuns[id] != null -> {
                    val known = ownedRuns.getValue(id!!)
                    if (known.status.terminal) JsonObject(value + me.rerere.rikkahub.subagent.encodeRun(known)) else value
                }
                !workspace && activeSubAgent && closeWorkspace -> JsonObject(value + mapOf(
                    "status" to JsonPrimitive("INDETERMINATE"), "error" to JsonPrimitive("process_interrupted"),
                    "message" to JsonPrimitive(PROCESS_INTERRUPTED_MESSAGE)))
                else -> JsonObject(value.mapValues { refresh(it.value, workspace) })
            }
        }
        else -> value
    }
    val nodes = source.messageNodes.map { node ->
        val messages = node.messages.map { message ->
            val parts = message.parts.map { part ->
                if (part !is UIMessagePart.Tool || !nativeBackgroundTool(part.toolName)) part else {
                    val output = part.output.map { result ->
                        if (result !is UIMessagePart.Text) result else {
                            val root = runCatching { JsonInstant.parseToJsonElement(result.text) }.getOrNull()
                            val updated = root?.let { refresh(it, part.toolName.startsWith("workspace_background_")) }
                            if (updated == null || updated == root) result else result.copy(text = updated.toString())
                        }
                    }
                    if (output == part.output) part else part.copy(output = output)
                }
            }
            if (parts == message.parts) message else message.copy(parts = parts)
        }
        if (messages == node.messages) node else node.copy(messages = messages)
    }
    return if (nodes == source.messageNodes) source else source.copy(messageNodes = nodes)
}
