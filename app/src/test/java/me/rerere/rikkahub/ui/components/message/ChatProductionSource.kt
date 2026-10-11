package me.rerere.rikkahub.ui.components.message

import java.io.File

/**
 * Narrow source contracts for private composables that expose no independent presentation model.
 * These guard existing UI-to-callback wiring, not runtime behavior; Compose and model tests cover
 * the public controls separately. Comments cannot satisfy an action contract.
 */
internal object ChatProductionSource {
    private val root: File by lazy {
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt").isFile }
            ?: error("Run chat source contracts from this repository or one of its module directories")
    }

    fun read(path: String): String = if (path.startsWith("@ui/")) {
        File(root, "ui/src/main/java/me/rerere/ui/${path.removePrefix("@ui/")}").readText()
    } else {
        File(root, "app/src/main/java/me/rerere/rikkahub/$path").readText()
    }

    fun function(path: String, name: String): String {
        val source = read(path)
        val code = mask(source, literals = true)
        val functionName = name.substringAfter("::")
        var scopeStart = 0
        var scopeEnd = code.length
        if ("::" in name) {
            val owner = name.substringBefore("::")
            val ownerDeclaration = Regex("\\b(?:object|class|interface)\\s+${Regex.escape(owner)}\\b")
                .find(code) ?: error("Missing production owner $owner in $path")
            scopeStart = code.indexOf('{', ownerDeclaration.range.last + 1)
            check(scopeStart >= 0) { "Missing body of $owner in $path" }
            scopeEnd = closeDelimiter(code, scopeStart, '{', '}')
        }
        val declaration = Regex("\\bfun\\s+(?:<[^\\n]+?>\\s+)?(?:[\\w.<>?]+\\.)?${Regex.escape(functionName)}\\s*\\(")
            .find(code, scopeStart)?.takeIf { it.range.first < scopeEnd }
            ?: error("Missing production function $name in $path")
        val openArguments = code.indexOf('(', declaration.range.first)
        val closeArguments = closeDelimiter(code, openArguments, '(', ')')
        val openBody = code.indexOf('{', closeArguments + 1)
        check(openBody >= 0) { "Missing body of $name in $path" }
        val closeBody = closeDelimiter(code, openBody, '{', '}')
        return source.substring(openBody + 1, closeBody)
    }

    fun canonical(source: String): String = mask(source, literals = false).replace(Regex("\\s+"), "")

    private fun closeDelimiter(code: String, start: Int, open: Char, close: Char): Int {
        var depth = 0
        for (index in start until code.length) {
            if (code[index] == open) depth++
            if (code[index] == close && --depth == 0) return index
        }
        error("Unclosed $open delimiter at $start")
    }

    /** Preserve offsets while skipping comments, ordinary/triple strings and character literals. */
    private fun mask(source: String, literals: Boolean): String {
        val result = source.toCharArray()
        fun blank(start: Int, end: Int) {
            for (index in start until end) if (result[index] != '\n') result[index] = ' '
        }
        var index = 0
        while (index < source.length) {
            val start = index
            when {
                source.startsWith("//", index) -> {
                    index = source.indexOf('\n', index).takeIf { it >= 0 } ?: source.length
                    blank(start, index)
                }
                source.startsWith("/*", index) -> {
                    index += 2
                    var depth = 1
                    while (index < source.length && depth > 0) {
                        when {
                            source.startsWith("/*", index) -> { depth++; index += 2 }
                            source.startsWith("*/", index) -> { depth--; index += 2 }
                            else -> index++
                        }
                    }
                    blank(start, index)
                }
                source.startsWith("\"\"\"", index) -> {
                    index = source.indexOf("\"\"\"", index + 3).takeIf { it >= 0 }?.plus(3) ?: source.length
                    if (literals) blank(start, index)
                }
                source[index] == '"' || source[index] == '\'' -> {
                    val quote = source[index++]
                    while (index < source.length) {
                        when (source[index++]) {
                            '\\' -> if (index < source.length) index++
                            quote -> break
                        }
                    }
                    if (literals) blank(start, index)
                }
                else -> index++
            }
        }
        return String(result)
    }
}
