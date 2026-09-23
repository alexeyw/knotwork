package app.knotwork.android.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Census of how production code builds a regular expression: every pattern must be
 * a **string literal in the source**, and anything spliced into it at runtime must
 * pass through `Regex.escape` (or `Pattern.quote`).
 *
 * **Why it is a defect, not a style.** `java.util.regex` backtracks. A pattern
 * whose quantifiers come from runtime text can take time exponential in its
 * length, and nothing can stop the match: it never suspends, so a coroutine
 * timeout does not fire, and it ignores interruption. `find_files` once compiled a
 * model-supplied glob into such a pattern, and a glob of about thirty characters
 * was enough to hold a core for seconds, forty for minutes. A literal skeleton is
 * reviewed code; an escaped splice adds only literal characters, never a quantifier.
 *
 * **What it cannot see.** It reads the three spellings the code base uses —
 * `Regex(…)`, `Pattern.compile(…)` and `"…".toRegex(…)` — in the production
 * sources, comments removed. A pattern assembled in a variable first and then
 * passed in fails here, which is the intended direction; a regex engine reached
 * some other way is not seen.
 */
class RegexConstructionKonsistTest {

    @Test
    fun `every production regex is a source literal with only escaped splices`() {
        val offenders = ProductionSources.code
            .mapValues { (_, code) -> offendersIn(code) }
            .filterValues { it.isNotEmpty() }

        assertEquals(
            "a regex is built from runtime text. Keep the pattern a string literal and splice runtime " +
                "values only through Regex.escape; to match untrusted input against a user-supplied " +
                "pattern, use a matcher that cannot backtrack (see WorkspaceGlob).",
            emptyMap<String, List<String>>(),
            offenders,
        )
    }

    @Test
    fun `the census recognises the literal constructions it claims to`() {
        // Keeps the rule above from passing vacuously: these sites build their patterns
        // from literals today, one of them with an escaped splice.
        val constructions = ProductionSources.code.values.sumOf { constructionsIn(it).size }
        assertTrue("only $constructions regex constructions recognised", constructions >= MIN_KNOWN_CONSTRUCTIONS)
        val sanitizer = ProductionSources.code.entries.single { it.key.endsWith("/CloudErrorSanitizer.kt") }.value
        assertTrue(constructionsIn(sanitizer).isNotEmpty())
        val engine = ProductionSources.code.entries.single { it.key.endsWith("/GraphExecutionEngine.kt") }.value
        assertEquals(emptyList<String>(), offendersIn(engine))
    }

    @Test
    fun `the census flags a pattern built from runtime text`() {
        assertEquals(1, offendersIn("val r = Regex(pattern.toString())").size)
        assertEquals(1, offendersIn("val r = Regex(\"^\" + glob)").size)
        assertEquals(1, offendersIn("val r = Regex(\"^\$glob\$\")").size)
        assertEquals(1, offendersIn("val r = glob.toRegex()").size)
        assertEquals(1, offendersIn("val r = Pattern.compile(source)").size)
        assertEquals(0, offendersIn("val r = Regex(\"a\${Regex.escape(label)}b\", RegexOption.IGNORE_CASE)").size)
        assertEquals(0, offendersIn("val r = \"\"\"x+\"\"\".toRegex()").size)
    }

    /** A string literal found in code: its span and the source of each template splice. */
    private data class Literal(val start: Int, val end: Int, val splices: List<String>)

    /** One `Regex(` / `Pattern.compile(` / `.toRegex(` site, with the literal it uses, if any. */
    private data class Construction(val snippet: String, val literal: Literal?, val literalIsWholeArgument: Boolean)

    private fun offendersIn(code: String): List<String> = constructionsIn(code)
        .filter { site ->
            val literal = site.literal
            literal == null ||
                !site.literalIsWholeArgument ||
                literal.splices.any { !it.contains("Regex.escape(") && !it.contains("Pattern.quote(") }
        }
        .map { it.snippet }

    private fun constructionsIn(code: String): List<Construction> {
        val literals = literalsIn(code)
        val byStart = literals.associateBy { it.start }
        val byEnd = literals.associateBy { it.end }
        val insideLiteral = { index: Int -> literals.any { index > it.start && index < it.end } }
        val sites = mutableListOf<Construction>()
        CALL.findAll(code).filterNot { insideLiteral(it.range.first) }.forEach { match ->
            val argument = skipSpaces(code, match.range.last + 1)
            val literal = byStart[argument]
            val whole = literal != null && skipSpaces(code, literal.end).let { it < code.length && code[it] in ",)" }
            sites += Construction(snippet(code, match.range.first), literal, whole)
        }
        TO_REGEX.findAll(code).filterNot { insideLiteral(it.range.first) }.forEach { match ->
            val literal = byEnd[match.range.first]
            sites += Construction(snippet(code, match.range.first), literal, literalIsWholeArgument = literal != null)
        }
        return sites
    }

    private fun snippet(code: String, from: Int): String =
        code.substring(from, minOf(code.length, from + SNIPPET_LENGTH)).lineSequence().first().trim()

    private fun skipSpaces(code: String, from: Int): Int {
        var i = from
        while (i < code.length && code[i].isWhitespace()) i++
        return i
    }

    /** Every top-level string literal of [code], with its template splices. */
    private fun literalsIn(code: String): List<Literal> {
        val found = mutableListOf<Literal>()
        var i = 0
        while (i < code.length) {
            when (code[i]) {
                '"' -> scanString(code, i).also {
                    found += it
                    i = it.end
                }
                '\'' -> i = skipCharLiteral(code, i)
                else -> i++
            }
        }
        return found
    }

    /** Scans the string literal opening at [start] (plain or raw), returning its span and splices. */
    private fun scanString(code: String, start: Int): Literal {
        val raw = code.startsWith(RAW_QUOTE, start)
        var i = start + if (raw) RAW_QUOTE.length else 1
        val splices = mutableListOf<String>()
        while (i < code.length) {
            when {
                raw && code.startsWith(RAW_QUOTE, i) -> {
                    var end = i + RAW_QUOTE.length
                    while (end < code.length && code[end] == '"') end++
                    return Literal(start, end, splices)
                }
                !raw && code[i] == '\\' -> i += 2
                !raw && code[i] == '"' -> return Literal(start, i + 1, splices)
                code.startsWith("\${", i) -> {
                    val close = matchingBrace(code, i + 1)
                    splices += code.substring(i + 2, close)
                    i = close + 1
                }
                code[i] == '$' && i + 1 < code.length && (code[i + 1].isLetter() || code[i + 1] == '_') -> {
                    var end = i + 1
                    while (end < code.length && (code[end].isLetterOrDigit() || code[end] == '_')) end++
                    splices += code.substring(i + 1, end)
                    i = end
                }
                else -> i++
            }
        }
        return Literal(start, code.length, splices)
    }

    /** Index of the `}` closing the `{` at [open], stepping over nested strings. */
    private fun matchingBrace(code: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < code.length) {
            when (code[i]) {
                '"' -> {
                    i = scanString(code, i).end
                    continue
                }
                '{' -> depth++
                '}' -> if (--depth == 0) return i
            }
            i++
        }
        return code.length - 1
    }

    private fun skipCharLiteral(code: String, start: Int): Int {
        var i = start + 1
        if (i < code.length && code[i] == '\\') i++
        i++
        return if (i < code.length && code[i] == '\'') i + 1 else start + 1
    }

    private companion object {
        /** `Regex(` as a constructor call (not `Regex.escape(`) and `Pattern.compile(`. */
        val CALL = Regex("""(?<![\w.])Regex\(|\bPattern\.compile\(""")

        /** `.toRegex(` — the receiver must be the literal that ends right before the dot. */
        val TO_REGEX = Regex("""\.toRegex\(""")

        const val RAW_QUOTE = "\"\"\""

        /**
         * Floor on the constructions recognised: grep counted 28 sites (comments included)
         * when this was written, all literal; a census that finds fewer than 20 has stopped
         * reading the code, not the code stopped building regexes.
         */
        const val MIN_KNOWN_CONSTRUCTIONS = 20

        const val SNIPPET_LENGTH = 80
    }
}
