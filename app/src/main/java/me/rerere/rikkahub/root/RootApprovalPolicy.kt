package me.rerere.rikkahub.root

data class RootApprovalRule(val id: String, val description: String, val examples: List<String>)

object RootApprovalPolicy {
    val rules: List<RootApprovalRule> = listOf(
        RootApprovalRule("protected_delete", "Recursively delete an entire protected root/data/system/shared-storage directory.", listOf("rm -rf /", "rm -rf /sdcard/*")),
        RootApprovalRule("filesystem_format", "Format a filesystem.", listOf("mkfs.ext4 /dev/block/by-name/userdata")),
        RootApprovalRule("block_write", "Write directly to a raw block device.", listOf("dd if=image of=/dev/block/by-name/boot", "echo data > /dev/block/by-name/boot")),
        RootApprovalRule("bootloader", "Flash, unlock, or switch a boot slot / enter the bootloader.", listOf("fastboot flash boot image", "reboot bootloader")),
        RootApprovalRule("factory_reset", "Factory-reset or wipe device data.", listOf("recovery --wipe_data", "cmd recovery wipe")),
        RootApprovalRule("selinux_disable", "Disable SELinux enforcement.", listOf("setenforce 0")),
        RootApprovalRule("verified_boot_disable", "Disable verified boot or filesystem verity.", listOf("avbctl disable-verity", "avbctl disable-verification")),
        RootApprovalRule("protected_remount", "Remount a working root/data/system/vendor/product partition.", listOf("mount -o remount,rw /system")),
    )

    /**
     * Inspect only visible outer-shell words. This is deliberately not a sandbox: script contents,
     * eval/sh -c payloads and computed paths are opaque, as selected by the user.
     */
    fun reason(command: String): String? {
        for (segment in visibleRootCommands(command)) {
            if (visibleBlockWrite(segment)) return rules.first { it.id == "block_write" }.description
            val words = segment.dropWhile { it.literal && ASSIGNMENT.matches(it.text) }
            val effective = unwrapLiteralCommands(words)
            val name = effective.firstOrNull()?.takeIf { it.literal }?.text?.substringAfterLast('/') ?: continue
            val args = effective.drop(1).filter { it.literal }.map { it.text }
            val ruleId = when {
                name == "rm" && args.any { it == "--recursive" || (it.startsWith("-") && !it.startsWith("--") && ('r' in it || 'R' in it)) } &&
                    args.any(::isProtectedWhole) -> "protected_delete"
                name == "mkfs" || name.startsWith("mkfs.") -> "filesystem_format"
                name == "dd" && args.any { it.startsWith("of=") && isBlock(it.substringAfter("of=")) } ||
                    name == "tee" && args.any(::isBlock) -> "block_write"
                name == "fastboot" && args.any { it in setOf("flash", "flashall", "update", "unlock", "unlock_critical", "lock", "lock_critical", "set_active", "--set-active") || it.startsWith("--set-active=") } ||
                    name == "bootctl" && "set-active-boot-slot" in args ||
                    name == "reboot" && args.any { it in setOf("bootloader", "fastboot") } -> "bootloader"
                name == "recovery" && args.any { it in setOf("--wipe_data", "--wipe-data", "--factory_reset") } ||
                    name == "fastboot" && args.any { it in setOf("-w", "erase", "wipe", "format") || it.startsWith("format:") } ||
                    name == "cmd" && args.firstOrNull() == "recovery" && args.any { it in setOf("wipe", "wipe-data", "factory-reset") } ||
                    name == "am" && args.any { it in setOf("android.intent.action.MASTER_CLEAR", "android.intent.action.FACTORY_RESET") } -> "factory_reset"
                name == "setenforce" && args.firstOrNull() in setOf("0", "Permissive", "permissive") -> "selinux_disable"
                name == "avbctl" && args.firstOrNull() in setOf("disable-verity", "disable-verification") -> "verified_boot_disable"
                name == "mount" && args.any { it.split(',').contains("remount") || it.removePrefix("-o").split(',').contains("remount") } &&
                    args.any { normalisePath(it) in REMOUNT_PATHS } -> "protected_remount"
                else -> null
            }
            if (ruleId != null) return rules.first { it.id == ruleId }.description
        }
        return null
    }

    private fun visibleBlockWrite(words: List<RootShellWord>): Boolean = words.indices.any { index ->
        words[index].operator && words[index].text in setOf(">", ">>") &&
            words.getOrNull(index + 1)?.let { it.literal && isBlock(it.text) } == true
    }

    private fun unwrapLiteralCommands(words: List<RootShellWord>): List<RootShellWord> {
        var remaining = words
        while (remaining.firstOrNull()?.literal == true) {
            val name = remaining.first().text.substringAfterLast('/')
            if (name !in setOf("env", "command", "exec", "toybox", "busybox", "do", "then", "else", "if", "while")) break
            remaining = remaining.drop(1)
            if (name == "env") {
                if (remaining.firstOrNull()?.text in setOf("-S", "--split-string")) return emptyList()
                remaining = remaining.dropWhile { it.literal && (ASSIGNMENT.matches(it.text) || it.text in setOf("-i", "--ignore-environment", "--")) }
            } else if (name == "command") {
                if (remaining.firstOrNull()?.text in setOf("-v", "-V")) return emptyList()
                remaining = remaining.dropWhile { it.literal && it.text == "--" }
            } else if (name == "exec") {
                if (remaining.firstOrNull()?.text == "-a") remaining = remaining.drop(2)
                remaining = remaining.dropWhile { it.literal && it.text in setOf("-c", "-l", "--") }
            }
        }
        return remaining
    }

    private fun isBlock(path: String): Boolean = BLOCK_PATH.matches(normalisePath(path))

    private fun isProtectedWhole(path: String): Boolean {
        val normal = normalisePath(path)
        if (normal in DELETE_PATHS) return true
        return DELETE_PATHS.any { root ->
            normal in WHOLE_GLOBS.map { suffix -> (if (root == "/") "" else root) + "/" + suffix }
        }
    }

    private val ASSIGNMENT = Regex("[A-Za-z_][A-Za-z0-9_]*=.*")
    private val DELETE_PATHS = setOf("/", "/data", "/system", "/sdcard", "/storage/emulated/0", "/data/media/0", "/storage/self/primary", "/mnt/sdcard")
    private val REMOUNT_PATHS = setOf("/", "/data", "/system", "/vendor", "/product")
    private val WHOLE_GLOBS = listOf("*", ".*", ".??*", "{*,.*}")
    private val BLOCK_PATH = Regex("/dev/(?:block(?:/.*)?|mmcblk[0-9].*|sd[a-z].*|nvme[0-9].*|vd[a-z].*|hd[a-z].*|dm-[0-9]+|mapper/.+)")
}

internal data class RootShellWord(val text: String, val literal: Boolean = true, val operator: Boolean = false)

internal fun normalisePath(value: String): String {
    if (!value.startsWith('/')) return value
    val parts = mutableListOf<String>()
    value.split('/').forEach {
        when (it) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
            else -> parts += it
        }
    }
    return "/" + parts.joinToString("/")
}

/** A small outer-shell lexer, not execution or recursive interpretation of shell payloads. */
internal fun visibleRootCommands(command: String): List<List<RootShellWord>> {
    val segments = mutableListOf<List<RootShellWord>>()
    var words = mutableListOf<RootShellWord>()
    val word = StringBuilder()
    var literal = true
    var present = false
    var quote: Char? = null
    var index = 0
    val heredocs = mutableListOf<Pair<String, Boolean>>()
    var heredocDelimiterExpected: Boolean? = null
    fun endWord() {
        if (present) {
            words += RootShellWord(word.toString(), literal)
            heredocDelimiterExpected?.let { heredocs += word.toString() to it }
            heredocDelimiterExpected = null
        }
        word.clear()
        literal = true
        present = false
    }
    fun endSegment() {
        endWord()
        if (words.isNotEmpty()) segments += words.toList()
        words = mutableListOf()
    }
    while (index < command.length) {
        val char = command[index]
        if (quote == '\'') {
            if (char == '\'') quote = null else word.append(char)
            index++
            continue
        }
        if (char == '"' || char == '\'') {
            if (quote == char) quote = null
            else if (quote == null) { quote = char; present = true }
            else word.append(char)
            index++
            continue
        }
        if (char == '\\' && index + 1 < command.length) {
            val next = command[index + 1]
            if (next != '\n') { word.append(next); present = true }
            index += 2
            continue
        }
        if (char == '$' || char.code == 96) {
            present = true
            literal = false
            word.append(char)
            index++
            if (char.code == 96) {
                while (index < command.length) {
                    val next = command[index++]
                    word.append(next)
                    if (next.code == 96) break
                }
            } else if (index < command.length && command[index] in listOf('(', '{')) {
                val open = command[index]
                val close = if (open == '(') ')' else '}'
                var depth = 0
                do {
                    val next = command[index++]
                    word.append(next)
                    if (next == open) depth++
                    if (next == close) depth--
                } while (index < command.length && depth > 0)
            }
            continue
        }
        if (quote != null) {
            word.append(char)
            index++
            continue
        }
        when {
            char == '#' && !present -> {
                while (index < command.length && command[index] != '\n') index++
            }
            char == '<' && command.getOrNull(index + 1) == '<' -> {
                endWord()
                val stripTabs = command.getOrNull(index + 2) == '-'
                words += RootShellWord(if (stripTabs) "<<-" else "<<", operator = true)
                heredocDelimiterExpected = stripTabs
                index += if (stripTabs) 3 else 2
            }
            char == '>' || char == '<' -> {
                endWord()
                val repeated = command.getOrNull(index + 1) == char
                words += RootShellWord(if (repeated) "$char$char" else char.toString(), operator = true)
                index += if (repeated) 2 else 1
            }
            char in listOf(';', '|', '&', '(', ')', '\n') -> {
                endSegment()
                index++
                if (char == '\n') {
                    // Skip only heredoc bodies; header redirections and later commands remain visible.
                    heredocs.forEach { (delimiter, stripTabs) ->
                        while (index < command.length) {
                            val end = command.indexOf('\n', index).let { if (it < 0) command.length else it }
                            val line = command.substring(index, end)
                            index = if (end < command.length) end + 1 else end
                            if ((if (stripTabs) line.trimStart('\t') else line) == delimiter) break
                        }
                    }
                    heredocs.clear()
                }
            }
            char.isWhitespace() -> { endWord(); index++ }
            else -> { word.append(char); present = true; index++ }
        }
    }
    if (quote != null) literal = false
    endSegment()
    return segments
}
