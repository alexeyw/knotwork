package app.knotwork.android.presentation.screenshots

import app.knotwork.android.architecture.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Fails a screenshot of this module captured without [AppRoborazziOptions].
 *
 * Roborazzi takes its comparison tolerance per call, not globally, so a call that
 * forgets it is verified with the stricter default and fails on CI's Linux renderer
 * for an anti-aliasing difference nobody can see — or gets "fixed" by re-recording
 * its baseline on one platform. `:catalog`'s `SnapshotComparisonOptionsGuardTest`
 * holds its own captures to the same rule; this reads every `captureRoboImage(...)`
 * call in this module's test sources, which compile with the test task, so an edit
 * re-runs it.
 */
class AppSnapshotOptionsGuardTest {

    @Test
    fun `given a screenshot capture then it passes the shared comparison options`() {
        val calls = File(ProductionSources.moduleDirectory(), "src/test/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != SELF }
            .flatMap { file -> callsIn(file) }
            .toList()
        assertTrue("found no captureRoboImage call — the scan no longer matches the code", calls.isNotEmpty())

        val missing = calls.filterNot { (_, arguments) -> REQUIRED_ARGUMENT.containsMatchIn(arguments) }
            .map { it.first }

        assertEquals("pass roborazziOptions = AppRoborazziOptions", emptyList<String>(), missing)
    }

    /** Each call as `File.kt:line` with the text of its argument list; calls in comments are skipped. */
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
        /** This file names the call in its own patterns. */
        const val SELF = "AppSnapshotOptionsGuardTest.kt"
        val CALL = Regex("""\bcaptureRoboImage\(""")
        val REQUIRED_ARGUMENT = Regex("""\broborazziOptions\s*=\s*AppRoborazziOptions\b""")
    }
}
