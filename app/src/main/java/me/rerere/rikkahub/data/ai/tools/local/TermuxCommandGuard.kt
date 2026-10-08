package me.rerere.rikkahub.data.ai.tools.local

import me.rerere.rikkahub.root.RootCommandGuard
import me.rerere.rikkahub.root.RootApprovalPolicy

/** The same accidental-destruction floor applies to every Termux dispatch, including headless calls. */
internal fun checkTermuxCommand(executable: String, arguments: Array<String>): String? =
    RootCommandGuard.check((listOf(executable) + arguments).joinToString(" "))
        ?: arguments.withIndex().firstNotNullOfOrNull { (index, argument) ->
            if (argument == "-c") arguments.getOrNull(index + 1)?.let(RootCommandGuard::check) else null
        }

/** Keep trailing input whitespace: removing it would turn `setenforce ` + `0` into a different word. */
internal fun termuxVisibleCommandLine(screen: String): String {
    val line = screen.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty()
    return Regex("^[^\\n]*?[#$>] ?(.*)$").matchEntire(line)?.groupValues?.get(1) ?: line
}

/** Only a complete action shown in the foreground approval request may use that approval. */
internal fun termuxSessionApprovalCheck(headless: Boolean, rawInput: String?): (String) -> String? = { command ->
    RootApprovalPolicy.reason(command)?.let { reason ->
        if (headless) "mandatory_confirmation: $reason"
        else if (command.trim() == rawInput?.trim() && RootApprovalPolicy.reason(rawInput.orEmpty()) != null) null
        else "mandatory_confirmation: Составная команда требует отдельного подтверждения. Очистите строку терминала и передайте полную команду одним вызовом."
    }
}

/** Retain unfinished input so separate send calls cannot split a command around the safety floor. */
internal class TermuxSessionInputGuard {
    private val inputs = mutableMapOf<String, String>()
    private val needsScreen = mutableSetOf<String>()

    @Synchronized
    fun check(
        session: String,
        text: String?,
        keys: List<String>,
        enter: Boolean,
        currentScreen: String? = null,
        additionalCheck: (String) -> String? = { null },
    ): String? {
        fun blocked(command: String): String? = RootCommandGuard.check(command) ?: additionalCheck(command)
        var command = inputs[session].orEmpty()
        if (currentScreen != null) {
            // Another terminal client may have edited the line since the previous send.
            // The screen already contains that send, so cached input is only a fallback.
            command = termuxVisibleCommandLine(currentScreen)
        } else if (session in needsScreen && (enter || keys.any(::isEnter))) {
            return "После изменения строки терминала сначала прочитайте её через termux_session_read и повторите ввод."
        }
        command += text.orEmpty()
        blocked(command)?.let { return it }
        var unknown = session in needsScreen && currentScreen == null
        keys.forEach { key ->
            when {
                isEnter(key) -> {
                    if (unknown) return "Отправьте клавишу редактирования отдельно, затем Enter: перед запуском нужна проверка строки терминала."
                    blocked(command)?.let { return it }
                    command = ""
                }
                key == "C-c" || key == "C-u" -> { command = ""; unknown = false }
                key == "BSpace" || key == "Backspace" || key == "C-h" -> command = command.dropLast(1)
                key == "Space" -> command += " "
                key == "C-w" -> command = command.trimEnd().substringBeforeLast(' ', "")
                key.length == 1 -> command += key
                else -> unknown = true
            }
            blocked(command)?.let { return it }
        }
        if (enter) {
            if (unknown) return "Отправьте клавишу редактирования отдельно, затем Enter: перед запуском нужна проверка строки терминала."
            blocked(command)?.let { return it }
            command = ""
        }
        inputs[session] = command
        if (unknown) needsScreen.add(session) else needsScreen.remove(session)
        return null
    }

    @Synchronized
    fun pending(session: String): String = inputs[session].orEmpty()

    @Synchronized
    fun forget(session: String) {
        inputs.remove(session)
        needsScreen.remove(session)
    }

    private fun isEnter(key: String): Boolean = key in setOf("Enter", "Return", "C-m", "C-j")
}
