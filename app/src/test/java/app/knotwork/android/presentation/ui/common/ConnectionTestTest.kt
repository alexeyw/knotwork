package app.knotwork.android.presentation.ui.common

import app.knotwork.android.domain.connection.ConnectionCheckResult
import app.knotwork.android.domain.connection.ConnectionFailure
import app.knotwork.android.domain.connection.ConnectionRefusal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/** Unit tests for [ConnectionTest], the state of a Test connection row. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionTestTest {

    private val time = TestTimeSource()
    private val reachable = ConnectionCheckResult.Reachable(listOf("m"))

    /**
     * A row on a scope of its own, run by the test's scheduler: `advanceUntilIdle` runs its
     * checks (it skips `backgroundScope`), and a check left running does not hold `runTest` open.
     */
    private fun TestScope.test() = ConnectionTest(CoroutineScope(StandardTestDispatcher(testScheduler)), time)

    @Test
    fun `given inputs a check cannot run with when taken then the row is disabled, and pressing Test does nothing`() =
        runTest {
            val row = test()
            var ran = false

            row.onInputs("no address", ConnectionRefusal.MissingAddress)
            row.start("h") {
                ran = true
                reachable
            }
            advanceUntilIdle()

            assertEquals(ConnectionTestState.Disabled(ConnectionRefusal.MissingAddress), row.state.value)
            assertEquals(false, ran)
        }

    @Test
    fun `given a check when started then it runs from a marked start and finishes with its result`() = runTest {
        val row = test()
        val answer = CompletableDeferred<ConnectionCheckResult>()
        row.onInputs("ok", refusal = null)

        row.start("api.groq.com") { answer.await() }
        advanceUntilIdle()
        time += 4.seconds
        val running = row.state.value as ConnectionTestState.Running
        answer.complete(reachable)
        advanceUntilIdle()

        assertEquals("api.groq.com", running.host)
        assertEquals(4.seconds, running.startedAt.elapsedNow())
        assertEquals(ConnectionTestState.Finished(reachable), row.state.value)
    }

    @Test
    fun `given the same inputs again when taken then the result stays`() = runTest {
        val row = test()
        row.onInputs("ok", refusal = null)
        row.start("h") { reachable }
        advanceUntilIdle()

        row.onInputs("ok", refusal = null)

        assertEquals(ConnectionTestState.Finished(reachable), row.state.value)
    }

    @Test
    fun `given changed inputs when taken then the result is dropped`() = runTest {
        val row = test()
        row.onInputs("key A", refusal = null)
        row.start("h") { reachable }
        advanceUntilIdle()

        row.onInputs("key B", refusal = null)
        val afterEdit = row.state.value
        row.onInputs("", ConnectionRefusal.MissingKey)

        assertEquals(ConnectionTestState.Idle, afterEdit)
        assertEquals(ConnectionTestState.Disabled(ConnectionRefusal.MissingKey), row.state.value)
    }

    @Test
    fun `given changed inputs while a check runs when taken then the check is cancelled`() = runTest {
        val row = test()
        var cancelled = false
        row.onInputs("key A", refusal = null)
        row.start("h") {
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        advanceUntilIdle()

        row.onInputs("key B", refusal = null)
        advanceUntilIdle()

        assertTrue(cancelled)
        assertEquals(ConnectionTestState.Idle, row.state.value)
    }

    @Test
    fun `given a running check when cancelled then it stops and the row is idle again`() = runTest {
        val row = test()
        var cancelled = false
        row.onInputs("ok", refusal = null)
        row.start("h") {
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        advanceUntilIdle()

        row.cancel()
        advanceUntilIdle()

        assertTrue(cancelled)
        assertEquals(ConnectionTestState.Idle, row.state.value)
    }

    @Test
    fun `given a running check when Test is pressed again then a second check does not start`() = runTest {
        val row = test()
        var starts = 0
        val answer = CompletableDeferred<ConnectionCheckResult>()
        row.onInputs("ok", refusal = null)

        repeat(2) {
            row.start("h") {
                starts++
                answer.await()
            }
        }
        advanceUntilIdle()

        assertEquals(1, starts)
    }

    @Test
    fun `given a finished check when Test is pressed again then it runs again`() = runTest {
        val row = test()
        var starts = 0
        row.onInputs("ok", refusal = null)

        repeat(2) {
            row.start("h") {
                starts++
                reachable
            }
            advanceUntilIdle()
        }

        assertEquals(2, starts)
    }

    @Test
    fun `given a check that throws anyway when run then the row shows a failure instead of crashing`() = runTest {
        val row = test()
        row.onInputs("ok", refusal = null)

        row.start("h") { error("unexpected") }
        advanceUntilIdle()

        assertEquals(
            ConnectionTestState.Finished(ConnectionCheckResult.Failed("h", ConnectionFailure.Other("unexpected"))),
            row.state.value,
        )
    }
}
