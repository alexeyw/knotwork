package app.knotwork.android.presentation.common

import app.knotwork.design.components.console.ConsoleValueLine
import app.knotwork.design.components.console.ConsoleValueSpan
import app.knotwork.design.components.console.ConsoleValueToken

/**
 * The one way the app lays JSON out for a person to read: the arguments of a tool call
 * waiting for approval (the chat card and the notification), a tool's parameter schema,
 * and a value opened in the console.
 *
 * Laying out changes whitespace between tokens and nothing else. Every token is copied as
 * it was written — a string keeps its escapes (`org.json` writes `/` as `\/` on Android),
 * a number its spelling (`org.json` turns `1.50` into `1.5`), a repeated key both its
 * entries — so what a surface shows parses to exactly what was sent. The one exception
 * is a character inside a string that would not show: a C1 control, a format character
 * (zero-width, direction override, byte-order mark), a line or paragraph separator, or a
 * lone surrogate is written as its `\uXXXX` escape. The escape reads back as the same
 * character, so the value is unchanged; what changes is that a direction override can no
 * longer reorder the text around it, and a zero-width character no longer hides.
 *
 * Text that is not valid JSON (RFC 8259) is returned exactly as it came: laying out a
 * guess would show something that was not sent. The grammar is checked token by token,
 * escapes included. One pass, iterative, so a deeply nested value cannot overflow the
 * thread's stack; linear in the input, so callers with a large value still run it off
 * the main thread.
 */
internal object JsonLayout {

    /**
     * Levels of indent [indented] writes at most; deeper lines keep the last level's
     * indent. The console's value viewer draws at most as many.
     */
    const val MAX_INDENT_LEVELS: Int = 6

    /** Spaces of indent per level, as `JSON.stringify(value, null, 2)` writes them. */
    private const val INDENT_WIDTH = 2

    /**
     * Lays [json] out one key or item per line, an empty object or array kept on its
     * line, each line with its depth and the kind of every token on it.
     *
     * @param json the text to lay out; whitespace around the value is ignored.
     * @return the lines, or `null` when [json] is not valid JSON.
     */
    fun lines(json: String): List<ConsoleValueLine>? {
        val sink = LineSink()
        return if (Walker(json, sink).walk()) sink.finish() else null
    }

    /**
     * Lays [json] out as [lines] does, as text: each line indented by two spaces per
     * level, at most [MAX_INDENT_LEVELS] levels.
     *
     * @param json the text to lay out.
     * @return the laid-out text, or [json] unchanged when it is not valid JSON.
     */
    fun indented(json: String): String {
        val sink = IndentedTextSink()
        return if (Walker(json, sink).walk()) sink.finish() else json
    }

    /**
     * Lays [json] out on one line, with a space after every `:` and `,` and nowhere
     * else: `{"path": "a.md", "tags": ["x", "y"], "meta": {}}`.
     *
     * @param json the text to lay out.
     * @return the laid-out text, or [json] unchanged when it is not valid JSON.
     */
    fun singleLine(json: String): String {
        val sink = SingleLineSink()
        return if (Walker(json, sink).walk()) sink.finish() else json
    }

    /**
     * Whether [json] is valid JSON, by the same grammar the layouts check.
     *
     * @param json the text to check; whitespace around the value is ignored.
     * @return `true` when [json] holds exactly one valid JSON value.
     */
    fun isValid(json: String): Boolean = Walker(json, NoSink).walk()

    /** Receives the structure [Walker] reads, in order, and lays it out. */
    private interface Sink {
        fun open(bracket: String)
        fun close(bracket: String)
        fun empty(brackets: String)
        fun comma()
        fun colon()
        fun token(text: String, kind: ConsoleValueToken)
    }

    /** Lays out nothing: [isValid] needs only the walk's verdict. */
    private object NoSink : Sink {
        override fun open(bracket: String) = Unit
        override fun close(bracket: String) = Unit
        override fun empty(brackets: String) = Unit
        override fun comma() = Unit
        override fun colon() = Unit
        override fun token(text: String, kind: ConsoleValueToken) = Unit
    }

    /** One key or item per line; how a line is written is the subclass's. */
    private abstract class IndentingSink : Sink {
        protected var depth = 0

        protected abstract fun append(text: String, kind: ConsoleValueToken)

        protected abstract fun newLine()

        override fun open(bracket: String) {
            append(bracket, ConsoleValueToken.Punctuation)
            depth++
            newLine()
        }

        override fun close(bracket: String) {
            depth--
            newLine()
            append(bracket, ConsoleValueToken.Punctuation)
        }

        override fun empty(brackets: String) = append(brackets, ConsoleValueToken.Punctuation)

        override fun comma() {
            append(",", ConsoleValueToken.Punctuation)
            newLine()
        }

        override fun colon() {
            append(":", ConsoleValueToken.Punctuation)
            appendSpace()
        }

        protected abstract fun appendSpace()

        override fun token(text: String, kind: ConsoleValueToken) = append(text, kind)
    }

    /** Lines with their depth and token spans, for the console's value viewer. */
    private class LineSink : IndentingSink() {
        private val lines = ArrayList<ConsoleValueLine>()
        private val line = StringBuilder()
        private val spans = ArrayList<ConsoleValueSpan>()
        private var lineDepth = 0

        override fun append(text: String, kind: ConsoleValueToken) {
            val start = line.length
            line.append(text)
            spans += ConsoleValueSpan(start, line.length, kind)
        }

        override fun appendSpace() {
            line.append(' ')
        }

        override fun newLine() {
            flush()
            lineDepth = depth
        }

        private fun flush() {
            lines += ConsoleValueLine(indent = lineDepth, text = line.toString(), spans = spans.toList())
            line.setLength(0)
            spans.clear()
        }

        fun finish(): List<ConsoleValueLine> {
            flush()
            return lines
        }
    }

    /** The same lines as text, indented by spaces. */
    private class IndentedTextSink : IndentingSink() {
        private val out = StringBuilder()

        override fun append(text: String, kind: ConsoleValueToken) {
            out.append(text)
        }

        override fun appendSpace() {
            out.append(' ')
        }

        override fun newLine() {
            out.append('\n')
            repeat(minOf(depth, MAX_INDENT_LEVELS) * INDENT_WIDTH) { out.append(' ') }
        }

        fun finish(): String = out.toString()
    }

    /** Everything on one line. */
    private class SingleLineSink : Sink {
        private val out = StringBuilder()

        override fun open(bracket: String) {
            out.append(bracket)
        }

        override fun close(bracket: String) {
            out.append(bracket)
        }

        override fun empty(brackets: String) {
            out.append(brackets)
        }

        override fun comma() {
            out.append(", ")
        }

        override fun colon() {
            out.append(": ")
        }

        override fun token(text: String, kind: ConsoleValueToken) {
            out.append(text)
        }

        fun finish(): String = out.toString()
    }

    /**
     * One pass over [json] that checks its grammar and hands what it reads to [sink].
     *
     * @param json the text to read.
     * @param sink where the structure goes; its output means nothing unless [walk]
     * returns `true`.
     */
    private class Walker(private val json: String, private val sink: Sink) {

        /** What the grammar allows next. */
        private enum class Expect { Value, Key, Colon, CommaOrClose, End }

        /** Open containers, innermost last: `true` for an object, `false` for an array. */
        private val containers = ArrayList<Boolean>()
        private var expect = Expect.Value
        private var i = 0

        /** Reads the whole text; `false` when it is not exactly one valid JSON value. */
        fun walk(): Boolean {
            while (true) {
                skipWhitespace()
                if (i == json.length) break
                if (!step()) return false
            }
            return expect == Expect.End
        }

        /** Reads one token at [i]; `false` when the grammar does not allow it here. */
        private fun step(): Boolean {
            val c = json[i]
            return when {
                expect == Expect.End -> false
                c == '{' || c == '[' -> open(isObject = c == '{')
                c == '}' || c == ']' -> close(isObject = c == '}')
                c == ',' -> comma()
                c == ':' -> colon()
                c == '"' -> string()
                else -> scalar()
            }
        }

        private fun open(isObject: Boolean): Boolean {
            if (expect != Expect.Value) return false
            i++
            skipWhitespace()
            val closer = if (isObject) '}' else ']'
            if (i < json.length && json[i] == closer) {
                // An empty container stays on its line, as JSON.stringify writes it.
                sink.empty(if (isObject) "{}" else "[]")
                i++
                afterValue()
                return true
            }
            sink.open(if (isObject) "{" else "[")
            containers += isObject
            expect = if (isObject) Expect.Key else Expect.Value
            return true
        }

        private fun close(isObject: Boolean): Boolean {
            // A close right after its open was taken by [open]; after a comma it is a trailing comma.
            if (expect != Expect.CommaOrClose || containers.last() != isObject) return false
            containers.removeAt(containers.lastIndex)
            sink.close(if (isObject) "}" else "]")
            i++
            afterValue()
            return true
        }

        private fun comma(): Boolean {
            if (expect != Expect.CommaOrClose) return false
            sink.comma()
            i++
            expect = if (containers.last()) Expect.Key else Expect.Value
            return true
        }

        private fun colon(): Boolean {
            if (expect != Expect.Colon) return false
            sink.colon()
            i++
            expect = Expect.Value
            return true
        }

        private fun string(): Boolean {
            val isKey = expect == Expect.Key
            if (!isKey && expect != Expect.Value) return false
            val text = readString() ?: return false
            sink.token(text, if (isKey) ConsoleValueToken.Key else ConsoleValueToken.Str)
            if (isKey) expect = Expect.Colon else afterValue()
            return true
        }

        private fun scalar(): Boolean {
            if (expect != Expect.Value) return false
            for (literal in LITERALS) {
                if (json.startsWith(literal, i)) {
                    sink.token(literal, ConsoleValueToken.Literal)
                    i += literal.length
                    afterValue()
                    return true
                }
            }
            val end = numberEnd(i) ?: return false
            sink.token(json.substring(i, end), ConsoleValueToken.Number)
            i = end
            afterValue()
            return true
        }

        /** After a whole value: inside a container a comma or its close follows, at the top nothing. */
        private fun afterValue() {
            expect = if (containers.isEmpty()) Expect.End else Expect.CommaOrClose
        }

        /**
         * Reads the string that opens at [i] and moves past it. Returns the string as
         * written, quotes included, with each character that would not show escaped —
         * or `null` when it never closes, holds a raw control character or an escape
         * RFC 8259 does not have.
         */
        private fun readString(): String? {
            val out = StringBuilder()
            out.append('"')
            var j = i + 1
            while (j < json.length) {
                val c = json[j]
                when {
                    c == '"' -> {
                        out.append('"')
                        i = j + 1
                        return out.toString()
                    }
                    c == '\\' -> {
                        val length = escapeLength(j)
                        if (length == 0) return null
                        out.append(json, j, j + length)
                        j += length
                    }
                    c < ' ' -> return null
                    // Printable ASCII always shows: no need to ask its Unicode category.
                    c <= '~' -> {
                        out.append(c)
                        j++
                    }
                    else -> j = appendCharacter(out, j)
                }
            }
            return null
        }

        /** Length of the escape at [start] (a backslash), or 0 when it is not one RFC 8259 has. */
        private fun escapeLength(start: Int): Int {
            if (start + 1 >= json.length) return 0
            return when (json[start + 1]) {
                '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> 2
                'u' -> if (start + UNICODE_ESCAPE_LENGTH <= json.length &&
                    (start + 2 until start + UNICODE_ESCAPE_LENGTH).all { json[it].isHexDigit() }
                ) {
                    UNICODE_ESCAPE_LENGTH
                } else {
                    0
                }
                else -> 0
            }
        }

        /**
         * Appends the raw character at [index] — two chars for a surrogate pair — or its
         * escape when it would not show, and returns the index after it.
         */
        private fun appendCharacter(out: StringBuilder, index: Int): Int {
            val c = json[index]
            val paired = c.isHighSurrogate() && index + 1 < json.length && json[index + 1].isLowSurrogate()
            val length = if (paired) 2 else 1
            val codePoint = if (paired) Character.toCodePoint(c, json[index + 1]) else c.code
            if (isUnseen(codePoint)) {
                for (k in index until index + length) out.append(escapeOf(json[k]))
            } else {
                out.append(json, index, index + length)
            }
            return index + length
        }

        /** Index after the number that starts at [start], or `null` when there is none there. */
        private fun numberEnd(start: Int): Int? {
            var j = start
            if (j < json.length && json[j] == '-') j++
            j = integerEnd(j) ?: return null
            if (j < json.length && json[j] == '.') j = digitsEnd(j + 1) ?: return null
            if (j < json.length && (json[j] == 'e' || json[j] == 'E')) {
                j++
                if (j < json.length && (json[j] == '+' || json[j] == '-')) j++
                j = digitsEnd(j) ?: return null
            }
            return j
        }

        /** Index after a number's integer part at [start]: a lone `0`, or digits that do not start with one. */
        private fun integerEnd(start: Int): Int? = when {
            start < json.length && json[start] == '0' -> start + 1
            start < json.length && json[start] in '1'..'9' -> digitsEnd(start)
            else -> null
        }

        /** Index after one or more digits at [start], or `null` when there is none. */
        private fun digitsEnd(start: Int): Int? {
            var j = start
            while (j < json.length && json[j] in '0'..'9') j++
            return if (j == start) null else j
        }

        private fun skipWhitespace() {
            while (i < json.length && json[i].let { it == ' ' || it == '\n' || it == '\r' || it == '\t' }) i++
        }

        private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
    }

    /**
     * Whether [codePoint] would not show where it stands: a control character, a format
     * character, a line or paragraph separator, or a lone surrogate.
     */
    private fun isUnseen(codePoint: Int): Boolean = when (Character.getType(codePoint).toByte()) {
        Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR,
        Character.PARAGRAPH_SEPARATOR, Character.SURROGATE,
        -> true
        else -> false
    }

    /** The `\uXXXX` escape of [c]. */
    private fun escapeOf(c: Char): String = "\\u" + c.code.toString(HEX_RADIX).uppercase().padStart(HEX_DIGITS, '0')

    private const val UNICODE_ESCAPE_LENGTH = 6
    private const val HEX_RADIX = 16
    private const val HEX_DIGITS = 4
    private val LITERALS = listOf("true", "false", "null")
}
