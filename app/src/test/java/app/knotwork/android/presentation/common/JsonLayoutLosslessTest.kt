package app.knotwork.android.presentation.common

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Guard: laying JSON out never changes what it says, and text that is not JSON is shown as
 * it came.
 *
 * **Why.** The approval card and the notification show a tool call's arguments before the
 * user approves it, and the user approves what runs (`§76`). The card used to rebuild each
 * argument with `org.json`, which on Android writes `/` as `\/` and rewrites numbers
 * (`1.50` → `1.5`, a big integer → a `Double`), so it showed a different text from the one
 * an MCP server received. Every JSON surface now goes through [JsonLayout]; this test holds
 * the rule over generated documents, so a layout that drops, reorders or respells a token
 * fails here whichever surface would have shown it.
 *
 * **What it checks**, over documents from a fixed seed — nested objects and arrays, keys and
 * strings with escapes, raw non-ASCII, emoji and characters that would not show, numbers in
 * every spelling, whitespace of every kind between tokens:
 * - both layouts parse (strict `kotlinx.serialization`) to the same value as the original;
 * - with the whitespace outside strings removed, a layout is the original byte for byte
 *   (a repeated key, a number's spelling and a string's escapes included) — for documents
 *   without a character the layout escapes;
 * - no character that would not show is left raw in a layout;
 * - a mutated document the layout calls invalid is returned unchanged, and one it calls
 *   valid is valid for the strict parser too.
 */
class JsonLayoutLosslessTest {

    private val strict = Json

    @Test
    fun `given generated JSON when laid out then it parses to the same value as the original`() {
        val random = Random(SEED)
        repeat(DOCUMENTS) { n ->
            val document = Generator(random, unseen = n % 3 == 0, repeatKeys = false).document()
            // Text the layout calls invalid comes back unchanged, which would pass every check below.
            assertTrue("#$n is valid JSON but the layout refuses it: $document", JsonLayout.isValid(document))
            val original = strict.parseToJsonElement(document)
            for ((name, layout) in LAYOUTS) {
                val laidOut = layout(document)
                assertEquals("$name of #$n: $document", original, parse(laidOut, "$name of #$n"))
            }
        }
    }

    @Test
    fun `given generated JSON without hidden characters when laid out then only whitespace between tokens changes`() {
        val random = Random(SEED + 1)
        repeat(DOCUMENTS) { n ->
            // Repeated keys too: a parsed value keeps one of them, so only this check sees a dropped one.
            val document = Generator(random, unseen = false, repeatKeys = n % 2 == 0).document()
            assertTrue("#$n: $document", JsonLayout.isValid(document))
            for ((name, layout) in LAYOUTS) {
                assertEquals("$name of #$n: $document", tokensOnly(document), tokensOnly(layout(document)))
            }
        }
    }

    @Test
    fun `given generated JSON with hidden characters when laid out then none is left raw`() {
        val random = Random(SEED + 2)
        repeat(DOCUMENTS) { n ->
            val document = Generator(random, unseen = true, repeatKeys = false).document()
            assertTrue("#$n: $document", JsonLayout.isValid(document))
            for ((name, layout) in LAYOUTS) {
                val raw = layout(document).codePoints().filter { it.isUnseen() }.toArray().map { it.toString(16) }
                assertEquals("$name of #$n left raw", emptyList<String>(), raw)
            }
        }
    }

    @Test
    fun `given mutated JSON when laid out then invalid text is unchanged and valid text is strictly valid`() {
        val random = Random(SEED + 3)
        var invalidSeen = 0
        repeat(DOCUMENTS) { n ->
            val mutant = mutate(Generator(random, unseen = n % 3 == 0, repeatKeys = false).document(), random)
            if (JsonLayout.isValid(mutant)) {
                val original = parse(mutant, "mutant #$n")
                for ((name, layout) in LAYOUTS) {
                    assertEquals("$name of mutant #$n: $mutant", original, parse(layout(mutant), "$name #$n"))
                }
            } else {
                invalidSeen++
                for ((name, layout) in LAYOUTS) assertEquals("$name of mutant #$n", mutant, layout(mutant))
            }
        }
        // The mutations must actually break documents, or the second branch tests nothing.
        assertTrue("only $invalidSeen of $DOCUMENTS mutants were invalid", invalidSeen > DOCUMENTS / 2)
    }

    private fun parse(text: String, label: String): JsonElement = try {
        strict.parseToJsonElement(text)
    } catch (e: IllegalArgumentException) {
        throw AssertionError("$label does not parse: ${e.message}\n$text", e)
    }

    /** [json] with every whitespace character outside strings removed. */
    private fun tokensOnly(json: String): String {
        val out = StringBuilder()
        var inString = false
        var i = 0
        while (i < json.length) {
            val c = json[i]
            when {
                inString && c == '\\' -> {
                    out.append(c).append(json[i + 1])
                    i++
                }
                c == '"' -> {
                    inString = !inString
                    out.append(c)
                }
                inString || c !in " \t\r\n" -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    /** Breaks [json] in one of the ways a model's output breaks, or leaves it whole by chance. */
    private fun mutate(json: String, random: Random): String {
        val at = random.nextInt(json.length + 1)
        return when (random.nextInt(MUTATIONS)) {
            0 -> json.take(at)
            1 -> json.removeRange(minOf(at, json.length - 1), minOf(at + 1, json.length))
            2 -> json.substring(0, at) + BREAKERS[random.nextInt(BREAKERS.size)] + json.substring(at)
            3 -> json.replaceFirst("\"", "'")
            else -> json.trimEnd('}', ']') + ",}"
        }
    }

    /**
     * Writes random JSON documents.
     *
     * @param unseen whether strings may hold characters that would not show.
     * @param repeatKeys whether an object may repeat a key.
     */
    private class Generator(private val random: Random, private val unseen: Boolean, private val repeatKeys: Boolean) {
        private val out = StringBuilder()
        private var keyCounter = 0

        fun document(): String {
            out.setLength(0)
            space()
            if (random.nextBoolean()) obj(0) else array(0)
            space()
            return out.toString()
        }

        private fun value(depth: Int) {
            when (random.nextInt(if (depth >= MAX_DEPTH) SCALAR_KINDS else ALL_KINDS)) {
                0 -> string()
                1 -> number()
                2 -> out.append(listOf("true", "false", "null").random(random))
                3 -> obj(depth + 1)
                else -> array(depth + 1)
            }
        }

        private fun obj(depth: Int) {
            out.append('{')
            space()
            val keys = ArrayList<String>()
            repeat(random.nextInt(MAX_MEMBERS)) { n ->
                if (n > 0) comma()
                val key = if (repeatKeys && keys.isNotEmpty() && random.nextInt(4) == 0) keys.random(random) else key()
                keys += key
                out.append(key)
                space()
                out.append(':')
                space()
                value(depth)
                space()
            }
            out.append('}')
        }

        private fun array(depth: Int) {
            out.append('[')
            space()
            repeat(random.nextInt(MAX_MEMBERS)) { n ->
                if (n > 0) comma()
                value(depth)
                space()
            }
            out.append(']')
        }

        private fun comma() {
            out.append(',')
            space()
        }

        private fun space() {
            repeat(random.nextInt(3)) { out.append(" \t\r\n"[random.nextInt(4)]) }
        }

        /** A distinct key: a fresh suffix keeps a parsed object from merging two keys by chance. */
        private fun key(): String = quoted().dropLast(1) + "#${keyCounter++}\""

        private fun string() {
            out.append(quoted())
        }

        private fun quoted(): String {
            val s = StringBuilder("\"")
            repeat(random.nextInt(MAX_STRING)) {
                val pieces = if (unseen) PIECES + UNSEEN else PIECES
                s.append(pieces[random.nextInt(pieces.size)])
            }
            return s.append('"').toString()
        }

        private fun number() {
            if (random.nextBoolean()) out.append('-')
            if (random.nextInt(4) == 0) {
                out.append('0')
            } else {
                out.append(random.nextLong(1, Long.MAX_VALUE))
                // Past Long and Double precision: org.json would turn it into a Double.
                if (random.nextInt(3) == 0) out.append(random.nextInt(BIG_TAIL)).append("000000000000")
            }
            if (random.nextBoolean()) {
                out.append(
                    '.',
                ).append(random.nextInt(1000)).append(if (random.nextBoolean()) "0" else "")
            }
            if (random.nextInt(3) == 0) {
                out.append(if (random.nextBoolean()) 'e' else 'E')
                out.append(listOf("", "+", "-").random(random))
                out.append(random.nextInt(400))
            }
        }
    }

    private companion object {
        const val SEED = 20_261_105
        const val DOCUMENTS = 2_000
        const val MUTATIONS = 5
        const val MAX_DEPTH = 5
        const val MAX_MEMBERS = 5
        const val MAX_STRING = 8
        const val SCALAR_KINDS = 3
        const val ALL_KINDS = 5
        const val BIG_TAIL = 1_000

        val LAYOUTS: List<Pair<String, (String) -> String>> = listOf(
            "indented" to JsonLayout::indented,
            "singleLine" to JsonLayout::singleLine,
        )

        /** What a string is made of: plain text, every escape RFC 8259 has, raw non-ASCII and emoji. */
        val PIECES = listOf(
            "a", "Z", "7", " ", "path", "/", "\\/", "\\\"", "\\\\", "\\b", "\\f", "\\n", "\\r", "\\t",
            "\\u00e9", "\\u00E9", "\\uD83D\\uDE00", "é", "заметки", "日本", "😀", "{", "}", "[", "]", ",", ":", "'",
        )

        /** Characters the layout escapes, raw: they would not show where they stand. */
        val UNSEEN = listOf(
            "\u202E", "\u202D", "\u2066", "\u200B", "\u200D", "\u200F", "\uFEFF", "\u00AD", "\u0085", "\u009F",
            "\u007F", "\u2028", "\u2029", "\u061C", "\uDB40\uDC41", "\uD800", "\uDFFF",
        )

        val BREAKERS = listOf(",", ":", "{", "}", "[", "]", "\"", "'", "\\", "x", "\u0001", "01", ".")

        /**
         * Whether a code point of a layout would not show: a lone surrogate comes out of
         * `codePoints()` on its own, a pair as one code point. The line breaks the indented
         * layout writes are its own.
         */
        private fun Int.isUnseen(): Boolean = this != '\n'.code &&
            when (Character.getType(this).toByte()) {
                Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR,
                Character.PARAGRAPH_SEPARATOR, Character.SURROGATE,
                -> true
                else -> false
            }
    }
}
