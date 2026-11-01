package app.knotwork.android.presentation.ui.chat.home

import app.knotwork.design.components.console.ConsoleValueBody
import app.knotwork.design.components.console.ConsoleValueFormat
import app.knotwork.design.components.console.ConsoleValueLine
import app.knotwork.design.components.console.ConsoleValueSpan
import app.knotwork.design.components.console.ConsoleValueToken

/** Columns a tab advances to the next stop of, in a text value. */
private const val TAB_STOP = 4

/**
 * Formats a Vars value for the console's value viewer. Pure and unbounded in time — a
 * value can be a megabyte — so callers run it off the main thread.
 *
 * A value whose trimmed text starts with `{` or `[` and parses as JSON is laid out the
 * way `JSON.stringify(value, null, 2)` lays it out: one key or item per line, an empty
 * object or array kept on its line. Unlike `org.json`, every token is copied as it was
 * written — a string keeps its escapes (`org.json` rewrites `/` as `\/`), a number its
 * spelling. The grammar is checked token by token; what follows a backslash inside a
 * string is not, since it is copied as written either way. A value that starts like JSON
 * but does not parse is shown as text and named
 * [ConsoleValueFormat.NotValidJson]; anything else is text, split on `\r\n`, `\r` and
 * `\n`, with each tab expanded to the next stop of [TAB_STOP] columns.
 */
internal object ConsoleValueFormatter {

    /**
     * Formats [value].
     *
     * @param value the value as the node had it.
     * @return the format the value was read as and its lines.
     */
    fun format(value: String): ConsoleValueBody.Ready {
        val trimmed = value.trim()
        if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
            JsonLayout(trimmed).layOut()?.let { return ConsoleValueBody.Ready(ConsoleValueFormat.Json, it) }
            return ConsoleValueBody.Ready(ConsoleValueFormat.NotValidJson, textLines(value))
        }
        return ConsoleValueBody.Ready(ConsoleValueFormat.Text, textLines(value))
    }

    /**
     * Splits [value] into its lines on `\r\n`, `\r` and `\n`, blank lines kept, and
     * expands each tab to the next stop of [TAB_STOP] columns.
     */
    fun textLines(value: String): List<ConsoleValueLine> {
        val lines = ArrayList<ConsoleValueLine>()
        val line = StringBuilder()
        var i = 0
        while (i < value.length) {
            when (val c = value[i]) {
                '\r', '\n' -> {
                    lines += ConsoleValueLine(indent = 0, text = line.toString())
                    line.setLength(0)
                    if (c == '\r' && i + 1 < value.length && value[i + 1] == '\n') i++
                }
                '\t' -> repeat(TAB_STOP - line.length % TAB_STOP) { line.append(' ') }
                else -> line.append(c)
            }
            i++
        }
        lines += ConsoleValueLine(indent = 0, text = line.toString())
        return lines
    }
}

/**
 * One pass over a JSON text that both checks its grammar and lays it out. Iterative,
 * with an explicit stack, so a deeply nested value cannot overflow the thread's stack.
 *
 * @param json the trimmed value, starting with `{` or `[`.
 */
private class JsonLayout(private val json: String) {

    /** What the grammar allows next. */
    private enum class Expect { Value, ValueOrClose, Key, KeyOrClose, Colon, CommaOrClose, End }

    private val lines = ArrayList<ConsoleValueLine>()
    private val line = StringBuilder()
    private val spans = ArrayList<ConsoleValueSpan>()
    private var lineIndent = 0

    /** Open containers, innermost last: `true` for an object, `false` for an array. */
    private val containers = ArrayList<Boolean>()
    private var expect = Expect.Value
    private var i = 0

    /** Lays the value out, or returns `null` when it is not valid JSON. */
    fun layOut(): List<ConsoleValueLine>? {
        while (true) {
            skipWhitespace()
            if (i == json.length) break
            if (!step()) return null
        }
        if (expect != Expect.End) return null
        flush()
        return lines
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
        if (expect != Expect.Value && expect != Expect.ValueOrClose) return false
        append(if (isObject) "{" else "[", ConsoleValueToken.Punctuation)
        i++
        skipWhitespace()
        val closer = if (isObject) '}' else ']'
        if (i < json.length && json[i] == closer) {
            // An empty container stays on its line, as JSON.stringify writes it.
            append(closer.toString(), ConsoleValueToken.Punctuation)
            i++
            afterValue()
            return true
        }
        containers += isObject
        expect = if (isObject) Expect.KeyOrClose else Expect.ValueOrClose
        newLine()
        return true
    }

    private fun close(isObject: Boolean): Boolean {
        val allowed = when (expect) {
            Expect.CommaOrClose -> true
            Expect.KeyOrClose, Expect.ValueOrClose -> true
            else -> false
        }
        if (!allowed || containers.isEmpty() || containers.last() != isObject) return false
        containers.removeAt(containers.lastIndex)
        newLine()
        append(if (isObject) "}" else "]", ConsoleValueToken.Punctuation)
        i++
        afterValue()
        return true
    }

    private fun comma(): Boolean {
        if (expect != Expect.CommaOrClose) return false
        append(",", ConsoleValueToken.Punctuation)
        i++
        expect = if (containers.last()) Expect.Key else Expect.Value
        newLine()
        return true
    }

    private fun colon(): Boolean {
        if (expect != Expect.Colon) return false
        append(":", ConsoleValueToken.Punctuation)
        line.append(' ')
        i++
        expect = Expect.Value
        return true
    }

    private fun string(): Boolean {
        val isKey = expect == Expect.Key || expect == Expect.KeyOrClose
        if (!isKey && expect != Expect.Value && expect != Expect.ValueOrClose) return false
        val end = stringEnd(i) ?: return false
        append(json.substring(i, end), if (isKey) ConsoleValueToken.Key else ConsoleValueToken.Str)
        i = end
        if (isKey) expect = Expect.Colon else afterValue()
        return true
    }

    private fun scalar(): Boolean {
        if (expect != Expect.Value && expect != Expect.ValueOrClose) return false
        for (literal in LITERALS) {
            if (json.startsWith(literal, i)) {
                append(literal, ConsoleValueToken.Literal)
                i += literal.length
                afterValue()
                return true
            }
        }
        val end = numberEnd(i) ?: return false
        append(json.substring(i, end), ConsoleValueToken.Number)
        i = end
        afterValue()
        return true
    }

    /** After a whole value: inside a container a comma or its close follows, at the top nothing. */
    private fun afterValue() {
        expect = if (containers.isEmpty()) Expect.End else Expect.CommaOrClose
    }

    /**
     * Index after the string that opens at [start], or `null` when it never closes or
     * holds a raw control character.
     */
    private fun stringEnd(start: Int): Int? {
        var j = start + 1
        while (j < json.length) {
            val c = json[j]
            when {
                c == '"' -> return j + 1
                c == '\\' -> j += 2
                c < ' ' -> return null
                else -> j++
            }
        }
        return null
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
        while (j < json.length && json[j].isAsciiDigit()) j++
        return if (j == start) null else j
    }

    private fun skipWhitespace() {
        while (i < json.length && json[i].let { it == ' ' || it == '\n' || it == '\r' || it == '\t' }) i++
    }

    private fun append(text: String, token: ConsoleValueToken) {
        val start = line.length
        line.append(text)
        spans += ConsoleValueSpan(start, line.length, token)
    }

    /** Ends the current line and starts one at the current depth. */
    private fun newLine() {
        flush()
        lineIndent = containers.size
    }

    private fun flush() {
        lines += ConsoleValueLine(indent = lineIndent, text = line.toString(), spans = spans.toList())
        line.setLength(0)
        spans.clear()
    }

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

    private companion object {
        val LITERALS = listOf("true", "false", "null")
    }
}
