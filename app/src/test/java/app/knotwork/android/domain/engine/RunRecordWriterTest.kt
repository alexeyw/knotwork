package app.knotwork.android.domain.engine

import app.knotwork.android.domain.engine.stuck.StuckSignal
import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.models.ClarificationRequest
import app.knotwork.android.domain.models.HardCeilingBreach
import app.knotwork.android.domain.models.PendingInteraction
import app.knotwork.android.domain.models.PendingInteractionKind
import app.knotwork.android.domain.models.PipelineRunStatus
import app.knotwork.android.domain.models.RunBudgetLedger
import app.knotwork.android.domain.models.RunCeilingAxis
import app.knotwork.android.domain.models.RunCeilingLimit
import app.knotwork.android.domain.models.RunCeilings
import app.knotwork.android.domain.models.RunNoticeCause
import app.knotwork.android.domain.models.RunOrigin
import app.knotwork.android.domain.models.RunSpend
import app.knotwork.android.domain.models.ToolRisk
import app.knotwork.android.domain.repositories.PendingInteractionRepository
import app.knotwork.android.domain.repositories.PipelineRunRepository
import app.knotwork.android.domain.repositories.RunTraceRepository
import app.knotwork.android.domain.services.CeilingNotifier
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for [RunRecordWriter].
 *
 * The suspension mirror is a small state machine whose wrong edge once left a
 * nested pipeline's root RUNNING behind a waiting child, so every transition is
 * pinned here: into each WAITING_* status, through the observation-only states that
 * must not end a wait, and back to RUNNING. A run that is not persisted must touch
 * no store at all — the strict mocks fail on any call.
 */
class RunRecordWriterTest {

    private val runs: PipelineRunRepository = mockk(relaxed = true)
    private val pending: PendingInteractionRepository = mockk(relaxed = true)
    private val notifier: CeilingNotifier = mockk(relaxed = true)
    private val trace: RunTraceRepository = mockk(relaxed = true)
    private val factory = RunRecordWriter.Factory(runs, pending, notifier, trace)

    private val approval = AgentOrchestratorState.WaitingForApproval("search_tool", "{}", ToolRisk.SENSITIVE, "req-1")
    private val clarification = AgentOrchestratorState.AwaitingClarification(
        ClarificationRequest(id = "q-1", sessionId = "s", question = "Which?", options = null, timeoutMs = 1L),
    )
    private val ceilingPause = AgentOrchestratorState.WaitingForCeilingRaise(RunCeilingAxis.STEPS, limit = 5, spent = 5)
    private val breach = HardCeilingBreach(RunCeilingAxis.STEPS, limit = 5, spent = 5)

    private fun ledger(rootRunId: String?) = RunBudgetLedger(
        ceilings = RunCeilings(
            origin = RunOrigin.CHAT,
            steps = RunCeilingLimit.Enforced(soft = 10, hard = 20),
            tokens = RunCeilingLimit.Enforced(soft = 10, hard = 20),
        ),
        rootRunId = rootRunId,
        stepsAlreadySpent = 3,
        tokensAlreadySpent = 7,
    )

    @Test
    fun `given a run that is not persisted then no store is touched and a ceiling cannot park`() = runTest {
        val strictRuns: PipelineRunRepository = mockk()
        val strictPending: PendingInteractionRepository = mockk()
        val strictNotifier: CeilingNotifier = mockk()
        val strictTrace: RunTraceRepository = mockk()
        val writer = RunRecordWriter.Factory(strictRuns, strictPending, strictNotifier, strictTrace).open(null, "s")

        assertEquals(RunSpend(), writer.spendSoFar())
        writer.consumeParkedCeiling()
        writer.enterNode("n")
        writer.recordSpend(ledger(rootRunId = "root"))
        assertFalse(writer.parkOnCeiling(breach))
        writer.mirror(approval)
        writer.mirror(AgentOrchestratorState.Loading)
        writer.endSuspension()
        assertNull(writer.recordedHeader())
        writer.recordHeader(TEST_RUN_HEADER)
    }

    @Test
    fun `given a persisted run when its header is read and written then the record is used`() = runTest {
        coEvery { runs.getRun("run-1") } returns mockk { every { header } returns TEST_RUN_HEADER }
        val writer = factory.open("run-1", "s")

        assertEquals(TEST_RUN_HEADER, writer.recordedHeader())
        writer.recordHeader(TEST_RUN_HEADER)

        coVerify(exactly = 1) { runs.setHeader("run-1", TEST_RUN_HEADER) }
    }

    @Test
    fun `given a run whose record is missing when its header is read then there is none`() = runTest {
        coEvery { runs.getRun("run-1") } returns null

        assertNull(factory.open("run-1", "s").recordedHeader())
    }

    @Test
    fun `given a persisted run then spend is read back and node and spend are written`() = runTest {
        coEvery { runs.getSpend("run-1") } returns RunSpend(steps = 4, tokens = 9)
        val writer = factory.open("run-1", "s")

        assertEquals(RunSpend(steps = 4, tokens = 9), writer.spendSoFar())
        writer.enterNode("llm_1")
        writer.recordSpend(ledger(rootRunId = "root"))

        coVerify { runs.updateCurrentNode("run-1", "llm_1") }
        // The spend lands on the tree's root record, not on this invocation's.
        coVerify { runs.recordSpend(rootRunId = "root", stepsSpent = 3, tokensSpent = 7) }
    }

    @Test
    fun `given a ledger without a root record then no spend is written`() = runTest {
        factory.open("run-1", "s").recordSpend(ledger(rootRunId = null))

        coVerify(exactly = 0) { runs.recordSpend(any(), any(), any()) }
    }

    @Test
    fun `given a parked ceiling question when the run resumes then it is consumed and its notice cancelled`() =
        runTest {
            coEvery { pending.getForRun("run-1") } returns PendingInteraction(
                runId = "run-1",
                sessionId = "s",
                kind = PendingInteractionKind.CEILING,
                requestedAt = 0L,
            )

            factory.open("run-1", "s").consumeParkedCeiling()

            coVerify { pending.delete("run-1") }
            verify { notifier.cancelCeilingNotification("s") }
        }

    @Test
    fun `given a parked approval or nothing parked then the resume leaves the store alone`() = runTest {
        coEvery { pending.getForRun("run-a") } returns PendingInteraction(
            runId = "run-a",
            sessionId = "s",
            kind = PendingInteractionKind.APPROVAL,
            requestedAt = 0L,
        )
        coEvery { pending.getForRun("run-b") } returns null

        factory.open("run-a", "s").consumeParkedCeiling()
        factory.open("run-b", "s").consumeParkedCeiling()

        coVerify(exactly = 0) { pending.delete(any()) }
        verify(exactly = 0) { notifier.cancelCeilingNotification(any()) }
    }

    @Test
    fun `given a ceiling park the store accepts then the question is durable and the user is told`() = runTest {
        val saved = slot<PendingInteraction>()
        coEvery { pending.save(capture(saved)) } returns true

        assertTrue(factory.open("child-1", "s").parkOnCeiling(breach))

        assertEquals("child-1", saved.captured.runId)
        assertEquals(PendingInteractionKind.CEILING, saved.captured.kind)
        assertEquals(RunCeilingAxis.STEPS, saved.captured.ceilingAxis)
        assertEquals(5, saved.captured.ceilingLimit)
        assertEquals(5, saved.captured.ceilingSpent)
        verify { notifier.sendCeilingPauseRequest("child-1", "s", breach) }
    }

    @Test
    fun `given a ceiling park the store refuses then the run must stop and nobody is told`() = runTest {
        coEvery { pending.save(any()) } returns false

        assertFalse(factory.open("run-1", "s").parkOnCeiling(breach))

        verify(exactly = 0) { notifier.sendCeilingPauseRequest(any(), any(), any()) }
    }

    @Test
    fun `given each waiting state then the record moves to its status and the trace is flushed`() = runTest {
        val writer = factory.open("run-1", "s")

        writer.mirror(approval)
        writer.mirror(clarification)
        writer.mirror(ceilingPause)

        coVerifyOrder {
            runs.updateStatus("run-1", PipelineRunStatus.WAITING_APPROVAL)
            trace.flush()
            runs.updateStatus("run-1", PipelineRunStatus.WAITING_CLARIFICATION)
            trace.flush()
            runs.updateStatus("run-1", PipelineRunStatus.WAITING_CEILING)
            trace.flush()
        }
    }

    @Test
    fun `given observations while waiting then the wait stays open until real progress ends it`() = runTest {
        val writer = factory.open("run-1", "s")
        writer.mirror(approval)

        writer.mirror(AgentOrchestratorState.ConsoleLog(emptyList(), "run-1"))
        writer.mirror(AgentOrchestratorState.NodeIO("n", "LITE_RT", "in", "out"))
        writer.mirror(AgentOrchestratorState.RunNotice(RunNoticeCause.LooksStuck(StuckSignal.REPEATED_STEP)))
        coVerify(exactly = 0) { runs.updateStatus("run-1", PipelineRunStatus.RUNNING) }

        writer.mirror(AgentOrchestratorState.Loading)
        coVerify(exactly = 1) { runs.updateStatus("run-1", PipelineRunStatus.RUNNING) }

        // Already running: the next state writes nothing more.
        writer.mirror(AgentOrchestratorState.Loading)
        coVerify(exactly = 1) { runs.updateStatus("run-1", PipelineRunStatus.RUNNING) }
    }

    @Test
    fun `given a node that finishes while waiting then the record returns to running once`() = runTest {
        val writer = factory.open("run-1", "s")
        writer.mirror(clarification)

        writer.endSuspension()
        writer.endSuspension()

        coVerify(exactly = 1) { runs.updateStatus("run-1", PipelineRunStatus.RUNNING) }
    }

    @Test
    fun `given a node that finishes without waiting then the record is not touched`() = runTest {
        val strictRuns: PipelineRunRepository = mockk()

        RunRecordWriter.Factory(strictRuns, pending, notifier, trace).open("run-1", "s").endSuspension()

        confirmVerified(strictRuns)
    }
}
