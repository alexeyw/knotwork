package app.knotwork.android.presentation.ui.chat.home

import app.knotwork.design.components.console.ConsoleValueFormat
import app.knotwork.design.components.console.ConsoleValueLine
import app.knotwork.design.components.console.ConsoleValueToken
import org.junit.Assert.assertEquals
import org.junit.Test

/** How [ConsoleValueFormatter] reads and lays out a Vars value for the value viewer. */
class ConsoleValueFormatterTest {

    /** Each line as its indent in spaces, two per level, then its text. */
    private fun List<ConsoleValueLine>.rendered(): List<String> = map { " ".repeat(it.indent * 2) + it.text }

    @Test
    fun `given a JSON object when formatted then it is laid out one key per line, as JSON stringify does`() {
        val body = ConsoleValueFormatter.format("""{"a":1,"b":[1, 2],"c":{},"d":[]}""")

        assertEquals(ConsoleValueFormat.Json, body.format)
        assertEquals(
            listOf("{", "  \"a\": 1,", "  \"b\": [", "    1,", "    2", "  ],", "  \"c\": {},", "  \"d\": []", "}"),
            body.lines.rendered(),
        )
    }

    @Test
    fun `given escapes and spelled-out numbers when formatted then every token is copied as written`() {
        // org.json reads numbers into Long or Double and writes them back (JSONTokener.readLiteral,
        // JSONObject.numberToString): 1.50 would come out as 1.5 and 1E+3 as 1000. The viewer shows
        // what arrived.
        val body = ConsoleValueFormatter.format("""{"url":"https:\/\/x.org\/a","n":1.50,"e":1E+3,"u":"\u00e9"}""")

        assertEquals(
            listOf(
                "{",
                "  \"url\": \"https:\\/\\/x.org\\/a\",",
                "  \"n\": 1.50,",
                "  \"e\": 1E+3,",
                "  \"u\": \"\\u00e9\"",
                "}",
            ),
            body.lines.rendered(),
        )
    }

    @Test
    fun `given a JSON line when formatted then its key, string, number, literal and punctuation are told apart`() {
        val line = ConsoleValueFormatter.format("""{"k":"v","n":-2,"t":true}""").lines[1]

        assertEquals("\"k\": \"v\",", line.text)
        assertEquals(
            listOf(
                "\"k\"" to ConsoleValueToken.Key,
                ":" to ConsoleValueToken.Punctuation,
                "\"v\"" to ConsoleValueToken.Str,
                "," to ConsoleValueToken.Punctuation,
            ),
            line.spans.map { line.text.substring(it.start, it.end) to it.token },
        )
        val last = ConsoleValueFormatter.format("""{"t":true,"n":-2}""").lines
        assertEquals(ConsoleValueToken.Literal, last[1].spans[2].token)
        assertEquals(ConsoleValueToken.Number, last[2].spans[2].token)
    }

    @Test
    fun `given JSON that does not parse when formatted then it is text and named not valid JSON`() {
        listOf(
            """{"a":1,}""",
            """[1,2""",
            """{"a" 1}""",
            """{"a":01}""",
            """{"a":"raw""" + "\n" + """line"}""",
            """{} trailing""",
            """{"a":tru}""",
        ).forEach { value ->
            val body = ConsoleValueFormatter.format(value)
            assertEquals(value, ConsoleValueFormat.NotValidJson, body.format)
        }
    }

    @Test
    fun `given a body cut at a budget when formatted then its own lines are kept`() {
        val body = ConsoleValueFormatter.format("{\"items\":[\n  {\"id\":1},\n  {\"id\":")

        assertEquals(ConsoleValueFormat.NotValidJson, body.format)
        assertEquals(listOf("{\"items\":[", "  {\"id\":1},", "  {\"id\":"), body.lines.rendered())
    }

    @Test
    fun `given text with line breaks, a blank line and tabs when formatted then lines and tab stops are kept`() {
        val body = ConsoleValueFormatter.format("one\r\ntwo\rthree\n\na\tb\tc")

        assertEquals(ConsoleValueFormat.Text, body.format)
        assertEquals(listOf("one", "two", "three", "", "a   b   c"), body.lines.map { it.text })
    }

    @Test
    fun `given JSON nested a hundred thousand deep when formatted then it is laid out without overflowing the stack`() {
        val depth = 100_000
        val body = ConsoleValueFormatter.format("[".repeat(depth) + "1" + "]".repeat(depth))

        assertEquals(ConsoleValueFormat.Json, body.format)
        assertEquals(depth, body.lines.maxOf { it.indent })
    }
}
