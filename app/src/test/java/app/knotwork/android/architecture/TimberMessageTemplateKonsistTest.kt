package app.knotwork.android.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Census of the message every `WARN`-and-above Timber call writes: it must be a
 * **string literal without templates**, with anything dynamic passed as a format
 * argument.
 *
 * **Why it is a defect, not a style.** `CrashlyticsTimberTree` sends a `WARN`+
 * record to the crash report after the user opts in, and it sends the call site's
 * template only — the arguments are never formatted in (PRIVACY §3.5: a report
 * carries the stack trace, nothing of the user's). A value spliced into the
 * literal itself (`"… $path"`), or a message held in a variable, *is* the
 * template, and would ride into the report: workspace paths, file names, a model's
 * error text. Found that way: the file screen and the workspace logged paths, and
 * the tool-failure log carried whole `JSONException` texts.
 *
 * **What it reads.** Production sources, comments removed; the calls
 * `Timber.w/e/wtf(…)` and `Timber.tag(…).w/e/wtf(…)`. The message is the first
 * argument, or the second after a throwable; it must be one or more string
 * literals joined by `+`, none with a `$` template. A call without a message
 * (`Timber.w(e)`) fails too: nothing tells a throwable from a string variable
 * here, and a message costs one literal. Lower levels are not read — they never
 * reach the crash tree.
 */
class TimberMessageTemplateKonsistTest {

    @Test
    fun `every warn-or-above Timber message is a template-free literal`() {
        val offenders = ProductionSources.code
            .mapValues { (_, code) -> offendersIn(code) }
            .filterValues { it.isNotEmpty() }

        assertEquals(
            "a WARN+ Timber message is built at runtime. Write it as a string literal and pass the values " +
                "as format arguments (\"failed for %s\", path): the crash report sends the literal only.",
            emptyMap<String, List<String>>(),
            offenders,
        )
    }

    @Test
    fun `the census recognises the calls it claims to`() {
        // Keeps the rule above from passing vacuously if the pattern stops matching.
        val calls = ProductionSources.code.values.sumOf { callsIn(it).size }
        assertTrue("only $calls WARN+ Timber calls recognised", calls >= MIN_KNOWN_CALLS)
    }

    @Test
    fun `the census flags the shapes it forbids and passes the ones it allows`() {
        assertEquals(1, offendersIn("Timber.w(\"failed for \$path\")").size)
        assertEquals(1, offendersIn("Timber.e(e, \"failed for \${file.path}\")").size)
        assertEquals(1, offendersIn("Timber.tag(\"T\").e(errorMsg)").size)
        assertEquals(1, offendersIn("Timber.tag(\n    \"T\",\n).w(e, message)").size)
        assertEquals(1, offendersIn("Timber.w(e)").size)
        assertEquals(1, offendersIn("Timber.wtf(\"a\" + reason)").size)
        assertEquals(0, offendersIn("Timber.w(\"failed for %s\", path)").size)
        assertEquals(0, offendersIn("Timber.tag(\"T\").e(e, \"a %s \" + \"b %s\", x, y)").size)
        assertEquals(0, offendersIn("Timber.e(e, \"cost \\\$5\")").size)
        assertEquals(0, offendersIn("Timber.d(\"debug \$path\")").size)
        assertEquals(0, offendersIn("Timber.i(message)").size)
    }

    /** A recognised call: its source snippet and its top-level arguments. */
    private data class Call(val snippet: String, val arguments: List<String>)

    private fun offendersIn(code: String): List<String> =
        callsIn(code).filterNot { isTemplateFree(messageOf(it)) }.map { it.snippet }

    /** The message argument of [call]: the first, or the second after a throwable; `null` if none is a literal. */
    private fun messageOf(call: Call): String? {
        val index = call.arguments.indexOfFirst { it.startsWith("\"") }
        return if (index in 0..1) call.arguments[index] else null
    }

    /** Whether [expression] is string literals joined by `+`, none of them with a `$` template. */
    private fun isTemplateFree(expression: String?): Boolean {
        if (expression == null) return false
        val literals = splitTopLevel(expression, '+')
        return literals.all { part ->
            val literal = part.trim()
            literal.startsWith("\"") && literal.endsWith("\"") && !TEMPLATE.containsMatchIn(literal)
        }
    }

    private fun callsIn(code: String): List<Call> = CALL.findAll(code).mapNotNull { match ->
        val arguments = argumentsFrom(code, match.range.last + 1) ?: return@mapNotNull null
        Call(code.substring(match.range.first, minOf(code.length, match.range.last + 1 + SNIPPET)), arguments)
    }.toList()

    /** Top-level arguments of the call whose `(` ends just before [start]; `null` if unbalanced. */
    private fun argumentsFrom(code: String, start: Int): List<String>? {
        var depth = 1
        var i = start
        var quote: String? = null
        while (i < code.length) {
            val c = code[i]
            when {
                quote != null -> when {
                    quote.length == 1 && c == '\\' -> i++
                    code.startsWith(quote, i) -> {
                        i += quote.length - 1
                        quote = null
                    }
                }
                code.startsWith("\"\"\"", i) -> {
                    quote = "\"\"\""
                    i += 2
                }
                c == '"' || c == '\'' -> quote = c.toString()
                c in "([{" -> depth++
                c in ")]}" -> {
                    depth--
                    if (depth == 0) return splitTopLevel(code.substring(start, i), ',').filter { it.isNotBlank() }
                }
            }
            i++
        }
        return null
    }

    /** [text] split at [separator]s outside brackets and string literals, each part trimmed. */
    private fun splitTopLevel(text: String, separator: Char): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var quote: String? = null
        var from = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quote != null -> when {
                    quote.length == 1 && c == '\\' -> i++
                    text.startsWith(quote, i) -> {
                        i += quote.length - 1
                        quote = null
                    }
                }
                text.startsWith("\"\"\"", i) -> {
                    quote = "\"\"\""
                    i += 2
                }
                c == '"' || c == '\'' -> quote = c.toString()
                c in "([{" -> depth++
                c in ")]}" -> depth--
                c == separator && depth == 0 -> {
                    parts += text.substring(from, i).trim()
                    from = i + 1
                }
            }
            i++
        }
        parts += text.substring(from).trim()
        return parts
    }

    private companion object {
        /** `Timber.w/e/wtf(` with an optional `.tag(…)` (one level of nested parentheses) before it. */
        val CALL = Regex("""\bTimber(\s*\.\s*tag\((?:[^()]|\([^()]*\))*\))?\s*\.\s*(w|e|wtf)\(""")

        /** A string template: `$name` or `${…}` not preceded by a backslash. */
        val TEMPLATE = Regex("""(?<!\\)\$(\{|[A-Za-z_])""")

        /** WARN+ calls the census must at least find; the tree had well over 300 at the time of writing. */
        const val MIN_KNOWN_CALLS = 250

        /** Characters of a call kept in a failure message. */
        const val SNIPPET = 120
    }
}
