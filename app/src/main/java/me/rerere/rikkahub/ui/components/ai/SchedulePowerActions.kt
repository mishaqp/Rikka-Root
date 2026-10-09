package me.rerere.rikkahub.ui.components.ai

import me.rerere.rikkahub.root.RootCommandGuard
import me.rerere.rikkahub.root.RootProcessResult

/** Called only from the explicit Doze whitelist button, never while enabling a feature. */
internal suspend fun addScheduleToDozeWhitelist(
    packageName: String,
    execute: suspend (String, Int) -> RootProcessResult,
): RootProcessResult {
    if (!Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+").matches(packageName)) {
        return RootProcessResult(error = "invalid_package_name")
    }
    val command = "dumpsys deviceidle whitelist +$packageName"
    if (RootCommandGuard.check(command) != null) {
        return RootProcessResult(error = "root_command_blocked")
    }
    return execute(command, 20_000)
}
