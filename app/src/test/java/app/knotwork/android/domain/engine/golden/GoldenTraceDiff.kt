package app.knotwork.android.domain.engine.golden

/**
 * Explains a golden-trace mismatch in a few lines: where the first difference is, what both
 * sides say around it, and how to see the whole diff.
 *
 * A full diff of a long trace does not fit a test report, and the first divergence is almost
 * always the cause of everything after it (one changed route moves every later line).
 */
internal object GoldenTraceDiff {

    private const val CONTEXT_LINES = 4

    /**
     * Describes how [actual] differs from [expected].
     *
     * @param expected The committed golden trace.
     * @param actual The trace the run produced.
     * @param expectedPath Where the golden file is.
     * @param actualPath Where the actual trace was written.
     * @return A failure message; never empty.
     */
    fun describe(expected: String, actual: String, expectedPath: String, actualPath: String): String {
        val expectedLines = expected.lines()
        val actualLines = actual.lines()
        val first = expectedLines.indices.firstOrNull { it >= actualLines.size || expectedLines[it] != actualLines[it] }
            ?: expectedLines.size
        return buildString {
            appendLine("Golden trace differs from $expectedPath at line ${first + 1}.")
            appendLine("--- expected (golden)")
            appendWindow(expectedLines, first)
            appendLine("+++ actual")
            appendWindow(actualLines, first)
            appendLine("Whole diff: git diff --no-index $expectedPath $actualPath")
            append(
                "If the change is intended, rewrite with ${GoldenTraceRenderer.RECORD_COMMAND} and explain the diff.",
            )
        }
    }

    private fun StringBuilder.appendWindow(lines: List<String>, center: Int) {
        val from = (center - CONTEXT_LINES).coerceAtLeast(0)
        val to = (center + CONTEXT_LINES).coerceAtMost(lines.size - 1)
        if (from > to) {
            appendLine("  (no line ${center + 1})")
            return
        }
        for (index in from..to) {
            val marker = if (index == center) ">" else " "
            appendLine("$marker ${(index + 1).toString().padStart(5)}  ${lines[index]}")
        }
    }
}
