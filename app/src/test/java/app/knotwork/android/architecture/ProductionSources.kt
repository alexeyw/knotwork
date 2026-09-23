package app.knotwork.android.architecture

import java.io.File

/**
 * The module's production Kotlin sources as text, for the guards that census a
 * name or an idiom rather than a type (`HitlDispatchKonsistTest`,
 * `TranscriptJoinKonsistTest`).
 *
 * **Which files.** Every production source set of the module (`main`, the
 * flavours, the build types) — a guard over `src/main` alone is blind to the
 * flavour code that ships. **Comments removed**, because KDoc names methods
 * and quotes anti-patterns freely; string literals are kept, so a guarded name
 * inside one fails loudly, which is the safe direction.
 */
internal object ProductionSources {

    /**
     * Code of every production Kotlin file, comments removed, keyed by path
     * under `app/src` (e.g. `main/java/.../ToolInvocationGate.kt`). Parsed once
     * per test JVM.
     */
    val code: Map<String, String> by lazy {
        val src = File(moduleDirectory(), "src")
        src.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith("test") && !it.name.startsWith("androidTest") }
            .flatMap { set -> set.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .associate { file -> file.relativeTo(src).invariantSeparatorsPath to stripComments(file.readText()) }
    }

    /**
     * Gradle runs unit tests with the module directory as the working
     * directory; resolved rather than assumed so a wrong guess fails loudly.
     */
    fun moduleDirectory(): File {
        val working = File("").absoluteFile
        return if (File(working, "src/main").isDirectory) working else File(working, "app")
    }

    /**
     * [code] with every comment replaced by spaces (newlines kept), string
     * and character literals left intact. Block comments nest, as in Kotlin.
     */
    fun stripComments(code: String): String {
        val out = StringBuilder(code.length)
        var i = 0
        var depth = 0
        var inLine = false
        var quote: String? = null
        while (i < code.length) {
            val c = code[i]
            val next = code.getOrNull(i + 1)
            when {
                inLine -> {
                    if (c == '\n') inLine = false
                    out.append(if (c == '\n') '\n' else ' ')
                    i++
                }
                depth > 0 -> {
                    if (c == '/' && next == '*') {
                        depth++
                        out.append("  ")
                        i += 2
                    } else if (c == '*' && next == '/') {
                        depth--
                        out.append("  ")
                        i += 2
                    } else {
                        out.append(if (c == '\n') '\n' else ' ')
                        i++
                    }
                }
                quote != null -> {
                    if (quote.length == 1 && c == '\\') {
                        out.append(c).append(next ?: ' ')
                        i += 2
                    } else if (code.startsWith(quote, i)) {
                        out.append(quote)
                        i += quote.length
                        quote = null
                    } else {
                        out.append(c)
                        i++
                    }
                }
                c == '/' && next == '/' -> inLine = true
                c == '/' && next == '*' -> {
                    depth = 1
                    out.append("  ")
                    i += 2
                }
                code.startsWith("\"\"\"", i) -> {
                    quote = "\"\"\""
                    out.append(quote)
                    i += quote.length
                }
                c == '"' || c == '\'' -> {
                    quote = c.toString()
                    out.append(c)
                    i++
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }
}
