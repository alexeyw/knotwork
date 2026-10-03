package app.knotwork.android.domain.engine.golden

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the one harness behaviour no golden trace can show: a run that does not finish fails
 * the scenario instead of hanging the test task.
 */
internal class GoldenTraceHarnessTest {

    @Test
    fun `given a run that outlives the virtual-time limit when run then the harness fails instead of hanging`() {
        // The engine waits 500 ms of virtual time before every live node, so a three-node run
        // needs well over 600 ms: the limit binds mid-run, exactly as it would on a run
        // waiting for an answer that never comes.
        val scenario = GoldenScenarios.all.first { it.source.fileStem == "local_only_qa" }

        val error = assertThrows(IllegalStateException::class.java) {
            runTest { GoldenTraceHarness(scenario, attemptLimitMs = 600).run(this) }
        }

        assertTrue(error.message, error.message.orEmpty().contains("did not finish within 600 ms"))
    }
}
