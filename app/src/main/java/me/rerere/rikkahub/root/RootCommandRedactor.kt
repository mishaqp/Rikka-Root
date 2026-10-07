package me.rerere.rikkahub.root

internal object RootCommandRedactor {
    private val names = setOf("id", "getprop", "dumpsys", "pm", "cmd", "ls", "cat", "grep", "find",
        "df", "du", "ps", "top", "uname", "whoami", "pwd", "stat", "mount", "umount", "rm", "dd",
        "tee", "mkfs", "mkfs.ext4", "mkfs.f2fs", "reboot", "fastboot", "bootctl", "recovery", "setenforce", "avbctl",
        "echo", "printf", "sh", "bash", "ash", "eval", "touch", "cp", "mv", "chmod", "chown", "am",
        "svc", "settings", "toybox", "busybox", "su", "sudo", "head", "tail", "sed", "awk", "wc",
        "sleep", "test", "true", "false", "command", "exec", "env")
    private val flags = setOf("-rf", "-fr", "-r", "-R", "-f", "-a", "-l", "-u", "-n", "-o",
        "-c", "-i", "-v", "--recursive", "--force", "--", "-w")

    fun redact(command: String): String = visibleRootCommands(command).joinToString(" ; ") { segment ->
        var expectName = true
        segment.joinToString(" ") { word ->
            val name = word.text.substringAfterLast('/')
            when {
                word.operator -> { expectName = false; word.text }
                expectName && word.literal && name in names -> {
                    expectName = name in setOf("toybox", "busybox", "command", "exec", "env", "su", "sudo")
                    name
                }
                word.literal && word.text in flags -> word.text
                else -> {
                    // Includes assignments, URLs, payload strings, printf formats and unlabelled secrets.
                    if (!word.text.matches(Regex("[A-Za-z_][A-Za-z0-9_]*=.*"))) expectName = false
                    "[redacted]"
                }
            }
        }
    }.take(2_048)
}
