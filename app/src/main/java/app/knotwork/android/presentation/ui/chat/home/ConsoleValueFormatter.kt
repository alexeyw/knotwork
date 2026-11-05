package app.knotwork.android.presentation.ui.chat.home

import app.knotwork.android.presentation.common.JsonLayout
import app.knotwork.design.components.console.ConsoleValueBody
import app.knotwork.design.components.console.ConsoleValueFormat
import app.knotwork.design.components.console.ConsoleValueLine

/** Columns a tab advances to the next stop of, in a text value. */
private const val TAB_STOP = 4

/**
 * Formats a Vars value for the console's value viewer. Pure and unbounded in time — a
 * value can be a megabyte — so callers run it off the main thread.
 *
 * A value whose trimmed text starts with `{` or `[` and parses as JSON is laid out by
 * [JsonLayout.lines], the layout every JSON surface of the app shares: one key or item per
 * line, an empty object or array kept on its line, every token copied as it was written
 * except a character that would not show. A value that starts like JSON but does not
 * parse is shown as text and named [ConsoleValueFormat.NotValidJson]; anything else is
 * text, split on `\r\n`, `\r` and `\n`, with each tab expanded to the next stop of
 * [TAB_STOP] columns.
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
            JsonLayout.lines(trimmed)?.let { return ConsoleValueBody.Ready(ConsoleValueFormat.Json, it) }
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
