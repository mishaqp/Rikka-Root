package me.rerere.rikkahub.root

import me.rerere.rikkahub.data.ai.tools.HardlineCommandGuard.CMD_POS

/** Accidental-destruction floor, not a sandbox or an adversarial shell parser. Approval remains mandatory. */
internal object RootCommandGuard {
    private val rules = listOf(
        Regex("\\brm\\s+(?:-[^\\s]+\\s+)*(?:/|/\\*|/system(?:/\\S*)?|/vendor(?:/\\S*)?|/product(?:/\\S*)?|/boot(?:/\\S*)?|/etc(?:/\\S*)?|/usr(?:/\\S*)?|/bin(?:/\\S*)?|/sbin(?:/\\S*)?|/lib(?:/\\S*)?|/data(?:/system(?:/\\S*)?|/\\*?)?)(?=\\s|[\"';&|)}]|$)", RegexOption.IGNORE_CASE) to "Удаление защищённых системных файлов запрещено. Выберите конкретные пользовательские файлы.",
        Regex("\\bdd\\b[^\\n]*\\bof=/dev/(?:block/\\S*|(?:sd|nvme|mmcblk|vd|hd)\\S*)", RegexOption.IGNORE_CASE) to "Запись напрямую в раздел устройства запрещена. Используйте обычный файл.",
        Regex(">\\s*/dev/(?:block/\\S*|(?:sd|nvme|mmcblk|vd|hd)\\S*)", RegexOption.IGNORE_CASE) to "Запись напрямую в раздел устройства запрещена. Используйте обычный файл.",
        Regex(CMD_POS + "(?:mkfs(?:\\.[\\w]+)?|reboot|shutdown|halt|poweroff)\\b", RegexOption.IGNORE_CASE) to "Форматирование или остановка устройства запрещены. Выполните это вручную, если необходимо.",
        Regex(CMD_POS + "(?:svc|cmd)\\s+power\\s+(?:reboot|shutdown)\\b", RegexOption.IGNORE_CASE) to "Перезагрузка или остановка устройства запрещены. Выполните это вручную, если необходимо.",
        Regex(CMD_POS + "setprop\\s+sys\\.powerctl\\b", RegexOption.IGNORE_CASE) to "Изменение режима питания устройства запрещено. Выполните это вручную, если необходимо.",
        Regex(":\\(\\)\\s*\\{\\s*:\\s*\\|\\s*:\\s*&\\s*\\}\\s*;\\s*:") to "Команда создаёт неограниченное число процессов. Используйте команду с ограниченным числом процессов.",
        Regex("\\bkill\\s+(?:-[^\\s]+\\s+)*-1\\b") to "Остановка всех процессов запрещена. Укажите конкретный процесс.",
        Regex("\\b(?:base64|xxd)\\s+[^|]*-[dr]\\b[^|]*\\|\\s*(?:sh|bash|ash|eval)\\b", RegexOption.IGNORE_CASE) to "Запуск скрытой команды запрещён. Передайте команду открытым текстом.",
        Regex("\\beval\\s+\\\$\\(") to "Запуск сгенерированной shell-команды запрещён. Передайте команду открытым текстом.",
    )

    fun check(command: String): String? {
        // Quotes around an absolute path do not make deleting it less destructive.
        val normalised = me.rerere.rikkahub.data.ai.tools.HardlineCommandGuard.neutraliseQuotedSeparators(command).replace(Regex("([\"'])(/[^\\s\"']+)\\1")) { it.groupValues[2] }
        return rules.firstOrNull { (pattern, _) -> pattern.containsMatchIn(normalised) }?.second
    }
}
