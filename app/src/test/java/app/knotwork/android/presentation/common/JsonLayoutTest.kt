package app.knotwork.android.presentation.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cases of the shared JSON layout one by one; [JsonLayoutLosslessTest] holds the rule over
 * generated documents.
 */
class JsonLayoutTest {

    @Test
    fun `given nested JSON when indented then it is laid out one key or item per line`() {
        val json = """{"path":"a.md","meta":{"tags":["x","y"],"empty":{},"none":[]},"n":1}"""

        assertEquals(
            """
            {
              "path": "a.md",
              "meta": {
                "tags": [
                  "x",
                  "y"
                ],
                "empty": {},
                "none": []
              },
              "n": 1
            }
            """.trimIndent(),
            JsonLayout.indented(json),
        )
    }

    @Test
    fun `given nested JSON when laid out on one line then a space follows each colon and comma only`() {
        val json = "{ \"path\" :\"a.md\",\n\t\"meta\":{\"tags\":[ \"x\" , \"y\" ],\"empty\":{ }}}"

        assertEquals("""{"path": "a.md", "meta": {"tags": ["x", "y"], "empty": {}}}""", JsonLayout.singleLine(json))
    }

    @Test
    fun `given escapes, number spellings and a repeated key when laid out then every token is copied as written`() {
        // org.json on Android writes `/` as `\/` and reads numbers into Long or Double:
        // 1.50 would come back as 1.5, 1E+3 as 1000.0, the big integer as 1.2345678901234568E22.
        val json = """{"url":"https:\/\/x.org","u":"\u00e9","q":"say \"hi\"","n":1.50,"e":1E+3,""" +
            """"z":-0,"big":12345678901234567890123,"k":1,"k":2}"""

        assertEquals(
            """{"url": "https:\/\/x.org", "u": "\u00e9", "q": "say \"hi\"", "n": 1.50, "e": 1E+3, """ +
                """"z": -0, "big": 12345678901234567890123, "k": 1, "k": 2}""",
            JsonLayout.singleLine(json),
        )
    }

    @Test
    fun `given a raw slash and raw non-ASCII text when laid out then they stay raw`() {
        assertEquals(
            """{"path": "/Users/me/заметки.md", "emoji": "😀"}""",
            JsonLayout.singleLine("""{"path":"/Users/me/заметки.md","emoji":"😀"}"""),
        )
    }

    @Test
    fun `given characters that would not show inside strings when laid out then each is written as its escape`() {
        // Direction override, zero-width space, byte-order mark, C1 next-line, line separator,
        // a lone surrogate, and a tag character (a format character outside the BMP: both halves).
        val unseen = "\u202E\u200B\uFEFF\u0085\u2028\uD800\uDB40\uDC41\u00AD"
        val json = "{\"k$unseen\":\"v$unseen\"}"
        val escaped = "\\u202E\\u200B\\uFEFF\\u0085\\u2028\\uD800\\uDB40\\uDC41\\u00AD"

        assertEquals("{\"k$escaped\": \"v$escaped\"}", JsonLayout.singleLine(json))
    }

    @Test
    fun `given a joiner inside an emoji sequence when laid out then the joiner is escaped and the emoji are not`() {
        assertEquals("[\"👨\\u200D👩\"]", JsonLayout.singleLine("[\"👨\u200D👩\"]"))
    }

    @Test
    fun `given text that is not valid JSON when laid out then it is returned exactly as it came`() {
        val invalid = listOf(
            "",
            "   ",
            "{\"a\":1,}",
            "[1,2,]",
            "{'a':1}",
            "{a:1}",
            "{\"a\":\"\\x\"}",
            "{\"a\":\"\\u12\"}",
            "{\"a\":\"tab\there\"}",
            "{\"a\":\"never closes}",
            "{\"a\":[1,2}",
            "[1,2",
            "{\"a\":1} trailing",
            "{\"a\" 1}",
            "{\"a\":1 \"b\":2}",
            "01",
            "1.",
            ".5",
            "+1",
            "-",
            "1e",
            "NaN",
            "nul",
            "truex",
            "\uFEFF{}",
            "{path: '/var/log',}\n",
        )
        for (text in invalid) {
            assertEquals("indented: $text", text, JsonLayout.indented(text))
            assertEquals("singleLine: $text", text, JsonLayout.singleLine(text))
            assertFalse("isValid: $text", JsonLayout.isValid(text))
            assertEquals("lines: $text", null, JsonLayout.lines(text))
        }
    }

    @Test
    fun `given a scalar or whitespace around a value when checked then it is valid JSON`() {
        for (text in listOf("42", "-0.5e-3", "\"s\"", "true", "false", "null", "  \n{}\t", "[]")) {
            assertTrue(text, JsonLayout.isValid(text))
        }
        assertEquals("\"s\"", JsonLayout.indented("  \"s\"  "))
    }

    @Test
    fun `given nesting deeper than the indent cap when indented then deeper lines keep the last level's indent`() {
        val depth = JsonLayout.MAX_INDENT_LEVELS + 2
        val json = "[".repeat(depth) + "1" + "]".repeat(depth)

        val widest = JsonLayout.indented(json).lines().maxOf { line -> line.takeWhile { it == ' ' }.length }

        assertEquals(JsonLayout.MAX_INDENT_LEVELS * 2, widest)
    }

    @Test
    fun `given JSON nested a hundred thousand deep when laid out then the stack does not overflow`() {
        val depth = 100_000
        val json = "[".repeat(depth) + "]".repeat(depth)

        assertEquals(json, JsonLayout.singleLine(json).filterNot { it == ' ' })
        assertTrue(JsonLayout.indented(json).startsWith("[\n  [\n"))
    }

    @Test
    fun `given a JSON object when laid out in lines then each line carries its depth`() {
        val lines = checkNotNull(JsonLayout.lines("""{"a":[1]}"""))

        assertEquals(listOf(0, 1, 2, 1, 0), lines.map { it.indent })
        assertEquals(listOf("{", "\"a\": [", "1", "]", "}"), lines.map { it.text })
    }
}
