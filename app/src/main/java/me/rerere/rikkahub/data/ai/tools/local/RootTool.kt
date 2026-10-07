package me.rerere.rikkahub.data.ai.tools.local

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
import me.rerere.rikkahub.root.RootShellManager

/** Approval is owned here, so registering this factory never silently removes the consent gate. */
fun buildRootTool(manager: RootShellManager): Tool = Tool(
    name = "root_exec",
    description = "Run a non-interactive shell command on the Android device using su, after verified UID 0. Requires a rooted device, a root-manager grant, and user approval for every call. Returns bounded stdout/stderr, exit_code, and errors. No Shizuku or Termux fallback. Commands are never retried after execution starts. timeout_ms defaults to 30000 and is limited to 1000–300000.",
    needsApproval = { true },
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
        val result = when {
            command.isNullOrBlank() || timeout == null -> buildJsonObject {
                put("error", "invalid_arguments")
                put("reason", "command must be a non-empty string and timeout_ms must be an integer.")
            }
            RootCommandGuard.check(command) != null -> buildJsonObject {
                put("error", "root_command_blocked")
                put("reason", RootCommandGuard.check(command))
            }
            else -> {
                val executed = manager.exec(command, timeout.coerceIn(1_000, 300_000))
                buildJsonObject {
                    put("backend", "root")
                    put("success", executed.error == null && executed.exitCode == 0)
                    put("stdout", executed.stdout)
                    put("stderr", executed.stderr)
                    executed.exitCode?.let { put("exit_code", it) }
                    executed.error?.let { put("error", it) }
                    executed.reason?.let { put("reason", it) }
                }
            }
        }
        listOf(UIMessagePart.Text(result.toString()))
    },
)
