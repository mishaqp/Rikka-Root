package me.rerere.rikkahub.root

/** Accidental-destruction floor, not a sandbox or an adversarial shell parser. Approval remains mandatory. */
internal object RootCommandGuard {
    private const val COMMAND = "(?:^|[;&|\\n`]|\\\$\\(|\\b(?:sh|bash|ash)\\s+-c\\s+[\"'])\\s*(?:(?:su|sudo)\\s+(?:-[^\\s]+\\s+)*)?(?:[\\w./_-]*/)?"
    private val rules = listOf(
        Regex("\\brm\\s+(?:-[^\\s]+\\s+)*(?:/|/\\*|/system(?:/\\S*)?|/vendor(?:/\\S*)?|/product(?:/\\S*)?|/boot(?:/\\S*)?|/etc(?:/\\S*)?|/usr(?:/\\S*)?|/bin(?:/\\S*)?|/sbin(?:/\\S*)?|/lib(?:/\\S*)?|/data(?:/system(?:/\\S*)?|/\\*?)?)(?=\\s|[\"']|$)", RegexOption.IGNORE_CASE) to "delete protected Android/system paths",
        Regex("\\bdd\\b[^\\n]*\\bof=/dev/(?:block/\\S*|(?:sd|nvme|mmcblk|vd|hd)\\S*)", RegexOption.IGNORE_CASE) to "overwrite a raw block device",
        Regex(">\\s*/dev/(?:block/\\S*|(?:sd|nvme|mmcblk|vd|hd)\\S*)", RegexOption.IGNORE_CASE) to "overwrite a raw block device",
        Regex(COMMAND + "(?:mkfs(?:\\.[\\w]+)?|reboot|shutdown|halt|poweroff)\\b", RegexOption.IGNORE_CASE) to "format or stop the device",
        Regex(":\\(\\)\\s*\\{\\s*:\\s*\\|\\s*:\\s*&\\s*\\}\\s*;\\s*:") to "fork bomb",
        Regex("\\bkill\\s+(?:-[^\\s]+\\s+)*-1\\b") to "kill all processes",
        Regex("\\b(?:base64|xxd)\\s+[^|]*-[dr]\\b[^|]*\\|\\s*(?:sh|bash|ash|eval)\\b", RegexOption.IGNORE_CASE) to "execute an encoded shell payload",
        Regex("\\beval\\s+\\\$\\(") to "execute generated shell code",
    )

    fun check(command: String): String? {
        // Quotes around an absolute path do not make deleting it less destructive.
        val normalised = command.replace(Regex("([\"'])(/[^\\s\"']+)\\1")) { it.groupValues[2] }
        return rules.firstOrNull { (pattern, _) -> pattern.containsMatchIn(normalised) }?.second
    }
}
