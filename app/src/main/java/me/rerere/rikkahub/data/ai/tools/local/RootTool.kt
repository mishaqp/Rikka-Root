package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.root.RootCommandGuard
import me.rerere.rikkahub.root.RootAccessStore
import me.rerere.rikkahub.root.RootApprovalPolicy
import me.rerere.rikkahub.root.RootInvocation
import me.rerere.rikkahub.root.RootShellManager

/** Ordinary mode retains its consent gate; automatic permission is global on this device. */
fun buildRootTool(manager: RootShellManager, accessStore: RootAccessStore? = null, assistantId: String? = null): Tool = Tool(
    name = "root_exec",
    description = "Run a non-interactive shell command on the Android device using su, after verified UID 0. Requires a rooted device and a root-manager grant. User approval is required unless global auto-approval or Always Allow is enabled on this device; visible protected operations still require approval. Returns bounded stdout/stderr, exit_code, and errors. No Shizuku or Termux fallback. Commands are never retried after execution starts. timeout_ms defaults to 30000 and is limited to 1000–300000.",
    needsApproval = { input ->
        val command = ((input as? JsonObject)?.get("command") as? JsonPrimitive)
            ?.takeIf { it.isString }?.contentOrNull
        command.isNullOrBlank() || assistantId == null || accessStore?.isAllowed("root_exec") != true ||
            RootApprovalPolicy.reason(command) != null
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject { put("type", "string"); put("description", "Android root shell command, for example id -u or dumpsys battery.") })
                put("timeout_ms", buildJsonObject { put("type", "integer"); put("description", "Command timeout in milliseconds, default 30000, range 1000–300000.") })
            },
            required = listOf("command"),
        )
    },
    execute = { input ->
        val args = input as? JsonObject
        val commandValue = args?.get("command") as? JsonPrimitive
        val command = commandValue?.takeIf { it.isString }?.contentOrNull
        val timeoutValue = args?.get("timeout_ms")
        val timeout = if (timeoutValue == null) 30_000 else (timeoutValue as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        val automaticAtStart = assistantId != null && accessStore?.isAllowed("root_exec") == true
        // Trusted invocation provenance survives revocation between journal acknowledgment and entry.
        val invocation = currentCoroutineContext()[RootInvocation]
        val automaticInvocation = invocation?.automatic ?: automaticAtStart
        val result = when {
            command.isNullOrBlank() || timeout == null -> buildJsonObject {
                put("error", "invalid_arguments")
                put("reason", "command must be a non-empty string and timeout_ms must be an integer.")
            }
            !automaticAtStart && !automaticInvocation && RootCommandGuard.check(command) != null -> buildJsonObject {
                put("error", "root_command_blocked")
                put("reason", RootCommandGuard.check(command))
            }
            else -> {
                // The loop owns Auto vs Approved and the durable non-replay checkpoint.
                // A failed journal write prevents launch; output is deliberately never journaled.
                val entryId = if (assistantId != null && accessStore != null) withContext(NonCancellable) {
                    accessStore.beginCommand(assistantId, command)
                } else null
                var exitCode: Int? = null
                var status = "failed"
                var journalError = false
                val executed = try {
                    manager.exec(command, timeout.coerceIn(1_000, 300_000)) {
                        !automaticInvocation || assistantId != null && accessStore?.isAllowed("root_exec") == true &&
                            (invocation?.automaticAllowed?.invoke() != false)
                    }.also {
                        exitCode = it.exitCode
                        status = it.error ?: if (it.exitCode == 0) "completed" else "failed"
                    }
                } catch (error: CancellationException) {
                    status = "cancelled"
                    throw error
                } finally {
                    if (entryId != null) withContext(NonCancellable) {
                        try {
                            accessStore!!.finishCommand(entryId, exitCode, status)
                        } catch (_: Exception) {
                            // Audit failure cannot replace the command's actual result or cancellation.
                            // No arbitrary exception text (or source command) is emitted here.
                            journalError = true
                        }
                    }
                }
                buildJsonObject {
                    put("backend", "root")
                    put("success", executed.error == null && executed.exitCode == 0)
                    put("stdout", executed.stdout)
                    put("stderr", executed.stderr)
                    executed.exitCode?.let { put("exit_code", it) }
                    executed.error?.let { put("error", it) }
                    executed.reason?.let { put("reason", it) }
                    if (journalError) put("journal_error", "root_journal_save_failed")
                }
            }
        }
        listOf(UIMessagePart.Text(result.toString()))
    },
)
