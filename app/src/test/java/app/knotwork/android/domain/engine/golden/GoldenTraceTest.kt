package app.knotwork.android.domain.engine.golden

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

/**
 * Golden-trace regression gate: every shipped pipeline, run through the real engine under a
 * scripted model, must produce exactly the trace committed for it.
 *
 * Each scenario is run twice and both traces must match before either is compared — a
 * nondeterministic trace would make the gate flaky, so it is refused here rather than
 * discovered later. The comparison then regenerates and diffs: on a mismatch the actual trace
 * is written under `build/golden-traces/actual/` and the failure names the first differing
 * line and the command that shows the whole diff.
 *
 * A golden file changes only on purpose: `-PrecordGoldenTraces` (see
 * [GoldenTraceRenderer.RECORD_COMMAND]) rewrites them, and the diff is explained in the change
 * that makes it. A refactoring of the engine merges with no diff at all.
 *
 * @param scenario The scenario under test.
 */
@RunWith(Parameterized::class)
internal class GoldenTraceTest(private val scenario: GoldenScenario) {

    @Test
    fun `trace matches its golden file`() {
        val first = runScenario()
        val second = runScenario()
        assertEquals(
            "Scenario $scenario renders a different trace on a second run — something is nondeterministic",
            first,
            second,
        )

        val golden = File(TRACES_DIR, scenario.goldenPath)
        if (recordMode()) {
            golden.parentFile.mkdirs()
            if (!golden.exists() || golden.readText() != first) golden.writeText(first)
            return
        }
        if (!golden.exists()) {
            fail(
                "No golden trace for $scenario at ${golden.path}. Record it with ${GoldenTraceRenderer.RECORD_COMMAND}",
            )
        }
        val expected = golden.readText()
        if (expected != first) {
            val actual = File(ACTUAL_DIR, scenario.goldenPath)
            actual.parentFile.mkdirs()
            actual.writeText(first)
            fail(GoldenTraceDiff.describe(expected, first, golden.path, actual.path))
        }
    }

    private fun runScenario(): String {
        var trace = ""
        runTest { trace = GoldenTraceHarness(scenario).run(this) }
        return trace
    }

    companion object {
        /** The committed golden traces, relative to the `app/` module directory. */
        val TRACES_DIR: File = File("src/test/golden/traces")

        /** Where a mismatching run leaves its actual trace. */
        private val ACTUAL_DIR = File("build/golden-traces/actual")

        /** System property that switches the test from comparing to rewriting. */
        const val RECORD_PROPERTY: String = "knotwork.goldenTraces.record"

        private fun recordMode(): Boolean = System.getProperty(RECORD_PROPERTY) == "true"

        /**
         * The scenarios, one test each.
         *
         * @return The parameter sets.
         */
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun scenarios(): List<Array<Any>> = GoldenScenarios.all.map { arrayOf(it) }
    }
}
