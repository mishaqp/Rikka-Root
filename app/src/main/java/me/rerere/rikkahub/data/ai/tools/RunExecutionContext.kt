package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.root.RootCommandGuard
import me.rerere.rikkahub.root.RootApprovalPolicy
import me.rerere.rikkahub.costguards.TokenBudgetLedger
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.data.ai.tools.local.TermuxSessionInputGuard
import me.rerere.rikkahub.data.ai.tools.local.checkTermuxCommand
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
enum class RunOrigin { SUB_AGENT, CRON, EXTERNAL_AUTOMATION, WORKFLOW, SKILL_TEST }
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
    private val trustMutations = setOf(
        "external_automation_set_enabled", "external_automation_add_trusted_package",
        "external_automation_remove_trusted_package",
        "mcp_set_tool_approval",
        "skill_install_from_url", "skill_install_from_text",
        "workflow_create", "workflow_update", "workflow_delete", "workflow_set_enabled", "workflow_run",
    )
    private val interactive = setOf(
        "ask_user", "grant_directory_access", "verify_fingerprint", "take_photo", "record_audio",
        "speech_to_text", "share", "open_file", "open_url", "launch_app", "launch_activity",
        "create_calendar_event", "create_contact", "send_email_intent", "send_sms_intent",
        "open_wifi_settings", "show_location_on_map", "nfc_read_tag", "nfc_write_tag", "subagent_dispatch",
        "get_screen_time", "keystore_encrypt", "keystore_decrypt",
    )
    fun blockReason(name: String, arguments: JsonElement): String? {
        if (name == "browser_eval_js") {
            (arguments as? JsonObject)?.let { HardlineCommandGuard.checkToolParsed(name, it) }
                ?.let { return "hardline:$it" }
        }
        if (name in trustMutations) return "mandatory_confirmation"
        if (name in interactive) return "interactive_tool"
        val obj = arguments as? JsonObject
        fun string(key: String): String? = (obj?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun strings(key: String): List<String> = (obj?.get(key) as? JsonArray)?.mapNotNull {
            (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content
        }.orEmpty()
        val commands = when (name) {
            "ssh_exec", "ssh_exec_saved" -> string("command")?.let {
                me.rerere.rikkahub.data.ai.tools.local.sshCommandInputs(it, string("stdin"))
            }.orEmpty()
            "root_exec", "workspace_shell", "workspace_background_start",
            "termux_session_start" -> listOfNotNull(string("command"))
            "termux_run_command" -> {
                if ((obj?.get("interactive") as? JsonPrimitive)?.content == "true") return "interactive_tool"
                val command = string("command") ?: string("executable")
                val argv = strings("arguments")
                if (command != null && checkTermuxCommand(command, argv.toTypedArray()) != null) return "root_command_blocked"
                buildList {
                    command?.let { add((listOf(it) + argv).joinToString(" ")) }
                    // A separately supplied shell -c argument must retain the same
                    // mandatory-confirmation semantics as the visible command string.
                    argv.forEachIndexed { index, value -> if (value == "-c") argv.getOrNull(index + 1)?.let(::add) }
                }
            }
            "termux_session_send" -> {
                val keys = strings("keys")
                // History/completion/cursor edits can execute a line not present in the
                // model's arguments. They need a foreground user instead of auto-approval.
                if (keys.any { it.length != 1 && it !in headlessLiteralKeys }) return "interactive_tool"
                val text = string("input")
                val enter = (obj?.get("enter") as? JsonPrimitive)?.content != "false"
                // An empty Enter could execute a restored line that this run did not type.
                if (text.isNullOrEmpty() && (enter || keys.any { it in setOf("Enter", "Return", "C-m", "C-j") }) &&
                    keys.none { it in setOf("C-c", "C-u") } && keys.none { it.length == 1 || it == "Space" }) return "interactive_tool"
                if (TermuxSessionInputGuard().check("headless-preflight", text, keys, enter) != null) return "root_command_blocked"
                if (TermuxSessionInputGuard().check("headless-preflight", text, keys, enter,
                    additionalCheck = { command -> RootApprovalPolicy.reason(command) }) != null) return "mandatory_confirmation"
                listOfNotNull(text)
            }
            else -> emptyList()
        }
        return when {
            commands.any { RootCommandGuard.check(it) != null } -> "root_command_blocked"
            commands.any { RootApprovalPolicy.reason(it) != null } -> "mandatory_confirmation"
            ToolPermissionPolicy.mandatoryConfirmation(name, arguments) -> "mandatory_confirmation"
            else -> null
        }
    }
    private val headlessLiteralKeys = setOf(
        "Enter", "Return", "C-m", "C-j", "C-c", "C-u", "BSpace", "Backspace", "C-h", "Space", "C-w",
    )
}
