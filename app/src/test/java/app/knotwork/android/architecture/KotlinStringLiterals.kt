package app.knotwork.android.architecture

/**
 * The string literals of Kotlin source, and the primary-constructor parameter
 * lists of its classes — the two shapes the interface-text guards read
 * (`DisplayedLiteralGuardTest`, `CatalogStringsWiringTest`).
 *
 * Works on code whose comments were already removed
 * ([ProductionSources.stripComments]); a literal inside a comment is not code.
 */
internal object KotlinStringLiterals {

    /**
     * One string literal.
     *
     * @property start Offset of the opening quote in the code.
     * @property end Offset just past the closing quote.
     * @property content The text between the quotes, as written (escapes and
     *   `$` templates kept).
     */
    data class Literal(val start: Int, val end: Int, val content: String)

    /**
     * A class's primary-constructor parameter list.
     *
     * @property className The class's simple name.
     * @property start Offset of the opening parenthesis.
     * @property end Offset just past the closing parenthesis.
     * @property parameters Parameter names in declaration order.
     * @property englishDefaults Names of the `String` parameters whose default is
     *   a literal with a letter in it, with that literal.
     */
    data class ConstructorParameters(
        val className: String,
        val start: Int,
        val end: Int,
        val parameters: List<String>,
        val englishDefaults: Map<String, String>,
    )

    /**
     * Every string literal of [code], in order — plain (`"…"`) and raw
     * (`"""…"""`). A template's `${…}` is part of its literal.
     *
     * @param code Kotlin source with comments removed.
     * @return The literals.
     */
    fun literals(code: String): List<Literal> {
        val found = mutableListOf<Literal>()
        var i = 0
        while (i < code.length) {
            val c = code[i]
            when {
                c == '\'' -> i = skipCharLiteral(code, i)
                code.startsWith("\"\"\"", i) -> {
                    val close = code.indexOf("\"\"\"", i + RAW_QUOTE).let { if (it < 0) code.length else it }
                    found += Literal(i, close + RAW_QUOTE, code.substring(i + RAW_QUOTE, close))
                    i = close + RAW_QUOTE
                }
                c == '"' -> {
                    val close = closingQuote(code, i + 1)
                    found += Literal(i, close + 1, code.substring(i + 1, close))
                    i = close + 1
                }
                else -> i++
            }
        }
        return found
    }

    /**
     * Every class declaration of [code] that has a primary-constructor
     * parameter list.
     *
     * @param code Kotlin source with comments removed.
     * @return One entry per class, in order.
     */
    fun constructors(code: String): List<ConstructorParameters> = CLASS_HEADER.findAll(code).mapNotNull { header ->
        val open = header.range.last
        val close = matchingParenthesis(code, open) ?: return@mapNotNull null
        val parameters = topLevelArguments(code.substring(open + 1, close))
        ConstructorParameters(
            className = header.groupValues[1],
            start = open,
            end = close + 1,
            parameters = parameters.mapNotNull { PARAMETER_NAME.find(it)?.groupValues?.get(1) },
            englishDefaults = parameters.mapNotNull { parameter ->
                val match = STRING_DEFAULT.find(parameter) ?: return@mapNotNull null
                val literal = match.groupValues[2]
                if (literal.any(Char::isLetter)) match.groupValues[1] to literal else null
            }.toMap(),
        )
    }.toList()

    /**
     * The arguments of every call of [className]'s constructor in [code], each
     * as the list of its top-level argument texts. A declaration of the class
     * (`class Name(`) is not a call.
     *
     * A file that never names the class is skipped before the pattern is built: a call
     * names it, and the census asks hundreds of classes about every production file
     * (`decisions.md §125`).
     *
     * @param code Kotlin source with comments removed.
     * @param className The class whose constructor calls to find.
     * @return The argument lists, in order.
     */
    fun constructorCalls(code: String, className: String): List<List<String>> {
        if (className !in code) return emptyList()
        return Regex("""(?<![\w.])${Regex.escape(className)}\(""").findAll(code)
            .filterNot {
                DECLARATION_BEFORE.containsMatchIn(
                    code.substring(maxOf(0, it.range.first - LOOKBEHIND), it.range.first),
                )
            }
            .mapNotNull { call ->
                val open = call.range.last
                val close = matchingParenthesis(code, open) ?: return@mapNotNull null
                topLevelArguments(code.substring(open + 1, close))
            }
            .toList()
    }

    /**
     * Offset of the parenthesis closing the one at [open], skipping literals and
     * nested brackets; `null` when unbalanced.
     */
    fun matchingParenthesis(code: String, open: Int): Int? {
        var depth = 0
        var i = open
        while (i < code.length) {
            when (code[i]) {
                '"' -> {
                    i = if (code.startsWith("\"\"\"", i)) {
                        code.indexOf("\"\"\"", i + RAW_QUOTE).let { if (it < 0) return null else it + RAW_QUOTE }
                    } else {
                        closingQuote(code, i + 1) + 1
                    }
                    continue
                }
                '\'' -> {
                    i = skipCharLiteral(code, i)
                    continue
                }
                '(', '[', '{' -> depth++
                ')', ']', '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }

    /** [list] split at its top-level commas, each part trimmed, empty parts dropped. */
    private fun topLevelArguments(list: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var startOfPart = 0
        var i = 0
        while (i < list.length) {
            when (list[i]) {
                '"' -> {
                    i = if (list.startsWith("\"\"\"", i)) {
                        list.indexOf("\"\"\"", i + RAW_QUOTE).let { if (it < 0) list.length else it + RAW_QUOTE }
                    } else {
                        closingQuote(list, i + 1) + 1
                    }
                    continue
                }
                '\'' -> {
                    i = skipCharLiteral(list, i)
                    continue
                }
                '(', '[', '{', '<' -> depth++
                ')', ']', '}' -> depth--
                // `->` of a function type or lambda closes no bracket.
                '>' -> if (list.getOrNull(i - 1) != '-') depth--
                ',' -> if (depth == 0) {
                    parts += list.substring(startOfPart, i)
                    startOfPart = i + 1
                }
            }
            i++
        }
        parts += list.substring(startOfPart)
        return parts.map(String::trim).filter(String::isNotEmpty)
    }

    /** Offset of the quote closing a plain literal whose content starts at [from]; templates may nest. */
    private fun closingQuote(code: String, from: Int): Int {
        var i = from
        while (i < code.length) {
            when {
                code[i] == '\\' -> i += 2
                code[i] == '"' -> return i
                code.startsWith("\${", i) -> {
                    val close = matchingParenthesis(code, i + 1) ?: return code.length - 1
                    i = close + 1
                }
                code[i] == '\n' -> return i
                else -> i++
            }
        }
        return code.length - 1
    }

    /** Offset just past the character literal starting at [open]. */
    private fun skipCharLiteral(code: String, open: Int): Int {
        val close = code.indexOf('\'', if (code.getOrNull(open + 1) == '\\') open + ESCAPED_CHAR_OFFSET else open + 1)
        return if (close < 0) code.length else close + 1
    }

    private const val RAW_QUOTE = 3
    private const val ESCAPED_CHAR_OFFSET = 3
    private const val LOOKBEHIND = 40
    private val CLASS_HEADER =
        Regex(
            """\bclass\s+(\w+)\s*(?:<[^>{(]*>)?\s*(?:(?:@\w+(?:\([^)]*\))?\s*)*(?:private\s+|internal\s+)?constructor\s*)?\(""",
        )
    private val DECLARATION_BEFORE = Regex("""\bclass\s+$|\bfun\s+$""")
    private val PARAMETER_NAME = Regex("""(?:^|\s)(?:va[lr]\s+)?(\w+)\s*:""")
    private val STRING_DEFAULT = Regex("""va[lr]\s+(\w+)\s*:\s*String\s*=\s*"((?:[^"\\]|\\.)*)"\s*$""")
}
