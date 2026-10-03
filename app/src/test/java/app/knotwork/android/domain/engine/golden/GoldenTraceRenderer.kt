package app.knotwork.android.domain.engine.golden

/**
 * Renders the normal form of a golden trace — the exact text committed under
 * `app/src/test/golden/traces/`.
 *
 * The format is line-oriented so a behavioural change reads as a small diff in review:
 * a header per event, and each payload as an indented block whose lines start with `|`.
 * Trailing whitespace inside a payload is made visible with [TRAILING_WHITESPACE_MARK]: a
 * prompt ending in `"AGENT: "` must survive an editor that trims lines, and must not compare
 * equal to one ending in `"AGENT:"`.
 */
internal object GoldenTraceRenderer {

    /** First line of every golden file; also the marker a hand-written file would lack. */
    const val FILE_BANNER: String = "# Golden run trace — written by GoldenTraceTest, never by hand."

    /** The command that rewrites the golden files, printed in every file and failure. */
    const val RECORD_COMMAND: String =
        "./gradlew :app:testFullDebugUnitTest --tests '*GoldenTraceTest*' -PrecordGoldenTraces"

    /** Appended to a payload line whose content ends in whitespace. */
    const val TRAILING_WHITESPACE_MARK: String = "⏎"

    /**
     * Renders a complete golden file.
     *
     * @param scenario The scenario the trace belongs to; supplies the file header.
     * @param events The recorded events, oldest first.
     * @return The file text, ending with a single newline.
     */
    fun render(scenario: GoldenScenario, events: List<GoldenEvent>): String = buildString {
        appendLine(FILE_BANNER)
        appendLine("# Rewrite: $RECORD_COMMAND")
        appendLine("pipeline: ${scenario.source.id}")
        appendLine("scenario: ${scenario.name}")
        appendLine("about: ${scenario.description}")
        appendLine("origin: ${scenario.origin.name}")
        appendBlock("prompt", scenario.prompt, indent = "")
        appendLine()
        events.forEach { event ->
            appendLine(event.header)
            event.blocks.forEach { (name, text) -> appendBlock(name, text, indent = "    ") }
        }
    }

    /**
     * Renders [text] as `|`-prefixed lines, the payload form used for every block.
     *
     * An empty text renders as the single line `(empty)` so that "no text" and "one empty
     * line" stay distinguishable; an empty line inside a text renders as a bare `|`.
     *
     * @param text The payload.
     * @return The rendered lines, without indentation.
     */
    fun payloadLines(text: String): List<String> {
        if (text.isEmpty()) return listOf("(empty)")
        return text.split("\n").map { line ->
            val body = if (line.isEmpty()) "|" else "| $line"
            if (line.isNotEmpty() && line.last().isWhitespace()) body + TRAILING_WHITESPACE_MARK else body
        }
    }

    private fun StringBuilder.appendBlock(name: String, text: String, indent: String) {
        appendLine("$indent$name:")
        payloadLines(text).forEach { appendLine("$indent  $it") }
    }
}
