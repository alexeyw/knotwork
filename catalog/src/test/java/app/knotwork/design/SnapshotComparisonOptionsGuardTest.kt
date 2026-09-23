package app.knotwork.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Fails a screenshot captured without [KnotworkRoborazziOptions].
 *
 * Roborazzi takes its comparison tolerance per call, not globally, so the one call that
 * forgets it is verified with the stricter default and fails on CI's Linux renderer for
 * an anti-aliasing difference nobody can see — or, worse, gets "fixed" by re-recording
 * its baseline on one platform. The guard reads every `captureRoboImage(...)` call in
 * the catalog's test sources; they compile with this module, so an edit re-runs it.
 */
class SnapshotComparisonOptionsGuardTest {

    @Test
    fun `given a screenshot capture then it passes the shared comparison options`() {
        val calls = File(TEST_SOURCES).walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> callsIn(file) }
            .toList()
        assertTrue("found no captureRoboImage call — the scan no longer matches the code", calls.isNotEmpty())

        val missing = calls.filterNot { (_, arguments) ->
            REQUIRED_ARGUMENT.containsMatchIn(arguments)
        }.map { it.first }

        assertEquals("pass roborazziOptions = KnotworkRoborazziOptions", emptyList<String>(), missing)
    }

    /**
     * Every capture checks the text it is about to record first: the accent-text
     * check ([assertNoAccentText]) covers exactly the states screenshots render, so
     * a capture that skips it adds a state nothing checks.
     */
    @Test
    fun `given a screenshot capture then it checks the rendered text colours first`() {
        val calls = File(TEST_SOURCES).walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> statementsBefore(file) }
            .toList()
        assertTrue("found no captureRoboImage call — the scan no longer matches the code", calls.isNotEmpty())

        val unchecked = calls.filterNot { (_, receiver) -> ACCENT_CHECK.containsMatchIn(receiver) }.map { it.first }

        assertEquals("chain assertNoAccentText() before captureRoboImage", emptyList<String>(), unchecked)
    }

    /** Each call as `File.kt:line` with the receiver chain written before it on its line. */
    private fun statementsBefore(file: File): List<Pair<String, String>> {
        val text = file.readText()
        return CALL.findAll(text)
            .map { match ->
                val lineStart = text.lastIndexOf('\n', match.range.first - 1) + 1
                val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                "${file.name}:$line" to text.substring(lineStart, match.range.first)
            }
            .filterNot { (_, before) -> before.trimStart().let { it.startsWith("*") || it.startsWith("//") } }
            .toList()
    }

    /** Each call as `File.kt:line` with the text of its argument list. */
    private fun callsIn(file: File): List<Pair<String, String>> {
        val text = file.readText()
        return CALL.findAll(text)
            .filterNot { match ->
                val lineStart = text.substring(text.lastIndexOf('\n', match.range.first - 1) + 1, match.range.first)
                lineStart.trimStart().let { it.startsWith("*") || it.startsWith("//") }
            }
            .map { match ->
                val open = match.range.last
                val line = text.substring(0, open).count { it == '\n' } + 1
                "${file.name}:$line" to argumentList(text, open)
            }
            .toList()
    }

    /** The text between the parenthesis at [open] and its matching close. */
    private fun argumentList(text: String, open: Int): String {
        var depth = 0
        for (index in open until text.length) {
            when (text[index]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return text.substring(open + 1, index)
            }
        }
        error("unbalanced parentheses after offset $open")
    }

    private companion object {
        const val TEST_SOURCES = "src/test/java"
        val CALL = Regex("""\bcaptureRoboImage\(""")
        val REQUIRED_ARGUMENT = Regex("""\broborazziOptions\s*=\s*KnotworkRoborazziOptions\b""")
        val ACCENT_CHECK = Regex("""\bassertNoAccentText\(\)""")
    }
}
