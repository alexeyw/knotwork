package app.knotwork.android.buildtools

/**
 * Keeps the size and complexity suppressions in the sources from growing: every one of
 * them has to be listed, and the list may only shrink.
 *
 * Five detekt rules measure how big or how tangled a declaration is — `LargeClass`,
 * `TooManyFunctions`, `LongMethod`, `CyclomaticComplexMethod`, `LongParameterList`. A
 * suppression of one of them is a standing exception to a threshold, and each was
 * meant to be temporary ("decomposition is future work"). Nothing ever made one go
 * away, and nothing noticed when one stopped doing anything: when they were counted,
 * 126 of the 155 in the repository suppressed no finding at all, because the code had
 * shrunk under the threshold, the rule ignores the declaration anyway, or the rule
 * does not run on that source set.
 *
 * So the sources are compared with a committed list, entry by entry. A suppression
 * that is not listed fails the build — the code is to be split, not excused. A listed
 * entry that no longer exists fails it too, so the line has to be deleted in the same
 * change that removed the suppression: the list records what is left, never what used
 * to be.
 *
 * **An entry is a path, a target and a rule** — `path :: fun name :: LongMethod` — and
 * never a line number, so editing the code around a suppression does not invalidate
 * the list. The target is the declaration the annotation sits on, so moving a
 * suppression from one function to another is a new entry, not the same one.
 *
 * **What it reads.** `@Suppress` and `@SuppressWarnings`, at any use-site target
 * (`@file:Suppress` names the whole file), with every rule-id form detekt accepts:
 * `LongMethod`, `complexity:LongMethod`, `complexity.LongMethod`, `detekt:LongMethod`,
 * `detekt:complexity:LongMethod`, `detekt.complexity.LongMethod`. A suppression of the
 * whole rule set or of everything (`complexity`, `all`, `detekt:all`) counts as one of
 * each of the five rules. Comments and string literals are skipped — a suppression
 * written inside a test fixture's source text suppresses nothing. An argument that is
 * not a plain string literal (a constant, a template) cannot be read, and fails the
 * build rather than being guessed at.
 *
 * **What it cannot see.** Whether a listed suppression still suppresses anything: that
 * takes running detekt with the annotation removed, and stays a manual probe. Nor a
 * threshold raised, or a path excluded, in the detekt configuration — those are edits
 * to `config/detekt/`, reviewed as such.
 *
 * A pure `Map<path, content> -> verdict` transform with no file-system access, so it
 * is unit-tested directly; [VerifySizeSuppressionsTask] feeds it the source tree.
 */
object SizeSuppressionRatchet {

    /** The rules whose suppressions are ratcheted, all of them in detekt's `complexity` set. */
    val RULES: Set<String> = setOf(
        "LargeClass",
        "TooManyFunctions",
        "LongMethod",
        "CyclomaticComplexMethod",
        "LongParameterList",
    )

    /** Target recorded for a file-level suppression (`@file:Suppress`). */
    const val FILE_TARGET: String = "(file)"

    /** Separator between the three fields of a list line. */
    private const val SEPARATOR: String = " :: "

    /** The rule set every ratcheted rule belongs to. */
    private const val RULE_SET: String = "complexity"

    /** Suppressing these ids suppresses every rule, the five included. */
    private val SUPPRESS_EVERYTHING: Set<String> = setOf("all", RULE_SET)

    /** Kotlin modifiers that may stand between an annotation and the declaration keyword. */
    private val MODIFIERS: Set<String> = setOf(
        "public", "private", "protected", "internal",
        "abstract", "open", "final", "override", "sealed", "data", "enum", "annotation",
        "inner", "value", "companion", "inline", "noinline", "crossinline", "tailrec",
        "operator", "infix", "suspend", "external", "const", "lateinit", "vararg",
        "expect", "actual",
    )

    /** Declaration keywords whose next word names the declaration. */
    private val NAMED_KEYWORDS: Set<String> = setOf("class", "interface", "object", "typealias")

    /** Declaration keywords that name nothing beyond themselves. */
    private val BARE_KEYWORDS: Set<String> = setOf("constructor", "init")

    /**
     * One suppression of one ratcheted rule.
     *
     * @property path Repository-relative path of the file, with `/` separators.
     * @property target The declaration the annotation sits on — `fun name`,
     *   `class Name`, `constructor` — or [FILE_TARGET].
     * @property rule The suppressed rule, one of [RULES].
     */
    data class Entry(val path: String, val target: String, val rule: String) {
        /** The entry as a line of the list file. */
        override fun toString(): String = "$path$SEPARATOR$target$SEPARATOR$rule"
    }

    /**
     * A suppression whose arguments are not plain string literals, so the rules it
     * names cannot be known from the text.
     *
     * @property path Repository-relative path of the file.
     * @property line 1-based line on which the annotation starts.
     */
    data class Unreadable(val path: String, val line: Int) {
        /** One-line rendering for the build failure message. */
        override fun toString(): String =
            "$path:$line: a @Suppress argument is not a plain string literal; write the rule ids out"
    }

    /**
     * Everything found in the sources.
     *
     * @property entries One entry per suppressed ratcheted rule, in source order.
     * @property unreadable Suppressions whose arguments could not be read.
     */
    data class Scan(val entries: List<Entry>, val unreadable: List<Unreadable>)

    /**
     * The difference between the sources and the list.
     *
     * Both sides are compared as multisets: two suppressions of the same rule on two
     * same-named targets of one file (two constructors, say) are two lines.
     *
     * @property unlisted Suppressions present in the sources and absent from the list.
     * @property stale Listed entries the sources no longer contain.
     */
    data class Verdict(val unlisted: List<Entry>, val stale: List<Entry>) {
        /** `true` when the sources and the list agree exactly. */
        val clean: Boolean get() = unlisted.isEmpty() && stale.isEmpty()
    }

    /** Thrown when a line of the list file is malformed. */
    class ParseException(message: String) : RuntimeException(message)

    /**
     * Finds every suppression of a ratcheted rule.
     *
     * @param files Repository-relative path to file content.
     * @return The entries and the unreadable suppressions, files in key order.
     */
    fun scan(files: Map<String, String>): Scan {
        val entries = mutableListOf<Entry>()
        val unreadable = mutableListOf<Unreadable>()
        for ((path, content) in files.toSortedMap()) {
            for (found in SuppressionLexer(content).suppressions()) {
                val rules = found.ids?.flatMap(::ratchetedRules)?.distinct()
                when {
                    rules == null -> unreadable += Unreadable(path, found.line)
                    rules.isNotEmpty() -> rules.mapTo(entries) { Entry(path, found.target, it) }
                }
            }
        }
        return Scan(entries, unreadable)
    }

    /**
     * Reads the committed list.
     *
     * @param text Contents of the list file. Blank lines and `#` comments are ignored.
     * @return The listed entries, in file order.
     * @throws ParseException when a line is not `path :: target :: Rule` or names a
     *   rule outside [RULES].
     */
    fun parseList(text: String): List<Entry> = text.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { line ->
            val parts = line.split(SEPARATOR)
            if (parts.size != 3 || parts.any { it.isBlank() }) {
                throw ParseException("List line is not `path${SEPARATOR}target${SEPARATOR}Rule`: `$line`.")
            }
            if (parts[2] !in RULES) throw ParseException("List line names no ratcheted rule: `$line`.")
            Entry(parts[0], parts[1], parts[2])
        }

    /**
     * Compares the sources with the list.
     *
     * @param found Entries found in the sources.
     * @param listed Entries in the committed list.
     * @return What is in one and not the other, each side in sorted order.
     */
    fun compare(found: List<Entry>, listed: List<Entry>): Verdict =
        Verdict(unlisted = minus(found, listed), stale = minus(listed, found))

    /** Multiset difference `a - b`, sorted by the rendered line. */
    private fun minus(a: List<Entry>, b: List<Entry>): List<Entry> {
        val remaining = b.groupingBy { it }.eachCount().toMutableMap()
        return a.filter { entry ->
            val left = remaining[entry] ?: 0
            if (left > 0) remaining[entry] = left - 1
            left == 0
        }.sortedBy { it.toString() }
    }

    /**
     * The ratcheted rules one suppression id stands for: none, one, or all five.
     *
     * @param id A string argument of `@Suppress`.
     * @return The rules it suppresses among [RULES].
     */
    private fun ratchetedRules(id: String): List<String> {
        val parts = id.split(':', '.').let { if (it.firstOrNull() == "detekt") it.drop(1) else it }
        return when {
            parts.size == 1 && parts[0] in SUPPRESS_EVERYTHING -> RULES.toList()
            parts.size == 1 && parts[0] in RULES -> parts
            parts.size == 2 && parts[0] == RULE_SET && parts[1] in RULES -> listOf(parts[1])
            else -> emptyList()
        }
    }

    /**
     * One `@Suppress` / `@SuppressWarnings` annotation.
     *
     * @property line 1-based line of the `@`.
     * @property ids Its string arguments, or `null` when one of them is not a plain literal.
     * @property target The declaration it applies to.
     */
    private class Found(val line: Int, val ids: List<String>?, val target: String)

    /**
     * A single pass over one Kotlin file that knows just enough of the language to
     * tell code from comments and strings, read an annotation's arguments, and name
     * the declaration after it.
     */
    private class SuppressionLexer(private val s: String) {

        /** Every suppression annotation in the file, in source order. */
        fun suppressions(): List<Found> {
            val result = mutableListOf<Found>()
            var i = 0
            while (i < s.length) {
                val next = skipNonCode(i)
                if (next != i) {
                    i = next
                    continue
                }
                if (s[i] == '@' && (i == 0 || !isIdentifierPart(s[i - 1]))) {
                    val found = readSuppression(i)
                    if (found != null) {
                        result += found.first
                        i = found.second
                        continue
                    }
                }
                i++
            }
            return result
        }

        /**
         * Reads the annotation at [at] if it is a suppression.
         *
         * @return The suppression and the index just past its argument list, or
         *   `null` for any other annotation.
         */
        private fun readSuppression(at: Int): Pair<Found, Int>? {
            val head = readAnnotationHead(at) ?: return null
            if (head.name.substringAfterLast('.') !in SUPPRESS_NAMES) return null
            val open = skipSpaces(head.end)
            if (open >= s.length || s[open] != '(') return null
            val close = matchingParen(open) ?: return null
            val ids = readStringArguments(open + 1, close)
            val target = if (head.useSite == "file") FILE_TARGET else targetAfter(close + 1)
            return Found(lineOf(at), ids, target) to close + 1
        }

        /**
         * The string literals between [from] and [to], or `null` when anything other
         * than literals, commas, brackets, `names =` and comments stands there.
         */
        private fun readStringArguments(from: Int, to: Int): List<String>? {
            val ids = mutableListOf<String>()
            var i = from
            while (i < to) {
                val c = s[i]
                when {
                    c.isWhitespace() || c == ',' || c == '[' || c == ']' || c == '=' -> i++
                    startsComment(i) -> i = skipNonCode(i)
                    s.startsWith("names", i) && !isIdentifierPart(s.getOrElse(i + 5) { ' ' }) -> i += 5
                    c == '"' && !s.startsWith("\"\"\"", i) -> {
                        val end = skipString(i)
                        val body = s.substring(i + 1, end - 1)
                        if ('$' in body || '\\' in body) return null
                        ids += body
                        i = end
                    }
                    else -> return null
                }
            }
            return ids
        }

        /**
         * Names the declaration that starts after an annotation ending at [from]:
         * skips further annotations and modifiers, then reads the keyword and name.
         */
        private fun targetAfter(from: Int): String {
            var i = from
            while (true) {
                i = skipTrivia(i)
                if (i >= s.length) return UNKNOWN_TARGET
                if (s[i] == '@') {
                    i = skipAnnotation(i) ?: return UNKNOWN_TARGET
                    continue
                }
                val word = readIdentifier(i) ?: return UNKNOWN_TARGET
                if (word in MODIFIERS) {
                    i += word.length
                    continue
                }
                return describe(word, i + word.length)
            }
        }

        /** Renders the declaration introduced by [keyword], whose text continues at [from]. */
        private fun describe(keyword: String, from: Int): String = when (keyword) {
            in BARE_KEYWORDS -> keyword
            in NAMED_KEYWORDS -> "$keyword ${readIdentifier(skipTrivia(from)) ?: ""}".trimEnd()
            "fun" -> describeFunction(from)
            "val", "var" -> "$keyword ${readQualifiedName(skipTrivia(from))}".trimEnd()
            else -> keyword
        }

        /**
         * Renders a function: `fun name`, `fun Receiver.name`, or `interface Name` for
         * a `fun interface`. Type parameters before the name are dropped; whitespace in
         * the receiver is collapsed so the rendering is one line.
         */
        private fun describeFunction(from: Int): String {
            var i = skipTrivia(from)
            if (readIdentifier(i) == "interface") {
                return "interface ${readIdentifier(skipTrivia(i + "interface".length)) ?: ""}".trimEnd()
            }
            if (i < s.length && s[i] == '<') i = skipBalanced(i, '<', '>') ?: return "fun"
            val end = s.indexOf('(', i).takeIf { it >= 0 } ?: return "fun"
            val signature = s.substring(i, end).replace(WHITESPACE, "")
            return "fun $signature".trimEnd()
        }

        /** An identifier with dots and type arguments, as a receiver-qualified name is written. */
        private fun readQualifiedName(from: Int): String {
            var i = from
            while (i < s.length && (isIdentifierPart(s[i]) || s[i] == '.' || s[i] == '<' || s[i] == '>')) i++
            return s.substring(from, i)
        }

        /**
         * Reads `@useSite:Qualified.Name` at [at].
         *
         * @return The use-site target (or `null`), the name, and the index after the
         *   name; `null` when no name follows the `@`.
         */
        private fun readAnnotationHead(at: Int): AnnotationHead? {
            var i = at + 1
            var useSite: String? = null
            val first = readIdentifier(i) ?: return null
            if (s.getOrNull(i + first.length) == ':' && s.getOrNull(i + first.length + 1) != ':') {
                useSite = first
                i += first.length + 1
            }
            val nameStart = i
            while (true) {
                val part = readIdentifier(i) ?: return null
                i += part.length
                if (s.getOrNull(i) == '.' && readIdentifier(i + 1) != null) i++ else break
            }
            return AnnotationHead(useSite, s.substring(nameStart, i), i)
        }

        /** Skips any annotation at [at] — `@Name`, `@Name(…)`, `@site:Name(…)`, `@[A B]`. */
        private fun skipAnnotation(at: Int): Int? {
            if (s.getOrNull(at + 1) == '[') return skipBalanced(at + 1, '[', ']')
            val head = readAnnotationHead(at) ?: return null
            val open = skipSpaces(head.end)
            return if (open < s.length && s[open] == '(') matchingParen(open)?.plus(1) else head.end
        }

        /** Skips whitespace and comments. */
        private fun skipTrivia(from: Int): Int {
            var i = from
            while (i < s.length) {
                i = when {
                    s[i].isWhitespace() -> i + 1
                    startsComment(i) -> skipNonCode(i)
                    else -> return i
                }
            }
            return i
        }

        /** Skips spaces and tabs only — an annotation's arguments start on its own line. */
        private fun skipSpaces(from: Int): Int {
            var i = from
            while (i < s.length && (s[i] == ' ' || s[i] == '\t')) i++
            return i
        }

        /** Index of the `)` closing the `(` at [open], stepping over strings and comments. */
        private fun matchingParen(open: Int): Int? = skipBalanced(open, '(', ')')?.minus(1)

        /**
         * Index just past the [close] that balances the [open] at [at], stepping over
         * strings, characters and comments.
         */
        private fun skipBalanced(at: Int, open: Char, close: Char): Int? {
            var depth = 0
            var i = at
            while (i < s.length) {
                val next = skipNonCode(i)
                if (next != i) {
                    i = next
                    continue
                }
                when (s[i]) {
                    open -> depth++
                    close -> if (--depth == 0) return i + 1
                }
                i++
            }
            return null
        }

        /** If a comment, string or character literal starts at [i], the index after it; else [i]. */
        private fun skipNonCode(i: Int): Int = when {
            s.startsWith("//", i) -> s.indexOf('\n', i).let { if (it < 0) s.length else it }
            s.startsWith("/*", i) -> skipBlockComment(i)
            s.startsWith("\"\"\"", i) -> skipRawString(i)
            s[i] == '"' -> skipString(i)
            s[i] == '\'' -> skipCharLiteral(i)
            else -> i
        }

        private fun startsComment(i: Int): Boolean = s.startsWith("//", i) || s.startsWith("/*", i)

        /** Block comments nest in Kotlin: `/* a /* b */ c */` is one comment. */
        private fun skipBlockComment(at: Int): Int {
            var depth = 0
            var i = at
            while (i < s.length) {
                when {
                    s.startsWith("/*", i) -> {
                        depth++
                        i += 2
                    }
                    s.startsWith("*/", i) -> {
                        i += 2
                        if (--depth == 0) return i
                    }
                    else -> i++
                }
            }
            return s.length
        }

        /** Skips a `"…"` literal, including `${…}` templates that may hold strings of their own. */
        private fun skipString(at: Int): Int {
            var i = at + 1
            while (i < s.length) {
                when {
                    s[i] == '\\' -> i += 2
                    s[i] == '"' -> return i + 1
                    s.startsWith("\${", i) -> i = skipBalanced(i + 1, '{', '}') ?: return s.length
                    else -> i++
                }
            }
            return s.length
        }

        /** Skips a `"""…"""` literal; a run of more than three quotes closes on its last three. */
        private fun skipRawString(at: Int): Int {
            var i = at + 3
            while (i < s.length) {
                when {
                    s.startsWith("\"\"\"", i) -> {
                        var end = i + 3
                        while (end < s.length && s[end] == '"') end++
                        return end
                    }
                    s.startsWith("\${", i) -> i = skipBalanced(i + 1, '{', '}') ?: return s.length
                    else -> i++
                }
            }
            return s.length
        }

        /** Skips `'x'`, `'\n'`, `'A'`. */
        private fun skipCharLiteral(at: Int): Int {
            var i = at + 1
            while (i < s.length && s[i] != '\'') i += if (s[i] == '\\') 2 else 1
            return minOf(i + 1, s.length)
        }

        private fun readIdentifier(from: Int): String? {
            if (from >= s.length) return null
            if (s[from] == '`') {
                val end = s.indexOf('`', from + 1)
                return if (end < 0) null else s.substring(from, end + 1)
            }
            if (!isIdentifierStart(s[from])) return null
            var i = from + 1
            while (i < s.length && isIdentifierPart(s[i])) i++
            return s.substring(from, i)
        }

        private fun lineOf(index: Int): Int = 1 + (0 until index).count { s[it] == '\n' }

        private fun isIdentifierStart(c: Char): Boolean = c.isLetter() || c == '_'

        private fun isIdentifierPart(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

        /** `@useSite:Name` read from the text. */
        private class AnnotationHead(val useSite: String?, val name: String, val end: Int)
    }

    /** The two annotation names a suppression is written with. */
    private val SUPPRESS_NAMES: Set<String> = setOf("Suppress", "SuppressWarnings")

    /** Target recorded when no declaration can be read after the annotation. */
    private const val UNKNOWN_TARGET: String = "?"

    private val WHITESPACE: Regex = Regex("""\s+""")
}
