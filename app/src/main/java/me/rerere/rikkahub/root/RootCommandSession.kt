package me.rerere.rikkahub.root

import java.io.File

/** Private control files identify the command's session leader independently of model-visible output. */
internal class RootCommandSession(private val suExecutable: String, directory: File) : AutoCloseable {
    private val active = File.createTempFile("root-session-", ".active", directory)
    private val process = File.createTempFile("root-session-", ".pid", directory)
    private val capability = File.createTempFile("root-session-", ".capability", directory)
    var cleanupError: String? = null
        private set

    init {
        listOf(active, process, capability).forEach { file ->
            file.setReadable(false, false)
            file.setWritable(false, false)
            file.setReadable(true, true)
            file.setWritable(true, true)
        }
    }

    fun wrap(command: String): String {
        // Append opaque shell arguments after normalizing the template indentation.
        val supervisor = """
            stat=${'$'}(cat /proc/${'$'}${'$'}/stat) || exit 125
            rest=${'$'}{stat##*) }
            set -- ${'$'}rest
            shift 19
            printf '%s %s\n' "${'$'}${'$'}" "${'$'}1" > ${quote(process.path)} || exit 125
            [ -f ${quote(active.path)} ] || exit 125
        """.trimIndent() + "\nsh -c " + quote(command) + " &\n" + """
            child=${'$'}!
            wait "${'$'}child"
            exit ${'$'}?
        """.trimIndent()
        // Keep the su shell waiting for the supervisor. If it already leads a group,
        // exec setsid can fork and return 0 before the actual command has completed.
        // A child launched without job control cannot already lead that inherited group.
        return """
            [ -f ${quote(active.path)} ] || exit 125
            if [ "${'$'}(id -u)" != 0 ]; then
                printf not_root > ${quote(capability.path)}
                exit 126
            fi
            if ! command -v setsid >/dev/null 2>&1; then
                printf unavailable > ${quote(capability.path)}
                exit 127
            fi
        """.trimIndent() + "\nset +m\nsetsid sh -c " + quote(supervisor) + "\nexit \$?"
    }

    fun capabilityUnavailable(): Boolean = capability.readText() == "unavailable"
    fun rootDenied(): Boolean = capability.readText() == "not_root"

    /** Runs before destroying the su client, since a Magisk daemon may own the actual child. */
    fun cancel() {
        active.delete() // A root grant that arrives late must not start the original command.
        val deadline = System.nanoTime() + 2_000_000_000L
        while (process.length() == 0L && System.nanoTime() < deadline) Thread.sleep(10)
        val fields = process.readText().trim().split(Regex("\\s+"))
        val pid = fields.getOrNull(0)?.toLongOrNull()?.takeIf { it > 1 } ?: return
        val started = fields.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0 } ?: return
        // Check the recorded kernel start time and session/group identity; a recycled PID is not ours.
        val identityCheck = """
            stat=${'$'}(cat /proc/$pid/stat 2>/dev/null) || exit 0
            rest=${'$'}{stat##*) }
            set -- ${'$'}rest
            [ "${'$'}3" = "$pid" ] && [ "${'$'}4" = "$pid" ] || exit 0
            shift 19
            [ "${'$'}1" = "$started" ] || exit 0
        """.trimIndent()
        // One KILL reaches all current group members while its leader is still identified.
        // A TERM grace period could let the leader exit and strand TERM-ignoring descendants.
        val cleanup = """
            $identityCheck
            kill -KILL -$pid 2>/dev/null || exit 126
        """.trimIndent()
        val result = RootProcessRunner.run(listOf(suExecutable, "-c", cleanup), 3_000, 512, 512)
        if (result.error != null || result.exitCode != 0) {
            cleanupError = "Не удалось подтвердить остановку root-процессов. Проверьте запущенные процессы на устройстве перед новым вызовом."
        }
    }

    override fun close() {
        active.delete()
        process.delete()
        capability.delete()
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
}
