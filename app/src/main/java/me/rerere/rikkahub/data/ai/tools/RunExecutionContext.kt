package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.root.RootCommandGuard
import me.rerere.rikkahub.root.RootApprovalPolicy
import me.rerere.rikkahub.costguards.TokenBudgetLedger
import me.rerere.rikkahub.data.model.ConversationConfig
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.uuid.Uuid

/** Trusted application state. Never decoded from model-controlled arguments or restored grants. */
data class RunExecutionContext(
    val runId: Uuid,
    val ownerAssistantId: Uuid,
    val origin: RunOrigin,
    val callerConversationId: Uuid? = null,
    val callerConversationConfig: ConversationConfig? = null,
    val workspaceCwd: String? = null,
    val modelId: String? = null,
    val allowedTools: Set<String>? = null,
    val maxSteps: Int = 12,
    val timeoutMillis: Long = 480_000,
    val webTaint: RunWebTaint = RunWebTaint(),
    val costBudget: TokenBudgetLedger? = null,
    val executionState: RunExecutionState = RunExecutionState(),
    val runStillAllowed: suspend () -> Boolean = { true },
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RunExecutionContext>
}
enum class RunOrigin { SUB_AGENT, CRON }
class RunExecutionState {
    @Volatile var effectsInFlight: Boolean = false
        internal set
}
class RunWebTaint(initial: Boolean = false, private val parent: RunWebTaint? = null) {
    private val tainted = AtomicBoolean(initial)
    fun isTainted(): Boolean = tainted.get() || parent?.isTainted() == true
    fun mark() { tainted.set(true); parent?.mark() }
}
object HeadlessToolPolicy {
    private val interactive = setOf(
        "ask_user", "grant_directory_access", "verify_fingerprint", "take_photo", "record_audio",
        "speech_to_text", "share", "open_file", "open_url", "launch_app", "launch_activity",
        "create_calendar_event", "create_contact", "send_email_intent", "send_sms_intent",
        "open_wifi_settings", "show_location_on_map", "nfc_read_tag", "nfc_write_tag", "subagent_dispatch",
        "get_screen_time", "keystore_encrypt", "keystore_decrypt",
    )
    fun blockReason(name: String, arguments: JsonElement): String? {
        val command = if (name in setOf("root_exec", "workspace_shell", "workspace_background_start"))
            ((arguments as? JsonObject)?.get("command") as? JsonPrimitive)?.takeIf { it.isString }?.content else null
        return when {
            name in interactive -> "interactive_tool"
            command != null && RootCommandGuard.check(command) != null -> "root_command_blocked"
            command != null && RootApprovalPolicy.reason(command) != null -> "mandatory_confirmation"
            ToolPermissionPolicy.mandatoryConfirmation(name, arguments) -> "mandatory_confirmation"
            else -> null
        }
    }
}
